# Personal Todo (Kotlin Multiplatform)

A personal to-do app for macOS, Android and iOS with one shared Compose Multiplatform UI, Room (SQLite) storage on every
platform, and an offline natural-language assistant built on Koog that runs against an on-device LLM.

## Features
- CRUD tasks with notes, priority (High / Medium / Low) and dates; lists rank by priority, then due time.
- Tabs: **Day**, **Week**, **Month**, **Recurring** (daily templates, e.g. "pick up kids at 3pm"; each day is tracked separately).
- Day tasks have a due time with a live countdown; extend by +5 / +15 / +30 / +60 / custom before or after they are due.
- When a task is due the app rings a doorbell and shows a sticky alert until you mark it Done, Extend, Dismiss or Delete.
- Always resident: macOS menu-bar icon (tooltip and menu show the next task), Android ongoing notification with countdown,
  iOS Live Activity + time-sensitive notifications. Alarms fire even when the UI is closed.
- Launch at login on macOS (LaunchAgent, KeepAlive) and a once-per-day "Plan your day" pop-up to enter the day's tasks.
- Chat assistant: type "remind me to call the dentist tomorrow at 10, high priority". Plans are shown as confirmation cards.
  Works strictly offline: on-device model (llama.cpp on macOS, LiteRT-LM on Android, Apple Intelligence on iOS) or a local Ollama / OpenAI-compatible
  server. Two consecutive assistant failures switch to a manual form prefilled by the built-in rule parser.
- Model management (macOS/Android): paste a Hugging Face `resolve` URL to download a model (resumable, progress, free-space
  check), pick a downloaded model, or point at Ollama (`http://localhost:11434`, e.g. `gemma4:26b`). iOS uses the system model
  (Apple Intelligence, iOS 26+) with nothing to download.

## Build & run
Prerequisites on this Mac are already set up: Homebrew JDK 21 (pinned in `gradle.properties`), Android SDK, Xcode 27.
Always `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` first.

| Platform | Command |
|---|---|
| macOS dev run | `./gradlew :desktopApp:run` |
| macOS installer | `./gradlew :desktopApp:packageDmg` → `desktopApp/build/compose/binaries/main/dmg/` |
| macOS launch at login | Settings → "Launch at login", or `/Applications/PersonalTodo.app/Contents/MacOS/PersonalTodo --install-login-agent` |
| Android | `./gradlew :androidApp:installDebug` (emulator or device) |
| iOS | `cd iosApp && xcodegen generate` then open `iosApp.xcodeproj` or `xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 18 Pro'` (see `iosApp/README.md`) |
| Tests | `./gradlew :shared:jvmTest :desktopApp:jvmTest` |

## Layout
`shared/` (domain, Room, scheduling engine, Koog assistant, Compose UI), `androidApp/`, `desktopApp/`, `iosApp/`.
Design, contracts and the edge-case checklist: `docs/ARCHITECTURE.md`. Working rules: `CLAUDE.md`.

## Platform notes and limits
- **iOS** cannot keep an app running permanently or pin a status-bar item. The app uses a Live Activity (lock screen /
  Dynamic Island) plus time-sensitive notifications that re-alert every 2 minutes (10 times) until acted on. The App Store
  build is called **Doorbell**; see `docs/APP_STORE.md` for the submission checklist and `store/` for listing assets.
- **Android** uses `setAlarmClock` exact alarms (install-time `USE_EXACT_ALARM`), a special-use foreground service and a
  full-screen alert over the lock screen. Accept the notification, full-screen-intent and battery-optimization prompts on
  first launch. On-device models must be `.litertlm` files (e.g. Gemma 3 1B from the `litert-community` org on Hugging Face).
- **macOS** on-device models are `.gguf` files (suggested: Qwen2.5-1.5B-Instruct Q4_K_M). Dev-mode launch-at-login from a
  checkout under `~/Documents` is blocked by macOS privacy (TCC); use the packaged app in `/Applications`.
