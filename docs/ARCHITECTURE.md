# Architecture

## Modules
```
shared/       KMP library (android, jvm, iosArm64, iosSimulatorArm64). Compose UI + domain + Room + Koog assistant.
androidApp/   Android app: foreground service (sticky notification), AlarmManager alarms, full-screen due alerts, LiteRT-LM engine.
desktopApp/   macOS Compose Desktop app: menu-bar Tray, always-on-top alert window, LaunchAgent (launch at login), llama.cpp (java-llama.cpp) engine, Ollama connection.
iosApp/       Xcode project (generated with xcodegen) hosting ComposeUIViewController; UNUserNotifications, Live Activity, Apple Foundation
              Models (on-device, iOS 26+) as the LLM engine. Ships on the App Store as "Doorbell" (see docs/APP_STORE.md).
```
Toolchain: Kotlin 2.4.21, Compose MP 1.12.1, AGP 9.4.1 (`com.android.kotlin.multiplatform.library` for shared), KSP 2.3.12,
Room 2.8.5 (bundled SQLite on all platforms), Koog 1.3.0, Gradle 9.8.1, JDK 21.

## Layering inside `shared/commonMain` (package `dev.aliziwa.todo`)
```
platform/     Ports (interfaces) implemented by each platform. See Ports.kt — the ONLY coupling point.
db/           Room entities, DAOs, TodoDatabase, migrations.
domain/       Pure Kotlin: Task, Priority, TaskScope, Recurrence, ranking, due-state machine, extension rules, date helpers.
data/         Repositories (Room <-> domain), settings store (simple key/value table), recurring-instance materializer.
scheduling/   DueEngine: observes tasks, keeps AlarmScheduler in sync, decides alerts, drives AlertPresenter/SoundPlayer, StickySummary.
assistant/    Koog agent + prompt + structured output schema + rule-based fallback parser + failure policy.
llm/          LlmBackend abstraction: LocalEngineBackend (wraps LocalLlmEngine port as a Koog LLMClient), OllamaBackend (Koog's
              Ollama client), model catalog + downloader (Ktor, resumable), settings.
ui/           Compose: tabs (Day / Week / Month / Recurring), task editor, countdown chips, due-alert dialog, chat screen,
              settings (LLM + launch at login + daily prompt time), daily "plan your day" sheet. ViewModels via lifecycle-viewmodel.
util/         Log, ids (uuid), result helpers.
TodoApp.kt    Process entry (`start`, `onAlarmFired`, `onDailyPromptFired`, `onAppForegrounded`, notification actions).
App.kt        Root composable `App(graph, initialRoute)`.
```

## Domain model (authoritative)
- `Priority { LOW, MEDIUM, HIGH }` — ranking: HIGH first, then earlier `dueAt`, then earlier `createdAt`. Done tasks sink to bottom.
- `TaskScope { DAY, WEEK, MONTH, RECURRING_DAILY }` — which tab the task lives in.
  - DAY: belongs to `scheduledDate` (local date) and has an optional `dueAt` time-of-day. Countdown + alert only when `dueAt` set.
  - WEEK / MONTH: belongs to a date range (ISO week / calendar month containing `scheduledDate`); optional dueAt date (no alarm
    unless the user sets a time, then it behaves like DAY for alerts).
  - RECURRING_DAILY: a template with `recurringTimeMinutes` (time of day). Each day an *instance* is materialized lazily
    (`RecurringCompletion` row keyed by templateId+date). Completing/dismissing affects only that date. Deleting the template removes
    future instances; history rows are kept.
- `TaskStatus { PENDING, DONE, DISMISSED }`. DISMISSED = user silenced the alert but did not complete (shows as overdue/muted).
- Extensions: `extend(byMinutes)` moves `dueAt` forward from `max(now, dueAt)` and increments `extensionCount`; allowed before and after
  due. Presets 5/15/30/60 + custom. Extension re-arms the alarm and clears the alert.
