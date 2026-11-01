package com.lucida.kmp

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal actual class Lock actual constructor() {
    private val l = ReentrantLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** One daemon thread with delayed tasks: the queue flush thread and the background I/O thread. */
internal class ExecutorScheduler(name: String) : Scheduler {
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    override fun execute(task: () -> Unit) = ex.execute(task)

    override fun schedule(delayMs: Long, task: () -> Unit): ScheduledTask {
        val f = ex.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return object : ScheduledTask {
            override val isDone get() = f.isDone
            override fun cancel() {
                f.cancel(false)
            }
        }
    }

    override fun idle() {
        ex.submit {}.get()
    }

    override fun shutdown() = ex.shutdown()
}

internal actual fun newScheduler(name: String): Scheduler = ExecutorScheduler(name)
