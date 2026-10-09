#!/usr/bin/env bash
# Rebuilds iosApp/Vendor/llama.xcframework from a pinned llama.cpp tag.
#
# Why vendored instead of SPM: ggml-org/llama.cpp no longer ships a Package.swift
# (404 at master and at every tag after ~b4000), so SPM resolution is impossible.
# The official prebuilt release asset (llama-<tag>-xcframework.zip) contains only
# ios-arm64 + macos slices -- no iOS *simulator* slice -- so it cannot be used for
# simulator builds either. We therefore build the ios-sim + ios-device slices from a
# pinned source checkout with upstream's own build-xcframework.sh.
#
# Requires: cmake, Xcode. Run with DEVELOPER_DIR pointing at Xcode.
set -euo pipefail
TAG="${LLAMA_TAG:-b11429}"   # == upstream release v0.6.0
HERE="$(cd "$(dirname "$0")" && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

if [[ ! -d "$HERE/llama.cpp/.git" ]]; then
  git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$HERE/llama.cpp"
fi
cd "$HERE/llama.cpp"
./build-xcframework.sh ios-sim ios-device
rm -rf "$HERE/llama.xcframework"
cp -R "$HERE/llama.cpp/build-apple/llama.xcframework" "$HERE/llama.xcframework"
echo "built $HERE/llama.xcframework from llama.cpp $TAG"