- Overdue DAY tasks from previous days roll into today's Day tab in an "Overdue" section until done/deleted.
- Alerts: when `now >= dueAt` and status PENDING and not alerted-since-last-change → `AlertPresenter.showDueAlert` + doorbell (loop/repeat
  every 30s until acted on). Alert is sticky until Done / Extend / Dismiss / Delete.
- Daily prompt: once per local day, on first launch/wake/unlock after `dailyPromptTime` (default 08:00) or immediately on start if
  not shown yet today and local time ≥ prompt time. Stored `lastDailyPromptDate`.

## Scheduling flow
```
Repository.observeTasks() ──► DueEngine ──► AlarmScheduler.schedule(taskId, dueAt) for every PENDING task with dueAt in future
                                       ──► AlertPresenter.updateSticky(summary)   (on every change + every minute)
Platform alarm fires ──► TodoApp.onAlarmFired(id) ──► DueEngine.evaluate(id) ──► showDueAlert + SoundPlayer.playDoorbell(loop)
User acts (UI or notification action) ──► TodoApp.completeTask/extendTask/dismissAlert/deleteTask ──► dismissDueAlert + sound.stop
Process start / foreground ──► DueEngine.evaluateAll() (catch alarms missed while dead; multiple due → alert queue, one at a time)
```
Platforms also keep their own minimal alarm persistence (Android: alarms survive via BootReceiver re-sync call `TodoApp.resyncAlarms()`).

## Assistant (Koog) design — strictly offline
```
User text ──► AssistantService.handle(text, context: today's tasks summary)
  1. Backend selection (settings): LOCAL_ENGINE (on-device) | OLLAMA (http://localhost:11434 or LAN) | RULES_ONLY
  2. Koog AIAgent, single-run strategy, structured output → `AssistantPlan` (list of ops: AddTask / UpdateTask / CompleteTask /
     DeleteTask / ExtendTask / ListTasks / Clarify / Chat) with a `reply` string. JSON schema given in system prompt; parser
     tolerant (strip code fences, find first {...}).
  3. On parse/inference failure: one repair retry with the error fed back. On second failure → RuleBasedParser (regex: times,
     "tomorrow", weekdays, "every day", "high/low priority", "in 30 min") → if it yields ops, propose them; else `Clarify`.
  4. Failure policy: 2 consecutive assistant failures (timeout, engine error, unparsable twice) → `AssistantMode.MANUAL_FALLBACK`:
     chat input shows "Assistant unavailable — switched to manual" banner and opens the manual Add Task sheet prefilled by the
     rule-based parse. Counter resets on success or when user re-enables in settings.
  5. Every plan is shown as a confirmation card (ops listed) → user confirms → executed through the repositories.
Koog is REQUIRED (`ai.koog:koog-agents`). Local engines are exposed to Koog via a custom `LLMClient` (`LocalEngineLLMClient`) built
on the `LocalLlmEngine` port; Ollama via Koog's `OllamaClient`.
Default model suggestions (user can paste any Hugging Face *resolve* URL):
  desktop (gguf): Qwen2.5-1.5B-Instruct-Q4_K_M.gguf; Android (litertlm): gemma-3-1b-it (LiteRT-LM community build).
  iOS has no download: `LocalLlmEngine.builtInModel` (additive port member) marks an OS-provided model (Apple Intelligence); the
  runtime then passes `builtInModel.id` to `load()` and the UI hides the download card. First run defaults to LOCAL_ENGINE there.
Settings also hold `userName` (greeting only; no hard-coded name ships), `PlatformCapabilities.appVersion` /
`supportsLaunchAtLogin` drive the About card and the launch-at-login toggle, and `AppLinks` holds the privacy/support URLs.
`data/DemoData.kt` seeds a demo day + chat transcript for store screenshots (debug launch flags only; never in normal runs).
Downloader: Ktor streaming to `modelsDir/<filename>.part` with Range resume, progress, cancel, free-space check (file size via HEAD),
rename on completion, SHA256 optional, reject non-matching extension for the platform engine.
```

