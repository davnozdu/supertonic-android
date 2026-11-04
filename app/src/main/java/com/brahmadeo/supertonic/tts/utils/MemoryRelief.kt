package com.brahmadeo.supertonic.tts.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** «Освобождать память для быстрой генерации»: когда NPU-модели не хватает памяти, MyTTS закрывает фоновые
 * приложения (или, без Shizuku и root, просит закрыть их уведомлением). Начиная с Android 14 обычное приложение чужие процессы
 * закрывать не может, поэтому команда `am kill-all` идёт через Shizuku (права adb, без root) или через root.
 * `am kill-all` закрывает только то, что Android считает безопасным: кэшированные фоновые приложения. Приложение на
 * экране, музыка, читалка, всё с постоянным уведомлением и сама MyTTS остаются. Восстанавливать ничего не нужно:
 * закрытые приложения запускаются заново, когда их открывают, их фоновые службы Android поднимает сам. */
object MemoryRelief {
    private const val TAG = "MemoryRelief"
    /** «Освобождать память для быстрой генерации». Выключено по умолчанию: при нехватке памяти считает процессор. */
    const val KEY = "npu_free_memory"
    private const val CHANNEL = "npu_memory_relief"
    private const val NOTIFICATION = 4215
    private const val ACTION_SKIP = "com.brahmadeo.supertonic.tts.SKIP_FREE_MEMORY"
    /** Сколько ждать ответа на уведомление, пока генерация стоит. */
    private const val ASK_MS = 60_000L
    /** После «Не сейчас» или молчания следующую пачку клипов не задерживаем повторным вопросом. */
    private const val QUIET_MS = 15 * 60_000L

    enum class Way(val title: String) { SHIZUKU("Shizuku"), ROOT("root"), MANUAL("вручную") }

    @Volatile private var answer = 0          // 2 — «Не ждать»
    @Volatile private var quietUntil = 0L

    fun enabled(context: Context) = prefs(context).getBoolean(KEY, false)

    /** Shizuku запущен и уже разрешил MyTTS работать через него. */
    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku запущен, но разрешения ещё нет: в настройках показывается кнопка «Разрешить». */
    fun shizukuNeedsPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku() = runCatching { Shizuku.requestPermission(1) }.isSuccess

    /** Каким способом будет закрывать: Shizuku, root или (нет ни того ни другого) пользователь сам смахивает приложения. */
    fun way(): Way = when {
        shizukuReady() -> Way.SHIZUKU
        rootAvailable() -> Way.ROOT
        else -> Way.MANUAL
    }

    /** Есть ли su в системе (без вызова: он может показать окно менеджера root). */
    private fun rootAvailable() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su")
        .any { java.io.File(it).exists() } ||
        System.getenv("PATH").orEmpty().split(':').any { java.io.File(it, "su").exists() }

    /** Закрыть фоновые приложения. true — команда выполнилась. Блокирует до ~10 с: не вызывать из главного потока. */
    fun free(context: Context): Boolean {
        val way = way()
        val ok = when (way) {
            Way.SHIZUKU -> viaShizuku(context)
            Way.ROOT -> viaRoot()
            Way.MANUAL -> false
        }
        Log.i(TAG, "am kill-all via ${way.title}: ${if (ok) "done" else "failed"}")
        return ok
    }

    private fun viaRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "am kill-all").redirectErrorStream(true).start()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun viaShizuku(context: Context): Boolean {
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, MemoryReliefUserService::class.java.name))
            .daemon(false).processNameSuffix("memory_relief").version(1)
        val latch = CountDownLatch(1)
        var service: IMemoryRelief? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.takeIf { it.pingBinder() }?.let { IMemoryRelief.Stub.asInterface(it) }
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        return try {
            Shizuku.bindUserService(args, connection)
            latch.await(10, TimeUnit.SECONDS) && service?.killBackground() == 0
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    /** Памяти для NPU не хватает. Переключатель выключен — сразу false (считает процессор, медленнее, но фоновые
     * приложения не трогаем). Включён — закрываем фоновые приложения через Shizuku/root и ждём, пока [enough];
     * без Shizuku и root просим смахнуть их из «Недавних» и ждём до минуты. Вызывается из потока генерации. */
    fun relieve(context: Context, what: String, shortMb: Long, enough: () -> Boolean): Boolean {
        if (!enabled(context)) return false
        if (way() != Way.MANUAL) {
            free(context)
            return waitFor(10_000L, enough)
        }
        if (SystemClock.elapsedRealtime() < quietUntil) return false
        answer = 0
        notifyAsk(context, what, shortMb)
        val deadline = SystemClock.elapsedRealtime() + ASK_MS
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (enough()) return true
                if (answer == 2) break
                Thread.sleep(1000)
            }
            // «Не сейчас» или минута без результата: следующие пачки клипов не задерживаем.
            quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
            return false
        } finally {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        }
    }

    private fun waitFor(ms: Long, enough: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < deadline) { if (enough()) return true; Thread.sleep(500) }
        return enough()
    }

    /** Без Shizuku и root: просим закрыть приложения самому. */
    private fun notifyAsk(context: Context, what: String, shortMb: Long) = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Память для NPU", NotificationManager.IMPORTANCE_HIGH))
        val skip = PendingIntent.getBroadcast(context, 2, Intent(ACTION_SKIP).setClass(context, MemoryReliefReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "$what: для NPU не хватает ~$shortMb МБ. Смахните ненужные приложения из «Недавних» — генерация подождёт " +
            "минуту, иначе посчитает процессор (медленнее). Чтобы MyTTS закрывала их сама, установите Shizuku."
        manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("MyTTS: освободите память для NPU")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setTimeoutAfter(ASK_MS).setAutoCancel(true)
            .addAction(0, "Не ждать", skip).build())
    }

    /** Кнопки уведомления. */
    class MemoryReliefReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            answer = 2
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0)
}
