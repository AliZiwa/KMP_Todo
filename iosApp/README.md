# iosApp — Doorbell

SwiftUI host for the shared Compose UI (`Shared.framework`, built from `shared/src/iosMain`),
plus the iOS implementations of the platform ports and the `TodoWidgets` WidgetKit extension
(Live Activity + home-screen widget).

The product is **Doorbell** (`dev.aliziwa.todo`, team `487Q4488BH`). `iosApp.xcodeproj` is generated
from `project.yml` by `xcodegen` — regenerate it after every `project.yml` change and commit the
result.

## Assistant: Apple Foundation Models

The on-device assistant is Apple Intelligence — `iosApp/iosApp/Ports/AppleFoundationModelEngine.swift`
implements the Kotlin `NativeLlmBridge`, and `SwiftBackedLlmEngine` (iosMain) adapts it to the shared
`LocalLlmEngine` port. There is **no gguf, no llama.cpp and no model download on iOS**: the engine
reports `builtInModel = BuiltInModel("apple-intelligence", "Apple Intelligence")` and an empty
`supportedExtensions`, so the shared layer defaults the assistant to the local engine on first run and
hides the downloader.

Requirements:

- iOS 26.0 or later (hence `deploymentTarget`/`IPHONEOS_DEPLOYMENT_TARGET` 26.0), Xcode 26+.
- An Apple Intelligence-capable device *with the feature switched on*. `loadModel` maps
  `SystemLanguageModel.default.availability` to the message the Settings card shows
  (device not eligible / turn it on in Settings / model still downloading).
- In the simulator, Apple Intelligence has to be available on the **host Mac** — the simulator
  borrows it.

Plans use *guided generation*: the shared layer passes `GenerationOptions.jsonSchema`
(`AssistantPlanSchema.STRICT`), `JsonSchemaToGenerationSchema` converts it to a
`DynamicGenerationSchema`/`GenerationSchema`, and the response is emitted as
`GeneratedContent.jsonString`. If a schema cannot be converted the engine logs and falls back to free
generation with a "JSON only" nudge.

## First-time setup

```sh
brew install xcodegen

# Always, for every Gradle / xcodebuild / xcrun command in this repo:
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

cd iosApp && xcodegen generate       # -> iosApp.xcodeproj
```

## Build and run

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -derivedDataPath iosApp/DerivedData build

xcrun simctl boot "iPhone 18 Pro"
xcrun simctl install booted iosApp/DerivedData/Build/Products/Debug-iphonesimulator/Doorbell.app
xcrun simctl launch booted dev.aliziwa.todo
```

The `Build Kotlin framework` run-script phase invokes
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`. While iterating on Swift only, set
`SKIP_KOTLIN_BUILD=1` to reuse the framework from the previous build.

## Signing / archiving

`project.yml` sets `DEVELOPMENT_TEAM: 487Q4488BH` with automatic signing, so a device build and an
archive work out of the box once Xcode is signed in to that team (Xcode ▸ Settings ▸ Accounts):

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath iosApp/DerivedData/Doorbell.xcarchive archive -allowProvisioningUpdates
```

The app and the widget share the `group.dev.aliziwa.todo` App Group and the app declares
`com.apple.developer.usernotifications.time-sensitive`; automatic signing provisions both.
Uploading to App Store Connect additionally needs an **Apple Distribution** certificate — do that
from Xcode's Organizer.

Privacy manifests live at `iosApp/iosApp/PrivacyInfo.xcprivacy` and
`iosApp/TodoWidgets/PrivacyInfo.xcprivacy` (no tracking, no collected data; declared API reasons:
UserDefaults `CA92.1`, file timestamps `C617.1`, disk space `E174.1`, system boot time `35F9.1` —
the last one because the Kotlin/Native runtime calls `mach_absolute_time`).

## Debug launch hooks (`#if DEBUG` only)

Useful in a headless simulator, where alerts and the keyboard cannot be tapped:

```sh
xcrun simctl launch booted dev.aliziwa.todo \
  -todoDebugProvisional 1 \            # provisional auth: no (untappable) notification prompt
  -todoDebugSeedDemo 1 \               # DemoData.seed — App Store-worthy fake day
  -todoDebugRoute chat \               # today | add | plan | chat | settings | task/<id> | alert/<id>
  -todoDebugAssistantPrompt "dentist friday at 3pm" \  # sends one assistant message
  -todoDebugAlarmSeconds 25 \          # schedule a real re-alert chain N seconds out
  -todoDebugAlarmTaskId <taskId> \     # key to use for it (must be a real task to show the alert)
  -todoDebugSticky 1                   # push a fake StickySummary to start the Live Activity
```

Note `-todoDebugProvisional 1` asks for `[.provisional, .sound, .badge]` *without* `.alert`:
including `.alert` makes iOS show the prompt anyway, which blocks screenshots.

## App Store screenshots

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
iosApp/scripts/screenshots.sh                      # both device classes, light + dark
SKIP_BUILD=1 APPEARANCES=light iosApp/scripts/screenshots.sh
DEVICES="iPhone 18 Pro Max" ROUTES="today chat" iosApp/scripts/screenshots.sh
```

Writes `store/screenshots/<device>/<appearance>/<n>-<route>.png` for the two sizes App Store Connect
wants: iPhone 18 Pro Max 1320×2868 and iPad Pro 13-inch (M5) 2064×2752. The script overrides the
status bar to 9:41, seeds the demo data, and walks `today, chat, plan, settings, add`. The first
launch of each pass is a warm-up: it absorbs the "plan your day" prompt, which otherwise hijacks the
first route of a session.

If a stale, unanswered notification prompt is stuck on a simulator from an older build,
`xcrun simctl erase <udid>` is the only reliable way to clear it.

## Regenerating the doorbell sound

`iosApp/Resources/doorbell.caf` is converted from the shared asset (notification sounds must
be CAF and ≤ 30 s):

```sh
afconvert -f caff -d LEI16 \
  shared/src/commonMain/composeResources/files/doorbell.wav \
  iosApp/iosApp/Resources/doorbell.caf
```
