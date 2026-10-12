import Foundation
import FoundationModels
import Shared

/// `Shared` exports a Kotlin domain class called `Task`, which shadows Swift concurrency's.
private typealias SwiftTask = _Concurrency.Task

/// Tiny thread-safe boolean used to make "finish exactly once" cheap and to abort from
/// another thread.
final class AtomicFlag {
    private let lock = NSLock()
    private var value: Bool

    init(_ initial: Bool = false) { value = initial }

    func set(_ newValue: Bool) {
        lock.lock(); value = newValue; lock.unlock()
    }

    func get() -> Bool {
        lock.lock(); defer { lock.unlock() }; return value
    }

    /// Sets the flag and returns what it was — the "has somebody already finished?" test.
    @discardableResult
    func getAndSet(_ newValue: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let old = value
        value = newValue
        return old
    }
}

/// On-device inference through Apple's Foundation Models (Apple Intelligence). There is no model
/// file: the OS owns the weights, so this engine reports a `builtInModel` and no supported file
/// extensions, and the app never downloads anything.
///
/// It implements the Kotlin-side `NativeLlmBridge` protocol rather than `LocalLlmEngine` directly:
/// `SwiftBackedLlmEngine` (iosMain) owns the `EngineState` machine and turns these callbacks into a
/// Kotlin `Flow`, which keeps Kotlin coroutine / `Result` types out of Swift.
///
/// For `jsonMode` requests the shared layer passes the assistant-plan JSON Schema; it is converted
/// into a `DynamicGenerationSchema` so decoding is *constrained* (guided generation) instead of
/// merely asked for in the prompt. If the conversion fails the engine falls back to free
/// generation with a "JSON only" nudge.
final class AppleFoundationModelEngine: NSObject, NativeLlmBridge {

    private static let modelId = "apple-intelligence"
    private static let modelName = "Apple Intelligence"

    private let lock = NSLock()
    private var generationTask: SwiftTask<Void, Never>?

    // MARK: - NativeLlmBridge

    /// A built-in model loads no file.
    func supportedFileExtensions() -> [String] { [] }

    func modelDisplayName() -> String? { Self.modelName }

    func builtInModelId() -> String? { Self.modelId }

    func builtInModelDisplayName() -> String? { Self.modelName }

    /// `path` is `BuiltInModel.id` and is ignored; "loading" is just an availability check.
    func loadModel(path: String, completion: @escaping (String?) -> Void) {
        switch SystemLanguageModel.default.availability {
        case .available:
            // Shaves a second or two off the first real request.
            SwiftTask.detached(priority: .utility) {
                LanguageModelSession().prewarm()
            }
            NSLog("[todo] Apple Intelligence available")
            completion(nil)
        case .unavailable(let reason):
            let message = Self.message(for: reason)
            NSLog("[todo] Apple Intelligence unavailable: \(message)")
            completion(message)
        }
    }

    func unloadModel() {
        cancelGeneration()
    }

