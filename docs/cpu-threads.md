# CPU workers · Beta 4.11

The main screen exposes **Потоки процессора** directly below speed. Values are
saved in `SupertonicPrefs` under `cpu_threads_<model-id>`; Silero RU/CIS have
independent settings. The range is 1 through available processors (safety cap
16). Reset selects a measured recommendation: Tera/Silero/Shtorm 2, Kokoro 4,
legacy Supertonic 6, always capped to available cores.

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
The probe does not play audio or send text to a cloud. Phone results are recorded below.

## OnePlus CPH2745 / Android16 · 5 October2026

Beta4.11 code104 passed all five real-dispatcher probes without playback or
cloud calls. Fresh app process per model; fixed accented Russian sample, speed
1.1. Below: warm pass (PCM cache explicitly cleared), at the final recommended
count. PSS includes the whole app process, not just model weights.

| Model | Workers | Generate ms | CPU ms | Audio ms | PSS MiB |
|---|---:|---:|---:|---:|---:|
| teratts_v2 | 2 | 1027 | 1901 | 6858 | 748 |
| silero_v5_5_ru | 2 | 77 | 140 | 4850 | 292 |
| silero_cis_ru | 2 | 72 | 135 | 5137 | 291 |
| shtorm_pocket_ru | 2 | 853 | 966 | 5227 | 656 |
| kokoro_ru_v2 | 4 | 2002 | 6144 | 4750 | 657 |

Silero RU and CIS are the lightest in this test. Kokoro full precision is the
most expensive in process CPU per second of generated speech. Single-voice
PSS is largest for Tera; Kokoro with its separate Dima + female full models
rose to ~1.2GiB in the earlier three-voice test. Do not turn these observations
into an unmeasured ranking of battery life or of Tera Teacher/local Gemma.

Tera: warm2/4/6 =1027/1308/1115ms, CPU1901/3866/4484ms.
Shtorm: warm2/4/6 =853/901/965ms, CPU966/2194/3640ms (stochastic durations).
Silero RU: warm2/4/6 =77/54/49ms, CPU140/198/259ms.
Kokoro full: warm2/4/6 =2012/2002/1579ms, CPU3791/6144/6864ms.
Thus two workers are a sensible economy default for Tera/Silero/Shtorm,
without approaching real-time deadlines; Kokoro retains four for a balanced
cold/warm latency across the earlier measurements and both package variants.
Six remains selectable when lowest latency matters more than CPU consumption.
This is one short sample per configuration, not an exhaustive performance
claim for all chapters, devices, temperatures or voices. Defaults are starting
points; user choices are retained.

The first UI check exposed Float truncation in the discrete slider. Beta4.11.1
uses nearest-integer rounding; Beta4.11.2 includes that fix and the measured
efficient defaults. Beta4.11.2 code106 installed successfully over the existing signature.
Android Release37297846901 and CI37297844725 succeeded. UI touch at4 saved4,
touch at6 saved6, reset saved4; verified UI XML and persisted preferences.
Kokoro4/Dima/full, original LLM/music settings preserved. Temporary USB
screen-on restored to0, screen timeout30000ms; volume was not changed.
User will perform the listening check; no audio played during these tests.
