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

    /** Какие приложения закрыты ради NPU (имена пакетов). Только в оперативной памяти: несколько строк, на флешку
     * не пишется. После генерации — одно уведомление со списком, затем список очищается ([finish]). */
    private val closed = java.util.LinkedHashSet<String>()
    @Volatile var lastError: String? = null
        private set

    /** Закрыть фоновые приложения. true — команда выполнилась. Блокирует до ~10 с: не вызывать из главного потока. */
    fun free(context: Context): Boolean {
        val way = way()
        lastError = null
        val out = when (way) {
            Way.SHIZUKU -> viaShizuku(context)
            Way.ROOT -> viaRoot()
            Way.MANUAL -> null
        }
        if (out == null && way == Way.ROOT) lastError = "Root не дал доступ. Разрешите MyTTS root в Magisk, KernelSU или APatch и нажмите ещё раз."
        if (out == null && way == Way.SHIZUKU) lastError = "Shizuku не ответил. Проверьте, что Shizuku запущен, и нажмите ещё раз."
        out?.let { record(context, it) }
        Log.i(TAG, "am kill-all via ${way.title}: ${if (out != null) "done, closed ${closed.size}" else "failed"}")
        return out != null
    }

    /** Разница списков процессов «до» и «после»: пакеты, у которых не осталось ни одного процесса. */
    private fun record(context: Context, out: String) {
        val parts = out.split("\n---\n")
        if (parts.size != 2) return
        fun packages(list: String) = list.lines().map { it.trim().substringBefore(':') }
            .filter { it.contains('.') && !it.startsWith("/") && !it.contains(' ') }.toSet()
        val gone = packages(parts[0]) - packages(parts[1]) - context.packageName
        synchronized(closed) { closed.addAll(gone) }
    }

    /** Названия закрытых приложений для показа (без системных служб без значка). */
    fun closedNames(context: Context): List<String> {
        val pm = context.packageManager
        return synchronized(closed) { closed.toList() }.mapNotNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.distinct()
    }

    /** Генерация закончена: если ради неё закрывали приложения — одно уведомление со списком, список очищается. */
    fun finish(context: Context) {
        val names = closedNames(context)
        synchronized(closed) { closed.clear() }
        if (names.isEmpty()) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Память для NPU", NotificationManager.IMPORTANCE_HIGH))
            val text = "Для быстрой генерации было закрыто: ${names.take(12).joinToString(", ")}" +
                (if (names.size > 12) " и ещё ${names.size - 12}" else "") +
                ". Они остались в «Недавних» и откроются с того же места; уведомления и фоновые службы Android вернёт сам."
            manager.notify(NOTIFICATION + 1, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("MyTTS: звуки готовы")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build())
        }
    }

    private fun viaRoot(): String? = runCatching {
        val p = ProcessBuilder("su", "-c", "ps -A -o NAME=; echo ---; am kill-all; sleep 1; ps -A -o NAME=")
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) text else null
    }.getOrNull()

    private fun viaShizuku(context: Context): String? {
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, MemoryReliefUserService::class.java.name))
            .daemon(false).processNameSuffix("memory_relief").version(2)
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
            if (latch.await(10, TimeUnit.SECONDS)) service?.killBackground() else null
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku: ${t.javaClass.simpleName}: ${t.message}")
            null
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
