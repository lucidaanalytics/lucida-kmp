package com.lucida.kmp

import kotlin.jvm.Volatile

data class CoreConfig(
    val apiKey: String,
    val apiHost: String,
    val http: HttpPort,
    /** Real persistence; the core gates it so nothing is written before consent. */
    val store: KeyValueStore,
    val libName: String = "lucida-kmp",
    val libVersion: String = "0.1.0",
    val clock: Clock = SystemClock,
    val consentMode: ConsentMode = ConsentMode.OPT_OUT,
    val queueBeforeConsent: Boolean = false,
    val queue: (url: String) -> QueueConfig = { QueueConfig(it, apiKey) },
    val onDrop: (List<Obj>, String) -> Unit = { _, _ -> },
    /** Ordered hooks run on every event before it is queued; see [HookChain]. */
    val beforeSend: List<BeforeSend> = emptyList(),
    val personProfiles: PersonProfiles = PersonProfiles.ALWAYS,
    /** Ask the server not to geolocate events (`$geoip_disable`). */
    val disableGeoip: Boolean = false,
    /** Kill switch: nothing is captured. */
    val disabled: Boolean = false,
    val sessionIdleMs: Long = 30 * 60_000,
    val sessionMaxMs: Long = 24 * 3_600_000,
    /** Keep the anonymous id across reset(). */
    val reuseAnonymousId: Boolean = false,
    /** Custom anonymous id generator. */
    val anonymousIdProvider: (() -> String)? = null,
    val bootstrap: Bootstrap? = null,
    /** Encrypt the persisted queue at rest (optional). */
    val queueCipher: QueueCipher? = null,
    /** Own-backend hosts that receive `X-Lucida-*` headers via [Core.tracing]. */
    val tracingHeaders: List<String> = emptyList(),
    val debug: Boolean = false,
    val log: (String) -> Unit = { println("[lucida] $it") },
)

/** Platform-agnostic client: identity, sessions, consent gating, super props, groups, queue. */
class Core(val cfg: CoreConfig) {
    val clock = cfg.clock
    val consent = Consent(cfg.store, cfg.consentMode, cfg.queueBeforeConsent)
    private val gated = GatedStore(cfg.store) { consent.has("analytics") }
    val identity = Identity(gated, clock, cfg.anonymousIdProvider ?: { uuidv7(clock.nowMs()) }, cfg.bootstrap, cfg.reuseAnonymousId)
    val session = SessionManager(gated, clock, cfg.sessionIdleMs, cfg.sessionMaxMs)
    private val props = SuperProps(gated)
    private val groupState = Groups(gated)
    private val person = PersonMode(gated, cfg.personProfiles)
    val host = cfg.apiHost.trimEnd('/')
    @Volatile var debug = cfg.debug

    // Raw store: events only reach the queue after consent, and a revoke must really delete it.
    val queue = EventQueue(
        cfg.http,
        cfg.queueCipher?.let { EncryptedStore(cfg.store, it, setOf("lc_queue")) } ?: cfg.store,
        clock, cfg.queue("$host/v1/batch"), cfg.onDrop,
    )
    private val hooks = HookChain(cfg.beforeSend) { debugLog(it) }
    private val held = ArrayList<Obj>()
    private val listeners = Listeners<(Obj) -> Unit>()

    val tracing = TracingHeaders(cfg.tracingHeaders, cfg.apiHost, { consent.has("analytics") }) { distinctId to session.touch() }

    /** Consent-gated storage for modules. */
    val storage: KeyValueStore get() = gated
    val distinctId get() = identity.distinctId
    val anonymousId get() = identity.anonymousId
    /** Pre-identify id, sent as `anon_distinct_id` to /v1/flags so continuity flags keep their bucket (LC-FLG-34). */
    val anonDistinctId: String? get() = identity.anonymousId.takeIf { identity.identified && it.isNotEmpty() && it != identity.distinctId }
    val groups get() = groupState.all()
    val isOptedOut get() = !consent.has("analytics")

    fun debugLog(msg: String) {
        if (debug) cfg.log(msg)
    }

    /** Observe every event that passes the consent gate and the hook chain. */
    fun onCaptured(fn: (Obj) -> Unit) {
        listeners.add(fn)
    }

    /** Extra properties for an event name (flag values); the caller's own properties win. */
    @Volatile var enricher: ((String) -> Obj?)? = null