    func startGeneration(
        request: NativeLlmRequest,
        onToken: @escaping (String) -> Void,
        onFinished: @escaping (String?) -> Void
    ) {
        let finished = AtomicFlag()
        let finish: (String?) -> Void = { message in
            guard !finished.getAndSet(true) else { return }
            onFinished(message)
        }

        if case .unavailable(let reason) = SystemLanguageModel.default.availability {
            return finish(Self.message(for: reason))
        }

        // The shared layer always sends the whole conversation, so every request gets a fresh
        // session: system messages become the instructions, earlier turns the transcript, and the
        // trailing user message the prompt.
        let messages = request.messages
        let instructions = messages
            .filter { $0.role == "system" }
            .map { $0.content }
            .joined(separator: "\n\n")
        var history = messages.filter { $0.role != "system" }

        var promptText = "Continue."
        if let last = history.last, last.role == "user" {
            promptText = last.content
            history.removeLast()
        }

        var transcriptEntries: [Transcript.Entry] = []
        if !instructions.isEmpty {
            transcriptEntries.append(
                .instructions(
                    Transcript.Instructions(
                        segments: [.text(Transcript.TextSegment(content: instructions))],
                        toolDefinitions: []
                    )
                )
            )
        }
        for turn in history where !turn.content.isEmpty {
            let segments: [Transcript.Segment] = [.text(Transcript.TextSegment(content: turn.content))]
            if turn.role == "assistant" {
                transcriptEntries.append(.response(Transcript.Response(assetIDs: [], segments: segments)))
            } else {
                transcriptEntries.append(.prompt(Transcript.Prompt(segments: segments)))
            }
        }

        // Guided generation when we were given a schema; otherwise just ask for JSON.
        var schema: GenerationSchema?
        if request.jsonMode {
            if let raw = request.jsonSchema, !raw.isEmpty {
                do {
                    schema = try JsonSchemaToGenerationSchema.convert(raw)
                } catch {
                    NSLog("[todo] schema conversion failed, falling back to free generation: \(error)")
                }
            }
            if schema == nil {
                promptText += "\n\nRespond with JSON only. No prose, no code fences."
            }
        }

        let options = Self.options(for: request)
        let transcript = Transcript(entries: transcriptEntries)

        let task = SwiftTask.detached(priority: .userInitiated) { [weak self] in
            defer { self?.clearTask() }
            do {
                let session = LanguageModelSession(transcript: transcript)
                if let schema {
                    let response = try await session.respond(
                        to: promptText,
                        schema: schema,
                        includeSchemaInPrompt: false,
                        options: options
                    )
                    // Structured output arrives as one object; the shared layer concatenates
                    // chunks, so emitting it in a single chunk is fine.
                    if !SwiftTask.isCancelled {
                        onToken(response.rawContent.jsonString)
                    }
                    finish(nil)
                } else {
                    // Snapshots are cumulative for `String` content, so forward the new suffix.
                    var emitted = 0
                    for try await snapshot in session.streamResponse(to: promptText, options: options) {
                        if SwiftTask.isCancelled { break }
                        let text = snapshot.content
                        if text.count > emitted {
                            let start = text.index(text.startIndex, offsetBy: emitted)
                            onToken(String(text[start...]))
                            emitted = text.count
                        }
                    }
                    finish(nil)
                }
            } catch is CancellationError {
                finish(nil)
            } catch {
                finish(Self.describe(error))
            }
        }

        lock.lock()
        generationTask?.cancel()
        generationTask = task
        lock.unlock()

        let timeout = Double(request.timeoutMillis) / 1000.0
        if timeout > 0 {
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout) {
                guard !finished.get() else { return }
                task.cancel()
                finish("Apple Intelligence took longer than \(Int(timeout)) s. Try again.")
            }
        }
    }

    func cancelGeneration() {
        lock.lock()
        let task = generationTask
        generationTask = nil
        lock.unlock()
        task?.cancel()
    }

    // MARK: - internals

    private func clearTask() {
        lock.lock()
        generationTask = nil
        lock.unlock()
    }

    private static func options(for request: NativeLlmRequest) -> FoundationModels.GenerationOptions {
        // temperature ~0 means "be deterministic", which Foundation Models expresses as greedy
        // sampling (and then rejects a temperature alongside it).
        if request.temperature <= 0.0001 {
            return FoundationModels.GenerationOptions(
                samplingMode: .greedy,
                maximumResponseTokens: Int(request.maxTokens)
            )
        }
        return FoundationModels.GenerationOptions(
            samplingMode: .random(probabilityThreshold: Double(request.topP)),
            temperature: Double(request.temperature),
            maximumResponseTokens: Int(request.maxTokens)
        )
    }

    private static func message(
        for reason: SystemLanguageModel.Availability.UnavailableReason
    ) -> String {
        switch reason {
        case .deviceNotEligible:
            return "This device does not support Apple Intelligence."
        case .appleIntelligenceNotEnabled:
            return "Turn on Apple Intelligence in Settings › Apple Intelligence & Siri."
        case .modelNotReady:
            return "Apple Intelligence is still downloading its model. Try again in a few minutes."
        @unknown default:
            return "Apple Intelligence is not available on this device right now."
        }
    }

    /// Short, user-facing text for the assistant card / chat bubble. The shared layer's strike
    /// policy decides whether to retry, so these never need to explain what to do next.
    private static func describe(_ error: Error) -> String {
        if let generation = error as? LanguageModelSession.GenerationError {
            switch generation {
            case .exceededContextWindowSize:
                return "This conversation got too long for Apple Intelligence. Start a new chat."
            case .guardrailViolation, .refusal:
                return "Apple Intelligence would not answer that. Try rewording it."
            case .unsupportedLanguageOrLocale:
                return "Apple Intelligence does not support this language yet."
            case .assetsUnavailable:
                return "Apple Intelligence is still downloading its model. Try again in a few minutes."
            case .decodingFailure:
                return "Apple Intelligence returned something I could not read."
            case .rateLimited:
                return "Apple Intelligence is busy. Try again in a moment."
            case .concurrentRequests:
                return "Another request is still running."
            case .unsupportedGuide:
                return "Apple Intelligence could not use that output format."
            @unknown default:
                return "Apple Intelligence could not finish that one."
            }
        }
        if let localized = error as? LocalizedError, let description = localized.errorDescription {
            return description
        }
        return error.localizedDescription
    }
}

