package com.lucida.kmp

private fun load(s: KeyValueStore, key: String): Obj? = s.get(key)?.let { runCatching { Json.decodeObj(it) }.getOrNull() }

/**
 * Anonymous UUIDv7 (or [newId]) until identify(); persisted through the (gated) store. State lock: `lock`; the write of
 * `lc_identity` is serialised by `ioLock` and happens outside `lock` (see the locking note in Platform.kt).
 */
class Identity(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val newId: () -> String = { uuidv7(clock.nowMs()) },
    bootstrap: Bootstrap? = null,
    private val reuseAnonymousId: Boolean = false,
) {
    private val lock = Lock()
    private val ioLock = Lock()
    private var did: String
    private var anon: String
    private var ident = false

    val distinctId: String get() = lock.withLock { did }
    val identified: Boolean get() = lock.withLock { ident }

    /** The device's anonymous id; survives reset() only when `reuseAnonymousId` is set. */
    val anonymousId: String get() = lock.withLock { anon }

    init {
        val p = load(store, "lc_identity")
        val id = p?.get("distinct_id") as? String
        if (id != null) {
            did = id
            ident = p["identified"] == true
            anon = p["anon_id"] as? String ?: id
        } else if (bootstrap?.distinctId != null) {
            did = bootstrap.distinctId
            ident = bootstrap.isIdentified
            anon = if (ident) newId() else did
            persist()
        } else {
            did = newId()
            anon = did
            persist()
        }
    }

    private fun persist() = ioLock.withLock {
        val json = lock.withLock { Json.encode(mapOf("distinct_id" to did, "anon_id" to anon, "identified" to ident)) }
        store.set("lc_identity", json)
    }

    /**
     * Returns the `$identify` props to send, or null when nothing changed. The previous id is linked as
     * `$anon_distinct_id` only while anonymous (the gateway refuses to merge two identified persons).
     */
    fun identify(newId: String): Obj? {
        var out: Obj? = null
        var dirty = false
        lock.withLock {
            if (newId.isEmpty() || newId == did) {
                if (newId.isNotEmpty() && !ident) {
                    ident = true
                    dirty = true
                }
            } else {
                val prev = did
                val wasAnon = !ident
                did = newId
                ident = true
                dirty = true
                out = if (wasAnon) mapOf("\$anon_distinct_id" to prev) else emptyMap()
            }
        }
        if (dirty) persist()
        return out
    }

    fun reset() {
        lock.withLock {
            if (!reuseAnonymousId) anon = newId()
            did = anon
            ident = false
        }
        persist()
    }
}

/** UUIDv7 session rotated after 30 min idle or 24 h total. The state lives in memory; the store only mirrors it. */
class SessionManager(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val idleMs: Long = 30 * 60_000,
    private val maxMs: Long = 24 * 3_600_000,
) {
    val windowId = uuidv7(currentTimeMillis())
    private val lock = Lock()
    private val ioLock = Lock()
    private var id: String? = null
    private var start = 0L
    private var last = 0L

    init {
        val s = load(store, "lc_session")
        val sid = s?.get("id") as? String
        val st = (s?.get("start") as? Number)?.toLong()
        val la = (s?.get("last") as? Number)?.toLong()
        if (sid != null && st != null && la != null) {
            id = sid
            start = st
            last = la
        }
    }

    fun touch(): String {
        val t = clock.nowMs()
        val sid = lock.withLock {
            val cur = id
            val fresh = cur == null || t - last > idleMs || t - start > maxMs
            if (fresh) {
                id = uuidv7(t)
                start = t
            }
            last = t
            id!!
        }
        persist()
        return sid
    }

    fun reset() {
        lock.withLock { id = null }
        persist()
    }

    private fun persist() = ioLock.withLock {
        val json = lock.withLock { id?.let { Json.encode(mapOf("id" to it, "start" to start, "last" to last)) } }
        if (json == null) store.remove("lc_session") else store.set("lc_session", json)
    }
}

/** Super properties merged into every event; persisted. */
class SuperProps(private val store: KeyValueStore) {
    private val lock = Lock()
    private val ioLock = Lock()
    private var p = LinkedHashMap(load(store, "lc_props") ?: emptyMap())

    private var cleared = false // a cleared set removes the key; any later register/unregister writes the (possibly empty) object again

    private fun persist() = ioLock.withLock {
        val json = lock.withLock { if (cleared) null else Json.encode(p) }
        if (json == null) store.remove("lc_props") else store.set("lc_props", json)
    }

    fun register(props: Obj) {
        lock.withLock { cleared = false; p.putAll(props) }
        persist()
    }

    fun registerOnce(props: Obj) {
        lock.withLock { cleared = false; props.forEach { (k, v) -> if (p[k] == null) p[k] = v } }
        persist()
    }

    fun unregister(key: String) {
        lock.withLock { cleared = false; p.remove(key) }
        persist()
    }

    fun all(): Obj = lock.withLock { LinkedHashMap(p) }

    fun clear() {
        lock.withLock {
            cleared = true
            p = LinkedHashMap()
        }
        persist()
    }
}

/** Group memberships (type -> key) attached to every event as `$groups`. */
class Groups(private val store: KeyValueStore) {
    private val lock = Lock()
    private val ioLock = Lock()
    private var g = LinkedHashMap((load(store, "lc_groups") ?: emptyMap()).mapValues { it.value.toString() })

    private fun persist() = ioLock.withLock {
        val json = lock.withLock { if (g.isEmpty()) null else Json.encode(g) }
        if (json == null) store.remove("lc_groups") else store.set("lc_groups", json)
    }

    fun set(type: String, key: String) {
        lock.withLock { g[type] = key }
        persist()
    }

    fun all(): Map<String, String>? = lock.withLock { if (g.isEmpty()) null else LinkedHashMap(g) }

    fun clear() {
        lock.withLock { g = LinkedHashMap() }
        persist()
    }
}