    fun capture(event: String, properties: Obj = emptyMap(), set: Obj? = null, setOnce: Obj? = null): Obj? {
        return locked {
            if (cfg.disabled) return null
            val granted = consent.has("analytics")
            if (!granted && !(consent.pending && consent.queueBeforeConsent)) return null
            // Person writes are blocked in NEVER mode and mark the device identified in IDENTIFIED_ONLY mode.
            val writes = !set.isNullOrEmpty() || !setOnce.isNullOrEmpty()
            if (writes && person.blocksPersonWrites) debugLog("personProfiles=never: ignoring \$set/\$set_once on $event")
            if (writes || event == "\$identify" || event == "\$create_alias" || event == "\$groupidentify") person.markIdentified()
            val g = groupState.all()
            val now = clock.nowMs()
            val personless = person.personless(event)
            val e = LinkedHashMap<String, Any?>().apply {
                put("uuid", uuidv7(now))
                put("event", event)
                put("distinct_id", identity.distinctId)
                put("timestamp", Instant.ofEpochMilli(now).toString())
                put(
                    "properties",
                    LinkedHashMap(
                        (enricher?.invoke(event) ?: emptyMap()) + props.all() + properties + mapOf(
                            "\$session_id" to session.touch(), "\$window_id" to session.windowId,
                            "\$lib" to cfg.libName, "\$lib_version" to cfg.libVersion,
                        ),
                    ).also {
                        if (personless) it["\$process_person_profile"] = false
                        if (cfg.disableGeoip) it["\$geoip_disable"] = true
                    },
                )
                if (!set.isNullOrEmpty() && !person.blocksPersonWrites) put("\$set", set)
                if (!setOnce.isNullOrEmpty() && !person.blocksPersonWrites) put("\$set_once", setOnce)
                if (g != null) put("\$groups", g)
            }
            val out = hooks.run(e) ?: return null
            listeners.forEach { it(out) }
            if (granted) queue.enqueue(out) else if (held.size < cfg.queue("").maxQueueSize) held.add(out)
            return out
        }
    }

    fun identify(id: String, set: Obj? = null, setOnce: Obj? = null) {
        if (person.blocksPersonWrites) return debugLog("personProfiles=never: identify ignored")
        val r = identity.identify(id)
        if (r != null || set != null || setOnce != null) capture("\$identify", r ?: emptyMap(), set, setOnce)
    }

    /** Link [alias] to the current (or given) distinct id. */
    fun alias(alias: String, original: String? = null): Obj? {
        if (person.blocksPersonWrites) return null.also { debugLog("personProfiles=never: alias ignored") }
        return capture("\$create_alias", mapOf("alias" to alias, "distinct_id" to (original ?: distinctId)))
    }

    /** Update person properties without another event of your own (sends a `$set` event). */
    fun setPersonProperties(set: Obj? = null, setOnce: Obj? = null): Obj? {
        if (person.blocksPersonWrites) return null.also { debugLog("personProfiles=never: setPersonProperties ignored") }
        return capture("\$set", emptyMap(), set, setOnce)
    }

    fun reset() {
        identity.reset()
        props.clear()
        groupState.clear()
        session.reset()
        person.reset()
    }

    fun register(p: Obj) = props.register(p)
    fun registerOnce(p: Obj) = props.registerOnce(p)
    fun unregister(key: String) = props.unregister(key)

    fun group(type: String, key: String, properties: Obj? = null) {
        groupState.set(type, key)
        capture("\$groupidentify", mapOf("\$group_type" to type, "\$group_key" to key, "\$group_set" to (properties ?: emptyMap<String, Any?>())))
    }

    /** Screen view: `$screen` with `$screen_name`. */
    fun screen(name: String, properties: Obj = emptyMap()) = capture("\$screen", properties + ("\$screen_name" to name))

    /** App opened through a URL / link: `$deep_link_opened` with the url, its query parameters and the referrer. */
    fun deepLink(url: String, referrer: String? = null): Obj? {
        val query = url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            percentDecode(k) to percentDecode(it.substringAfter('=', ""))
        }
        return capture("\$deep_link_opened", buildMap { put("url", url); put("query", query); referrer?.let { put("referrer", it) } })
    }

    /** Record a consent decision. Granting analytics releases state and held events; revoking drops them. */
    fun setConsent(update: Map<String, Boolean>) {
        locked {
            val was = consent.has("analytics")
            val will = update["analytics"] ?: was
            if (was && !will) {
                capture("\$opt_out")
                queue.drainThenClear()
            }
            consent.set(update)
            if (!was && will) {
                gated.flushBuffered()
                held.toList().forEach(queue::enqueue)
                held.clear()
                capture("\$opt_in")
            }
            if (!will) {
                held.clear()
                gated.discardBuffered()
            }
        }
    }

    fun optIn() = setConsent(mapOf("analytics" to true, "replay" to true, "marketing" to true))
    fun optOut() = setConsent(mapOf("analytics" to false, "replay" to false, "marketing" to false))
    fun flush() = queue.flushAsync()
    fun shutdown() = queue.shutdown()
}
