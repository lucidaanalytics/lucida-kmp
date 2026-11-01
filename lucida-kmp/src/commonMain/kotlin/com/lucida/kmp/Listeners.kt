package com.lucida.kmp

/** Copy-on-write listener list (CopyOnWriteArrayList is JVM-only). Iteration sees a stable snapshot. */
internal class Listeners<T> {
    private var items: List<T> = emptyList()

    fun add(fn: T) {
        locked { items = items + fn }
    }

    fun forEach(f: (T) -> Unit) = items.forEach(f)
}
