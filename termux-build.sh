#!/bin/bash
# =============================================================================
# Supertonic Termux Build Script
# 
# This script automates the process of building the native Rust library with 
# XNNPACK support and assembling the Android APK directly on a Termux device.
# =============================================================================

set -e

# --- Configuration ---
# Paths for NDK and ONNX Runtime as provided by the user context
NDK_VERSION="android-ndk-r29"
NDK_PATH="/data/data/com.termux/files/home/.android-sdk/ndk/$NDK_VERSION"
ORT_LIB_DIR="/data/data/com.termux/files/home/onnxruntime-android/jni/arm64-v8a"
TARGET="aarch64-linux-android"
API_LEVEL="29"

# Automatically detect the toolchain subdirectory (usually linux-aarch64 in Termux)
TOOLCHAIN_DIR=$(ls -d $NDK_PATH/toolchains/llvm/prebuilt/linux-* | head -n 1)
CLANG_PATH="$TOOLCHAIN_DIR/bin/${TARGET}${API_LEVEL}-clang"

echo "=== Supertonic Termux Build Script ==="
echo "NDK Path: $NDK_PATH"
echo "Clang:    $CLANG_PATH"
echo "ORT Libs: $ORT_LIB_DIR"

# Verify paths exist
if [ ! -d "$NDK_PATH" ]; then
    echo "Error: NDK path not found at $NDK_PATH"
    exit 1
fi
if [ ! -f "$CLANG_PATH" ]; then
    echo "Error: Clang not found at $CLANG_PATH"
    exit 1
fi
if [ ! -d "$ORT_LIB_DIR" ]; then
    echo "Error: ONNX Runtime libs not found at $ORT_LIB_DIR"
    exit 1
fi

# --- 1. Build Rust Library ---
echo ""
echo "--- Step 1: Building Rust library with XNNPACK ---"
# ORT_LIB_LOCATION is required by the 'ort' crate to link against libonnxruntime.so
export ORT_LIB_LOCATION="$ORT_LIB_DIR"
# Tell cargo which linker to use for the Android target
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG_PATH"

cd rust
cargo build --target $TARGET --features xnnpack --release
cd ..

# --- 2. Prepare JNI Libs ---
echo ""
echo "--- Step 2: Preparing JNI libraries for Android build ---"
mkdir -p app/src/main/jniLibs/arm64-v8a
cp rust/target/$TARGET/release/libsupertonic_tts.so app/src/main/jniLibs/arm64-v8a/
cp "$ORT_LIB_DIR/libonnxruntime.so" app/src/main/jniLibs/arm64-v8a/

# --- 3. Build Android App ---
echo ""
echo "--- Step 3: Assembling APK with Gradle ---"
# Using system gradle as requested
gradle assembleDebug

# --- 4. Export APK ---
echo ""
echo "--- Step 4: Exporting APK to /sdcard/ ---"
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
if [ -f "$APK_PATH" ]; then
    DEST="/sdcard/supertonic-xnnpack-test.apk"
    cp "$APK_PATH" "$DEST" || DEST="/storage/emulated/0/supertonic-xnnpack-test.apk" && cp "$APK_PATH" "$DEST"
    echo "Success! APK copied to $DEST"
else
    echo "Error: APK not found!"
    exit 1
fi

echo ""
echo "=== Build Completed Successfully ==="
