#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then NDK=$(sed -n 's/^ndk.dir=//p' "$ROOT/local.properties"); fi
ESPEAK_REV=4870adfa25b1a32b4361592f1be8a40337c58d6c
SOURCE="$ROOT/build/kokoro-espeak-src"
if [ ! -d "$SOURCE/.git" ]; then git clone https://github.com/espeak-ng/espeak-ng.git "$SOURCE"; fi
git -C "$SOURCE" checkout --detach "$ESPEAK_REV"
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$ESPEAK_REV"
cmake -S "$ROOT/app/src/main/cpp/kokoro" -B "$ROOT/build/kokoro-native" \
  -DESPEAK_SOURCE="$SOURCE" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build "$ROOT/build/kokoro-native" --target kokoro_g2p --parallel 2
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$ROOT/build/kokoro-native/libkokoro_g2p.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/"
