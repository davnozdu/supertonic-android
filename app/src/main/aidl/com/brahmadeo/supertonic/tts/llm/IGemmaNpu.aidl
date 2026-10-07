package com.brahmadeo.supertonic.tts.llm;

import com.brahmadeo.supertonic.tts.llm.IHtpGate;

/** Gemma 4 on the Hexagon NPU, served from its own process (GemmaNpuService). Errors arrive as
 * IllegalStateException; a native abort kills only that process (DeadObjectException here). */
interface IGemmaNpu {
    void load(String modelPath, int contextTokens, int threads, IHtpGate gate);
    String generate(String prompt, int maxTokens, int slot);
    oneway void cancel();
    String stats();
}
