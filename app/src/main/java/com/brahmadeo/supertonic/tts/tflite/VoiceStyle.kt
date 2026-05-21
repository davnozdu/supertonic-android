package com.brahmadeo.supertonic.tts.tflite

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Parsed voice style JSON (matches voice_styles/M*.json, F*.json layout).
 * style_dp shape [1, 8, 16] = 128 floats, style_ttl shape [1, 50, 256] = 12800 floats.
 */
data class VoiceStyle(val styleDp: FloatArray, val styleTtl: FloatArray) {
    companion object {
        fun load(file: File): VoiceStyle {
            val root = JSONObject(file.readText())
            return VoiceStyle(
                styleDp = flatten(root.getJSONObject("style_dp").getJSONArray("data"), 1 * 8 * 16),
                styleTtl = flatten(root.getJSONObject("style_ttl").getJSONArray("data"), 1 * 50 * 256),
            )
        }

        private fun flatten(arr: JSONArray, expected: Int): FloatArray {
            val out = FloatArray(expected)
            var idx = 0
            fun recurse(node: Any) {
                if (node is JSONArray) {
                    for (i in 0 until node.length()) recurse(node.get(i))
                } else {
                    out[idx++] = (node as Number).toFloat()
                }
            }
            recurse(arr)
            require(idx == expected) { "VoiceStyle: parsed $idx floats, expected $expected" }
            return out
        }
    }
}
