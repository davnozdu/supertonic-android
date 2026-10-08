# Проверка ударений, чисел и пунктуации на телефоне

Тексты прогоняются через тот же путь подготовки, что и при чтении (LLM, валидатор, проверка ударений, Silero,
словарь-судья), диагностикой `SpeechDiagnosticsActivity --ez stressProbe true`. Звук не воспроизводится,
настройки не меняются. Ключи API остаются внутри приложения.

Телефон должен быть **разблокирован**: при заблокированном экране Android 16 отключает приложению сеть
(`blocked=APP_BACKGROUND`), и всё уходит в словарь. Activity не экспортирована, запуск только через `su`.

```bash
cd tools/stress_tests
./run_probe.sh idiot_ch1.txt OLLAMA /tmp/ch1.json          # «Идиот», гл. 1, 45 абзацев
python3 -I ch1_key.py /tmp/ch1.json                        # 41 ключевое слово (Beta 16: 39/41)
./run_probe.sh hard.txt OLLAMA /tmp/hard.json              # омографы, имена, частые ошибки
python3 -I hard_stress.py score /tmp/hard.json             # 71 слово (Beta 16: 66/71)
./run_probe.sh formats.txt OLLAMA /tmp/formats.json        # даты, числа, римские, аббревиатуры, единицы
./run_probe.sh punctuation.txt OLLAMA /tmp/punct.json      # запятые и ловушки «не раз было»
python3 -I analyze_stress.py /tmp/ch1.json                 # охват, имена, разночтения
```

Дополнительные аргументы пробы (четвёртый параметр `run_probe.sh`): `--es verifier SAME|GEMINI|OLLAMA`,
`--es engine gpu|npu`, `--ez localThinking true`; провайдер `LOCAL` (Gemma) или `OFFLINE` (только Silero).
Файлы `cache/gemma-instruction.txt` и `cache/cloud-instruction.txt` подменяют промпт только на один прогон.

Эталоны сверены со словарями: верно «подного́тную», «в сло́е», «де́дов», «зи́мам», «дорога́» (кратк. прил.).
