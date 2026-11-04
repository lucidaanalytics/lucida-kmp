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

/*
 * Locking (no process-wide lock). Every component owns a private `Lock`:
 *
 * - State locks (`lock`) guard one component's fields only. They are leaves: while holding one, call nothing outside the
 *   component, run no user code (hooks, listeners, enricher, presenters) and do no I/O (store, http). Copy out, release,
 *   then act. Because no state lock is held while another is taken, there is no cross-component order to get wrong.
 * - Write locks (`ioLock`, `Core.consentChange`) only serialise writers of one stored key so the last state wins on disk.
 *   Order: `ioLock` -> `lock` of the same component (snapshot taken under the state lock, written outside it). No read path
 *   and no state lock ever waits on a write lock, so a slow disk never blocks capture, identify or flag reads.
 * - The only nesting between components is the documented table (outer -> inner), all inner locks being leaves:
 *     Core.heldLock -> Consent.lock          (pre-consent buffer vs the consent decision)
 *     EventQueue.lock -> Consent.lock        (enqueue re-checks consent so nothing queues after a revoke)
 *     GatedStore.ioLock -> Consent.lock      (`allowed()` is asked under ioLock, never under GatedStore.lock)
 *     ReplayRecorder.lock -> Consent.lock    (`enabled`)
 *     ReplayRecorder.lock -> SessionManager.lock
 * - Tests: `ConcurrencyTest` hammers capture / identify / flag reads from several threads.
 */

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
