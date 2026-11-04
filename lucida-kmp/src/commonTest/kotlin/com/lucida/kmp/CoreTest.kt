package com.lucida.kmp

import kotlin.jvm.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeHttp : HttpPort {
    val calls = ArrayList<String>()
    val bodies = ArrayList<String>()
    val gzips = ArrayList<Boolean>()
    @Volatile var statuses = mutableListOf(200)
    @Volatile var response = "{}"
    private val lock = Lock()

    override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult {
        lock.withLock {
            calls.add("$method $url")
            gzips.add(gzip)
            if (body != null) bodies.add(body)
        }
        val s = lock.withLock { if (statuses.size > 1) statuses.removeAt(0) else statuses[0] }
        return HttpResult(s, response)
    }
}

class Harness(mode: ConsentMode = ConsentMode.OPT_OUT, flushAt: Int = 100, val http: FakeHttp = FakeHttp(), val store: MemoryStore = MemoryStore()) {
    var now = 1_760_000_000_000L
    val core = Core(
        CoreConfig(
            apiKey = "lc_pk_x", apiHost = "https://ingest.test/", http = http, store = store, clock = { now },
            consentMode = mode, queue = { QueueConfig(it, "lc_pk_x", flushAt = flushAt, flushIntervalMs = 60_000, random = { 0.0 }) },
        ),
    )
}

class CoreTest {
    @Test fun batchesGzipsAndWireShape() {
        val t = Harness(flushAt = 2)
        t.core.capture("a")
        t.core.capture("b")
        t.core.queue.idle()
        assertEquals("POST https://ingest.test/v1/batch?compression=gzip", t.http.calls.single())
        assertTrue(t.http.gzips.single())
        val body = Json.decodeObj(t.http.bodies.single())
        assertEquals("lc_pk_x", body["api_key"])

        @Suppress("UNCHECKED_CAST")
        val e = (body["batch"] as List<Obj>).first()

        @Suppress("UNCHECKED_CAST")
        val p = e["properties"] as Obj
        assertEquals("lucida-kmp", p["\$lib"])
        assertNotNull(p["\$session_id"])
        assertEquals(0, t.core.queue.size)
    }

    @Test fun retriesA500ThenSucceedsAndPersistsUntilAcked() {
        val t = Harness(http = FakeHttp().apply { statuses = mutableListOf(500, 200) })
        t.core.capture("a")
        t.core.queue.flush() // 500: stays queued and persisted, retry armed
        assertEquals(1, t.core.queue.size)
        assertNotNull(t.store.get("lc_queue"))
        repeat(40) { if (t.core.queue.size > 0) sleepMs(50) } // backoff timer (500 ms with random=0)
        assertEquals(0, t.core.queue.size)
        assertNull(t.store.get("lc_queue"))
    }

    @Test fun queueSurvivesRestart() {
        val t = Harness()
        t.core.capture("a")
        assertEquals(1, Harness(store = t.store).core.queue.size)
    }

    @Test fun dropsNonRetryable400() {
        val dropped = mutableListOf<String>()
        val http = FakeHttp().apply { statuses = mutableListOf(400) }
        val core = Core(CoreConfig("k", "https://h", http, MemoryStore(), onDrop = { _, r -> dropped.add(r) }, queue = { QueueConfig(it, "k", flushAt = 100) }))
        core.capture("a")
        core.queue.flush()
        assertEquals(listOf("status_400"), dropped)
        assertEquals(0, core.queue.size)
    }

    @Test fun optInNothingStoredOrSentBeforeConsent() {
        val t = Harness(ConsentMode.OPT_IN)
        assertNull(t.core.capture("x"))
        t.core.register(mapOf("a" to 1))
        t.core.identify("user-1")
        assertTrue(t.store.data.isEmpty(), "store must stay empty before consent: ${t.store.data.keys}")
        assertTrue(t.http.bodies.isEmpty())
        t.core.optIn()
        assertTrue(t.store.get("lc_identity")!!.contains("user-1"))
        t.core.capture("y")
        t.core.queue.flush()
        assertTrue(t.http.bodies.isNotEmpty())
    }

    @Test fun anonDistinctIdIsSetOnlyAfterIdentify() {
        val t = Harness()
        assertNull(t.core.anonDistinctId)
        val anon = t.core.distinctId
        t.core.identify("user-1")
        assertEquals(anon, t.core.anonDistinctId)
    }

    @Test fun optOutSendsOptOutThenWipesQueue() {
        val t = Harness()
        t.core.capture("a")
        t.core.optOut()
        t.core.queue.idle()
        assertNull(t.store.get("lc_queue"))
        assertTrue(t.http.bodies.single().contains("\$opt_out"))
        assertNull(t.core.capture("b"))
    }

