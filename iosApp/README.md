# iosApp

SwiftUI host for the shared Compose UI (`Shared.framework`, built from `shared/src/iosMain`),
plus the iOS implementations of the platform ports and the `TodoWidgets` WidgetKit extension
(Live Activity + home-screen widget).

Neither `iosApp.xcodeproj` nor `Vendor/llama.xcframework` is checked in — both are generated.

## First-time setup

```sh
brew install cmake xcodegen

# Always, for every Gradle / xcodebuild / xcrun command in this repo:
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

# 1. Build llama.cpp for iOS (device + simulator). Takes ~6 min, needs ~2 GB of scratch space.
./Vendor/build-llama-xcframework.sh          # -> Vendor/llama.xcframework

# 2. Generate the Xcode project from project.yml.
xcodegen generate                            # -> iosApp.xcodeproj
```

`build-llama-xcframework.sh` clones llama.cpp at a pinned tag (`b11429`, upstream release
v0.6.0; override with `LLAMA_TAG=...`) and runs upstream's `build-xcframework.sh` for
`ios-sim ios-device`. It must be built from source because upstream no longer ships a
`Package.swift` (so SPM is impossible) and the prebuilt release xcframework has no iOS
simulator slice.

## Build and run

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -derivedDataPath iosApp/DerivedData build

xcrun simctl boot "iPhone 18 Pro"
xcrun simctl install booted iosApp/DerivedData/Build/Products/Debug-iphonesimulator/PersonalTodo.app
xcrun simctl launch booted dev.aliziwa.todo
```

The `Build Kotlin framework` run-script phase invokes
`./gradlew :shared:embedAndSignAppleFrameworkForXcode`. While iterating on Swift only, set
`SKIP_KOTLIN_BUILD=1` to reuse the framework from the previous build.

## Running on a device

Simulator builds sign ad-hoc. For a real device, set your team — either in `project.yml`
(`settings.base.DEVELOPMENT_TEAM`) and re-run `xcodegen generate`, or on the command line:

```sh
xcodebuild ... DEVELOPMENT_TEAM=ABCDE12345
```

The app and the widget share the `group.dev.aliziwa.todo` App Group, and the app declares
`com.apple.developer.usernotifications.time-sensitive`; both need a provisioning profile that
includes them.

## Debug launch hooks (`#if DEBUG` only)

Useful in a headless simulator, where the notification permission alert cannot be tapped:

```sh
xcrun simctl launch booted dev.aliziwa.todo \
  -todoDebugProvisional 1 \    # provisional auth instead of the (untappable) prompt
  -todoDebugAlarmSeconds 25 \  # schedule a real re-alert chain N seconds out
  -todoDebugAlarmTaskId demo \ # key to use for it
  -todoDebugSticky 1           # push a fake StickySummary to start the Live Activity
```

## Regenerating the doorbell sound

`iosApp/Resources/doorbell.caf` is converted from the shared asset (notification sounds must
be CAF and ≤ 30 s):

```sh
afconvert -f caff -d LEI16 \
  shared/src/commonMain/composeResources/files/doorbell.wav \
  iosApp/iosApp/Resources/doorbell.caf
```
