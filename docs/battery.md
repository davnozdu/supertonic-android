# Battery (OnePlus CPH2745 / SM8850, 7 October 2026)

Method: Wi-Fi ADB, USB unplugged, screen off, Moon+ Reader, LLM AUTO (cloud), multi-voice, background music on.
Energy from the fuel gauge `charge_counter` (2 mAh steps), idle baseline ≈121 mA subtracted; audio made from the
PCM cache size at the end of each 10-minute window (OnePlus logd drops many MyTTS log lines). Pausing charging via
`mmi_charging_enable` does not work: the phone then runs from USB and the battery current is 0.

| Kokoro full, 10 min of reading | Energy above idle | Audio prepared | Per minute of audio |
|---|---|---|---|
| NPU on | 72 mAh | 15.1 min | ≈4.8 mAh |
| NPU off | 160 mAh | 18.4 min | ≈8.7 mAh (+80 %) |

Most energy went into the opening burst: 15–18 minutes of audio were prepared ahead within ~5 minutes, then
playback ran near idle (~80–120 mA). 4.15 caps ahead synthesis by time ("Запас готового звука вперёд", default
5 min) on top of the RAM limit, and the ahead worker sleeps until played audio frees room instead of polling at 5 Hz.

Per-sentence logs (voice routing, text/synth traces, cache hits, ahead PCM, per-call synthesis timings) print only
with the `verbose_logs` pref or while SpeechDiagnostics runs (`utils/DiagLog`); lifecycle, errors and LLM failures
always log.