// MARK: - JSON Schema → DynamicGenerationSchema

enum JsonSchemaConversionError: Error, CustomStringConvertible {
    case notAnObject
    case unsupported(String)

    var description: String {
        switch self {
        case .notAnObject: return "schema root is not a JSON object"
        case .unsupported(let what): return "unsupported JSON Schema construct: \(what)"
        }
    }
}

/// Converts the subset of JSON Schema the shared layer emits (objects with properties/required,
/// arrays, nested objects, string enums, string/integer/number/boolean leaves) into a
/// `GenerationSchema` for Foundation Models' guided generation.
enum JsonSchemaToGenerationSchema {

    static func convert(_ json: String) throws -> GenerationSchema {
        guard
            let data = json.data(using: .utf8),
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            throw JsonSchemaConversionError.notAnObject
        }
        let dynamic = try node(root, name: "Plan", raw: json)
        return try GenerationSchema(root: dynamic, dependencies: [])
    }

    private static func node(
        _ node: [String: Any],
        name: String,
        raw: String
    ) throws -> DynamicGenerationSchema {
        let description = node["description"] as? String

        if let choices = node["enum"] as? [Any] {
            let strings = choices.compactMap { $0 as? String }
            guard !strings.isEmpty, strings.count == choices.count else {
                throw JsonSchemaConversionError.unsupported("non-string enum in \(name)")
            }
            return DynamicGenerationSchema(name: name, description: description, anyOf: strings)
        }

        if let anyOf = node["anyOf"] as? [[String: Any]] {
            let choices = try anyOf.enumerated().map {
                try Self.node($0.element, name: "\(name)_\($0.offset)", raw: raw)
            }
            guard !choices.isEmpty else {
                throw JsonSchemaConversionError.unsupported("empty anyOf in \(name)")
            }
            return DynamicGenerationSchema(name: name, description: description, anyOf: choices)
        }

        // "type": ["string", "null"] shows up in hand-written schemas; take the first non-null.
        var type = node["type"] as? String
        if type == nil, let types = node["type"] as? [String] {
            type = types.first { $0 != "null" }
        }

        switch type {
        case "object":
            let properties = node["properties"] as? [String: Any] ?? [:]
            let required = Set(node["required"] as? [String] ?? [])
            var converted: [DynamicGenerationSchema.Property] = []
            for key in orderedKeys(of: properties, in: raw) {
                guard let child = properties[key] as? [String: Any] else {
                    throw JsonSchemaConversionError.unsupported("property \(key) is not an object")
                }
                converted.append(
                    DynamicGenerationSchema.Property(
                        name: key,
                        description: child["description"] as? String,
                        schema: try Self.node(child, name: "\(name)_\(key)", raw: raw),
                        isOptional: !required.contains(key)
                    )
                )
            }
            return DynamicGenerationSchema(
                name: name,
                description: description,
                properties: converted
            )

        case "array":
            guard let items = node["items"] as? [String: Any] else {
                throw JsonSchemaConversionError.unsupported("array \(name) without items")
            }
            return DynamicGenerationSchema(
                arrayOf: try Self.node(items, name: "\(name)_item", raw: raw),
                minimumElements: node["minItems"] as? Int,
                maximumElements: node["maxItems"] as? Int
            )

        case "string":
            return DynamicGenerationSchema(type: String.self)
        case "integer":
            return DynamicGenerationSchema(type: Int.self)
        case "number":
            return DynamicGenerationSchema(type: Double.self)
        case "boolean":
            return DynamicGenerationSchema(type: Bool.self)
        default:
            throw JsonSchemaConversionError.unsupported("type \(type ?? "nil") in \(name)")
        }
    }

    /// `JSONSerialization` loses key order, which decides the order the model fills fields in.
    /// Recover it from the raw text (first occurrence of `"key"`), alphabetical as a tiebreak.
    private static func orderedKeys(of properties: [String: Any], in raw: String) -> [String] {
        let positions = properties.keys.reduce(into: [String: String.Index]()) { acc, key in
            if let range = raw.range(of: "\"\(key)\"") { acc[key] = range.lowerBound }
        }
        return properties.keys.sorted { a, b in
            switch (positions[a], positions[b]) {
            case let (x?, y?): return x == y ? a < b : x < y
            case (nil, .some): return false
            case (.some, nil): return true
            default: return a < b
            }
        }
    }
}
