package com.brahmadeo.supertonic.tts.utils

import kotlin.system.exitProcess

/** Работает не в MyTTS, а в процессе, который Shizuku запускает от имени shell (как `adb shell`): только там
 * разрешено закрывать чужие фоновые приложения. Конструктор без параметров — так его создаёт Shizuku. */
class MemoryReliefUserService : IMemoryRelief.Stub() {
    override fun destroy() { exitProcess(0) }
    override fun killBackground(): String {
        fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        val before = run("ps", "-A", "-o", "NAME=")
        run("am", "kill-all")
        Thread.sleep(1000)
        return before + "\n---\n" + run("ps", "-A", "-o", "NAME=")
    }
}
