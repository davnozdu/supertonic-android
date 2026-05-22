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

# ONNX Runtime Java API wraps native sessions / EPs.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Our hybrid Kotlin engine — referenced indirectly through SupertonicTTS
# dispatch; protect it from being merged/renamed by R8.
-keep class com.brahmadeo.supertonic.tts.tflite.** { *; }
