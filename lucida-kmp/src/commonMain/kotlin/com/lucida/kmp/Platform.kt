package com.lucida.kmp

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Re-entrant mutex (ReentrantLock on the JVM, NSRecursiveLock on Apple). Kotlin has no common `synchronized`. */
internal expect class Lock() {
    fun lock()
    fun unlock()
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T> Lock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

// shortcut: one process-wide lock guards all SDK state; critical sections are short (no I/O inside), so contention is negligible.
// Upgrade to per-object locks if profiling ever shows contention.
@PublishedApi internal val globalLock = Lock()

@OptIn(ExperimentalContracts::class)
internal inline fun <T> locked(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return globalLock.withLock(block)
}

/** Epoch milliseconds from the platform clock. */
internal expect fun currentTimeMillis(): Long

/** A cancellable delayed task. */
interface ScheduledTask {
    val isDone: Boolean
    fun cancel()
}

/** Port: a single-threaded work queue with delayed tasks (the queue flush thread, the background I/O thread). */
interface Scheduler {
    fun execute(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledTask

    /** Block until work already submitted has run (tests, shutdown). */
    fun idle()
    fun shutdown()
}

internal expect fun newScheduler(name: String): Scheduler

/** Device and app facts the platform can read; they ride on events as `$os`, `$device_model`, ... */
data class DeviceInfo(
    val os: String,
    val osVersion: String,
    val model: String,
    val manufacturer: String? = null,
    val appVersion: String = "unknown",
    val appBuild: String? = null,
)

/** Everything platform specific the client needs. Android must call `attachAndroid(app)` first; iOS and JVM work as-is. */
class Platform(val store: KeyValueStore, val http: HttpPort, val device: DeviceInfo)

/** The platform's default storage (SharedPreferences / NSUserDefaults / JSON file), transport and device info. */
expect fun platformDefaults(requestTimeoutMs: Int = 15_000): Platform
