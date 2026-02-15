# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Build Debug APK**: `./gradlew assembleDebug`
- **Install Debug APK**: `./gradlew installDebug`
- **Build Release APK**: `./gradlew assembleRelease`
- **Clean Build**: `./gradlew clean`
- **Rust Build (Android)**: Automatically handled by Gradle (`./gradlew cargoBuild`)
- **Rust Build (CLI/Host)**: `cd rust && cargo build --release`

## Test & Lint Commands

- **Run Unit Tests (Android)**: `./gradlew testDebugUnitTest`
- **Run Single Unit Test (Android)**: `./gradlew testDebugUnitTest --tests "com.brahmadeo.supertonic.tts.ExampleTest"`
- **Run Instrumented Tests**: `./gradlew connectedDebugAndroidTest`
- **Lint Code**: `./gradlew lintDebug`
- **Rust Check**: `cd rust && cargo check`
- **Rust Test**: `cd rust && cargo test`
- **Run Rust CLI Example**: `cd rust && cargo run --release --bin example_onnx -- --text "Hello world"`

## Architecture Overview

This is a hybrid Android application that uses a native Rust backend for high-performance Text-to-Speech (TTS) inference via ONNX Runtime.

- **Frontend (Android)**:
  - Written in Kotlin.
  - UI built with Jetpack Compose (Material 3).
  - Implements Android's `TextToSpeechService` API for system-wide integration.
  - Communicates with the backend via JNI.

- **Backend (Rust)**:
  - Located in `rust/`.
  - Compiled to a shared library (`libsupertonic_tts.so`) for Android.
  - Uses `ort` (ONNX Runtime) for model inference.
  - Handles audio generation, thermal management, and text normalization.
  - Can be run as a standalone CLI for testing/debugging models on the host machine.

- **Bridge (JNI)**:
  - `app/src/main/java/com/brahmadeo/supertonic/tts/SupertonicTTS.kt`: Kotlin wrapper.
  - `rust/src/lib.rs`: Rust JNI implementation.

## Directory Structure

- `app/`: Android application module.
  - `src/main/java/`: Kotlin source code.
  - `src/main/res/`: Android resources.
- `rust/`: Rust library crate (`supertonic_tts`).
  - `src/`: Rust source code.
  - `vendor/`: Vendored dependencies (critical for F-Droid offline builds).
- `metadata/`: F-Droid build metadata and recipes.
- `assets/`: Contains ONNX models and voice styles (managed via Git LFS).

## Development Notes

- **SDK/NDK**: Requires Android SDK 35 and NDK 26.x (r26b).
- **Rust Targets**: Ensure `aarch64-linux-android` (and others as needed) are added via `rustup`.
- **ONNX Runtime**: The Android build extracts `libonnxruntime.so` from the AAR dependency.
- **F-Droid/Offline Builds**: The project is configured for F-Droid. Dependencies are vendored in `rust/vendor`. When adding new Rust dependencies, ensure they are vendored correctly.
