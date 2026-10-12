# Personal Todo (KMP) — working rules

Kotlin Multiplatform to-do app: Android, iOS, macOS (Compose Desktop/JVM). Shared Compose UI, Room KMP storage,
Koog-based offline natural-language assistant backed by on-device LLMs.

Read `docs/ARCHITECTURE.md` (contracts, decisions, edge cases) and `docs/PLAN.md` (who owns what) before changing anything.

## Build
- JDK 21 is pinned in `gradle.properties` (`org.gradle.java.home`). Do not change it.
- Always export `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` before Gradle/xcodebuild (xcode-select points at CLT and must not be changed; no sudo).
- Android SDK: `~/Library/Android/sdk` (see `local.properties`). Emulator `Pixel_10a` may already be running (`adb devices`).
- Commands:
  - `./gradlew :shared:jvmTest` — shared unit tests (fast, run often)
  - `./gradlew :shared:compileKotlinJvm :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`
  - `./gradlew :desktopApp:run` / `./gradlew :desktopApp:packageDmg`
  - `./gradlew :androidApp:installDebug`
  - `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` then `xcodebuild` inside `iosApp/`
- Desktop packaging (`:desktopApp:packageDmg`) and `:desktopApp:run` use a non-Homebrew JDK discovered via `/usr/libexec/java_home`
  (Compose's `checkRuntime` rejects Homebrew JDKs); compilation still uses the pinned JDK 21. See `desktopApp/build.gradle.kts`.
- iOS: `cd iosApp && xcodegen generate`. The LLM engine is Apple's Foundation Models framework (iOS 26+, no vendored llama.cpp).
  Screenshots for the store: `iosApp/scripts/screenshots.sh`. See `iosApp/README.md` and `docs/APP_STORE.md`.
- Use `--console=plain` and redirect long output to a log file; grep for `error:` / `e: ` instead of dumping it.
- Never run two Gradle builds concurrently in the same checkout from one agent; other agents may be building — if you see a lock
  timeout, wait 60s and retry rather than killing daemons.

## Ownership (hard rules for agents)
| Area | Owner |
|---|---|
| `shared/src/commonMain`, `shared/src/commonTest`, `shared/build.gradle.kts` (common block), `shared/schemas` | shared-core agent |
| `shared/src/androidMain`, `androidApp/` | android agent |
| `shared/src/jvmMain`, `desktopApp/` | desktop agent |
| `shared/src/iosMain`, `iosApp/` | ios agent |
| `gradle/libs.versions.toml`, root gradle files, `docs/`, `CLAUDE.md` | supervisor (agents may APPEND library entries to the catalog; never change `[versions]` toolchain lines) |

- Platform agents never edit `commonMain`. If a port in `platform/Ports.kt` is missing something, report it to the supervisor
  with the exact signature you need; keep working with a local adapter in your own module meanwhile.
- The shared agent never edits platform source sets or platform modules.
- Re-read a shared file (catalog, `shared/build.gradle.kts`) immediately before editing it; keep edits minimal and additive.
- Do not reformat files you don't own. Do not rename packages. Package root is `dev.aliziwa.todo`.
- Commit nothing; the supervisor commits.

## Conventions
- Kotlin 2.4, explicit API not required. `kotlinx.datetime` 0.8 (uses `kotlin.time.Instant`). All times stored as epoch millis UTC
  plus the local date string (`yyyy-MM-dd`) the task belongs to; render in `Clock.timeZoneId()`.
- No DI framework: manual wiring in `AppGraph`.
- Logging: `dev.aliziwa.todo.util.Log` (shared agent provides) — never `println` in shared code.
- Strings hard-coded English for now (single user). Keep UI copy short.
- Offline-first: the only network calls allowed are (a) user-initiated model downloads and (b) user-configured local-network LLM
  endpoints (Ollama / OpenAI-compatible). Nothing else, ever.
