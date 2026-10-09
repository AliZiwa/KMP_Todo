import Foundation
import Shared

/// Tiny thread-safe boolean used to abort the decode loop from another thread.
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
}

#if LLAMA_ENABLED
import llama

/// On-device inference through llama.cpp (gguf; Metal on device, CPU in the simulator).
///
/// It implements the Kotlin-side `NativeLlmBridge` protocol rather than `LocalLlmEngine`
/// directly: `SwiftBackedLlmEngine` (iosMain) owns the `EngineState` machine and turns these
/// callbacks into a Kotlin `Flow`, which keeps Kotlin coroutine/`Result` types out of Swift.
///
/// llama.cpp is vendored as `Vendor/llama.xcframework`, built from tag b11429 by
/// `Vendor/build-llama-xcframework.sh` — upstream dropped `Package.swift`, so SPM is
/// impossible, and the official prebuilt release xcframework has no simulator slice.
final class LlamaCppEngine: NSObject, NativeLlmBridge {

    private let queue = DispatchQueue(label: "dev.aliziwa.todo.llama", qos: .userInitiated)
    private static var backendInitialized = false

    private var model: OpaquePointer?
    private var context: OpaquePointer?
    private var vocab: OpaquePointer?
    private var loadedPath: String?

    private let cancelled = AtomicFlag()

    // MARK: - NativeLlmBridge

    func supportedFileExtensions() -> [String] { ["gguf"] }

    func modelDisplayName() -> String? {
        loadedPath.map { ($0 as NSString).lastPathComponent }
    }

    func loadModel(path: String, completion: @escaping (String?) -> Void) {
        queue.async { [weak self] in
            guard let self else { return completion("engine released") }
            self.disposeModel()

            guard FileManager.default.fileExists(atPath: path) else {
                return completion("Model file not found: \(path)")
            }

            if !LlamaCppEngine.backendInitialized {
                llama_backend_init()
                LlamaCppEngine.backendInitialized = true
            }

            var modelParams = llama_model_default_params()
            #if targetEnvironment(simulator)
            // ggml's Metal backend is not usable in the simulator; stay on the CPU there.
            modelParams.n_gpu_layers = 0
            #else
            modelParams.n_gpu_layers = 99
            #endif

            guard let loadedModel = llama_model_load_from_file(path, modelParams) else {
                return completion("Failed to load model (out of memory or unsupported gguf)")
            }

            var ctxParams = llama_context_default_params()
            ctxParams.n_ctx = 4096
            ctxParams.n_batch = 512
            ctxParams.n_ubatch = 512
            let cores = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount - 1))
            ctxParams.n_threads = cores
            ctxParams.n_threads_batch = cores

            guard let loadedContext = llama_init_from_model(loadedModel, ctxParams) else {
                llama_model_free(loadedModel)
                return completion("Failed to create llama context")
            }