    @Test fun identityAliasGroupsSuperPropsReset() {
        val t = Harness()
        val seen = mutableListOf<Obj>()
        t.core.onCaptured { seen.add(it) }
        val anon = t.core.distinctId
        t.core.register(mapOf("plan" to "pro"))
        t.core.identify("u1", mapOf("email" to "a@b.c"))

        @Suppress("UNCHECKED_CAST")
        fun props() = seen.last()["properties"] as Obj
        assertEquals(anon, props()["\$anon_distinct_id"])
        assertEquals("u1", seen.last()["distinct_id"])
        assertEquals(mapOf("email" to "a@b.c"), seen.last()["\$set"])
        t.core.identify("u2")
        assertFalse(props().containsKey("\$anon_distinct_id"))
        t.core.alias("al")
        assertEquals("al", props()["alias"])
        t.core.group("company", "acme")
        t.core.capture("z")
        assertEquals(mapOf("company" to "acme"), seen.last()["\$groups"])
        assertEquals("pro", props()["plan"])
        t.core.reset()
        assertNotEquals("u2", t.core.distinctId)
        t.core.capture("after")
        assertFalse(seen.last().containsKey("\$groups"))
    }

    @Test fun sessionRotatesAfter30MinIdle() {
        val t = Harness()

        @Suppress("UNCHECKED_CAST")
        fun sid() = (t.core.capture("e")!!["properties"] as Obj)["\$session_id"]
        val a = sid()
        t.now += 29 * 60_000
        assertEquals(a, sid())
        t.now += 31 * 60_000
        assertNotEquals(a, sid())
    }

    @Test fun facadeSessionIdMatchesCapturedSessionId() {
        var now = 1_760_000_000_000L
        val lc = Lucida(
            CoreConfig(
                apiKey = "lc_pk_x", apiHost = "https://ingest.test/", http = FakeHttp(), store = MemoryStore(), clock = { now },
                queue = { QueueConfig(it, "lc_pk_x", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
            appVersion = "1.0",
        )
        val sid = lc.sessionId

        @Suppress("UNCHECKED_CAST")
        assertEquals(sid, (lc.core.capture("e")!!["properties"] as Obj)["\$session_id"])
        now += 31 * 60_000
        assertNotEquals(sid, lc.sessionId)
    }

    @Test fun screenView() {
        val t = Harness()
        val e = t.core.screen("Cart", mapOf("k" to 1))!!
        assertEquals("\$screen", e["event"])

        @Suppress("UNCHECKED_CAST")
        assertEquals("Cart", (e["properties"] as Obj)["\$screen_name"])
    }

    @Test fun lifecycleEvents() {
        val t = Harness()
        val names = mutableListOf<String>()
        t.core.onCaptured { names.add(it["event"] as String) }
        Lifecycle(t.core, "1.0").launched()
        assertEquals(listOf("\$app_installed", "\$app_opened"), names)
        names.clear()
        Lifecycle(t.core, "1.1").launched()
        assertEquals(listOf("\$app_updated", "\$app_opened"), names)
        names.clear()
        Lifecycle(t.core, "1.1").backgrounded()
        assertEquals(listOf("\$app_backgrounded"), names)
        t.core.queue.idle()
        assertTrue(t.http.bodies.isNotEmpty())
    }

    @Test fun flagsServerResponseCacheAndLocal() {
        val http = FakeHttp().apply { response = """{"flags":{"f":{"enabled":true,"variant":"b"},"g":false},"request_id":"r1"}""" }
        val t = Harness(http = http)
        val f = Flags(t.core)
        f.reload()
        assertEquals("b", f.get("f"))
        assertFalse(f.isEnabled("g"))
        assertEquals("b", Flags(t.core).get("f")) // cold start from cache

        val local = Harness()
        val lf = Flags(local.core, listOf(mapOf("key" to "on", "type" to "boolean", "active" to true, "salt" to "s", "aggregation" to mapOf("kind" to "person"), "conditions" to listOf(mapOf("rollout_pct" to 100)))))
        lf.reload()
        assertTrue(lf.isEnabled("on"))
        assertTrue(local.http.calls.isEmpty())
    }

    @Test fun uuidv7Shape() {
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(uuidv7(1_760_000_000_000L)))
    }

    @Test fun jsonRoundTrip() {
        val v = mapOf("a" to listOf(1L, 2.5, "x\"\n", null, true), "b" to mapOf("c" to "é"))
        assertEquals(v, Json.decode(Json.encode(v)))
    }
}
