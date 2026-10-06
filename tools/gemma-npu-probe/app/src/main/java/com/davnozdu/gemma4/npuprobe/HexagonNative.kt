package com.davnozdu.gemma4.npuprobe

object HexagonNative {
    init { System.loadLibrary("gemma_hexagon_probe") }
    external fun benchmark(directory: String, arguments: Array<String>, stdoutPath: String, stderrPath: String): Int
}
