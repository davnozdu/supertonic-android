package com.brahmadeo.supertonic.tts

import com.brahmadeo.supertonic.tts.tera.TeraCustomVoices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TeraCustomVoicesTest {
    private val ttl = intArrayOf(1, 50, 256)
    private val dp = intArrayOf(1, 8, 16)

    @Test fun npyRoundTripMatchesTheBuiltInLayout() {
        val data = FloatArray(128) { it * 0.01f - 0.5f }
        val bytes = TeraCustomVoices.npy(data, dp)
        // Same 128-byte header as the bundled Tera styles: TeraEngine.loadNpy and installed() rely on it.
        assertEquals(128 + 128 * 4, bytes.size)
        val header = String(bytes, 10, 118, Charsets.US_ASCII)
        assertTrue(header.startsWith("{'descr': '<f4', 'fortran_order': False, 'shape': (1, 8, 16), }"))
        assertTrue(header.endsWith("\n"))
        assertArrayEquals(data, TeraCustomVoices.readNpy(bytes, dp), 0f)
        assertEquals(128 + 12800 * 4, TeraCustomVoices.npy(FloatArray(12800), ttl).size)
    }

    @Test fun float64NpyIsConverted() {
        val dict = "{'descr': '<f8', 'fortran_order': False, 'shape': (1, 8, 16), }".padEnd(117) + "\n"
        val bb = ByteBuffer.allocate(10 + dict.length + 128 * 8).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0x93.toByte()).put("NUMPY".toByteArray()).put(1).put(0).putShort(dict.length.toShort()).put(dict.toByteArray())
        repeat(128) { bb.putDouble(it / 128.0) }
        val out = TeraCustomVoices.readNpy(bb.array(), dp)
        assertEquals(127 / 128f, out[127], 1e-6f)
    }

    @Test fun wrongShapeIsRejected() {
        val bytes = TeraCustomVoices.npy(FloatArray(128), intArrayOf(1, 16, 8))
        try { TeraCustomVoices.readNpy(bytes, dp); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1×16×8"))
        }
    }

    private fun nested(n0: Int, n1: Int, n2: Int, value: (Int) -> Double) = JSONArray().apply {
        var i = 0
        repeat(n0) { put(JSONArray().apply { repeat(n1) { put(JSONArray().apply { repeat(n2) { put(value(i++)) } }) } }) }
    }

    @Test fun supertonicJsonComponentIsFlattenedInOrder() {
        val o = JSONObject().put("dims", JSONArray(listOf(1, 8, 16))).put("data", nested(1, 8, 16) { it / 1000.0 })
        val out = TeraCustomVoices.component(o, dp, "style_dp")
        assertEquals(128, out.size)
        assertEquals(0.127f, out[127], 1e-6f)
    }

    @Test fun jsonWithWrongDimsOrCountFails() {
        val wrongDims = JSONObject().put("dims", JSONArray(listOf(1, 16, 8))).put("data", nested(1, 16, 8) { 0.0 })
        val short = JSONObject().put("dims", JSONArray(listOf(1, 8, 16))).put("data", nested(1, 8, 15) { 0.0 })
        for (o in listOf(wrongDims, short)) {
            try { TeraCustomVoices.component(o, dp, "style_dp"); fail() } catch (_: TeraCustomVoices.Failure) { }
        }
    }

    @Test fun labelsHideThePrefixAndHash() {
        assertEquals("Свой · Римский", TeraCustomVoices.label("my_Римский_a1b2c3"))
        assertEquals("Свой · gravel pete", TeraCustomVoices.label("my_gravel_pete_0f9e8d"))
        assertTrue(TeraCustomVoices.isCustom("my_x_000000"))
        assertFalse(TeraCustomVoices.isCustom("ru_f1"))
    }
}
