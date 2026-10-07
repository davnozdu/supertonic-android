# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our native bridge
-keep class com.brahmadeo.supertonic.tts.SupertonicTTS { *; }

# Keep AIDL interfaces and their stubs
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackService { *; }
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackListener { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackService$Stub { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackListener$Stub { *; }

# Keep models/data classes that might be used for serialization/reflection
# If you have any data classes used with Gson/JSON, add them here.
-keep class com.brahmadeo.supertonic.tts.utils.LexiconManager$** { *; }

# Fix: Missing classes detected while running R8 (Missing JP2Decoder from Readium/PDFium)
-dontwarn com.gemalto.jp2.JP2Decoder

# LiteRT / TensorFlow Lite — heavy JNI surface, keep the legacy
# org.tensorflow.lite.* API and the new com.google.ai.edge.litert.* API.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# LiteRT-LM JNI calls Kotlin configuration getters by their original names.
# This is a separate package from litert: renaming SamplerConfig/ThinkingConfig
# causes a native JNI abort (mid == null), which also terminates system TTS.
-keep class com.google.ai.edge.litertlm.** { *; }

# ONNX Runtime Java API wraps native sessions / EPs.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PyTorch and ExecuTorch use original Java class/field names from JNI.
-keep class org.pytorch.** { *; }
-keep class com.facebook.jni.** { *; }

# Our hybrid Kotlin engine — referenced indirectly through SupertonicTTS
# dispatch; protect it from being merged/renamed by R8.
-keep class com.brahmadeo.supertonic.tts.tflite.** { *; }

# Native PocketTTS calls AudioSink.onAudio by its exact JVM name.
-keep class com.brahmadeo.supertonic.tts.pocket.** { *; }
-keep class com.brahmadeo.supertonic.tts.kokoro.KokoroPhonemizer { *; }

# Readability uses SLF4J's no-op fallback; no logger binding is shipped on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder

# Gemma NPU bridge calls these from native code (HTP arbitration with QNN).
-keep class com.brahmadeo.supertonic.tts.utils.Npu {
    public static void lockHtp();
    public static void unlockHtp();
}
