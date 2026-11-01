package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
private fun props(e: Obj?) = e!!["properties"] as Obj

private fun core(cfg: (CoreConfig) -> CoreConfig = { it }, http: FakeHttp = FakeHttp(), store: MemoryStore = MemoryStore(), flushAt: Int = 100, queue: QueueConfig? = null): Core {
    var now = 1_760_000_000_000L
    return Core(
        cfg(
            CoreConfig(
                "k", "https://ingest.test", http, store, clock = { now++ },
                queue = { queue?.copy(url = it) ?: QueueConfig(it, "k", flushAt = flushAt, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
        ),
    )
}

class HooksTest {
    @Test fun chainAmendsDropsAndKeepsEventWhenAHookThrows() {
        val logs = mutableListOf<String>()
        val c = core({
            it.copy(
                debug = true, log = { m -> logs.add(m) },
                beforeSend = listOf(
                    { e -> e + ("properties" to (props(e) + ("a" to 1))) },
                    { _ -> error("boom") },
                    { e -> if (e["event"] == "drop") null else e },
                    { e -> e + ("properties" to (props(e) + ("b" to props(e)["a"]))) },
                ),
            )
        })
        val e = c.capture("keep")
        assertEquals(1, props(e)["a"])
        assertEquals(1, props(e)["b"])
        assertTrue(logs.single().contains("boom"))
        assertNull(c.capture("drop"))
        assertEquals(1, c.queue.size)
    }

    @Test fun sampleHookStampsSurvivors() {
        val keep = sampleHook(0.5) { 0.1 }(mapOf("event" to "x", "properties" to mapOf("p" to 1)))
        assertEquals(0.5, props(keep)["\$sample_rate"])
        assertEquals(1, props(keep)["p"])
        assertNull(sampleHook(0.5) { 0.9 }(mapOf("event" to "x")))
        assertNull(sampleHook(0.0) { 0.0 }(mapOf("event" to "x")))
        val all = mapOf("event" to "x")
        assertEquals(all, sampleHook(1.0) { 0.99 }(all))
    }
}

class PersonProfilesTest {
    @Test fun alwaysIsUnchanged() {
        assertFalse(props(core().capture("a")).containsKey("\$process_person_profile"))
    }

    @Test fun identifiedOnlyFlipsOnIdentifyAndPersists() {
        val store = MemoryStore()
        val c = core({ it.copy(personProfiles = PersonProfiles.IDENTIFIED_ONLY) }, store = store)
        assertEquals(false, props(c.capture("a"))["\$process_person_profile"])
        c.identify("u1")
        assertFalse(props(c.capture("b")).containsKey("\$process_person_profile"))
        val again = core({ it.copy(personProfiles = PersonProfiles.IDENTIFIED_ONLY) }, store = store)
        assertFalse(props(again.capture("c")).containsKey("\$process_person_profile"))
        again.reset()
        assertEquals(false, props(again.capture("d"))["\$process_person_profile"])
    }

    @Test fun identifiedOnlyFlipsOnPersonPropertySet() {
        val c = core({ it.copy(personProfiles = PersonProfiles.IDENTIFIED_ONLY) })
        c.capture("a", set = mapOf("x" to 1))
        assertFalse(props(c.capture("b")).containsKey("\$process_person_profile"))
    }

    @Test fun neverBlocksIdentityAndPersonWrites() {
        val c = core({ it.copy(personProfiles = PersonProfiles.NEVER) })
        val id = c.distinctId
        c.identify("u1", mapOf("a" to 1))
        assertEquals(id, c.distinctId)
        assertNull(c.alias("z"))
        assertNull(c.setPersonProperties(mapOf("a" to 1)))
        val e = c.capture("a", set = mapOf("a" to 1))!!
        assertFalse(e.containsKey("\$set"))
        assertEquals(false, props(e)["\$process_person_profile"])
        assertEquals(1, c.queue.size)
    }
}

class CoreOptionsTest {
    @Test fun bootstrapAndIdentityOptions() {
        val c = core({ it.copy(bootstrap = Bootstrap("boot-1", isIdentified = true)) })
        assertEquals("boot-1", c.distinctId)
        assertTrue(c.identity.identified)
        c.reset()
        assertEquals(c.anonymousId, c.distinctId)

        val reuse = core({ it.copy(reuseAnonymousId = true, anonymousIdProvider = { "anon-fixed" }) })
        assertEquals("anon-fixed", reuse.getAnonymousIdForTest())
        reuse.identify("u")
        reuse.reset()
        assertEquals("anon-fixed", reuse.distinctId)
    }

    private fun Core.getAnonymousIdForTest() = anonymousId

    @Test fun disabledGeoipOptedOutDebugAndSessionExpiry() {
        val off = core({ it.copy(disabled = true) })
        assertNull(off.capture("a"))
        val c = core({ it.copy(disableGeoip = true, sessionIdleMs = 10) })
        assertEquals(true, props(c.capture("a"))["\$geoip_disable"])
        val s1 = props(c.capture("b"))["\$session_id"]
        sleepMs(1)
        // the injected clock advances 1 ms per read, so a 10 ms idle window has passed after a few reads
        repeat(20) { c.clock.nowMs() }
        assertTrue(props(c.capture("c"))["\$session_id"] != s1)
        assertFalse(c.isOptedOut)
        c.optOut()
        assertTrue(c.isOptedOut)
        val logs = mutableListOf<String>()
        val d = core({ it.copy(log = { m -> logs.add(m) }) })
        d.debugLog("hidden")
        d.debug = true
        d.debugLog("shown")
        assertEquals(listOf("shown"), logs)
    }

    @Test fun setPersonPropertiesAndPerCaptureSet() {
        val c = core()
        val e = c.setPersonProperties(mapOf("a" to 1), mapOf("b" to 2))!!
        assertEquals("\$set", e["event"])
        assertEquals(mapOf("a" to 1), e["\$set"])
        assertEquals(mapOf("b" to 2), e["\$set_once"])
    }

    @Test fun deepLinkEvent() {
        val e = core().deepLink("myapp://item/3?ref=a%20b&x=1#frag", "android-app://com.x")!!
        assertEquals("\$deep_link_opened", e["event"])
        assertEquals(mapOf("ref" to "a b", "x" to "1"), props(e)["query"])
        assertEquals("android-app://com.x", props(e)["referrer"])
    }

    @Test fun queueBatchSizeRetryDelayAndWifiGate() {
        val http = FakeHttp()
        var wifi = false
        val c = core(http = http, queue = QueueConfig("", "k", flushAt = 100, flushIntervalMs = 60_000, maxBatchSize = 2, random = { 0.0 }, networkAllowed = { wifi }))
        repeat(5) { c.capture("e$it") }
        c.queue.flush()
        assertTrue(http.bodies.isEmpty(), "cellular must not flush in wifi-only mode")
        assertEquals(5, c.queue.size)
        wifi = true
        c.queue.flush()
        assertEquals(3, http.bodies.size) // 2 + 2 + 1
        assertEquals(0, c.queue.size)
    }

    @Test fun encryptedQueueAtRest() {
        val cipher = object : QueueCipher {
            override fun encrypt(plain: String) = "enc:" + plain.reversed()
            override fun decrypt(cipher: String) = if (cipher.startsWith("enc:")) cipher.removePrefix("enc:").reversed() else null
        }
        val store = MemoryStore()
        val c = core({ it.copy(queueCipher = cipher) }, store = store)
        c.capture("secret-event")
        val raw = store.get("lc_queue")!!
        assertTrue(raw.startsWith("enc:") && !raw.contains("secret-event"))
        assertEquals(1, core({ it.copy(queueCipher = cipher) }, store = store).queue.size)
        store.set("lc_queue", "garbage")
        assertEquals(0, core({ it.copy(queueCipher = cipher) }, store = store).queue.size)
    }

    @Test fun tracingHeadersHostMatchingAndConsent() {
        val c = core({ it.copy(tracingHeaders = listOf("api.example.com", ".corp.io", "ingest.test")) })
        val h = c.tracing.headersFor("https://api.example.com/v1/x")
        assertEquals(c.distinctId, h["X-Lucida-Distinct-Id"])
        assertNotNull(h["X-Lucida-Session-Id"])
        assertTrue(c.tracing.headersFor("https://a.corp.io/x").isNotEmpty())
        assertTrue(c.tracing.headersFor("https://corp.io/x").isEmpty())
        assertTrue(c.tracing.headersFor("https://other.com/x").isEmpty())
        assertTrue(c.tracing.headersFor("https://ingest.test/v1/batch").isEmpty(), "never to the Lucida host")
        c.optOut()
        assertTrue(c.tracing.headersFor("https://api.example.com/v1/x").isEmpty())
    }
}

class FlagOptionsTest {
    private fun setup(opts: FlagOptions = FlagOptions(), boot: Bootstrap? = null): Triple<Core, Flags, FakeHttp> {
        val http = FakeHttp().apply { response = """{"flags":{"f":true}}""" }
        val c = core({ it.copy(bootstrap = boot) }, http = http)
        return Triple(c, Flags(c, options = opts), http)
    }

    @Test fun exposureToggleAndBootstrapValues() {
        val (c, f) = setup(FlagOptions(sendFeatureFlagEvent = false), Bootstrap(flags = mapOf("b" to "v1"), payloads = mapOf("b" to mapOf("k" to 1))))
        val names = mutableListOf<String>()
        c.onCaptured { names.add(it["event"] as String) }
        assertEquals("v1", f.get("b"))
        assertEquals(mapOf("k" to 1), f.payload("b"))
        assertTrue(names.isEmpty())
        val (c2, f2) = setup()
        val n2 = mutableListOf<String>()
        c2.onCaptured { n2.add(it["event"] as String) }
        f2.get("x")
        assertEquals(listOf("\$feature_flag_called"), n2)
    }

    @Test fun evaluationPropertiesContextsAndCallback() {
        val (_, f, http) = setup(FlagOptions(evaluationContexts = listOf("prod")))
        var seen: Obj? = null
        f.onFeatureFlags { seen = it }
        f.setPersonPropertiesForFlags(mapOf("plan" to "pro"), reload = false)
        assertTrue(http.bodies.isEmpty())
        f.setGroupPropertiesForFlags("company", mapOf("tier" to 2))
        val body = Json.decodeObj(http.bodies.last())
        assertEquals(mapOf("plan" to "pro"), body["person_properties"])
        assertEquals(mapOf("company" to mapOf("tier" to 2L)), body["group_properties"])
        assertEquals(listOf("prod"), body["evaluation_contexts"])
        assertEquals(true, seen!!["f"])
        f.resetPersonPropertiesForFlags()
        assertEquals(emptyMap<String, Any?>(), Json.decodeObj(http.bodies.last())["person_properties"])
    }
}

class ReplayOptionsTest {
    private val tree = ViewNode(ViewKind.INPUT, 0, 0, 10, 10, text = "secret", maskInput = false, children = emptyList())

    @Test fun inputMaskToggleAndImageMask() {
        fun attrs(n: ViewNode): Obj {
            val s = Wireframe.snapshot(n, "S", 10, 10, 0)

            @Suppress("UNCHECKED_CAST")
            val doc = ((s.events[1]["data"] as Obj)["node"] as Obj)

            @Suppress("UNCHECKED_CAST")
            val html = (doc["childNodes"] as List<Obj>)[1]

            @Suppress("UNCHECKED_CAST")
            val body = (html["childNodes"] as List<Obj>)[1]

            @Suppress("UNCHECKED_CAST")
            return ((body["childNodes"] as List<Obj>)[0]["attributes"] as Obj)
        }
        assertEquals("secret", attrs(tree)["value"])
        assertEquals("******", attrs(tree.copy(maskInput = true))["value"])
        val img = ViewNode(ViewKind.IMAGE, 0, 0, 5, 5, maskImage = true)
        assertTrue(attrs(img).containsKey("data-lc-mask"))
        assertFalse(attrs(img.copy(maskImage = false)).containsKey("data-lc-mask"))
    }

    private fun recorder(o: ReplayOptions): Pair<ReplayRecorder, Core> {
        val c = core()
        c.setConsent(mapOf("analytics" to true, "replay" to true))
        return ReplayRecorder(c, o) to c
    }

    private val node = ViewNode(ViewKind.CONTAINER, 0, 0, 10, 10)

    @Test fun startStopIsActiveAndSampling() {
        val (r, _) = recorder(ReplayOptions())
        assertTrue(r.isActive)
        r.stop()
        assertFalse(r.isActive)
        r.screen(node, "A", 10, 10)
        assertTrue(r.needsSnapshot)
        r.start()
        assertTrue(r.isActive)
        assertFalse(recorder(ReplayOptions(sampleRate = 0.0)).first.isActive)
        assertTrue(recorder(ReplayOptions(sampleRate = 1.0)).first.isActive)
    }

    @Test fun throttleSkipsRapidScreens() {
        val (r, _) = recorder(ReplayOptions(throttleMs = 10_000))
        r.screen(node, "A", 10, 10)
        assertFalse(r.needsSnapshot)
        r.screen(node, "B", 10, 10)
        r.flush() // nothing to ship but must not throw; the second capture was throttled
    }
}
