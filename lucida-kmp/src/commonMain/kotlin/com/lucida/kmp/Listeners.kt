package com.lucida.kmp

/** Copy-on-write listener list (CopyOnWriteArrayList is JVM-only). Iteration sees a stable snapshot. */
internal class Listeners<T> {
    private val lock = Lock()
    private var items: List<T> = emptyList()

    fun add(fn: T) {
        lock.withLock { items = items + fn }
    }

    /** Runs the listeners on a snapshot, outside the lock (they are user code). */
    fun forEach(f: (T) -> Unit) = lock.withLock { items }.forEach(f)
}
