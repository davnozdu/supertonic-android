#!/usr/bin/env bash
# Gemma 4 on the Hexagon NPU: pinned prebuilt llama.cpp runtime + our small JNI bridge (arm64 only).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=$(sed -n 's/^ndk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
if [ -z "$NDK" ]; then NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}; fi
RUNTIME="$ROOT/build/gemma-npu-runtime"
python3 "$ROOT/tools/gemma_npu/fetch_runtime.py" "$RUNTIME"
COMMIT=$(python3 -c "import json;print(json.load(open('$ROOT/tools/gemma_npu/runtime.json'))['llamaCommit'])")
SOURCE="$ROOT/build/llama-src"
if [ "$(git -C "$SOURCE" rev-parse HEAD 2>/dev/null || true)" != "$COMMIT" ]; then
  rm -rf "$SOURCE"; mkdir -p "$SOURCE"
  git -C "$SOURCE" init -q
  git -C "$SOURCE" remote add origin https://github.com/ggml-org/llama.cpp.git
  git -C "$SOURCE" fetch -q --depth 1 origin "$COMMIT"
  git -C "$SOURCE" checkout -q FETCH_HEAD
fi
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$COMMIT"
cmake -S "$ROOT/app/src/main/cpp/gemma_npu" -B "$ROOT/build/gemma-npu-native" \
  -DLLAMA_SOURCE="$SOURCE" -DRUNTIME_DIR="$RUNTIME" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build "$ROOT/build/gemma-npu-native" --target gemma_npu --parallel 2
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"; mkdir -p "$OUT"
cp "$ROOT/build/gemma-npu-native/libgemma_npu.so" "$RUNTIME"/*.so "$OUT/"
