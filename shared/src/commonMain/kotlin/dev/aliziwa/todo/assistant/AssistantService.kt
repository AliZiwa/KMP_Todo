package dev.aliziwa.todo.assistant

import ai.koog.agents.core.agent.AIAgent
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.data.SettingsRepository
import dev.aliziwa.todo.data.TaskRepository
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.llm.LlmRuntime
import dev.aliziwa.todo.llm.LlmRuntimeFactory
import dev.aliziwa.todo.llm.LlmUnavailable
import dev.aliziwa.todo.llm.LlmUnavailableException
import dev.aliziwa.todo.llm.describe
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.platform.PlatformServices
import dev.aliziwa.todo.util.Ids
import dev.aliziwa.todo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class ChatRole { USER, ASSISTANT, SYSTEM }

enum class PlanState { NONE, PENDING, CONFIRMED, REJECTED }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val at: Long,
    val plan: ResolvedPlan? = null,
    val planState: PlanState = PlanState.NONE,
)

/** READY = LLM in use; RULES_ONLY = deterministic parser; MANUAL_FALLBACK = 2 strikes, form instead. */
enum class AssistantMode { READY, RULES_ONLY, MANUAL_FALLBACK }

/**
 * The assistant. One code path: text -> plan (Koog LLM or rule parser) -> validated actions ->
 * confirmation card -> execute.
 *
 * Failure policy (mandatory): two consecutive failures (timeout, engine error, unparsable twice)
 * switch to [AssistantMode.MANUAL_FALLBACK] and expose a prefilled [manualPrefill] draft so the
 * user can finish the job by hand. The counter resets on success or when re-enabled in Settings.
 */
