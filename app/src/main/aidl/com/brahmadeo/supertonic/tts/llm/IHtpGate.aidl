package com.brahmadeo.supertonic.tts.llm;

/** The app-wide HTP turn (utils.Npu) held in the main process on behalf of the Gemma NPU process. */
interface IHtpGate {
    void lock();
    void unlock();
}
