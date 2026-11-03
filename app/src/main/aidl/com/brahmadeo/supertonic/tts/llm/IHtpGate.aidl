package com.brahmadeo.supertonic.tts.llm;

import android.os.IBinder;

/** The app-wide HTP turn (utils.Npu) held in the main process on behalf of another process (Gemma NPU, sounds).
 *  [watch] takes the caller's own binder token: when that process dies while holding the turn, the gate releases it,
 *  otherwise a crashed worker would keep the NPU locked for the reader. */
interface IHtpGate {
    void lock();
    void unlock();
    void watch(IBinder client);
}