class AssistantService(
    private val repo: TaskRepository,
    private val settings: SettingsRepository,
    private val runtimeFactory: LlmRuntimeFactory,
    private val resolver: PlanResolver,
    private val executor: PlanExecutor,
    private val ruleParser: RuleBasedParser,
    private val platform: PlatformServices,
    private val scope: CoroutineScope,
) {
    private val tag = "AssistantService"

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _mode = MutableStateFlow(AssistantMode.RULES_ONLY)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _manualPrefill = MutableStateFlow<TaskDraft?>(null)
    val manualPrefill: StateFlow<TaskDraft?> = _manualPrefill.asStateFlow()

    private val _statusLine = MutableStateFlow("Rule-based parsing")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    private var consecutiveFailures = 0
    private var inFlight: Job? = null

    init {
        // Resolving the runtime also LOADS the configured on-device model, so a restart with a
        // model already chosen is ready before the user's first message (collectLatest so a
        // settings change cancels an in-flight load).
        scope.launch {
            settings.settings.collectLatest { refreshStatus(it) }
        }
        // Keep the status line honest while the engine loads / fails underneath us.
        platform.localLlmEngine?.let { engine ->
            scope.launch {
                engine.state.collect { state ->
                    if (_mode.value != AssistantMode.MANUAL_FALLBACK &&
                        settings.settings.value.llmBackend == LlmBackend.LOCAL_ENGINE
                    ) {
                        engineStatusLine(state)?.let { _statusLine.value = it }
                    }
                }
            }
        }
    }

    private fun engineStatusLine(state: EngineState): String? = when (state) {
        EngineState.NotLoaded -> null
        is EngineState.Loading -> "Loading the model" + (state.progress?.let { " — ${(it * 100).toInt()}%" } ?: "…")
        is EngineState.Ready -> state.modelName
        is EngineState.Error -> "Model could not be loaded: ${state.message}"
    }

    private suspend fun refreshStatus(s: AppSettings) {
        if (_mode.value == AssistantMode.MANUAL_FALLBACK) {
            _statusLine.value = "Assistant unavailable — switched to manual"
            return
        }
        if (s.llmBackend == LlmBackend.LOCAL_ENGINE && s.localModelPath != null) {
            _statusLine.value = "Loading the model…"
        }
        val runtime = runtimeFactory.resolve(s)
        if (runtime.isSuccess) {
            _mode.value = AssistantMode.READY
            _statusLine.value = runtime.getOrThrow().label
        } else {
            applyUnavailable(s, (runtime.exceptionOrNull() as? LlmUnavailableException)?.reason)
        }
    }

    /** No LLM right now — explain it in the banner. This is never a "strike". */
    private fun applyUnavailable(s: AppSettings, reason: LlmUnavailable?) {
        _mode.value = AssistantMode.RULES_ONLY
        _statusLine.value = when {
            reason != null -> reason.describe()
            s.llmBackend == LlmBackend.RULES_ONLY -> "Rule-based parsing (no model)"
            else -> "Rule-based parsing"
        }
    }

    /** Useful on the chat screen's empty state. */
    fun needsModelSetup(): Boolean = _mode.value != AssistantMode.READY

    fun cancel() {
        inFlight?.cancel()
        inFlight = null
        _busy.value = false
        platform.localLlmEngine?.cancelGeneration()
    }

    fun clearManualPrefill() {
        _manualPrefill.value = null
    }

    /** Settings "re-enable assistant" button. */
    suspend fun reenable() {
        consecutiveFailures = 0
        _mode.value = AssistantMode.RULES_ONLY
        settings.update { it.copy(assistantEnabled = true) }
        refreshStatus(settings.current())
        append(ChatRole.SYSTEM, "Assistant re-enabled.")
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        append(ChatRole.USER, trimmed)
        inFlight = scope.launch { handle(trimmed) }
    }

    private suspend fun handle(text: String) {
        _busy.value = true
        try {
            val current = settings.current()
            val snapshot = repo.snapshotNow()
            val now = platform.clock.nowEpochMillis()

            if (_mode.value == AssistantMode.MANUAL_FALLBACK) {
                fallbackToManual(text, now, snapshot, "Assistant is in manual mode.")
                return
            }

            val resolvedRuntime = runtimeFactory.resolve(current)
            val runtime = resolvedRuntime.getOrNull()
            if (runtime == null) {
                // No LLM available: deterministic parse, still a confirmation card. A missing or
                // broken model is a configuration problem, not an assistant failure, so it never
                // counts towards the 2-strike manual fallback — but we do say what happened.
                val reason = (resolvedRuntime.exceptionOrNull() as? LlmUnavailableException)?.reason
                applyUnavailable(current, reason)
                when (reason) {
                    is LlmUnavailable.EngineError,
                    is LlmUnavailable.EngineLoading,
                    LlmUnavailable.NoModelConfigured,
                    -> append(ChatRole.SYSTEM, reason.describe() + " — using the built-in parser for now.")
                    else -> Unit
                }
                val plan = ruleParser.parse(text, now)
                proposeOrReply(resolver.resolve(plan, snapshot, now, PlanSource.RULES, text))
                return
            }
            _mode.value = AssistantMode.READY

            val zone = DateMath.zone(platform.clock.timeZoneId())
            val today = DateMath.today(now, zone)
            val contextItems = resolver.candidateItems(snapshot, today)
            val systemPrompt = Prompts.system(today, now, zone, contextItems)

            val first = runCatching {
                withTimeout(current.generationTimeoutMillis) {
                    runAgent(runtime, systemPrompt, text, jsonMode = true)
                }
            }

            val raw = first.getOrNull()
            if (raw == null) {
                Log.w(tag, "generation failed", first.exceptionOrNull())
                recordFailure(text, now, snapshot, first.exceptionOrNull()?.message ?: "Generation failed")
                return
            }

            val attempt = interpret(raw, snapshot, now, text)
            if (attempt.isSuccess) {
                consecutiveFailures = 0
                proposeOrReply(attempt.getOrThrow())
                return
            }

            // One repair attempt, feeding the error back.
            val firstError = attempt.exceptionOrNull()?.message ?: "invalid JSON"
            val repairPrompt = Prompts.repair(raw, firstError)
            val second = runCatching {
                withTimeout(current.generationTimeoutMillis) {
                    runAgent(runtime, systemPrompt, "$text\n\n$repairPrompt", jsonMode = true)
                }
            }
            val repaired = second.getOrNull()?.let { interpret(it, snapshot, now, text) }
            if (repaired?.isSuccess == true) {
                consecutiveFailures = 0
                proposeOrReply(repaired.getOrThrow())
                return
            }

            recordFailure(text, now, snapshot, repaired?.exceptionOrNull()?.message ?: firstError)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(tag, "assistant failed", error)
            val now = platform.clock.nowEpochMillis()
            recordFailure(text, now, repo.snapshotNow(), error.message ?: "Unexpected error")
        } finally {
            _busy.value = false
            inFlight = null
        }
    }

    /**
     * Parses raw model output and validates it against the database.
     *
     * A plan that parses but whose every actionable op is rejected is reported as a FAILURE, not a
     * successful empty plan: small models love to echo the schema's enum placeholder as the op
     * name, which previously produced a confident reply with nothing created.
     */
    private fun interpret(
        raw: String,
        snapshot: dev.aliziwa.todo.domain.TaskSnapshot,
        nowMillis: Long,
        userText: String,
    ): Result<ResolvedPlan> {
        val parsed = PlanJson.parse(raw).getOrElse { return Result.failure(it) }
        val resolved = resolver.resolve(parsed, snapshot, nowMillis, PlanSource.LLM, userText)
        if (resolved.isFullRejectionOf(parsed)) {
            val detail = resolved.warnings.joinToString("; ").ifBlank {
                "the \"op\" values were not real operations (got: " +
                    parsed.ops.joinToString(", ") { "\"${it.op}\"" } + ")"
            }
            return Result.failure(IllegalStateException("No usable operation — $detail"))
        }
        return Result.success(resolved)
    }

    /** One Koog `AIAgent` run with the default single-run strategy, no tools, String in / String out. */
    private suspend fun runAgent(
        runtime: LlmRuntime,
        systemPrompt: String,
        userText: String,
        jsonMode: Boolean,
    ): String {
        val agent = AIAgent(
            promptExecutor = runtime.executor,
            llmModel = if (jsonMode) runtime.model else runtime.plainModel,
            systemPrompt = systemPrompt,
            temperature = 0.1,
            maxIterations = 4,
            id = "todo-assistant",
        )
        return try {
            agent.run(userText)
        } finally {
            runCatching { agent.close() }
        }
    }

    private suspend fun recordFailure(
        text: String,
        nowMillis: Long,
        snapshot: dev.aliziwa.todo.domain.TaskSnapshot,
        reason: String,
    ) {
        consecutiveFailures++
        Log.w(tag, "assistant failure #$consecutiveFailures: $reason")
        if (consecutiveFailures >= FAILURE_LIMIT) {
            _mode.value = AssistantMode.MANUAL_FALLBACK
            _statusLine.value = "Assistant unavailable — switched to manual"
            fallbackToManual(text, nowMillis, snapshot, "Assistant unavailable ($reason). Switched to manual.")
        } else {
            // First strike: silently fall back to the deterministic parser for this message.
            val plan = ruleParser.parse(text, nowMillis)
            val resolved = resolver.resolve(plan, snapshot, nowMillis, PlanSource.RULES, text)
            append(ChatRole.SYSTEM, "Model hiccup ($reason) — used the built-in parser instead.")
            proposeOrReply(resolved)
        }
    }

    /** MANUAL_FALLBACK: open the Add Task sheet prefilled from the rule-based parse. */
    private fun fallbackToManual(
        text: String,
        nowMillis: Long,
        snapshot: dev.aliziwa.todo.domain.TaskSnapshot,
        note: String,
    ) {
        val plan = ruleParser.parse(text, nowMillis)
        val resolved = resolver.resolve(plan, snapshot, nowMillis, PlanSource.RULES, text)
        val draft = resolved.actions.filterIsInstance<PlannedAction.Add>().firstOrNull()?.draft
            ?: TaskDraft(title = text.take(200), scope = TaskScope.DAY)
        _manualPrefill.value = draft
        append(ChatRole.SYSTEM, note)
        append(ChatRole.ASSISTANT, "Opened the task form prefilled with \"${draft.title}\".")
    }

    private fun proposeOrReply(plan: ResolvedPlan) {
        // Warnings live on the card (so the UI can style them), not inlined into the bubble.
        val text = plan.reply
        if (plan.needsConfirmation) {
            append(ChatRole.ASSISTANT, text, plan = plan, planState = PlanState.PENDING)
        } else {
            // Keep the plan even with no actions, so a warnings-only outcome is visible.
            append(ChatRole.ASSISTANT, text, plan = plan)
            // List-only plans need no confirmation; run them straight away.
            if (plan.actions.isNotEmpty()) scope.launch { executor.execute(plan) }
        }
    }

    // ------------------------------------------------------------ confirmation

    fun confirm(messageId: String) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        val plan = message.plan ?: return
        if (message.planState != PlanState.PENDING) return
        scope.launch {
            val outcome = executor.execute(plan)
            replace(messageId) { it.copy(planState = PlanState.CONFIRMED) }
            val summary = when {
                outcome.failures.isEmpty() && outcome.applied > 0 ->
                    "Done — ${outcome.applied} change${if (outcome.applied == 1) "" else "s"} applied."
                outcome.applied > 0 -> "Applied ${outcome.applied}, ${outcome.failures.size} failed."
                outcome.listRequested -> "Here you go."
                else -> "Nothing was applied."
            }
            append(ChatRole.ASSISTANT, summary)
        }
    }

    fun reject(messageId: String) {
        replace(messageId) {
            if (it.planState == PlanState.PENDING) it.copy(planState = PlanState.REJECTED) else it
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Settings "Test" button. Runs a PLAIN-TEXT probe (not JSON mode) and insists on a real answer:
     * a grammar-constrained engine replying `{}` is a failed test, not a passing one.
     */
    suspend fun testBackend(): Result<String> {
        val current = settings.current()
        runtimeFactory.clearLoadFailure()
        val runtime = runtimeFactory.resolve(current).getOrElse { return Result.failure(it) }
        return runCatching {
            withTimeout(minOf(current.generationTimeoutMillis, 30_000)) {
                val reply = runAgent(
                    runtime = runtime,
                    systemPrompt = "You are a connection test. Reply with the single word OK and nothing else.",
                    userText = "ping",
                    jsonMode = false,
                ).trim()
                require(isMeaningfulProbeReply(reply)) {
                    "${runtime.label} answered \"${reply.take(40)}\" — the model is reachable but not " +
                        "producing text (check the model file / name)"
                }
                "${runtime.label}: ${reply.take(80)}"
            }
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    /**
     * Replaces the transcript wholesale. Only used by [dev.aliziwa.todo.data.DemoData] so the chat
     * screen is not an empty state in App Store screenshots — nothing here is ever executed.
     */
    fun seedTranscript(messages: List<ChatMessage>) {
        _messages.value = messages
    }

    private fun append(
        role: ChatRole,
        text: String,
        plan: ResolvedPlan? = null,
        planState: PlanState = PlanState.NONE,
    ) {
        _messages.value = _messages.value + ChatMessage(
            id = Ids.newId(),
            role = role,
            text = text,
            at = platform.clock.nowEpochMillis(),
            plan = plan,
            planState = planState,
        )
    }

    private fun replace(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == messageId) transform(it) else it }
    }

    private companion object {
        const val FAILURE_LIMIT = 2
    }
}

/** A probe reply must be actual text — empty output or a bare `{}` / `[]` means JSON mode leaked. */
internal fun isMeaningfulProbeReply(reply: String): Boolean {
    val compact = reply.filterNot { it.isWhitespace() }
    if (compact.isEmpty()) return false
    return compact != "{}" && compact != "[]" && compact != "\"\"" && compact != "null"
}
