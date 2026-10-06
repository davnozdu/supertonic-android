package com.brahmadeo.supertonic.tts.llm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object LocalModelDownload {
    /** A pinned local model file: size and SHA-256 are verified before it becomes usable. */
    class Spec(val id: String, val size: Long, val sha256: String, val url: String, val fileName: String, val sizeLabel: String)
    /** LiteRT-LM GPU/CPU package (default local engine). */
    val LITERT = Spec("litert", 2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        "gemma-4-E2B-it.litertlm", "2,59 ГБ")
    /** Same Gemma 4 E2B, Q4_0 GGUF for llama.cpp ggml-hexagon (experimental NPU engine); >2 GB, so not mirrored on GitHub. */
    val HEXAGON = Spec("hexagon", 2620370976L, "e531007218dfab990486a5de7676a6932d6ea8dea233d1f698d7c21cf8a16889",
        "https://huggingface.co/h2loop-ai/gemma-4-e2b-hexagon/resolve/1bb2044c313769541558f2c27fa67561894d0f26/gemma4-e2b-w4.gguf",
        "gemma4-e2b-w4.gguf", "2,44 ГБ")
    const val SIZE = 2588147712L
    /** llm_settings key: "gpu" = LiteRT (default), "npu" = Hexagon. One local engine at a time. */
    const val ENGINE_KEY = "local_engine"
    val status = MutableStateFlow("Gemma 4 не скачана")
    val downloading = MutableStateFlow(false)
    @Volatile var downloadingId: String = ""
    fun supported() = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    fun modelFile(ctx: Context, spec: Spec = LITERT) = File(ctx.noBackupFilesDir, "llm/${spec.fileName}")
    fun ready(ctx: Context, spec: Spec = LITERT) = modelFile(ctx, spec).let { it.isFile && it.length() == spec.size && File(it.parentFile, "verified-${spec.sha256}").exists() }
    fun npuSelected(ctx: Context) = ctx.getSharedPreferences("llm_settings", Context.MODE_PRIVATE).getString(ENGINE_KEY, "gpu") == "npu" && GemmaHexagon.supported(ctx)
    /** The model the selected local engine needs. */
    fun active(ctx: Context) = if (npuSelected(ctx)) HEXAGON else LITERT
    fun activeReady(ctx: Context) = ready(ctx, active(ctx))
    fun start(ctx: Context, spec: Spec = LITERT) { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, LocalModelDownloadService::class.java).putExtra("spec", spec.id)) }
    fun cancel(ctx: Context) { ctx.stopService(Intent(ctx, LocalModelDownloadService::class.java)) }
}

class LocalModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connection: HttpURLConnection? = null
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(message: String, progress: Int = -1) = NotificationCompat.Builder(this, "llm_download")
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Скачивание Gemma 4 E2B")
        .setContentText(message).setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val spec = if (intent?.getStringExtra("spec") == LocalModelDownload.HEXAGON.id) LocalModelDownload.HEXAGON else LocalModelDownload.LITERT
        LocalModelDownload.downloadingId = spec.id
        val notifications = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel("llm_download", "Скачивание локальной LLM", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(4204, notification("Подключение…"))
        LocalModelDownload.downloading.value = true
        job = scope.launch {
            try {
                val target = LocalModelDownload.modelFile(this@LocalModelDownloadService, spec)
                require(LocalModelDownload.supported()) { "Gemma 4 требует 64-битный Android" }
                target.parentFile!!.mkdirs()
                val partial = File(target.path + ".part")
                if (partial.length() > spec.size) partial.delete()
                var offset = partial.length()
                require(android.os.StatFs(target.parentFile!!.path).availableBytes > spec.size - offset + 100_000_000L) { "Недостаточно места: нужно около ${spec.sizeLabel}" }
                if (offset < spec.size) {
                    val c = URL(spec.url).openConnection() as HttpURLConnection
                    connection = c; c.connectTimeout = 15_000; c.readTimeout = 30_000
                    if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
                    val code = c.responseCode
                    require(code == 200 || code == 206) { "HTTP $code" }
                    if (offset > 0 && code == 200) { partial.delete(); offset = 0 }
                    if (code == 206) require(c.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "Некорректный Range" }
                    var received = offset
                    var updatedAt = 0L
                    c.inputStream.use { input -> java.io.FileOutputStream(partial, offset > 0).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            received += n; require(received <= spec.size) { "Некорректный размер" }
                            output.write(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - updatedAt > 1000) {
                                updatedAt = now
                                val percent = (received * 100 / spec.size).toInt()
                                LocalModelDownload.status.value = "Скачивание: $percent% (${spec.sizeLabel})"
                                notifications.notify(4204, notification(LocalModelDownload.status.value, percent))
                            }
                        }
                    } }
                }
                require(partial.length() == spec.size) { "Скачивание неполное; нажмите скачать для продолжения" }
                LocalModelDownload.status.value = "Проверка SHA-256…"
                notifications.notify(4204, notification(LocalModelDownload.status.value))
                val digest = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != spec.sha256) { partial.delete(); error("SHA-256 не совпала; скачайте заново") }
                ensureActive()
                check(partial.renameTo(target)) { "Не удалось сохранить модель" }
                File(target.parentFile, "verified-${spec.sha256}").writeText(hash)
                LocalModelDownload.status.value = "Gemma 4 скачана и проверена"
            } catch (_: CancellationException) { LocalModelDownload.status.value = "Скачивание приостановлено; можно продолжить" }
            catch (e: Exception) {
                LocalModelDownload.status.value = if (!isActive) "Скачивание приостановлено; можно продолжить" else "Ошибка: ${e.message?.take(140)}"
            }
            finally {
                connection?.disconnect(); connection = null
                LocalModelDownload.downloading.value = false
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { connection?.disconnect(); scope.cancel(); super.onDestroy() }
}
