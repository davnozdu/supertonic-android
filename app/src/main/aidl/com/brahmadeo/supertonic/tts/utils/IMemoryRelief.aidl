package com.brahmadeo.supertonic.tts.utils;

/** Сервис Shizuku (процесс shell): закрывает фоновые приложения, когда NPU не хватает памяти. */
interface IMemoryRelief {
    /** Код транзакции, которым Shizuku останавливает свой пользовательский сервис. */
    void destroy() = 16777114;
    /** `am kill-all`: код выхода команды. */
    int killBackground() = 1;
}
