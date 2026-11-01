package com.lucida.kmp

private fun load(s: KeyValueStore, key: String): Obj? = s.get(key)?.let { runCatching { Json.decodeObj(it) }.getOrNull() }

/** Anonymous UUIDv7 (or [newId]) until identify(); persisted through the (gated) store. */
class Identity(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val newId: () -> String = { uuidv7(clock.nowMs()) },
    bootstrap: Bootstrap? = null,
    private val reuseAnonymousId: Boolean = false,
) {
    var distinctId: String
        private set
    var identified = false
        private set

    /** The device's anonymous id; survives reset() only when `reuseAnonymousId` is set. */
    var anonymousId: String
        private set

    init {
        val p = load(store, "lc_identity")
        val id = p?.get("distinct_id") as? String
        if (id != null) {
            distinctId = id
            identified = p["identified"] == true
            anonymousId = p["anon_id"] as? String ?: id
        } else if (bootstrap?.distinctId != null) {
            distinctId = bootstrap.distinctId
            identified = bootstrap.isIdentified
            anonymousId = if (identified) newId() else distinctId
            save()
        } else {
            distinctId = newId()
            anonymousId = distinctId
            save()
        }
    }

    private fun save() = store.set("lc_identity", Json.encode(mapOf("distinct_id" to distinctId, "anon_id" to anonymousId, "identified" to identified)))

    /**
     * Returns the `$identify` props to send, or null when nothing changed. The previous id is linked as
     * `$anon_distinct_id` only while anonymous (the gateway refuses to merge two identified persons).
     */
    fun identify(newId: String): Obj? {
        return locked {
            if (newId.isEmpty() || newId == distinctId) {
                if (newId.isNotEmpty() && !identified) {
                    identified = true
                    save()
                }
                return null
            }
            val prev = distinctId
            val wasAnon = !identified
            distinctId = newId
            identified = true
            save()
            return if (wasAnon) mapOf("\$anon_distinct_id" to prev) else emptyMap()
        }
    }

    fun reset() {
        locked {
            if (!reuseAnonymousId) anonymousId = newId()
            distinctId = anonymousId
            identified = false
            save()
        }
    }
}

/** UUIDv7 session rotated after 30 min idle or 24 h total. */
class SessionManager(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val idleMs: Long = 30 * 60_000,
    private val maxMs: Long = 24 * 3_600_000,
) {
    val windowId = uuidv7(currentTimeMillis())

    fun touch(): String {
        return locked {
            val t = clock.nowMs()
            val s = load(store, "lc_session")
            val last = (s?.get("last") as? Number)?.toLong()
            val start = (s?.get("start") as? Number)?.toLong()
            val fresh = s == null || last == null || start == null || t - last > idleMs || t - start > maxMs
            val id = if (fresh) uuidv7(t) else s!!["id"] as String
            store.set("lc_session", Json.encode(mapOf("id" to id, "start" to (if (fresh) t else start), "last" to t)))
            return id
        }
    }

    fun reset() = store.remove("lc_session")
}

/** Super properties merged into every event; persisted. */
class SuperProps(private val store: KeyValueStore) {
    private var p = LinkedHashMap(load(store, "lc_props") ?: emptyMap())

    private fun save() = store.set("lc_props", Json.encode(p))

    fun register(props: Obj) {
        locked {
            p.putAll(props)
            save()
        }
    }

    fun registerOnce(props: Obj) {
        locked {
            props.forEach { (k, v) -> if (p[k] == null) p[k] = v }
            save()
        }
    }

    fun unregister(key: String) {
        locked {
            p.remove(key)
            save()
        }
    }

    fun all(): Obj = locked { LinkedHashMap(p) }

    fun clear() {
        locked {
            p = LinkedHashMap()
            store.remove("lc_props")
        }
    }
}

/** Group memberships (type -> key) attached to every event as `$groups`. */
class Groups(private val store: KeyValueStore) {
    private var g = LinkedHashMap((load(store, "lc_groups") ?: emptyMap()).mapValues { it.value.toString() })

    fun set(type: String, key: String) {
        locked {
            g[type] = key
            store.set("lc_groups", Json.encode(g))
        }
    }

    fun all(): Map<String, String>? = locked { if (g.isEmpty()) null else LinkedHashMap(g) }

    fun clear() {
        locked {
            g = LinkedHashMap()
            store.remove("lc_groups")
        }
    }
}
