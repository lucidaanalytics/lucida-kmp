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
    private val personProps = LinkedHashMap<String, Any?>()
    private val groupProps = LinkedHashMap<String, Map<String, Any?>>()
    private val listeners = Listeners<(Obj) -> Unit>()

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
        if (options.sendFeatureFlags) core.enricher = { event -> if (event == "\$snapshot") null else flagEventProps(locked { LinkedHashMap(values) }) }
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

    /** Person properties used for flag evaluation only (no `$set` event, so no ingestion race). */
    fun setPersonPropertiesForFlags(props: Obj, reload: Boolean = true) {
        locked { personProps.putAll(props) }
        if (reload) reload()
    }

    fun resetPersonPropertiesForFlags(reload: Boolean = true) {
        locked { personProps.clear() }
        if (reload) reload()
    }

    fun setGroupPropertiesForFlags(type: String, props: Map<String, Any?>, reload: Boolean = true) {
        locked { groupProps[type] = (groupProps[type] ?: emptyMap()) + props }
        if (reload) reload()
    }

    fun resetGroupPropertiesForFlags(reload: Boolean = true) {
        locked { groupProps.clear() }
        if (reload) reload()
    }

    fun setDefinitions(d: List<Obj>) {
        locked {
            defs = d
        }
    }

    private fun context(): Obj = buildMap {
        put("distinct_id", core.distinctId)
        put("groups", core.groups ?: emptyMap<String, String>())
        put("person_properties", LinkedHashMap(personProps))
        if (groupProps.isNotEmpty()) put("group_properties", LinkedHashMap(groupProps))
        put("device_properties", deviceProperties)
        appVersion?.let { put("app_version", it) }
    }

    /** Local pass; true when the server must decide (or there are no definitions). */
    private fun evalLocal(): Boolean {
        return locked {
            if (defs.isEmpty()) return true
            var server = false
            val v = LinkedHashMap<String, Any?>()
            val p = LinkedHashMap<String, Any?>()
            evaluateAll(defs, context()).forEach { (k, r) ->
                if (requiresServer(r)) server = true
                else {
                    v[k] = r["value"]
                    r["payload"]?.let { p[k] = it }
                }
            }
            local = v
            values.putAll(v)
            payloads.putAll(p)
            return server
        }
    }

    fun reload() {
        val mine = locked { ++seq }
        if (!evalLocal()) {
            val snapshot = all()
            listeners.forEach { it(snapshot) }
            return
        }
        if (!core.consent.has("analytics")) return
        val body = Json.encode(
            buildMap {
                put("api_key", core.cfg.apiKey)
                put("distinct_id", core.distinctId)
                core.anonDistinctId?.let { put("anon_distinct_id", it) }
                put("groups", core.groups ?: emptyMap<String, String>())
                put("person_properties", locked { LinkedHashMap(personProps) })
                locked { if (groupProps.isNotEmpty()) put("group_properties", LinkedHashMap(groupProps)) }
                if (options.evaluationContexts.isNotEmpty()) put("evaluation_contexts", options.evaluationContexts)
            },
        )
        val r = core.cfg.http.send("POST", "${core.host}/v1/flags", body, mapOf("Content-Type" to "application/json"))
        if (r.status !in 200..299) return
        val j = runCatching { Json.decodeObj(r.body) }.getOrNull() ?: return
        val v = LinkedHashMap<String, Any?>()
        val p = LinkedHashMap<String, Any?>()
        (j["flags"] as? Map<*, *>)?.forEach { (k, x) ->
            if (x is Map<*, *>) {
                v[k as String] = x["value"] ?: x["variant"] ?: (x["enabled"] == true)
                x["payload"]?.let { p[k] = it }
            } else {
                v[k as String] = x
            }
        }
        locked {
            if (mine != seq) return // a newer reload superseded this one
            requestId = j["request_id"] as? String
            values = LinkedHashMap(local + v)
            payloads = p
            core.storage.set(CACHE, Json.encode(mapOf("values" to values, "payloads" to payloads)))
        }
        val snapshot = all()
        listeners.forEach { it(snapshot) }
    }

    /** false | true | variant key. Emits `$feature_flag_called` once per key/value. */
    fun get(key: String): Any? {
        val v = locked { values[key] ?: false }
        if (options.sendFeatureFlagEvent && locked { called.add("$key=$v") }) {
            core.capture("\$feature_flag_called", mapOf("\$feature_flag" to key, "\$feature_flag_response" to v))
        }
        return v
    }

    fun isEnabled(key: String) = get(key) != false
    fun payload(key: String): Any? = locked { payloads[key] }
    fun all(): Obj = locked { LinkedHashMap(values) }

    private companion object {
        const val CACHE = "lc_flags"
    }
}
