package com.brahmadeo.supertonic.tts.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** «Проверить мой телефон»: чип, память, NPU и что из моделей MyTTS на нём работает хорошо, медленно или не влезет.
 * Цифры моделей — замеры на телефоне (CPH2745, Snapdragon 8 Elite Gen 5, 16 ГБ): память процесса во время работы и
 * скорость; на слабых телефонах всё медленнее, поэтому пороги с запасом. Ничего не меняет без кнопки. */
object DeviceCheck {
    /** Поколение NPU (Hexagon), для которого в APK есть библиотеки: V79 — Snapdragon 8 Elite, V81 — 8 Elite Gen 5. */
    enum class Npu(val title: String) {
        V81("есть — Hexagon V81 (Snapdragon 8 Elite Gen 5), поддерживается полностью"),
        V79("есть — Hexagon V79 (Snapdragon 8 Elite): голос и звуки на NPU, Gemma на NPU — нет"),
        OTHER("у этого Snapdragon NPU другого поколения: MyTTS его не использует"),
        NONE("нет (или не Snapdragon): всё считает процессор")
    }

    enum class Verdict(val mark: String) { GOOD("✓"), SLOW("⚠"), NO("✗") }
    class Item(val section: String, val name: String, val verdict: Verdict, val why: String)

    class Report(
        val soc: String, val npu: Npu, val ramGb: Double, val availGb: Double, val swapGb: Double, val cores: Int,
        val maxGhz: Double, val android: String, val freeStorageGb: Double, val tier: String,
        val items: List<Item>, val current: List<String>, val tips: List<String>,
    ) {
        /** Что подходит этому телефону и что нет — от оперативной памяти и NPU. */
        val can get() = items.filter { it.verdict == Verdict.GOOD }.map { it.name }
        val slow get() = items.filter { it.verdict == Verdict.SLOW }.map { it.name }
        val cannot get() = items.filter { it.verdict == Verdict.NO }.map { it.name }
    }

