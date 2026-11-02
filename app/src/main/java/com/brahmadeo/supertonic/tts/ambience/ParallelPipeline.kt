package com.brahmadeo.supertonic.tts.ambience

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * «Параллельно NPU и процессор»: клипы книги считают сразу несколько исполнителей. [producers] берут задания из общей
 * очереди (например, DiT на NPU и DiT на процессоре — каждый свой клип), [consumers] — готовые латенты (например,
 * декодер на NPU и декодер на процессоре). Кто свободен, тот и берёт следующее — нагрузка делится сама.
 *
 * Готовых латентов в очереди не больше двух на обработчика. Любая ошибка останавливает всех и пробрасывается отсюда
 * (как в [ClipPipeline]); уже сохранённые клипы остаются, недоделанные доделает вызывающий.
 */
class ParallelPipeline<T, R>(private val items: List<T>, private val producers: List<(T) -> R>,
                             private val consumers: List<(R) -> Unit>) {
    fun run() {
        if (items.isEmpty()) return
        val jobs = ConcurrentLinkedQueue(items)
        val ready = LinkedBlockingQueue<R>(maxOf(2, 2 * consumers.size))
        val failure = AtomicReference<Throwable?>(null)
        val stopped = AtomicBoolean(false)
        val producing = AtomicInteger(producers.size)
        fun fail(t: Throwable) { if (failure.compareAndSet(null, t)) stopped.set(true) }
        val threads = ArrayList<Thread>()
        producers.forEachIndexed { k, produce ->
            threads += Thread({
                try {
                    while (!stopped.get()) {
                        val item = jobs.poll() ?: break
                        val r = produce(item)
                        while (!stopped.get() && !ready.offer(r, 200, TimeUnit.MILLISECONDS)) { }
                    }
                } catch (t: Throwable) { if (!stopped.get()) fail(t) } finally { producing.decrementAndGet() }
            }, "sa3-produce-$k")
        }
        consumers.forEachIndexed { k, consume ->
            threads += Thread({
                try {
                    while (!stopped.get()) {
                        val r = ready.poll(200, TimeUnit.MILLISECONDS)
                        if (r != null) consume(r) else if (producing.get() == 0 && ready.isEmpty()) break
                    }
                } catch (t: Throwable) { if (!stopped.get()) fail(t) }
            }, "sa3-consume-$k")
        }
        threads.forEach { it.isDaemon = true; it.start() }
        try {
            threads.forEach { it.join() }
        } catch (e: InterruptedException) {
            stopped.set(true); threads.forEach { it.interrupt() }
            threads.forEach { runCatching { it.join(60_000) } }
            throw e
        }
        failure.get()?.let { throw it }
    }
}
