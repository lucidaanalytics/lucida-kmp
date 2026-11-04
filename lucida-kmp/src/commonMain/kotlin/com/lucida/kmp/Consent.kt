package com.lucida.kmp

enum class ConsentMode { OPT_IN, OPT_OUT }

private const val KEY = "lc_consent"
private val CATEGORIES = listOf("analytics", "replay", "marketing")

/**
 * opt_in: every category starts denied. opt_out: granted unless a stored decision says otherwise.
 * Only [set] writes to the real store.
 */
class Consent(private val store: KeyValueStore, val mode: ConsentMode = ConsentMode.OPT_OUT, val queueBeforeConsent: Boolean = false) {
    private val lock = Lock()
    private val ioLock = Lock()
    private val state = CATEGORIES.associateWith { mode == ConsentMode.OPT_OUT }.toMutableMap()

    init {
        store.get(KEY)?.let { raw ->
            runCatching { Json.decodeObj(raw) }.getOrNull()?.let { s ->
                for (c in CATEGORIES) (s[c] as? Boolean)?.let { state[c] = it }
            }
        }
    }

    fun has(category: String) = lock.withLock { state[category] == true }

    /** True while an opt_in project still waits for an analytics decision. */
    val pending: Boolean get() = lock.withLock { mode == ConsentMode.OPT_IN && state["analytics"] != true }

    fun all(): Map<String, Boolean> = lock.withLock { state.toMap() }

    /** The decision takes effect at once; the stored copy follows (written outside the state lock, last state wins). */
    fun set(update: Map<String, Boolean>) {
        lock.withLock { for (c in CATEGORIES) update[c]?.let { state[c] = it } }
        ioLock.withLock {
            val json = lock.withLock { Json.encode(state) }
            store.set(KEY, json)
        }
    }
}

/**
 * Keeps writes in memory until [allowed] is true; guarantees no storage before consent. `ioLock` serialises writers and
 * [flushBuffered]; `allowed()` is asked under `ioLock` only (never under `lock`), so a consent flip cannot slip between
 * the check and the write.
 */
class GatedStore(private val inner: KeyValueStore, private val allowed: () -> Boolean) : KeyValueStore {
    private val lock = Lock()
    private val ioLock = Lock()
    private val mem = HashMap<String, String?>()

    override fun get(key: String): String? {
        val buffered = lock.withLock { if (mem.containsKey(key)) Buffered(mem[key]) else null }
        return if (buffered != null) buffered.value else inner.get(key)
    }

    override fun set(key: String, value: String) {
        ioLock.withLock {
            if (allowed()) {
                lock.withLock { mem.remove(key) }
                inner.set(key, value)
            } else {
                lock.withLock { mem[key] = value }
            }
        }
    }

    override fun remove(key: String) {
        ioLock.withLock {
            if (allowed()) {
                lock.withLock { mem.remove(key) }
                inner.remove(key)
            } else {
                lock.withLock { mem[key] = null }
            }
        }
    }

    fun flushBuffered() {
        ioLock.withLock {
            val pending = lock.withLock { HashMap(mem).also { mem.clear() } }
            pending.forEach { (k, v) -> if (v == null) inner.remove(k) else inner.set(k, v) }
        }
    }

    fun discardBuffered() = lock.withLock { mem.clear() }

    private class Buffered(val value: String?)
}
