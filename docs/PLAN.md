# Delivery plan

Supervisor: Claude (Fable 5.1). Four implementation agents run in parallel against the skeleton the supervisor verified green.
All agents: build often, keep your own area compiling, write unit tests where logic is pure, and report back with
(1) what is done, (2) how it was verified (exact commands + results), (3) open issues / needed port changes.

## Wave 1 (parallel)

### A. shared-core agent — `shared/src/commonMain`, `commonTest`
1. Domain (`domain/`): Task, Priority, TaskScope, TaskStatus, RecurringTemplate/instance model, ranking comparator, DueState,
   extension rules, LocalDate/week/month helpers (kotlinx-datetime 0.8). Unit tests.
2. DB (`db/`): evolve TaskEntity, add `recurring_completions`, `settings` (key/value), `alert_state` tables; DAOs with Flows;
   bump version with migrations; export schema. In-memory JVM tests for DAO + repository using the bundled driver.
3. Repositories (`data/`): TaskRepository (CRUD, observe by tab/date range, overdue rollover, recurring materialization), SettingsRepository.
4. Scheduling (`scheduling/`): DueEngine (alarm sync, evaluate, alert queue, sticky summary every minute, daily prompt logic),
   wired in `TodoApp` (fill in `onAlarmFired`, `onDailyPromptFired`, `onAppForegrounded`, notification actions, `resyncAlarms()`).
5. Assistant (`assistant/`, `llm/`): Koog AIAgent + structured `AssistantPlan`, `LocalEngineLLMClient` (Koog `LLMClient` over
   `LocalLlmEngine` port), `OllamaBackend` (Koog Ollama client, base URL from settings), RuleBasedParser, FailurePolicy (2 strikes →
   manual fallback), ModelDownloader (Ktor, resumable), ModelCatalog (suggested HF URLs per platform), settings.
   Verify Koog 1.3.0 API surface from the sources jars in `~/.gradle/caches` before writing code against it.
6. UI (`ui/`): tabs Day/Week/Month/Recurring, ranked lists with priority badges + countdown chips (tick every second), task editor
   sheet (title, notes, priority, scope, date, time, recurring time), extension menu (+5/+15/+30/+60/custom), due-alert dialog
   (Done/Extend/Dismiss/Delete) also used when `App` is opened from an alert, daily "Plan your day" sheet (quick-add several tasks),
   chat screen (messages, plan confirmation cards, fallback banner, manual fallback), settings (LLM backend: on-device model picker +
   download by URL with progress / Ollama URL + model name + test button; daily prompt time; sound on/off; launch at login toggle
   when `capabilities` supports it). Routes: `today`, `add`, `plan`, `task/<id>`, `alert/<id>`, `chat`, `settings`.
7. Keep `Ports.kt` stable; additive changes only (announce them in your report).

### B. android agent — `shared/src/androidMain`, `androidApp/`
Foreground service + ongoing notification (sticky summary with actions), `AlarmManager.setAlarmClock` scheduler + receiver +
BootReceiver (calls `TodoApp.resyncAlarms()`), full-screen due alert (activity over lock screen; doorbell loop via MediaPlayer on
alarm stream; notification actions Done / +15 / Dismiss / Delete call `TodoApp.*`), daily prompt alarm + notification, runtime
permissions flow (POST_NOTIFICATIONS, full-screen intent, exact alarm not needed with setAlarmClock, battery optimization prompt
once), `LiteRtLlmEngine` implementing `LocalLlmEngine` with LiteRT-LM (or Koog's litert client), `AppPaths`, `HttpEngineProvider`
(OkHttp), capabilities (openUrl, bringToFront). Copy `doorbell.wav` to `res/raw`. Verify on the running emulator with adb
(install, schedule a task 1 min ahead, confirm alarm + sound + notification actions; kill app and confirm alarm still fires).

### C. desktop agent — `shared/src/jvmMain`, `desktopApp/`
Tray menu-bar icon (tooltip/next-due text, menu: Open, Add task, Plan my day, Chat, Quit), window close → hide (process stays),
always-on-top alert window (Done/Extend/Dismiss/Delete) + `SoundPlayer` via javax.sound.sampled (loop), in-process
`AlarmScheduler` (coroutine timers, re-armed on wake: detect sleep by monotonic gaps), daily prompt window (RunAtLoad + on wake),
`LaunchAtLogin` via `~/Library/LaunchAgents/dev.aliziwa.todo.plist` (RunAtLoad, KeepAlive, points at the installed .app or the
dev run script — document both), `LlamaCppEngine` implementing `LocalLlmEngine` with `de.kherud:llama` 4.2.0 (Metal, chat template,
JSON grammar when jsonMode), Ollama reachability check helper, packaging `packageDmg` (include LSUIElement=false, app icon),
single-instance guard (second launch focuses first). Verify: `:desktopApp:run` + a task due in 1 minute → tray updates, alert window,
doorbell; Ollama at localhost:11434 (gemma4:26b present) works through the assistant; download a small gguf and run it.

### D. ios agent — `shared/src/iosMain`, `iosApp/`
Install `xcodegen` (brew) and generate `iosApp/iosApp.xcodeproj` from `project.yml`; SwiftUI app hosting `ComposeUIViewController`
from the `Shared` framework (Gradle `embedAndSignAppleFrameworkForXcode` build phase). Implement in Swift and inject into
`TodoApp.start`: `AlarmScheduler` via `UNUserNotificationCenter` (time-sensitive, custom doorbell sound (convert wav→caf ≤30s),
re-alert chain), `AlertPresenter` (notification categories with actions Done / +15 / Dismiss / Delete → `TodoApp.*`; Live Activity
with ActivityKit widget extension showing next task + countdown; fallback persistent notification), `SoundPlayer` (AVAudioPlayer),
`AppPaths`, Darwin Ktor engine, `LocalLlmEngine` via llama.cpp Swift package (`https://github.com/ggml-org/llama.cpp` SPM, Metal) —
if SPM resolution fails, fall back to a small C/ObjC wrapper built from a pinned llama.cpp tag via a Run Script phase; document.
Daily prompt via local notification + plan sheet on open. Verify with `xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 18 Pro'`
(`DEVELOPER_DIR` env!) and run on the simulator; notification sound and actions testable in simulator.

## Wave 2 (supervisor): integration review per platform, cross-platform build matrix, fixes, end-to-end smoke runs, commit.
