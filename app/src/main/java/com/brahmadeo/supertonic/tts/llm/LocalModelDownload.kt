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
    const val SIZE = 2588147712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm"
    val status = MutableStateFlow("Gemma 4 не скачана")
    val downloading = MutableStateFlow(false)
    fun modelFile(ctx: Context) = File(ctx.noBackupFilesDir, "llm/gemma-4-E2B-it.litertlm")
    fun ready(ctx: Context) = modelFile(ctx).let { it.isFile && it.length() == SIZE && File(it.parentFile, "verified-$SHA256").exists() }
    fun start(ctx: Context) { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, LocalModelDownloadService::class.java)) }
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
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("llm_download", "Скачивание локальной LLM", NotificationManager.IMPORTANCE_LOW))
        startForeground(4204, notification("Подключение…"))
        LocalModelDownload.downloading.value = true
        job = scope.launch {
            try {
                val target = LocalModelDownload.modelFile(this@LocalModelDownloadService)
                target.parentFile!!.mkdirs()
                val partial = File(target.path + ".part")
                if (partial.length() > LocalModelDownload.SIZE) partial.delete()
                var offset = partial.length()
                if (offset < LocalModelDownload.SIZE) {
                    val c = URL(LocalModelDownload.URL).openConnection() as HttpURLConnection
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
                            received += n; require(received <= LocalModelDownload.SIZE) { "Некорректный размер" }
                            output.write(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - updatedAt > 1000) {
                                updatedAt = now
                                val percent = (received * 100 / LocalModelDownload.SIZE).toInt()
                                LocalModelDownload.status.value = "Скачивание: $percent% (2,59 ГБ)"
                                notifications.notify(4204, notification(LocalModelDownload.status.value, percent))
                            }
                        }
                    } }
                }
                require(partial.length() == LocalModelDownload.SIZE) { "Скачивание неполное; нажмите скачать для продолжения" }
                LocalModelDownload.status.value = "Проверка SHA-256…"
                notifications.notify(4204, notification(LocalModelDownload.status.value))
                val digest = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != LocalModelDownload.SHA256) { partial.delete(); error("SHA-256 не совпала; скачайте заново") }
                check(partial.renameTo(target)) { "Не удалось сохранить модель" }
                File(target.parentFile, "verified-${LocalModelDownload.SHA256}").writeText(hash)
                LocalModelDownload.status.value = "Gemma 4 скачана и проверена"
            } catch (_: CancellationException) { LocalModelDownload.status.value = "Скачивание приостановлено; можно продолжить" }
            catch (e: Exception) { LocalModelDownload.status.value = "Ошибка: ${e.message?.take(140)}" }
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
