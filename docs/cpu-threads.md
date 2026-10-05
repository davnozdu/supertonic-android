# CPU workers · Beta 4.11

The main screen exposes **Потоки процессора** directly below speed. Values are
saved in `SupertonicPrefs` under `cpu_threads_<model-id>`; Silero RU/CIS have
independent settings. The range is 1 through available processors (safety cap
16). Reset selects a conservative recommendation: Tera/legacy Supertonic 6,
Silero, Shtorm and Kokoro 4, always capped to available cores.

The count is passed to ORT, PyTorch/ExecuTorch, PocketTTS JNI, and the legacy
TFLite/XNNPACK engines. A resident engine with an old count is closed under the
synthesis lock before the next uncached generation. The current PCM playback
continues. CPU changes invalidate future PCM/ahead work while keeping LLM text
results. The count is also present in PCM keys to guard preference races.

These are inference workers, not a CPU affinity or whole-process thread limit:
Android, audio output, HTTP and LLM still have their own threads. Increasing the
count does not guarantee lower latency or lower battery use on heterogeneous
mobile CPUs. Existing custom values are never overwritten by new defaults.

`SpeechDiagnosticsActivity --es model <id> --ez threadsProbe true` runs the
actual dispatcher at 2/4/6 workers on the same accented preview sentence, one
cold and one warm pass per count. It logs elapsed time, process CPU time, audio
length and whole-process PSS, then restores the original model/voice/count.
The probe does not play audio or send text to a cloud. Phone results follow
once the GitHub APK is installed.