            self.model = loadedModel
            self.context = loadedContext
            self.vocab = llama_model_get_vocab(loadedModel)
            self.loadedPath = path
            completion(nil)
        }
    }

    func unloadModel() {
        queue.async { [weak self] in self?.disposeModel() }
    }

    func startGeneration(
        request: NativeLlmRequest,
        onToken: @escaping (String) -> Void,
        onFinished: @escaping (String?) -> Void
    ) {
        cancelled.set(false)
        queue.async { [weak self] in
            guard let self else { return onFinished("engine released") }
            guard let context = self.context, let vocab = self.vocab, let model = self.model else {
                return onFinished("No model loaded")
            }

            let deadline = Date().addingTimeInterval(TimeInterval(request.timeoutMillis) / 1000.0)
            let prompt = LlamaCppEngine.applyChatTemplate(model: model, messages: request.messages)
            var tokens = LlamaCppEngine.tokenize(vocab: vocab, text: prompt, addSpecial: true)
            guard !tokens.isEmpty else { return onFinished("Prompt tokenized to nothing") }

            let nCtx = Int(llama_n_ctx(context))
            if tokens.count >= nCtx - 8 {
                tokens = Array(tokens.suffix(nCtx - 8))
            }

            // Fresh KV cache per request: the shared layer always sends the full conversation.
            llama_memory_clear(llama_get_memory(context), true)

            // ---- sampler chain ----
            var chainParams = llama_sampler_chain_default_params()
            chainParams.no_perf = true
            let sampler = llama_sampler_chain_init(chainParams)
            defer { llama_sampler_free(sampler) }

            if request.jsonMode {
                LlamaCppEngine.jsonGrammar.withCString { grammar in
                    "root".withCString { root in
                        if let g = llama_sampler_init_grammar(vocab, grammar, root) {
                            llama_sampler_chain_add(sampler, g)
                        }
                    }
                }
            }
            if request.temperature <= 0.0001 {
                llama_sampler_chain_add(sampler, llama_sampler_init_greedy())
            } else {
                llama_sampler_chain_add(sampler, llama_sampler_init_top_p(request.topP, 1))
                llama_sampler_chain_add(sampler, llama_sampler_init_temp(request.temperature))
                llama_sampler_chain_add(sampler, llama_sampler_init_dist(UInt32.max))
            }

            // ---- prompt ingestion, chunked to n_batch ----
            let batchSize = Int(llama_n_batch(context))
            var offset = 0
            while offset < tokens.count {
                if self.cancelled.get() { return onFinished(nil) }
                let end = min(offset + batchSize, tokens.count)
                var chunk = Array(tokens[offset..<end])
                let rc: Int32 = chunk.withUnsafeMutableBufferPointer { buf in
                    llama_decode(context, llama_batch_get_one(buf.baseAddress, Int32(buf.count)))
                }
                if rc != 0 {
                    return onFinished("llama_decode failed while reading the prompt (code \(rc))")
                }
                offset = end
            }

            // ---- decode loop ----
            var produced = 0
            var pending = ""
            let stops = request.stopSequences.filter { !$0.isEmpty }

            while produced < Int(request.maxTokens) {
                if self.cancelled.get() { break }
                if Date() > deadline {
                    return onFinished("Generation timed out after \(request.timeoutMillis) ms")
                }

                var token = llama_sampler_sample(sampler, context, -1)
                if llama_vocab_is_eog(vocab, token) { break }

                let piece = LlamaCppEngine.piece(vocab: vocab, token: token)
                if !piece.isEmpty {
                    pending += piece
                    onToken(piece)
                }
                produced += 1

                if stops.contains(where: { pending.hasSuffix($0) }) { break }

                let rc: Int32 = withUnsafeMutablePointer(to: &token) { ptr in
                    llama_decode(context, llama_batch_get_one(ptr, 1))
                }
                if rc != 0 {
                    return onFinished("llama_decode failed while generating (code \(rc))")
                }
            }

            onFinished(nil)
        }
    }

    func cancelGeneration() {
        cancelled.set(true)
    }

    // MARK: - internals

    private func disposeModel() {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
        context = nil
        model = nil
        vocab = nil
        loadedPath = nil
    }

    /// Applies the model's own chat template via `llama_chat_apply_template`; falls back to
    /// ChatML when the gguf carries no template.
    private static func applyChatTemplate(model: OpaquePointer, messages: [NativeLlmMessage]) -> String {
        let template = llama_model_chat_template(model, nil).map { String(cString: $0) } ?? "chatml"

        var owned: [UnsafeMutablePointer<CChar>] = []
        defer { owned.forEach { free($0) } }

        var msgs: [llama_chat_message] = []
        msgs.reserveCapacity(messages.count)
        for message in messages {
            guard let role = strdup(message.role), let content = strdup(message.content) else {
                return fallbackChatml(messages)
            }
            owned.append(role)
            owned.append(content)
            msgs.append(llama_chat_message(role: UnsafePointer(role), content: UnsafePointer(content)))
        }

        let totalChars = messages.reduce(0) { $0 + $1.role.utf8.count + $1.content.utf8.count }
        var size = max(1024, totalChars * 2 + 512)
        for _ in 0..<2 {
            var buf = [CChar](repeating: 0, count: size + 1)
            let written = template.withCString { tmpl in
                msgs.withUnsafeBufferPointer { ptr in
                    llama_chat_apply_template(tmpl, ptr.baseAddress, ptr.count, true, &buf, Int32(size))
                }
            }
            if written < 0 { return fallbackChatml(messages) }
            if Int(written) <= size {
                buf[Int(written)] = 0
                return String(cString: buf)
            }
            size = Int(written) + 1
        }
        return fallbackChatml(messages)
    }

    private static func fallbackChatml(_ messages: [NativeLlmMessage]) -> String {
        var out = ""
        for message in messages {
            out += "<|im_start|>\(message.role)\n\(message.content)<|im_end|>\n"
        }
        return out + "<|im_start|>assistant\n"
    }

    private static func tokenize(vocab: OpaquePointer, text: String, addSpecial: Bool) -> [llama_token] {
        let byteCount = Int32(text.utf8.count)
        var capacity = byteCount + 16
        var tokens = [llama_token](repeating: 0, count: Int(capacity))
        var count = text.withCString { cstr in
            llama_tokenize(vocab, cstr, byteCount, &tokens, capacity, addSpecial, true)
        }
        if count < 0 {
            capacity = -count
            tokens = [llama_token](repeating: 0, count: Int(capacity))
            count = text.withCString { cstr in
                llama_tokenize(vocab, cstr, byteCount, &tokens, capacity, addSpecial, true)
            }
        }
        guard count > 0 else { return [] }
        return Array(tokens[0..<Int(count)])
    }

    private static func piece(vocab: OpaquePointer, token: llama_token) -> String {
        var buf = [CChar](repeating: 0, count: 128)
        var written = llama_token_to_piece(vocab, token, &buf, Int32(buf.count), 0, true)
        if written < 0 {
            buf = [CChar](repeating: 0, count: Int(-written) + 1)
            written = llama_token_to_piece(vocab, token, &buf, Int32(buf.count), 0, true)
        }
        guard written > 0 else { return "" }
        let bytes = buf[0..<Int(written)].map { UInt8(bitPattern: $0) }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Minimal JSON GBNF so `jsonMode` output always parses.
    private static let jsonGrammar = """
    root   ::= object
    value  ::= object | array | string | number | ("true" | "false" | "null") ws
    object ::= "{" ws ( string ":" ws value ("," ws string ":" ws value)* )? "}" ws
    array  ::= "[" ws ( value ("," ws value)* )? "]" ws
    string ::= "\\"" ( [^"\\\\] )* "\\"" ws
    number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9] [0-9]{0,15})? ws
    ws     ::= | " " | "\\n" [ \\t]{0,20}
    """
}
#endif
