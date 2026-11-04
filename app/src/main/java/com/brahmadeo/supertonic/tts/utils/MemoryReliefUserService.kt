package com.brahmadeo.supertonic.tts.utils

import kotlin.system.exitProcess

/** Работает не в MyTTS, а в процессе, который Shizuku запускает от имени shell (как `adb shell`): только там
 * разрешено закрывать чужие фоновые приложения. Конструктор без параметров — так его создаёт Shizuku. */
class MemoryReliefUserService : IMemoryRelief.Stub() {
    override fun destroy() { exitProcess(0) }
    override fun killBackground(): Int = ProcessBuilder("am", "kill-all").redirectErrorStream(true).start().waitFor()
}
