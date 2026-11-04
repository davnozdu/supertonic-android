package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.os.Build
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.Npu
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Stable Audio 3 Small SFX (Stability AI Community License; T5Gemma under the Gemma Terms of Use; "Powered by
 * Stability AI"). Our GitHub release ambience-sa3-v1 mirrors the official LiteRT build of
 * stabilityai/stable-audio-3-optimized@08b17423 unchanged, plus the NPU graphs we derived from its dit_fp16.onnx
 * (changes listed in NOTICE.txt). Everything is pinned by size and SHA-256 and downloaded only on request; the
 * NPU graphs (≈0.9 GB) only when the NPU switch is on. */
object AmbienceModel {
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/ambience-sa3-v1"
    const val NPU_KEY = "ambience_npu"
    data class Asset(val name: String, val size: Long, val sha: String)
    val ASSETS = listOf(
        Asset("tokenizer.model", 4_241_003L, "61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2"),
        Asset("encoder_fp16.tflite", 563_818_608L, "8530d0b3e6b9b9dcf1239145c2a853fb749708eaddbb472ff8f0802b50059372"),
        Asset("dit_w8a8.tflite", 539_350_360L, "d3f99409f446eff9a625768ba2ee09a2347ea90c4897c1662fbcd8837fae3045"),
        Asset("dec_w8a8.tflite", 89_329_616L, "90cad5ef81e6b18eb205012aee03bc53ed59e1c17033b79a84a4612674b1e03a"),
        Asset("LICENSE.md", 11_852L, "d6f6b1a4dce5c852bd6d7d9482d002baf0ccdb71e662250b73be9eec8764ee8d"),
        Asset("LICENSE_GEMMA.md", 10_541L, "e77acc0d3163bb7534675045c584b4d04b387b529239fc4b3647da0a01ba4745"),
        Asset("NOTICE.txt", 2_049L, "9cb849e59ae91296694324860e83d90dcbde7ac3c5f5d27ef5dbb79969a1051c"),
    )
    /** 30 s DiT for the HTP (all-fp16 safe) and its fp32 CPU front for everything that depends only on t/seconds. */
    val NPU_ASSETS = listOf(
        Asset("dit_L323_main.onnx", 923_766_247L, "e30c054b1a34a2eea6e25c19f668192e619327126486893b358547deab9c53af"),
        Asset("dit_pre.onnx", 21_792_854L, "7611401792c7bb24a0c1af4bca88cd877aca6ec8ba63cbe53773cdfe56c5294d"),
    )
    /** «Музыка по настроению»: Stable Audio 3 Small Music (same T5 and decoder), its NPU graphs made the same way. */
    val MUSIC_ASSETS = listOf(
        Asset("dit_music_w8a8.tflite", 539_350_360L, "1d8ef99bf10f51cc52b68f7b527d3773828e700758b253edd8a8e088a906e241"),
    )
    val MUSIC_NPU_ASSETS = listOf(
        Asset("music_dit_L323_main.onnx", 923_780_604L, "b76f554f3f66c9b82307a352c72a6a3db5fb116fac057e6aee3b747a2be1408b"),
        Asset("music_dit_pre.onnx", 21_792_854L, "4cb8277b82ce3569323543f10360803addca880b6d706e8095cc64403966e168"),
    )
    /** Stable Audio 3 Medium (1.4 B, music only): clearer timbre, ~2.4× the CPU of the small one; same T5 and latents. */
    val MEDIUM_ASSETS = listOf(
        Asset("dit_medium_w8a8.tflite", 1_667_708_664L, "e60ea3a8b530de93ee7f1fd7b4426597006dbcde7bce45750d39c439f4ef9602"),
        Asset("NOTICE_MEDIUM.txt", 1_728L, "acde99a66b7e912b9c9d3cf6da2784a7ee70f5314e07f6d7a85b7c878c2116aa"),
    )
    /** The medium DiT for the HTP in three parts (layers 0–7, 8–15, 16–23; each compiles on its own within the
     * phone's memory and stays < 2 GB as an embedded context) and its fp32 CPU front. */
    val MEDIUM_NPU_ASSETS = listOf(
        Asset("medium_dit_L323_r_p0.onnx", 960_813_101L, "2fd544d27814af2ecdbdac519639f9a54117fd80dd46c6a75b6a189e244632c4"),
        Asset("medium_dit_L323_r_p1.onnx", 952_607_398L, "1d73f64d7bb89d6cc81c69b0ed0d241906b4ed793daf362aa0ce4bc0a33b89de"),
        // p2b (2 ноября 2026): та же часть 2 с двумя правками под fp16 на HTP — project_out ×1/64…×64 (сумма
        // переполнялась) и аргумент Exp в softmax не ниже −20 (в слое 22 он доходил до −880 и HTP давал NaN).
        // Прежняя p2 на NPU давала шум вместо звука. На CPU p2b совпадает с p2 (68–69 дБ).
        Asset("medium_dit_L323_r_p2b.onnx", 953_523_660L, "e72f51732815b4292f824be81da4c9981c0ee877d741c28457abb514bf9508cc"),
        Asset("medium_dit_pre.onnx", 47_063_721L, "7134b158307db424298c3896f4e96b06d6091336895523823fab2a9ad1f62d37"),
    )
    /** SAME-L decoder (the same latents as SAME-S): more detail, ~+30 % of the generation time. */
    val LARGE_DECODER = listOf(
        Asset("dec_l_w8a8.tflite", 515_381_432L, "53dbca41ec9620257834bda4f3008a2cd5072afba564b84906f8ffdfca2647e7"),
    )
    /** SAME-L для NPU: свой экспорт с полосным вниманием, fp16 (2 ноября 2026). На CPU
     * совпадает с эталоном 53 дБ (w8a8-tflite — 30 дБ). Берётся, когда включены «Большой декодер» и NPU.
     * Окна по 64 латента (перекрытие 16), три части по 4 блока. Проверено на телефоне (beta182, проба
     * ambienceSamelCheck): окно 96 QNN не финализирует («Failed to finalize QNN graph», 1002 — отказ DSP),
     * окно 64 собирается; весь 30-секундный клип 5,7 с против 10,2 с у CPU, CPU-время 0,28 с против 33 с,
     * звук = компьютерной цепочке (к CPU-декодеру 26,8 дБ против 29,8 дБ у самой цепочки, корреляция 0,999).
     * Тензоры не выше ранга 4 (без измерения батча): QNN отвергал ранг 5. */
    val LARGE_DECODER_NPU = listOf(
        Asset("samel_dec_L64_p0.onnx", 290_881_812L, "d5aa71780d361324c5c25ca1be23693228fb68dff712c600c4fc72582f0e9ebd"),
        Asset("samel_dec_L64_p1.onnx", 286_944_415L, "bfa2c20e1dcf9913dff37cee0d2605123b34879c1509c12193548cd4a887d2e4"),
        Asset("samel_dec_L64_p2.onnx", 288_519_856L, "0dc89dd728f3d784d058f397be760a5381890dd3396662369fea538c964974dd"),
    )
    private const val LARGE_DECODER_NPU_MARKER = "verified-ambience-sa3-v1-samel-w64"
    private val SAMEL_NAMES = listOf("sa3-samel-W64-p0-d5aa7178", "sa3-samel-W64-p1-bfa2c20e", "sa3-samel-W64-p2-0dc89dd7")
    const val SAMEL_SCOPE = "_ambience_samel"
    const val SAMEL_WINDOW = 64
    fun largeDecoderNpuReady(context: Context) = complete(context, LARGE_DECODER_NPU, LARGE_DECODER_NPU_MARKER)
    /** Пойдёт ли большой декодер на NPU: включены «Большой декодер» и NPU, файл скачан, сбоя сборки не было. */
    fun largeDecoderNpuPlanned(context: Context) = largeDecoderWanted(context) && largeDecoderReady(context) &&
        npuWanted(context, SAMEL_SCOPE) && largeDecoderNpuReady(context)

    /** Большой декодер на NPU и тогда, когда DiT тоже на NPU (по очереди на одном HTP вместо конвейера NPU || CPU):
     * по времени примерно так же, но процессор почти свободен — меньше нагрев и расход батареи. Раньше вдвоём не
     * помещались в память; после beta201 (кэш NPU без лишней копии) — помещаются. Не хватит памяти — процессор. */
    const val SAMEL_WITH_DIT = "ambience_samel_with_dit"
    fun decoderWithDit(context: Context) = prefs(context).getBoolean(SAMEL_WITH_DIT, true)

    /** Сессия SAME-L на NPU (первый раз — компиляция, затем из кэша за секунды) или null — тогда декодирует CPU. */
    fun largeDecoderNpu(context: Context, allowCompile: Boolean = !AmbienceSettings.npuNight(prefs(context)),
                        onCompile: () -> Unit = {}, diagnostic: Boolean = false): Sa3Generator.NpuDecoder? {
        if (!diagnostic && !largeDecoderNpuPlanned(context)) return null
        if (!enoughMemory(context, NEED_SAMEL_MB, "большой декодер")) return null
        // Отладка (только проба): pref samel_debug = "окно:частей" — файлы samel_dec_L<окно>_p<k>.onnx без проверки
        // размера, свои имена кэша. Так варианты пробуются без пересборки приложения.
        val debug = if (diagnostic) prefs(context).getString("samel_debug", null)?.split(':')?.mapNotNull { it.trim().toIntOrNull() } else null
        val window = debug?.getOrNull(0) ?: SAMEL_WINDOW
        val files = if (debug != null) (0 until (debug.getOrNull(1) ?: 3)).map { "samel_dec_L${window}_p$it.onnx" } else LARGE_DECODER_NPU.map { it.name }
        val names = if (debug != null) files.indices.map { "sa3-samel-dbg-W$window-n${files.size}-p$it" } else SAMEL_NAMES
        if (debug == null && !largeDecoderNpuReady(context)) return null
        if (!allowCompile && names.any { !Npu.cacheReady(context, it) }) return null
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        fun open(k: Int) = Npu.session(context, env, File(root(context), files[k]), emptyMap(), names[k],
            family = "sa3-samel", siblings = names)
        val sessions = ArrayList<ai.onnxruntime.OrtSession>()
        var building = -1
        return try {
            // Каждая часть компилируется отдельно и сразу закрывается (пик памяти — одна часть), потом все из кэша.
            for (k in names.indices) if (!Npu.cacheReady(context, names[k])) { onCompile(); building = k; open(k).close(); building = -1 }
            for (k in names.indices) sessions.add(open(k))
            Log.i("Ambience", "SAME-L NPU sessions ready (window $window, parts ${names.size}), PSS ${android.os.Debug.getPss() / 1024} MB")
            Sa3Generator.NpuDecoder(sessions, window)
        } catch (e: Throwable) {
            sessions.forEach { runCatching { it.close() } }
            Log.w("Ambience", "SAME-L NPU unavailable: ${e.javaClass.simpleName}: ${e.message}")
            val drop = if (building >= 0) listOf(names[building]) else names
            File(context.filesDir, "npu-cache").listFiles()?.filter { f -> drop.any { f.name.startsWith("$it-") } }?.forEach { it.delete() }
            // Какая часть не собралась — в файл пробы (журнал OnePlus обрезает).
            if (diagnostic) runCatching { File(context.cacheDir, "ambience/samel-fail.txt").appendText("window=$window parts=${names.size} failed part=$building: ${e.message}\n") }
            if (debug == null) Npu.markFailed(context, "${e.javaClass.simpleName}: ${e.message}", SAMEL_SCOPE)
            null
        }
    }

    /** Свободная память (МБ), без которой NPU-модель не загружается — см. [enoughMemory]. */
    // Замер (CPH2745, 3 ноября): средняя на NPU — пик расхода ~5,5 ГБ (буферы NPU 3,4 ГБ + скачок памяти процесса
    // при загрузке), свободная падала с 7,2 до 1,64 ГБ. Порог — пик + ~1 ГБ запаса. После beta201 (кэш NPU без
    // встроенной копии) процесс держит ~2,9 ГБ вместо 5,6, а сама проверка учитывает половину свободной подкачки:
    // 4 ноября при 3,9–5,3 ГБ свободной RAM и 5 ГБ подкачки музыка зря уходила на процессор (26 с на клип).
    private const val NEED_MEDIUM_MB = 6500L
    private const val NEED_SMALL_MB = 3000L
    private const val NEED_SAMEL_MB = 2500L

    /** Хватит ли памяти на загрузку NPU-модели: availMem системы с запасом. Нет — модель берётся на процессоре.
     * Причина — в журнал и в cache/ambience-memory.txt (журнал приложения прошивка глушит). */
    fun enoughMemory(context: Context, needMb: Long, what: String): Boolean {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
        fun availMb(): Long = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1048576
        // Подкачка (zram) тоже место: система вытесняет туда фоновые приложения. Половина свободной подкачки —
        // запас на сжатие. Но совсем без свободной RAM не грузимся: нужно хотя бы [needMb]/2 «живыми».
        fun shortMb(): Long { val a = availMb(); return maxOf(needMb - (a + swapFreeMb() / 2), needMb / 2 - a, 0L) }
        if (shortMb() == 0L) return true
        // «Освобождать память для быстрой генерации»: закрыть фоновые приложения (Shizuku/root) и проверить снова.
        if (com.brahmadeo.supertonic.tts.utils.MemoryRelief.relieve(context, what, shortMb()) { shortMb() == 0L }) {
            Log.i("Ambience", "$what: память для NPU освобождена, свободно ${availMb()} МБ")
            return true
        }
        val availMb = availMb()
        val swapFreeMb = swapFreeMb()
        val line = "${java.util.Date()}: $what на NPU не загружается — свободно $availMb МБ, подкачки $swapFreeMb МБ, " +
            "нужно $needMb (с учётом половины подкачки); считает процессор"
        Log.w("Ambience", line)
        runCatching { File(context.cacheDir, "ambience-memory.txt").appendText(line + "\n") }
        lowMemory = "Мало свободной памяти ($availMb МБ): $what считает процессор, медленнее. Закройте фоновые приложения или " +
            "включите «Освобождать память для быстрой генерации» в настройках фоновой музыки."
        notifyLowMemory(context, lowMemory!!)
        return false
    }

    /** Память перед подготовкой книги: сколько свободно, сколько нужно NPU-модели, которой будут считаться звуки,
     * и сколько не хватает (0 — хватает). null — звуки на NPU не планируются (NPU выключен или графов нет). */
    class MemStatus(val availMb: Long, val swapFreeMb: Long, val needMb: Long, val shortMb: Long, val what: String)
    fun memoryStatus(context: Context): MemStatus? {
        val p = prefs(context)
        if (!p.getBoolean(NPU_KEY, false)) return null
        val mediumMusic = AmbienceSettings.moodMusic(p) && music(context) == MEDIUM_MUSIC && npuPlanned(context, true)
        val mediumEvents = AmbienceSettings.events(p) && AmbienceSettings.eventsMedium(p) && mediumNpuPlanned(context)
        val small = npuPlanned(context, false)
        if (!mediumMusic && !mediumEvents && !small) return null
        val need = if (mediumMusic || mediumEvents) NEED_MEDIUM_MB else NEED_SMALL_MB
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val avail = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1048576
        val swap = swapFreeMb()
        val short = maxOf(need - (avail + swap / 2), need / 2 - avail, 0L)
        return MemStatus(avail, swap, need, short, if (need == NEED_MEDIUM_MB) "средняя модель" else "малая модель")
    }

    private fun swapFreeMb(): Long = runCatching {
        File("/proc/meminfo").readLines().firstOrNull { it.startsWith("SwapFree:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024) ?: 0L
    }.getOrDefault(0L)

    /** Последнее предупреждение о нехватке памяти (для итогового сообщения о звуках книги); сбрасывается при чтении. */
    @Volatile private var lowMemory: String? = null
    fun takeLowMemory(): String? = lowMemory.also { lowMemory = null }
    @Volatile private var lowMemoryNotifiedAt = 0L

    /** Видимое уведомление, не чаще раза в час: без него пользователь не узнает, почему стало медленно. */
    private fun notifyLowMemory(context: Context, text: String) {
        val now = System.currentTimeMillis()
        if (now - lowMemoryNotifiedAt < 60 * 60 * 1000L) return
        lowMemoryNotifiedAt = now
        runCatching {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.createNotificationChannel(android.app.NotificationChannel("ambience_memory", "Память для NPU",
                android.app.NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(4214, androidx.core.app.NotificationCompat.Builder(context, "ambience_memory")
                .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("MyTTS: мало памяти для NPU")
                .setContentText(text).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).build())
        }
    }

    const val MUSIC_MODEL_KEY = "music_model"
    const val LARGE_DECODER_KEY = "music_decoder_large"
    private const val LARGE_DECODER_MARKER = "verified-ambience-sa3-v1-dec-l"

    /** One music model: its CPU DiT (rung signatures), its NPU graphs (30 s) and their compiled-cache names. */
    class Music(val id: String, val title: String, val ditFile: String, val rungs: IntArray, val assets: List<Asset>, val marker: String,
                val npuAssets: List<Asset>, val npuMarker: String, val npuParts: List<String>, val npuPre: String, val npuCache: String, val family: String,
                /** Where an NPU failure of this model is recorded. */
                val failScope: String = Npu.AMBIENCE,
                /** Имена скомпилированных графов по частям, если не "<npuCache>-p<k>". */
                val npuNames: List<String>? = null,
                val obsolete: List<String> = emptyList())
    val SMALL_MUSIC = Music("small", "Малая", "dit_music_w8a8.tflite", Sa3Generator.SMALL_RUNGS, MUSIC_ASSETS, "verified-ambience-sa3-v1-music",
        MUSIC_NPU_ASSETS, "verified-ambience-sa3-v1-music-npu", listOf("music_dit_L323_main.onnx"), "music_dit_pre.onnx", "sa3-music-L323-b76f554f", "sa3-music")
    val MEDIUM_MUSIC = Music("medium", "Средняя", "dit_medium_w8a8.tflite", Sa3Generator.MEDIUM_RUNGS, MEDIUM_ASSETS, "verified-ambience-sa3-v1-medium",
        MEDIUM_NPU_ASSETS, "verified-ambience-sa3-v1-medium-npu3", listOf("medium_dit_L323_r_p0.onnx", "medium_dit_L323_r_p1.onnx", "medium_dit_L323_r_p2b.onnx"),
        "medium_dit_pre.onnx", "sa3-medium-L323-2fd544d2", "sa3-medium", Npu.AMBIENCE_MEDIUM,
        // Части 0–1 не менялись (их скомпилированные графы остаются), у исправленной части 2 своё имя кэша.
        npuNames = listOf("sa3-medium-L323-2fd544d2-p0", "sa3-medium-L323-2fd544d2-p1", "sa3-medium-L323-2fd544d2-p2b"),
        /** Прежние файлы этой модели, которые больше не нужны (удаляются, когда новые на месте). */
        obsolete = listOf("medium_dit_L323_r_p2.onnx"))
    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0)
    /** The chosen music model (pref [MUSIC_MODEL_KEY], the small one by default). */
    fun music(context: Context) = if (prefs(context).getString(MUSIC_MODEL_KEY, "small") == "medium") MEDIUM_MUSIC else SMALL_MUSIC
    fun largeDecoderWanted(context: Context) = prefs(context).getBoolean(LARGE_DECODER_KEY, false)
    fun largeDecoderReady(context: Context) = complete(context, LARGE_DECODER, LARGE_DECODER_MARKER)
    /** The decoder for music: SAME-L when switched on and downloaded, else SAME-S. */
    fun musicDecoder(context: Context) = if (largeDecoderWanted(context) && largeDecoderReady(context)) "dec_l_w8a8.tflite" else "dec_w8a8.tflite"
    fun musicBytes(context: Context) = music(context).assets.sumOf { it.size } + (if (largeDecoderWanted(context)) LARGE_DECODER.sumOf { it.size } else 0L)
    fun musicNpuBytes(context: Context) = music(context).npuAssets.sumOf { it.size }
    fun musicReady(context: Context) = ready(context) && music(context).let { complete(context, it.assets, it.marker) } &&
        (!largeDecoderWanted(context) || largeDecoderReady(context))
    /** False while a model has no NPU graphs (the CPU makes its music then). */
    fun musicNpuReady(context: Context) = musicReady(context) && music(context).let { m ->
        (m.npuAssets.isNotEmpty() && complete(context, m.npuAssets, m.npuMarker)).also { ok ->
            // Новые файлы на месте — прежние версии (≈1 ГБ) больше не нужны.
            if (ok) m.obsolete.forEach { File(root(context), it).takeIf { f -> f.exists() }?.delete() }
        } }
    private fun musicNpuMissing(context: Context) = music(context).npuAssets.isNotEmpty() && !musicNpuReady(context)
    /** A generator for music with the chosen model and decoder. */
    fun musicGenerator(context: Context, threads: Int, npu: Sa3Generator.NpuDit?, keep: Boolean = true) = music(context).let {
        Sa3Generator(root(context), threads, npu, it.ditFile, it.rungs, musicDecoder(context), keep)
    }
    private fun musicWanted(context: Context) = AmbienceSettings.moodMusic(context.getSharedPreferences("SupertonicPrefs", 0))

    const val NPU_LENGTH = 323
    private const val NPU_CACHE = "sa3-dit-L323-e30c054b"
    private val lock = Mutex()
    private const val PARALLEL = 3
    private const val MARKER = "verified-ambience-sa3-v1"
    private const val NPU_MARKER = "verified-ambience-sa3-v1-npu2"

    fun supported() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, "ambience/sa3-small-sfx")
    fun totalBytes() = ASSETS.sumOf { it.size }
    fun npuBytes() = NPU_ASSETS.sumOf { it.size }

    private fun complete(context: Context, assets: List<Asset>, marker: String) =
        File(root(context), marker).exists() && assets.all { File(root(context), it.name).length() == it.size }
    fun ready(context: Context) = supported() && complete(context, ASSETS, MARKER)
    fun npuReady(context: Context) = ready(context) && complete(context, NPU_ASSETS, NPU_MARKER)

    /** Own switch, off by default; also needs an HTP and no recorded build failure for this app version. */
    fun npuWanted(context: Context, scope: String = Npu.AMBIENCE) = context.getSharedPreferences("SupertonicPrefs", 0).getBoolean(NPU_KEY, false) &&
        Npu.supported(context) && !Npu.failed(context, scope)

    /** Up to [PARALLEL] files at once (each resumable and SHA-checked on its own): one HTTP stream from GitHub is
     * often throttled, and the model is three large files. */
    private suspend fun fetch(context: Context, assets: List<Asset>, marker: String, label: String, progress: (String, Float) -> Unit) {
        val dir = root(context).apply { mkdirs() }
        val total = assets.sumOf { it.size }
        val received = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
        kotlinx.coroutines.coroutineScope {
            for (asset in assets.sortedByDescending { it.size }) launch { gate.withPermit {
                val target = File(dir, asset.name)
                // A shorter file left by an older pinned version is not a partial download: resuming would splice two
                // versions. The checksum catches it (and deletes the file); one clean retry then fetches it whole.
                for (attempt in 0..1) try {
                    ResumableModelFile.fetch("$BASE/${asset.name}", target, asset.size, asset.sha) { got, _ ->
                        received[asset.name] = got
                        val sum = received.values.sum()
                        progress("$label: ${sum / 1048576} / ${total / 1048576} МБ", sum.toFloat() / total)
                    }
                    break
                } catch (e: IllegalStateException) { if (attempt == 1) throw e; target.delete() }
                received[asset.name] = asset.size
            } }
        }
        File(dir, marker).writeText("ok")
    }

    /** The CPU model, plus the NPU graphs when the NPU switch is on, plus the music model when «Музыка по настроению» is. */
    suspend fun download(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор фоновых звуков требует ARM64" }
                if (!ready(context)) fetch(context, ASSETS, MARKER, "Звуки", progress)
                if (npuWanted(context) && !npuReady(context)) fetch(context, NPU_ASSETS, NPU_MARKER, "Звуки · NPU", progress)
                if (musicWanted(context)) fetchMusic(context, progress)
                progress("Генератор звуков установлен", 1f)
            } }
        }

    /** «Своя музыка»: the base model and the music model (+ its NPU graphs when the NPU is on), whatever the book switches. */
    suspend fun downloadMusic(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор музыки требует ARM64" }
                if (!ready(context)) fetch(context, ASSETS, MARKER, "Звуки", progress)
                fetchMusic(context, progress)
                progress("Модель музыки установлена", 1f)
            } }
        }

    /** The chosen music model, the large decoder when switched on, the NPU graphs when the NPU is on. */
    private suspend fun fetchMusic(context: Context, progress: (String, Float) -> Unit) {
        val m = music(context)
        if (!complete(context, m.assets, m.marker)) fetch(context, m.assets, m.marker, "Музыка · ${m.title.lowercase()}", progress)
        if (largeDecoderWanted(context) && !largeDecoderReady(context)) fetch(context, LARGE_DECODER, LARGE_DECODER_MARKER, "Большой декодер", progress)
        if (npuWanted(context, m.failScope) && musicNpuMissing(context)) fetch(context, m.npuAssets, m.npuMarker, "Музыка · NPU", progress)
        if (largeDecoderWanted(context) && npuWanted(context, SAMEL_SCOPE) && !largeDecoderNpuReady(context))
            fetch(context, LARGE_DECODER_NPU, LARGE_DECODER_NPU_MARKER, "Большой декодер · NPU", progress)
        dropStaleSamel(context)
    }

    /** Графы SAME-L другого окна (например, прежние samel_dec_L96_*) после смены окна не используются — это ~870 МБ. */
    private fun dropStaleSamel(context: Context) {
        val keep = LARGE_DECODER_NPU.map { it.name }.toSet()
        root(context).listFiles()?.filter { it.name.startsWith("samel_dec_") && it.name !in keep }?.forEach { it.delete() }
    }

    /** Deletes the model, the NPU graphs and their compiled HTP cache. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) { lock.withLock {
        root(context).deleteRecursively()
        File(context.filesDir, "npu-cache").listFiles()?.filter { it.name.startsWith("sa3-dit-") || it.name.startsWith("sa3-music-") || it.name.startsWith("sa3-medium-") || it.name.startsWith("sa3-samel-") }?.forEach { it.delete() }
    } }
    fun installedBytes(context: Context) = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** DiT on the NPU for 30 s clips, or null (CPU) when it is off, not downloaded or fails to build. A build
     * failure turns the NPU off for this app version; the first build compiles the HTP graph (~2 min, cached). */
    /** One NPU DiT: its graph files, CPU front, cache names (one per part) and failure scope. */
    private class NpuTarget(val parts: List<String>, val pre: String, val cache: String, val family: String, val scope: String, val names: List<String>, val label: String)
    private fun target(context: Context, music: Boolean, model: Music? = null): NpuTarget {
        val m = model ?: music(context)
        val parts = if (music) m.npuParts else listOf("dit_L323_main.onnx")
        val cache = if (music) m.npuCache else NPU_CACHE
        // One graph keeps its old cache name; the parts of a cut model get "-p<k>" and keep each other's caches.
        return NpuTarget(parts, if (music) m.npuPre else "dit_pre.onnx", cache, if (music) m.family else "sa3-dit",
            if (music) m.failScope else Npu.AMBIENCE,
            if (music && m.npuNames != null) m.npuNames else if (parts.size == 1) listOf(cache) else parts.indices.map { "$cache-p$it" },
            if (music) "музыка, ${m.title.lowercase()}" else "звуки")
    }
    /** Аварийный выключатель средней модели на NPU. До p2b (2 ноября 2026) её часть 2 на HTP давала шум вместо звука;
     * с p2b звук совпадает с CPU (пианино: полосы спектра ±1 дБ). Оставлен на случай новой регрессии QNN. */
    const val MEDIUM_NPU_BROKEN = false
    private fun mediumBlocked(context: Context, music: Boolean, model: Music?) =
        MEDIUM_NPU_BROKEN && (model ?: if (music) music(context) else null) == MEDIUM_MUSIC &&
            // Гибрид (последняя часть на CPU в fp32) звучит верно — тогда NPU для средней разрешён.
            2 !in cpuParts(context, target(context, music, model))
    const val CPU_PARTS_KEY = "ambience_npu_cpu_parts"
    private fun cpuParts(context: Context, t: NpuTarget): Set<Int> =
        if (t.parts.size < 2) emptySet() else prefs(context).getString(CPU_PARTS_KEY, "").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in t.parts.indices }.toSet()
    private fun npuUsable(context: Context, music: Boolean, model: Music? = null, diagnostic: Boolean = false) =
        (diagnostic || !mediumBlocked(context, music, model)) &&
        npuWanted(context, target(context, music, model).scope) &&
            (if (model != null) mediumNpuReady(context) else if (music) musicNpuReady(context) else npuReady(context))
    /** Средняя модель для звуков событий (её же сделана база): скачана вместе со «Средней» моделью музыки. */
    fun mediumReady(context: Context) = ready(context) && complete(context, MEDIUM_ASSETS, MEDIUM_MUSIC.marker)
    private fun mediumNpuReady(context: Context) = mediumReady(context) &&
        complete(context, MEDIUM_NPU_ASSETS, MEDIUM_MUSIC.npuMarker)
    /** Звуки событий средней моделью на NPU: тумблер NPU, графы средней скачаны, сбоя сборки нет. */
    fun mediumNpuPlanned(context: Context) = npuUsable(context, true, MEDIUM_MUSIC)
    /** Генератор звуков событий средней моделью: её DiT и декодер SAME-S — как у готовой базы звуков. */
    fun mediumSfxGenerator(context: Context, threads: Int, keep: Boolean = true) =
        Sa3Generator(root(context), threads, null, MEDIUM_MUSIC.ditFile, MEDIUM_MUSIC.rungs, "dec_w8a8.tflite", keep)
    /** True when the DiT for these sounds / this music is meant to run on the NPU: the switch is on, the graphs are
     * there and this model has no recorded build failure. Says nothing about the compile still being needed. */
    fun npuPlanned(context: Context, music: Boolean) = npuUsable(context, music)
    /** True when this DiT would use the NPU but its graphs are not compiled yet. */
    fun npuUncompiled(context: Context, music: Boolean) =
        npuUsable(context, music) && target(context, music).names.any { !Npu.cacheReady(context, it) }

    /** DiT on the NPU for 30 s clips, or null (CPU) when it is off, not downloaded or fails to build. A build
     * failure turns the NPU off for this model and app version. The first build compiles the HTP graphs (the small
     * models ~2 min, the medium one up to an hour), part by part, each cached as soon as it is made. With
     * «Готовить NPU ночью» ([allowCompile] false) nothing is compiled here: the CPU makes the clip and the night job
     * compiles. [onCompile] (part, parts) before each part compiled; [stop] is checked between parts. */
    fun npuDit(context: Context, music: Boolean = false, allowCompile: Boolean = !AmbienceSettings.npuNight(prefs(context)),
               onCompile: (Int, Int) -> Unit = { _, _ -> }, stop: () -> Boolean = { false },
               /** Явно заданная модель (средняя для звуков событий), иначе выбранная модель музыки. */
               model: Music? = null, diagnostic: Boolean = false): Sa3Generator.NpuDit? {
        if (!npuUsable(context, music, model, diagnostic)) return null
        val t = target(context, music, model)
        if (!allowCompile && t.names.any { !Npu.cacheReady(context, it) }) return null
        // Память: загрузка средней модели на NPU берёт ~5,5 ГБ (RAM + буферы NPU), малой — ~2 ГБ. Не хватает —
        // DiT считает процессор (медленнее), а не система выгружает процесс посреди книги или базы.
        val medium = t.parts.size > 1
        if (!enoughMemory(context, if (medium) NEED_MEDIUM_MB else NEED_SMALL_MB, if (medium) "средняя модель" else "малая модель")) return null
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        // Части графа, которые считает CPU в fp32 (pref ambience_npu_cpu_parts, например "2"): у средней модели
        // последняя часть переполняет fp16 на HTP. Остальные части — на NPU.
        val cpuParts = cpuParts(context, t)
        fun open(k: Int) = if (k in cpuParts) env.createSession(File(root(context), t.parts[k]).path,
                ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
            else Npu.session(context, env, File(root(context), t.parts[k]), emptyMap(), t.names[k], family = t.family, siblings = t.names)
        val sessions = ArrayList<ai.onnxruntime.OrtSession>()
        var building = -1
        return try {
            // Compile the missing parts one at a time and free each (a compile peaks at several GB), then load all
            // from the cache. family: older compiled graphs (~1 GB each) are deleted, not only older runtimes.
            for (k in t.parts.indices) if (k !in cpuParts && !Npu.cacheReady(context, t.names[k])) {
                if (stop()) return null
                onCompile(k + 1, t.parts.size); building = k
                if (t.parts.size > 1) open(k).close() else sessions.add(open(k))
                building = -1
            }
            if (stop()) { sessions.forEach { it.close() }; return null }
            if (sessions.isEmpty()) for (k in t.parts.indices) sessions.add(open(k))
            Log.i("Ambience", "NPU session ready (music=$music, parts=${t.parts.size}), PSS ${android.os.Debug.getPss() / 1024} MB")
            val preSession = env.createSession(File(root(context), t.pre).path, ai.onnxruntime.OrtSession.SessionOptions())
            Sa3Generator.NpuDit(sessions, preSession, NPU_LENGTH)
        } catch (e: Throwable) {
            sessions.forEach { runCatching { it.close() } }
            Log.w("Ambience", "NPU DiT unavailable: ${e.javaClass.simpleName}: ${e.message}")
            // A rejected build may still have written its context (~1 GB): never keep or reuse it. Parts compiled
            // before it stay (a load failure of a cached part drops them all).
            val drop = if (building >= 0) listOf(t.names[building]) else t.names
            File(context.filesDir, "npu-cache").listFiles()?.filter { f -> drop.any { f.name.startsWith("$it-") } }?.forEach { it.delete() }
            Npu.markFailed(context, "${e.javaClass.simpleName}: ${e.message}", t.scope)
            null
        }
    }

    /** One heavy generator task at a time (an NPU compile, a book's sounds, own music): each holds the HTP and
     * several GB; a second one waits for the first instead of running beside it. */
    private val work = java.util.concurrent.locks.ReentrantLock()
    fun <T> exclusive(block: () -> T): T { work.lockInterruptibly(); try { return block() } finally { work.unlock() } }
    val busy get() = work.isLocked

    /** «Готовить NPU ночью»: compiles every NPU DiT that would be used and is not compiled yet (the sounds, the chosen
     * music model), part by part; stops between parts when [stop] says so (unplugged, «Остановить»). */
    fun compileMissing(context: Context, stop: () -> Boolean, progress: (String) -> Unit): Boolean = exclusive {
        // Звуки, музыка и — если звуки событий делает средняя модель — её графы, даже когда музыка малая.
        val medium = if (AmbienceSettings.eventsMedium(prefs(context)) && AmbienceSettings.npuShort(prefs(context))) MEDIUM_MUSIC else null
        val targets = listOf<Pair<Boolean, Music?>>(false to null, true to null) + listOfNotNull(medium?.let { true to it })
        fun uncompiled(music: Boolean, model: Music?) =
            npuUsable(context, music, model) && target(context, music, model).names.any { !Npu.cacheReady(context, it) }
        for ((music, model) in targets) {
            if (!uncompiled(music, model)) continue
            val label = target(context, music, model).label
            npuDit(context, music, allowCompile = true, onCompile = { k, n -> progress("Подготовка NPU ($label): часть $k из $n") },
                stop = stop, model = model)?.close()
            if (stop()) return@exclusive false
        }
        targets.none { (music, model) -> uncompiled(music, model) }
    }

}