    fun npu(): Npu {
        val model = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).uppercase()
        val qcom = Build.VERSION.SDK_INT < 31 || Build.SOC_MANUFACTURER.contains("QTI", true) || Build.SOC_MANUFACTURER.contains("Qualcomm", true)
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" || !qcom) return Npu.NONE
        return when {
            model.startsWith("SM8850") || model.startsWith("SM8845") -> Npu.V81
            model.startsWith("SM8750") || model.startsWith("SM8735") -> Npu.V79
            model.startsWith("SM") || model.startsWith("SC") || model.startsWith("QCS") -> Npu.OTHER
            else -> Npu.NONE
        }
    }

    fun run(context: Context): Report {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ram = mi.totalMem / 1073741824.0
        val avail = mi.availMem / 1073741824.0
        val swap = meminfo("SwapFree") / 1048576.0
        val cores = Runtime.getRuntime().availableProcessors()
        val maxGhz = (0 until cores).maxOfOrNull { c ->
            runCatching { File("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1e6 }.getOrDefault(0.0)
        } ?: 0.0
        val storage = runCatching { StatFs(context.filesDir.path).availableBytes / 1073741824.0 }.getOrDefault(0.0)
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
        val n = npu()
        val hasNpu = n == Npu.V81 || n == Npu.V79
        // Номинал «16 ГБ» Android показывает как ~15 ГБ: пороги чуть ниже круглых чисел.
        val tier = when {
            ram >= 11 && hasNpu -> "мощный: с ускорителем NPU — подходит всё, включая генерацию звуков и музыки"
            ram >= 7.5 -> "хороший: все голоса, облачная LLM; тяжёлое (звуки, локальная Gemma) медленно"
            ram >= 5.5 -> "средний: лёгкие и средние голоса, облачная LLM; генерацию звуков не включать"
            else -> "слабый: лёгкие голоса (Silero) и облачная LLM; тяжёлые модели будут тормозить или не загрузятся"
        }
        val items = ArrayList<Item>()
        fun add(s: String, name: String, v: Verdict, why: String) = items.add(Item(s, name, v, why))
        val voice = "Голос — дело вкуса: послушайте разные модели (проба голоса в выборе модели). Ниже — как каждая запустится на этом телефоне"
        add(voice, "Silero (RU, CIS)", Verdict.GOOD, "~0,3 ГБ памяти, самая быстрая и экономная — запустится на любом телефоне.")
        add(voice, "Tera", if (ram >= 4) Verdict.GOOD else Verdict.SLOW,
            "~0,8 ГБ, 2 потока процессора" + (if (hasNpu) "; часть считает NPU (включите «Ускоритель NPU»)." else "."))
        add(voice, "Shtorm", if (ram >= 5.5) Verdict.GOOD else Verdict.SLOW, "0,7–0,9 ГБ, быстрее реального времени на среднем телефоне.")
        add(voice, "Kokoro, полная", when {
            hasNpu && ram >= 7.5 -> Verdict.GOOD
            ram >= 7.5 -> Verdict.SLOW
            else -> Verdict.NO
        }, if (hasNpu) "~1,1 ГБ; на NPU вдвое быстрее и в 7 раз меньше нагружает процессор — включите NPU у Kokoro."
            else "~1,2 ГБ и тяжело для процессора без NPU: возможны паузы на старте абзацев и больший расход батареи.")
        add(voice, "Kokoro, Q8", if (ram >= 5.5) (if (hasNpu) Verdict.GOOD else Verdict.SLOW) else Verdict.NO,
            "~1 ГБ; на процессоре медленнее полной версии, зато меньше места на диске.")
        val llm = "Подготовка текста (LLM)"
        add(llm, "Облачная LLM (DeepSeek, Gemini, Ollama)", Verdict.GOOD, "Работает на любом телефоне, нужен интернет. Лучшие ударения.")
        add(llm, "Локальная Gemma 4", when {
            n == Npu.V81 && ram >= 11 -> Verdict.GOOD
            ram >= 7.5 -> Verdict.SLOW
            else -> Verdict.NO
        }, when {
            n == Npu.V81 -> "2,4 ГБ на диске, ~2 ГБ памяти; на NPU 25–35 токенов/с — выберите движок «NPU»."
            ram >= 7.5 -> "2,6 ГБ на диске, ~1,2 ГБ памяти + видеопамять; на GPU работает, но греет телефон."
            else -> "Не хватит памяти вместе с голосом: используйте облачную LLM или словари."
        })
        val amb = "Фоновые звуки и музыка"
        add(amb, "Готовая база звуков событий", Verdict.GOOD, "Только скачать (~360 МБ), генерации нет — подходит всем.")
        add(amb, "Генерация звуков локаций (малая модель)", when {
            hasNpu && ram >= 7.5 -> Verdict.GOOD
            ram >= 7.5 -> Verdict.SLOW
            else -> Verdict.NO
        }, if (hasNpu) "На NPU ~2–3 с на клип 30 с; нужно ~3 ГБ свободной памяти."
            else "Без NPU 15–30 с на клип и сильный нагрев; на слабом телефоне не включать.")
        add(amb, "Музыка, средняя модель", when {
            hasNpu && ram >= 11 -> Verdict.GOOD
            ram >= 11 -> Verdict.SLOW
            else -> Verdict.NO
        }, if (hasNpu) "Нужно ~6,5 ГБ свободной памяти (с подкачкой); включите «Освобождать память для быстрой генерации»."
            else "Без NPU ~30 с и больше на клип; лучше малая модель музыки или своя MP3.")
        add(amb, "Музыка, малая модель", if (ram >= 7.5) (if (hasNpu) Verdict.GOOD else Verdict.SLOW) else Verdict.NO,
            if (hasNpu) "На NPU несколько секунд на клип." else "На процессоре 15–20 с на клип.")

        val current = ArrayList<String>()
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val model = prefs.getString("selected_model", AssetManager.DEFAULT_MODEL)
        val npuOn = prefs.getBoolean(com.brahmadeo.supertonic.tts.utils.Npu.KEY, false)
        if (model == AssetManager.KOKORO_MODEL && !hasNpu && ram < 7.5)
            current += "Выбрана Kokoro — на этом телефоне она может читать с паузами. Если звучание нравится — оставьте; если мешают паузы, послушайте Silero или Tera."
        if (model == AssetManager.KOKORO_MODEL && hasNpu && !npuOn) current += "Kokoro считается без NPU — включите «Ускоритель NPU» (вдвое быстрее, меньше батареи)."
        if (hasNpu && !npuOn && model == AssetManager.TERA_MODEL) current += "У Tera выключен «Ускоритель NPU» — включите, вокодер станет быстрее."
        if (!hasNpu && npuOn) current += "Включён «Ускоритель NPU», но подходящего NPU нет — переключатель ничего не даёт."
        val ambNpu = prefs.getBoolean("ambience_npu", false)
        if (hasNpu && !ambNpu && prefs.getBoolean("ambience_enabled", false)) current += "Фоновые звуки считаются процессором — включите «Генерация на NPU» в «Фоновой музыке»."
        if (prefs.getString("music_model", "small") == "medium" && !(hasNpu && ram >= 11))
            current += "Выбрана средняя модель музыки — на этом телефоне очень медленно. Рекомендуется малая."
        val engine = context.getSharedPreferences("llm_settings", 0).getString("local_engine", "gpu")
        if (engine == "npu" && n != Npu.V81) current += "Gemma выбрана на NPU, но нужен Snapdragon 8 Elite Gen 5 — выберите GPU."
        if (current.isEmpty()) current += "Текущие настройки подходят этому телефону."

        val tips = ArrayList<String>()
        if (avail < 3) tips += "Сейчас свободно всего ${"%.1f".format(avail)} ГБ: закройте фоновые приложения перед подготовкой книги — иначе генерация уйдёт на процессор."
        if (storage < 3) tips += "На диске свободно ${"%.1f".format(storage)} ГБ: модели и звуки книг занимают 1–4 ГБ."
        if (hasNpu) tips += "Для генерации звуков включите «Освобождать память для быстрой генерации» (нужен Shizuku или root) — MyTTS сама закроет фоновые приложения, когда NPU не хватает памяти."
        tips += "Пробу голоса и ударения проверяйте в обычном чтении: первый запуск модели всегда дольше (загрузка в память)."
        return Report(soc, n, ram, avail, swap, cores, maxGhz, Build.VERSION.RELEASE, storage, tier, items, current, tips)
    }

    /** «Подобрать всё за меня»: настройки под память и NPU этого телефона. Меняет только переключатели и выбор
     * вариантов моделей (полная/Q8, малая/средняя, движок Gemma); голос не меняет и ничего не скачивает — что нужно
     * сделать руками (скачать лёгкую модель, сменить голос), возвращает отдельным списком. */
    class Tuning(val changed: List<String>, val manual: List<String>)
    fun optimize(context: Context): Tuning {
        val n = npu()
        val hasNpu = n == Npu.V81 || n == Npu.V79
        val am = context.getSystemService(ActivityManager::class.java)
        val ram = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1073741824.0
        val strong = hasNpu && ram >= 11
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val llm = context.getSharedPreferences("llm_settings", 0)
        val e = prefs.edit()
        val changed = ArrayList<String>(); val manual = ArrayList<String>()
        fun bool(key: String, value: Boolean, on: String, off: String, def: Boolean = false) {
            if (prefs.getBoolean(key, def) != value) { e.putBoolean(key, value); changed += if (value) on else off }
        }
        fun str(key: String, def: String, value: String, text: String) {
            if (prefs.getString(key, def) != value) { e.putString(key, value); changed += text }
        }
        bool(com.brahmadeo.supertonic.tts.utils.Npu.KEY, hasNpu, "Ускоритель NPU для голоса — включён", "Ускоритель NPU для голоса — выключен (подходящего NPU нет)")
        bool("ambience_npu", hasNpu, "Генерация фоновых звуков на NPU — включена", "Генерация фоновых звуков на NPU — выключена (NPU нет)")
        if (com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.fullReady(context))
            bool("kokoro_full_precision", hasNpu || ram >= 7.5, "Kokoro — полная точность (быстрее на NPU)", "Kokoro — Q8 (меньше памяти)", def = true)
        str("music_model", "small", if (strong) prefs.getString("music_model", "small")!! else "small", "Модель музыки — малая (средней не хватит памяти или NPU)")
        if (!strong) str(com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.EVENTS_MODEL, "small", "small", "Звуки событий — малая модель")
        if (ram < 7.5) {
            bool(com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.MOOD_MUSIC, false, "", "Музыка по настроению — выключена (генерация не влезет в память)")
        }
        if (hasNpu) {
            val way = MemoryRelief.way()
            if (way != MemoryRelief.Way.MANUAL) bool(MemoryRelief.KEY, true, "Освобождать память для быстрой генерации — включено (${way.title})", "")
            else manual += "Установите Shizuku (или выдайте MyTTS root) — тогда MyTTS сможет сама освобождать память для NPU."
        }
        val engine = llm.getString(com.brahmadeo.supertonic.tts.llm.LocalModelDownload.ENGINE_KEY, "gpu")
        val gemmaNpu = n == Npu.V81 && com.brahmadeo.supertonic.tts.llm.LocalModelDownload.ready(context, com.brahmadeo.supertonic.tts.llm.LocalModelDownload.HEXAGON)
        if (gemmaNpu && engine != "npu") { llm.edit().putString(com.brahmadeo.supertonic.tts.llm.LocalModelDownload.ENGINE_KEY, "npu").apply(); changed += "Локальная Gemma — на NPU" }
        if (!gemmaNpu && engine == "npu") { llm.edit().putString(com.brahmadeo.supertonic.tts.llm.LocalModelDownload.ENGINE_KEY, "gpu").apply(); changed += "Локальная Gemma — на GPU (NPU для неё нет)" }
        e.apply()
        val model = prefs.getString("selected_model", AssetManager.DEFAULT_MODEL)
        if (model == AssetManager.KOKORO_MODEL && !hasNpu && ram < 7.5)
            manual += "Kokoro на этом телефоне может читать с паузами. Голос — дело вкуса: если паузы мешают, послушайте Silero или Tera (меню → «Модели»)."
        if (ram < 7.5) manual += "Для подготовки текста используйте облачную LLM (DeepSeek/Gemini) — локальная Gemma не влезет в память вместе с голосом."
        return Tuning(changed.filter { it.isNotEmpty() }, manual)
    }

    private fun meminfo(key: String): Long = runCatching {
        File("/proc/meminfo").readLines().firstOrNull { it.startsWith("$key:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: 0L
    }.getOrDefault(0L)
}
