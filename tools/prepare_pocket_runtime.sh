#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then NDK=$(sed -n 's/^ndk.dir=//p' "$ROOT/local.properties"); fi
mkdir -p "$ROOT/build/pocket-headers" "$ROOT/app/src/main/jniLibs/arm64-v8a"
for HEADER in onnxruntime_c_api.h onnxruntime_cxx_api.h onnxruntime_cxx_inline.h onnxruntime_float16.h onnxruntime_ep_c_api.h; do
  curl -fL "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.26.0/include/onnxruntime/core/session/$HEADER" -o "$ROOT/build/pocket-headers/$HEADER"
done
cd "$ROOT"
./gradlew extractOnnxLib
cmake -S app/src/main/cpp/pocket -B build/pocket-native -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
cmake --build build/pocket-native --target pockettts_jni --parallel 2
cp build/pocket-native/libpockettts_jni.so app/src/main/jniLibs/arm64-v8a/
