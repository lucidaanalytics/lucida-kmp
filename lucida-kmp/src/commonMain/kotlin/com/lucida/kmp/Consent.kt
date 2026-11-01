package com.lucida.kmp

enum class ConsentMode { OPT_IN, OPT_OUT }

private const val KEY = "lc_consent"
private val CATEGORIES = listOf("analytics", "replay", "marketing")

/**
 * opt_in: every category starts denied. opt_out: granted unless a stored decision says otherwise.
 * Only [set] writes to the real store.
 */
class Consent(private val store: KeyValueStore, val mode: ConsentMode = ConsentMode.OPT_OUT, val queueBeforeConsent: Boolean = false) {
    private val state = CATEGORIES.associateWith { mode == ConsentMode.OPT_OUT }.toMutableMap()

    init {
        store.get(KEY)?.let { raw ->
            runCatching { Json.decodeObj(raw) }.getOrNull()?.let { s ->
                for (c in CATEGORIES) (s[c] as? Boolean)?.let { state[c] = it }
            }
        }
    }

    fun has(category: String) = locked { state[category] == true }

    /** True while an opt_in project still waits for an analytics decision. */
    val pending: Boolean get() = locked { mode == ConsentMode.OPT_IN && state["analytics"] != true }

    fun all(): Map<String, Boolean> = locked { state.toMap() }

    fun set(update: Map<String, Boolean>) {
        locked {
            for (c in CATEGORIES) update[c]?.let { state[c] = it }
            store.set(KEY, Json.encode(state))
        }
    }
}

/** Keeps writes in memory until [allowed] is true; guarantees no storage before consent. */
class GatedStore(private val inner: KeyValueStore, private val allowed: () -> Boolean) : KeyValueStore {
    private val mem = HashMap<String, String?>()

    override fun get(key: String): String? = locked { if (mem.containsKey(key)) mem[key] else inner.get(key) }

    override fun set(key: String, value: String) {
        locked {
            if (allowed()) inner.set(key, value) else mem[key] = value
        }
    }

    override fun remove(key: String) {
        locked {
            if (allowed()) inner.remove(key) else mem[key] = null
        }
    }

    fun flushBuffered() {
        locked {
            mem.forEach { (k, v) -> if (v == null) inner.remove(k) else inner.set(k, v) }
            mem.clear()
        }
    }

    fun discardBuffered() = locked { mem.clear() }
}
