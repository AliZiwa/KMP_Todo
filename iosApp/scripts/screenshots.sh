#!/usr/bin/env bash
# App Store screenshots for Jukiza-Reminder.
#
#   export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
#   iosApp/scripts/screenshots.sh                 # both device classes, light + dark
#   APPEARANCES=light iosApp/scripts/screenshots.sh
#   DEVICES="iPhone 18 Pro Max" iosApp/scripts/screenshots.sh
#
# Writes store/screenshots/<device>/<appearance>/<n>-<route>.png.
# Expected sizes: iPhone 18 Pro Max 1320x2868 (6.9"), iPad Pro 13-inch (M5) 2064x2752.
#
# Needs a Debug build: the launch hooks (-todoDebugSeedDemo, -todoDebugRoute,
# -todoDebugAssistantPrompt, -todoDebugProvisional) are all `#if DEBUG`.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$PWD"
OUT="$ROOT/store/screenshots"
APP="$ROOT/iosApp/DerivedData/Build/Products/Debug-iphonesimulator/Jukiza-Reminder.app"
BUNDLE_ID="dev.aliziwa.jukizareminder"

: "${DEVELOPER_DIR:=/Applications/Xcode.app/Contents/Developer}"
export DEVELOPER_DIR

DEVICES_DEFAULT=$'iPhone 18 Pro Max\niPad Pro 13-inch (M5)'
DEVICES="${DEVICES:-$DEVICES_DEFAULT}"
APPEARANCES="${APPEARANCES:-light dark}"
ROUTES="${ROUTES:-today chat plan settings add}"
SKIP_BUILD="${SKIP_BUILD:-0}"

# Seconds to wait after launch before the screenshot. The chat route has to wait for
# Apple Intelligence to answer.
SETTLE=6
SETTLE_CHAT=30

log() { printf '\n=== %s\n' "$*"; }

udid_for() {
  xcrun simctl list devices available \
    | sed -n "s/^ *$1 (\([0-9A-F-]\{36\}\)).*/\1/p" | head -1
}

shoot() {           # shoot <udid> <dir> <index> <route>
  local udid="$1" dir="$2" index="$3" route="$4"
  local args=(-todoDebugProvisional 1 -todoDebugRoute "$route")
  local settle=$SETTLE
  if [ "$route" = chat ]; then
    args+=(-todoDebugAssistantPrompt "dentist friday at 3pm")
    settle=$SETTLE_CHAT
  fi
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  sleep 1
  xcrun simctl launch "$udid" "$BUNDLE_ID" "${args[@]}" >/dev/null
  sleep "$settle"
  mkdir -p "$dir"
  xcrun simctl io "$udid" screenshot "$dir/$index-$route.png" >/dev/null
  printf '  %s  %s\n' "$dir/$index-$route.png" \
    "$(sips -g pixelWidth -g pixelHeight "$dir/$index-$route.png" | awk '/pixel/ {printf "%s ", $2}')"
}

if [ "$SKIP_BUILD" != "1" ]; then
  log "Building Jukiza-Reminder.app (Debug, simulator)"
  # A concrete simulator, not 'generic/platform=iOS Simulator': the generic destination adds
  # x86_64 to ARCHS and Compose's syncComposeResourcesForIos rejects that arch.
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -destination 'platform=iOS Simulator,name=iPhone 18 Pro Max' \
    -derivedDataPath iosApp/DerivedData \
    -configuration Debug build -quiet
fi
[ -d "$APP" ] || { echo "missing $APP" >&2; exit 1; }

while IFS= read -r device; do
  [ -n "$device" ] || continue
  udid="$(udid_for "$device")"
  if [ -z "$udid" ]; then
    echo "skipping unknown simulator: $device" >&2
    continue
  fi
  slug="$(echo "$device" | tr 'A-Z ' 'a-z-' | tr -cd 'a-z0-9-')"
  log "$device ($udid)"

  xcrun simctl boot "$udid" >/dev/null 2>&1 || true
  xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true

  # A clean, marketing-friendly status bar.
  xcrun simctl status_bar "$udid" override \
    --time "9:41" --batteryState charged --batteryLevel 100 \
    --wifiBars 3 --cellularBars 4 --operatorName "" >/dev/null

  # A build at the pre-rename bundle id (or any stale copy) must not linger: an unanswered
  # system prompt from an old build sits over SpringBoard, hides the status bar in captures
  # and cannot be dismissed headlessly. If a capture still lacks the status bar, run
  # `xcrun simctl erase <udid>` once and re-run this script.
  xcrun simctl uninstall "$udid" dev.aliziwa.todo >/dev/null 2>&1 || true
  xcrun simctl install "$udid" "$APP" >/dev/null

  for appearance in $APPEARANCES; do
    log "$device — $appearance"
    xcrun simctl ui "$udid" appearance "$appearance" >/dev/null

    # Warm-up launch: seeds the demo data and absorbs the "plan your day" prompt, which
    # otherwise hijacks the first route of the session.
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl launch "$udid" "$BUNDLE_ID" \
      -todoDebugProvisional 1 -todoDebugSeedDemo 1 -todoDebugRoute today >/dev/null
    sleep 12

    index=1
    for route in $ROUTES; do
      shoot "$udid" "$OUT/$slug/$appearance" "$index" "$route"
      index=$((index + 1))
    done
  done

  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl status_bar "$udid" clear >/dev/null 2>&1 || true
  xcrun simctl ui "$udid" appearance light >/dev/null 2>&1 || true
done <<< "$DEVICES"

log "done — $OUT"
