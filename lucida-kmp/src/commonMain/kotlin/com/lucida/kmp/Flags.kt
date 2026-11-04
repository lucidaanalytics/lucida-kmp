package com.lucida.kmp

/**
 * Feature flags: local evaluation over optional bootstrap definitions, server evaluation (POST /v1/flags)
 * for flags that need it (cohorts) or when no definitions are present. The last response is cached in
 * (gated) storage so cold starts have values offline. [reload] blocks; call it off the main thread.
 */
class Flags(
    private val core: Core,
    private var defs: List<Obj> = emptyList(),
    private val deviceProperties: Obj = emptyMap(),
    private val appVersion: String? = null,
    private val options: FlagOptions = FlagOptions(),
) {
    // State lock: guards every field below. A leaf (see Platform.kt): never held across core calls, user callbacks, http or storage.
    private val lock = Lock()
    private val ioLock = Lock() // serialises writes of the `lc_flags` cache
    private val personProps = LinkedHashMap<String, Any?>()
    private val groupProps = LinkedHashMap<String, Map<String, Any?>>()
    private val listeners = Listeners<(Obj) -> Unit>()
    private val statusListeners = Listeners<(FlagsStatus) -> Unit>()
    private val meta = HashMap<String, Triple<String, Int, Boolean>>() // reason, condition index, evaluated locally
    private var loaded = false

    private var values = LinkedHashMap<String, Any?>()
    private var payloads = LinkedHashMap<String, Any?>()
    private var local = emptyMap<String, Any?>()
    private val called = HashSet<String>()
    var requestId: String? = null
        private set
    private var seq = 0

    init {
        core.storage.get(CACHE)?.let { raw ->
            runCatching {
                val j = Json.decodeObj(raw)
                @Suppress("UNCHECKED_CAST")
                values = LinkedHashMap(j["values"] as Obj)
                @Suppress("UNCHECKED_CAST")
                payloads = LinkedHashMap(j["payloads"] as Obj)
            }
        }
    }

    init {
        if (options.sendFeatureFlags) core.enricher = { event -> if (event == "\$snapshot") null else flagEventProps(lock.withLock { LinkedHashMap(values) }) }
        // Bootstrap wins over the cache until the first reload replaces it.
        core.cfg.bootstrap?.let {
            values.putAll(it.flags)
            payloads.putAll(it.payloads)
        }
    }

    /** Called after every successful reload with all flag values. */
    fun onFeatureFlags(fn: (Obj) -> Unit) {
        listeners.add(fn)
    }

    /** CHANGED after each successful reload, STALE when a reload fails after a success, ERROR when none has succeeded yet. */
    fun onFlagsStatus(fn: (FlagsStatus) -> Unit) {
        statusListeners.add(fn)
    }

    private fun fail() {
        val s = if (lock.withLock { loaded }) FlagsStatus.STALE else FlagsStatus.ERROR
        statusListeners.forEach { it(s) }
    }

    private fun publish() {
        lock.withLock { loaded = true }
        val snapshot = all()
        listeners.forEach { it(snapshot) }
        statusListeners.forEach { it(FlagsStatus.CHANGED) }
    }

    /** Person properties used for flag evaluation only (no `$set` event, so no ingestion race). */
    fun setPersonPropertiesForFlags(props: Obj, reload: Boolean = true) {
        lock.withLock { personProps.putAll(props) }
        if (reload) reload()
    }

    fun resetPersonPropertiesForFlags(reload: Boolean = true) {
        lock.withLock { personProps.clear() }
        if (reload) reload()
    }

    fun setGroupPropertiesForFlags(type: String, props: Map<String, Any?>, reload: Boolean = true) {
        lock.withLock { groupProps[type] = (groupProps[type] ?: emptyMap()) + props }
        if (reload) reload()
    }

    fun resetGroupPropertiesForFlags(reload: Boolean = true) {
        lock.withLock { groupProps.clear() }
        if (reload) reload()
    }

    fun setDefinitions(d: List<Obj>) {
        lock.withLock {
            defs = d
        }
    }

    /** Called with [lock] held; [distinctId] and [groups] were read from the core beforehand. */
    private fun context(distinctId: String, groups: Map<String, String>?): Obj = buildMap {
        put("distinct_id", distinctId)
        put("groups", groups ?: emptyMap<String, String>())
        put("person_properties", LinkedHashMap(personProps))
        if (groupProps.isNotEmpty()) put("group_properties", LinkedHashMap(groupProps))
        put("device_properties", deviceProperties)
        appVersion?.let { put("app_version", it) }
    }

    /** Local pass; true when the server must decide (or there are no definitions). Evaluates on a snapshot, outside the lock. */
    private fun evalLocal(): Boolean {
        val distinctId = core.distinctId
        val groups = core.groups
        val (d, ctx) = lock.withLock { defs to context(distinctId, groups) }
        if (d.isEmpty()) return true
        var server = false
        val v = LinkedHashMap<String, Any?>()
        val p = LinkedHashMap<String, Any?>()
        val m = HashMap<String, Triple<String, Int, Boolean>>()
        evaluateAll(d, ctx).forEach { (k, r) ->
            if (requiresServer(r)) server = true
            else {
                v[k] = r["value"]
                r["payload"]?.let { p[k] = it }
                m[k] = Triple(r["reason"] as? String ?: "", (r["condition_index"] as? Number)?.toInt() ?: -1, true)
            }
        }
        lock.withLock {
            local = v
            values.putAll(v)
            payloads.putAll(p)
            meta.putAll(m)
        }
        return server
    }

    fun reload() {
        val mine = lock.withLock { ++seq }
        if (!evalLocal()) {
            publish()
            return
        }
        if (!core.consent.has("analytics")) return
        val body = Json.encode(
            buildMap {
                put("api_key", core.cfg.apiKey)
                put("distinct_id", core.distinctId)
                core.anonDistinctId?.let { put("anon_distinct_id", it) }
                put("groups", core.groups ?: emptyMap<String, String>())
                put("person_properties", lock.withLock { LinkedHashMap(personProps) })
                lock.withLock { if (groupProps.isNotEmpty()) put("group_properties", LinkedHashMap(groupProps)) }
                if (options.evaluationContexts.isNotEmpty()) put("evaluation_contexts", options.evaluationContexts)
            },
        )
        val r = core.cfg.http.send("POST", "${core.host}/v1/flags", body, mapOf("Content-Type" to "application/json"))
        val j = if (r.status in 200..299) runCatching { Json.decodeObj(r.body) }.getOrNull() else null
        if (j == null) {
            fail()
            return
        }
        val v = LinkedHashMap<String, Any?>()
        val p = LinkedHashMap<String, Any?>()
        val m = HashMap<String, Triple<String, Int, Boolean>>()
        (j["flags"] as? Map<*, *>)?.forEach { (k, x) ->
            if (x is Map<*, *>) {
                v[k as String] = x["value"] ?: x["variant"] ?: (x["enabled"] == true)
                x["payload"]?.let { p[k] = it }
                m[k] = Triple(x["reason"] as? String ?: "", (x["condition_index"] as? Number)?.toInt() ?: -1, false)
            } else {
                v[k as String] = x
            }
        }
        // Apply and snapshot under the state lock, write the cache outside it; ioLock keeps two reloads' writes in order.
        ioLock.withLock {
            val cache = lock.withLock {
                if (mine != seq) return // a newer reload superseded this one
                requestId = j["request_id"] as? String
                values = LinkedHashMap(local + v)
                payloads = p
                meta.putAll(m)
                Json.encode(mapOf("values" to values, "payloads" to payloads))
            }
            core.storage.set(CACHE, cache)
        }
        publish()
    }

    /**
     * Value, payload, variant, raw Lucida reason and request id. A key with no value is served from
     * `FlagOptions.fallbacks` with reason `fallback` (no event); null when it has no fallback either.
     */
    fun getFeatureFlagDetails(key: String): FlagDetails? {
        val known = lock.withLock { values.containsKey(key) }
        if (!known) return options.fallbacks[key]?.let { FlagDetails(it, null, null, "fallback", -1, null, false) }
        val v = get(key)
        val (m, rid, pl) = lock.withLock { Triple(meta[key], requestId, payloads[key]) }
        return FlagDetails(v, pl, v as? String, m?.first ?: "", m?.second ?: -1, rid, m?.third ?: false)
    }

    /** false | true | variant key. Emits `$feature_flag_called` once per key/value; fallbacks emit nothing. */
    fun get(key: String): Any? {
        // a configured fallback is served silently; a missing flag without one still reports `false`
        val v = lock.withLock { if (values.containsKey(key)) values[key] ?: false else null }
            ?: options.fallbacks[key]?.let { return it } ?: false
        if (options.sendFeatureFlagEvent && lock.withLock { called.add("$key=$v") }) {
            core.capture("\$feature_flag_called", mapOf("\$feature_flag" to key, "\$feature_flag_response" to v))
        }
        return v
    }

    fun isEnabled(key: String) = get(key) != false
    fun payload(key: String): Any? = lock.withLock { payloads[key] }
    fun all(): Obj = lock.withLock { LinkedHashMap(values) }

    private companion object {
        const val CACHE = "lc_flags"
    }
}
