package com.brahmadeo.supertonic.tts.books

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.ModelContext
import java.io.File

object BookVoiceCatalog {
    fun ready(ctx: Context, model: String): Boolean = model in BookVoiceRef.models && runCatching { AssetManager.isReady(ModelContext(ctx, model)) }.getOrDefault(false)
    fun voices(ctx: Context, models: List<String>): List<String> = BookVoiceRef.interleave(models.filter { ready(ctx, it) }.map { model ->
        AssetManager.russianVoices(ModelContext(ctx, model)).map { BookVoiceRef(model, it).key }
    })
    fun file(ctx: Context, value: String): File? {
        val ref = BookVoiceRef.parse(value) ?: return null
        val scoped = ModelContext(ctx, ref.model)
        if (ref.voice !in AssetManager.russianVoices(scoped)) return null
        return AssetManager.voiceFile(scoped, ref.voice).takeIf { it.isFile }
    }
    /** Resolve only existing engine voice files inside app-owned model directories. */
    fun modelForFile(ctx: Context, path: String): String? {
        val file = File(path).canonicalFile
        val root = ctx.filesDir.canonicalFile
        val relative = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return null
        val ref = when {
            relative.startsWith("${AssetManager.MODEL_VERSION}/tera/styles/") && file.name == "style_ttl.npy" -> BookVoiceRef(AssetManager.TERA_MODEL, file.parentFile!!.name)
            file.parentFile == File(root, "silero-v5_5_ru") -> BookVoiceRef(AssetManager.SILERO_MODEL, file.nameWithoutExtension)
            file.parentFile == File(root, "silero-cis_ru") -> BookVoiceRef(AssetManager.SILERO_CIS_MODEL, file.nameWithoutExtension)
            file.parentFile == File(root, "kokoro-ru-v2") -> BookVoiceRef(AssetManager.KOKORO_MODEL, file.nameWithoutExtension)
            relative.startsWith("shtorm-pocket-v2/") -> BookVoiceRef(AssetManager.POCKET_MODEL, file.nameWithoutExtension)
            else -> return null
        }
        return ref.model.takeIf { file(ctx, ref.key)?.canonicalFile == file }
    }
    fun label(ctx: Context, value: String): String = label(value, VoiceGenderSettings.gender(ctx, value))
    fun label(value: String, sex: String? = BookVoiceAssign.gender(value)): String {
        val ref = BookVoiceRef.parse(value) ?: return com.brahmadeo.supertonic.tts.tera.TeraVoices.label(value)
        val voice = when (ref.voice) {
            "sveta" -> "Света"; "masha" -> "Маша"; "dima" -> "Дима"
            "aidar" -> "Айдар"; "eugene" -> "Евгений"; "kseniya" -> "Ксения"; "baya" -> "Бая"; "xenia" -> "Ксения (Xenia)"
            "ru_roman" -> "Роман"; "ru_alexandr" -> "Александр"; "ru_dmitriy" -> "Дмитрий"; "ru_bogdan" -> "Богдан"
            else -> com.brahmadeo.supertonic.tts.tera.TeraVoices.label(ref.voice)
        }
        val gender = when (sex) { "m" -> "мужской"; "f" -> "женский"; else -> "пол не определён" }
        return "${BookVoiceRef.models.getValue(ref.model)} · $voice · $gender"
    }
}