## Platform behaviors
| Requirement | macOS (desktop) | Android | iOS |
|---|---|---|---|
| Always running / status surface | Tray (menu bar) icon + tooltip "Next: X in 12m"; closing window hides to tray | Foreground service with ongoing notification (next task + countdown, actions) | Live Activity (lock screen / Dynamic Island) with next due countdown; persistent "today" notification when activity unsupported |
| Exact due alert when app not visible | App stays resident (LaunchAgent KeepAlive) + in-process scheduler | `AlarmManager.setAlarmClock` (exact, no special permission, shows alarm icon) → BroadcastReceiver → full-screen-intent notification + service plays sound | `UNUserNotificationCenter` time-sensitive notifications with custom doorbell sound; re-alert chain every 2 min ×10 until acted on |
| Doorbell | javax.sound.sampled loop | MediaPlayer on alarm stream, loop | notification sound (≤30s caf) + AVAudioPlayer loop while foreground |
| Sticky alert | Always-on-top undecorated window with Done / Extend / Dismiss / Delete | Full-screen Activity over lock screen + ongoing notification with same actions | Alert UI on open; notification actions Done / +15 / Dismiss |
| Launch on login + morning prompt | `~/Library/LaunchAgents/dev.aliziwa.todo.plist` (RunAtLoad, KeepAlive) installed from Settings (default on first run) ; daily "Plan your day" window | BootReceiver + daily alarm → notification "Plan your day" (tap opens plan sheet) | Daily local notification "Plan your day"; plan sheet on open |
| Storage | Room + bundled SQLite in `~/Library/Application Support/PersonalTodo/todo.db` | Room + bundled SQLite | Room + bundled SQLite in Application Support |
| On-device LLM | java-llama.cpp (gguf, Metal) | LiteRT-LM via Koog `prompt-executor-litert-client` (litertlm) | Apple Foundation Models (`SystemLanguageModel`, guided generation from the plan JSON schema), Swift `NativeLlmBridge` wrapped by `SwiftBackedLlmEngine`; unavailable device → rules / local server |
| Connect to local server LLM | Ollama / LM Studio (OpenAI-compatible) via Koog | same (LAN URL) | same (LAN URL) |

## Edge cases checklist (every agent verifies the ones in their area)
Time: DST transitions (store UTC millis, compute with TimeZone), midnight rollover while app open (Day tab refreshes; countdowns
re-evaluated), device timezone change, clock set backwards (alarms re-synced on resync), task due in the past when created (alert
immediately, ask to confirm), tasks due at same minute (alert queue, not overlapping sounds), very long countdown (>24h shows "in 2d 3h").
Lifecycle: process killed before alarm (platform alarms persist; resync on boot/start), alarm fires while an alert is already showing
(queue), app opened from notification action (deep link route), rapid double-tap on Done (idempotent), uninstall/reinstall (fresh DB).
Data: empty title rejected, title trimmed, max 200 chars, notes optional, delete with undo (5s), DB migration path (version bump +
AutoMigration or explicit), schema exported to `shared/schemas`.
Recurring: template time change applies from today, skipping today only, completing today's instance doesn't affect tomorrow,
deleting template; instance due time already passed when created today.
Assistant: no model configured (chat shows setup CTA + rule-based still works), model download interrupted (resume), disk full,
wrong file type, model load OOM (catch → Error state → fallback), generation timeout (60s), garbage/non-JSON output (repair → rules),
model hallucinates ops for non-existent tasks (validate ids/titles against DB; fuzzy title match or Clarify), ambiguous time
("at 5" → ask AM/PM only if both plausible; default next upcoming), relative dates ("tomorrow", "next Monday", "in 2 hours"),
multiple tasks in one message, Ollama unreachable (connection refused → counts as failure), user types while generation runs
(queue or disable send), cancel generation.
Audio: sound device missing (no crash), user mutes (settings toggle), two alerts (single looping player instance).
Platform: macOS notification permission denied (we don't rely on it; use window), Android 13+ POST_NOTIFICATIONS runtime permission,
Android 14+ FGS type + USE_FULL_SCREEN_INTENT (request via settings deep link when denied), battery optimizations (prompt once),
iOS notification permission denied (show in-app banner), iOS Live Activity unsupported (fallback notification), low-power mode.
