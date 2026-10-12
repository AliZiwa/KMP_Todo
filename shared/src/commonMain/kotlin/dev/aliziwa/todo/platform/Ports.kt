package dev.aliziwa.todo.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * PLATFORM CONTRACTS ("ports").
 *
 * commonMain code depends ONLY on these interfaces. Each platform module (androidApp, desktopApp,
 * iosApp via Swift) provides implementations and hands them to [dev.aliziwa.todo.TodoApp.start].
 *
 * Rules:
 *  - Shared agent may ADD members with default implementations; never remove/rename without supervisor approval.
 *  - Platform agents implement these in their own module; they never edit this file.
 *  - Everything here must be main-safe (no blocking calls on the caller's thread).
 */

/** Minimal task projection pushed to platform alert/notification surfaces. */
data class DueAlert(
    val taskId: String,
    val title: String,
    val dueAtEpochMillis: Long,
    val priority: Int,          // 0 = LOW, 1 = MEDIUM, 2 = HIGH (mirrors domain Priority.ordinal)
    val isRecurring: Boolean,
    val extensionCount: Int,
)

/** What a persistent surface (menu bar, ongoing notification, Live Activity) should show. */
data class StickySummary(
    val nextTaskTitle: String?,
    val nextDueAtEpochMillis: Long?,
    val dueNowCount: Int,
    val remainingTodayCount: Int,
    val completedTodayCount: Int,
)

/**
 * Exact-time wakeups that must fire even if the UI is closed / app is backgrounded.
 * Platform implementation calls [dev.aliziwa.todo.TodoApp.onAlarmFired] when it fires.
 */
interface AlarmScheduler {
    fun schedule(taskId: String, atEpochMillis: Long)

    /**
     * Same as [schedule] but carries the task title, for platforms that must compose the
     * notification body at schedule time (iOS `UNUserNotificationCenter`).
     * For a recurring instance the title is the template's title.
     *
     * ADDITIVE (shared agent, wave 1): defaults to the 2-arg overload, so implementations that only
     * override [schedule] keep working. The shared layer always calls THIS overload.
     */
    fun schedule(taskId: String, atEpochMillis: Long, title: String) = schedule(taskId, atEpochMillis)

    fun cancel(taskId: String)
    fun cancelAll()
    /** Daily "plan your day" prompt. [hourOfDay]/[minute] in local time. */
    fun scheduleDailyPrompt(hourOfDay: Int, minute: Int)
    fun cancelDailyPrompt()
}

/**
 * Platform-native sticky surfaces. The shared layer decides WHEN; platform decides HOW.
 * Implementations must be idempotent (showing the same alert twice must not duplicate).
 */
interface AlertPresenter {
    fun showDueAlert(alert: DueAlert)
    fun dismissDueAlert(taskId: String)
    fun updateSticky(summary: StickySummary)
    fun showDailyPrompt()
    fun dismissDailyPrompt()
}

interface SoundPlayer {
    /** Play bundled doorbell. If [loop] true, repeats until [stop]. Must be non-blocking. */
    fun playDoorbell(loop: Boolean)
    fun stop()
}

interface AppPaths {
    /** Writable, persistent app data directory (absolute path, exists). */
    val dataDir: String
    /** Directory for downloaded LLM model files (absolute path, exists). */
    val modelsDir: String
    /** Database file absolute path. */
    val databasePath: String
}

interface Clock {
    fun nowEpochMillis(): Long
    /** IANA timezone id, e.g. "America/New_York". */
    fun timeZoneId(): String
}

// ------------------------------- Local LLM -------------------------------

enum class LlmRole { SYSTEM, USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val content: String)

data class GenerationOptions(
    val maxTokens: Int = 512,
    val temperature: Float = 0.1f,
    val topP: Float = 0.9f,
    /** When non-null, engines that support grammar/JSON mode should constrain output to JSON. */
    val jsonMode: Boolean = true,
    val stopSequences: List<String> = emptyList(),
    val timeoutMillis: Long = 60_000,
    /**
     * A JSON Schema (draft-07 shaped, already valid JSON) the output should conform to, set only
     * when [jsonMode] is true. Engines with schema-constrained decoding should use it — LiteRT-LM
     * `ResponseFormat.json(schema)`, llama.cpp `jsonSchemaToGrammar` — and engines without it may
     * ignore the field entirely.
     *
     * ADDITIVE (shared agent, wave 1): defaulted, so existing engine implementations are unaffected.
     * The shared layer fills it from `dev.aliziwa.todo.assistant.AssistantPlanSchema.STRICT`.
     */
    val jsonSchema: String? = null,
)

sealed interface EngineState {
    data object NotLoaded : EngineState
    data class Loading(val progress: Float?) : EngineState
    data class Ready(val modelPath: String, val modelName: String) : EngineState
    data class Error(val message: String, val cause: Throwable? = null) : EngineState
}

/**
 * A model that ships with the OS (e.g. Apple Intelligence). Engines exposing one need no model file.
 */
data class BuiltInModel(val id: String, val displayName: String)

/**
 * An on-device inference engine (llama.cpp on desktop, LiteRT-LM on Android, Apple Foundation
 * Models on iOS). The engine owns chat-template formatting for the model it loaded.
 */
interface LocalLlmEngine {
    val state: StateFlow<EngineState>
    /** Which file extensions this engine can load, e.g. ["gguf"] or ["litertlm", "task"]. */
    val supportedExtensions: List<String>

    /**
     * Non-null when the engine uses an OS-provided model. [load] then receives [BuiltInModel.id]
     * as the path; [supportedExtensions] is empty and nothing is ever downloaded.
     *
     * ADDITIVE (shared agent, wave 3) — defaulted, so file-based engines are unaffected.
     */
    val builtInModel: BuiltInModel? get() = null

    suspend fun load(modelPath: String): Result<Unit>
    suspend fun unload()
    /** Streams generated text chunks. Completes normally when generation ends; throws on failure. */
    fun generate(messages: List<LlmMessage>, options: GenerationOptions): Flow<String>
    fun cancelGeneration()
}

/** Provided by each platform so Ktor can download models. */
interface HttpEngineProvider {
    fun createHttpClient(): io.ktor.client.HttpClient
}

/** Platform-specific extras that have no common home. All optional. */
interface PlatformCapabilities {
    /** macOS: install/uninstall LaunchAgent. Others: unsupported (return false). */
    suspend fun setLaunchAtLogin(enabled: Boolean): Boolean = false
    suspend fun isLaunchAtLoginEnabled(): Boolean = false
    /** Bring the app's main window/activity to front. */
    fun bringToFront() {}
    /** Open a URL in the system browser (used for Hugging Face links). */
    fun openUrl(url: String) {}

    /**
     * Free space in bytes on the volume holding [directory], or null when the platform cannot tell.
     * Used by the model downloader to refuse a download that cannot fit.
     * ADDITIVE (shared agent, wave 1) — default implementation means platforms need not implement it.
     */
    suspend fun freeSpaceBytes(directory: String): Long? = null

    /**
     * User-facing app version ("1.0.3", or "1.0.3 (42)"), shown in Settings → About.
     * ADDITIVE (shared agent, wave 3) — empty string means "do not show a version line".
     */
    val appVersion: String get() = ""

    /**
     * True only where [setLaunchAtLogin] does something (macOS). The Settings "Launch at login"
     * card is hidden otherwise — on iOS it is meaningless.
     * ADDITIVE (shared agent, wave 3).
     */
    val supportsLaunchAtLogin: Boolean get() = false

    val platformName: String
}

/** Bundle handed to the shared layer at startup. */
class PlatformServices(
    val alarms: AlarmScheduler,
    val alerts: AlertPresenter,
    val sound: SoundPlayer,
    val paths: AppPaths,
    val clock: Clock,
    val http: HttpEngineProvider,
    val capabilities: PlatformCapabilities,
    /** The platform's on-device engine. May be null if platform has none yet; the app then offers only remote local-network LLMs (Ollama) and rule-based parsing. */
    val localLlmEngine: LocalLlmEngine?,
)
