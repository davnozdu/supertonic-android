# Gemma 4 и NPU CPH2745 — проверка 6 октября 2026

## Что подтверждено на телефоне

ADB `3B15AQ003F500000`: `ro.product.model=CPH2745`, `ro.soc.model=SM8850`.
Google сопоставляет SM8850 с Snapdragon 8 Elite Gen 5 / Hexagon V81,
SM8750 — с V79:
https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/HTP_INSTRUCTIONS.md

Синтез Gemma на NPU в этой проверке НЕ запускался. MyTTS не перезапускался,
модели и громкость не менялись. Оставшийся временный USB stay-on=7 возвращён
в 0 по инструкции памяти проекта; screen_off_timeout=15000 не менялся.

## Официальные LiteRT-LM пакеты

В текущем каталоге E2B имеются Qualcomm `sm8750` и `qcs8275`, но нет `sm8850`:
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main

Google рекомендует брать пакет, соответствующий SoC:
https://developers.google.com/edge/litert/next/litert_lm_npu

Отсутствие пакета SM8850 не доказывает аппаратную невозможность запуска Gemma 4.
Qualcomm прямо допускает использование некоторых context binaries старых устройств
на новых чипах, с возможной потерей производительности. Поэтому утверждение,
что пакет SM8750 обязательно не запустится на SM8850, пока НЕ проверено:
https://workbench.aihub.qualcomm.com/docs/hub/faq.html#which-qualcomm-ai-engine-direct-model-format-should-i-use

Официальный Gallery 1.0.19 имеет APK `ai-edge-gallery-sm8850.apk`, но наличие
NPU runtime в APK не означает наличие совместимого NPU-пакета именно Gemma 4.
https://github.com/google-ai-edge/gallery/releases/tag/1.0.19

## Найденные альтернативы

RunAnywhere публикует готовые HNPU артефакты:

- E2B-it: https://huggingface.co/runanywhere/gemma4_e2b_HNPU
  commit c3bc94b2d0064ccdc78d993abe2c9fdb038aed3e, директория v81.
  v81/gemma4-e2b.json: dsp_arch=v81, max_ctx=512.
- E4B: https://huggingface.co/runanywhere/gemma4_e4b_HNPU
  commit 61118e0dbc36cb97c82aabbd37ab4db39063a102, директория v81.
  v81/gemma-4-E4B.json: dsp_arch=v81, max_ctx=1024.

Это отдельный формат (.bin + JSON + tokenizer), предназначенный для QHexRT,
не замена файла .litertlm в существующем MyTTS. README SDK заявляет поддержку
Android arm64 / Hexagon v75, v79, v81 и расширение контекста за compiled window;
пределы контекста и качество для нашего длинного промпта нужно проверять отдельно.
https://github.com/RunanywhereAI/runanywhere-sdks#hexagon-npu-acceleration-qhexrt

Совпадение архитектуры подтверждено метаданными. Успешное создание графов,
реальная NPU-генерация, скорость и русские ударения на CPH2745 НЕ проверены.

Ещё найден https://github.com/finnff/LLMChatApp — его «ON DEVICE» означает
загруженные артефакты, а не успешную генерацию: README описывает QNN 1002,
несовместимость runtime/compiled context и незавершённую проверку в приложении.
Не использовать этот репозиторий как доказательство успешного запуска Gemma 4.

## MyTTS

LlmProviders.kt выбирает `Backend.GPU()` / `Backend.CPU()`;
LocalModelDownload.kt скачивает общий `gemma-4-E2B-it.litertlm`.
Переключатель NPU Tera не включает NPU для Gemma. Для практической проверки
HNPU потребуется отдельное приложение/проба с QHexRT и его пакетами. Код MyTTS
в рамках аудита не менялся.

## Выбор схемы интеграции (повторная проверка 6 октября)

Пользователь уточнил: интересует только Gemma 4; озвучка не входит в эту задачу.
Разделять близость к текущему коду и готовность к первой NPU-генерации:

| Схема | Что уже есть | Что предстоит | Оценка |
| --- | --- | --- | --- |
| LiteRT-LM + Backend.NPU + официальный SM8750 пакет | Текущий Kotlin API, опубликованный .litertlm | Проверить работу пакета SM8750 на SM8850, подобрать согласованный dispatch/QAIRT V81 | Минимум изменений API, запуск не гарантирован |
| LiteRT-LM + собственный пакет SM8850 | API уже используется; Google описывает компиляцию .tflite для SM8850 | Получить/экспортировать совместимые графы Gemma 4, квантовать, AOT-компилировать компоненты и собрать .litertlm | Ближе всего к текущей архитектуре, больше работы с моделью |
| QHexRT + Gemma 4 E2B v81 | Опубликованы модель, native runtime и Kotlin AAR | Адаптер загрузки/генерации/отмены; согласовать библиотеки; проверить реальный запуск | Наиболее прямой путь к первой проверке готового NPU-пакета |

Практическая рекомендация для задачи только Gemma 4: отдельная проба
QHexRT + gemma4_e2b_HNPU/v81. E4B проверять следующей, если требуется большая
модель. Сохранять LiteRT GPU/CPU как независимый запасной путь. Скорость,
качество, расход памяти и реальную NPU-генерацию пока не обещать.

Подтверждения:

- LiteRT Kotlin: Backend.NPU(nativeLibraryDir=...) поддерживается;
  https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- QHexRT native catalog явно разрешает gemma4_e2b для V79/V81 и gemma4_e4b для V81;
  https://github.com/RunanywhereAI/runanywhere-sdks/blob/main/engines/qhexrt/qhexrt_model_catalog.cpp
- QHexRT wrapper имеет system_prompt, параметры sampling, streaming, reset
  независимого запроса и отмену. Не переносить поддержку возможностей main
  автоматически на старый AAR без проверки выбранной версии:
  https://github.com/RunanywhereAI/runanywhere-sdks/blob/main/engines/qhexrt/qhexrt_llm_ops.cpp
- Maven Central на момент проверки: runanywhere-qhexrt-android:0.20.19,
  зависит от runanywhere-sdk:0.20.19. Это опубликованный AAR, не только исходники.
  https://repo.maven.apache.org/maven2/io/github/sanchitmonga22/runanywhere-qhexrt-android/maven-metadata.xml
- Движок QHexRT binary-only. Модельный max_ctx512/1024 и заявленное SDK
  расширение compiled window требуют проверки на конкретном сценарии.

При сосуществовании разных QAIRT избегать произвольного pickFirst одинаковых
libQnn*.so. Отдельный процесс с отдельной директорией согласованных native libs
является вариантом изоляции, но один android:process сам по себе не решает
упаковку двух версий библиотек в APK.

Повторная проверка — анализ кода/пакетов/метаданных. Новая интеграция, сборка
APK и запуск Gemma на телефоне не выполнялись.
