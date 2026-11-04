package com.brahmadeo.supertonic.tts.utils

/** Полочный фильтр верхних частот (RBJ, S = 1): +[gainDb] выше [f0] Гц, низы без изменений. Для «Яркости» Shtorm
 * (идея форка TeraTTS 1.11): кодек Shtorm (24 кГц) смягчает верха, голос звучит глуховато. Состояние фильтра идёт
 * через все куски фразы подряд — швов между ними нет. */
class HighShelf(gainDb: Float, f0: Float, fs: Float) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    init {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / fs
        val cw = Math.cos(w0); val sw = Math.sin(w0)
        val alpha = sw / 2.0 * Math.sqrt(2.0)
        val sa = 2.0 * Math.sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cw + sa
        b0 = a * ((a + 1) + (a - 1) * cw + sa) / a0
        b1 = -2 * a * ((a - 1) + (a + 1) * cw) / a0
        b2 = a * ((a + 1) + (a - 1) * cw - sa) / a0
        a1 = 2 * ((a - 1) - (a + 1) * cw) / a0
        a2 = ((a + 1) - (a - 1) * cw - sa) / a0
    }
    fun process(x: FloatArray) {
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }
}
