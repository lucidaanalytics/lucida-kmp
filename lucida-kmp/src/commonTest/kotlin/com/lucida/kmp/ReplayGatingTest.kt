package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayGatingTest {
    private val tree = ViewNode(ViewKind.CONTAINER, 0, 0, 100, 200, children = listOf(ViewNode(ViewKind.TEXT, 10, 10, 50, 20, text = "Hello")))

    private fun harness(response: String = "{}"): Harness {
        val h = Harness(ConsentMode.OPT_OUT, http = FakeHttp().apply { this.response = response })
        h.core.setConsent(mapOf("analytics" to true, "replay" to true))
        return h
    }

    private fun replayPosts(h: Harness) = h.http.bodies.filter { it.contains("session_id") && it.contains("\"events\"") && !it.contains("\"batch\"") }.map { Json.decodeObj(it) }

    @Test fun screenListsDenyWins() {
        assertTrue(ReplayPolicy.screenAllowed("A", emptyList(), emptyList()))
        assertFalse(ReplayPolicy.screenAllowed("A", listOf("B"), emptyList()))
        assertFalse(ReplayPolicy.screenAllowed("A", listOf("A"), listOf("A")))
        assertTrue(ReplayPolicy.screenAllowed("A", emptyList(), listOf("B")))
    }

    @Test fun remoteCanOnlyTighten() {
        val r = RemoteReplayConfig(sampleRate = 0.5, minDurationMs = 3000)
        assertEquals(0.5, ReplayPolicy.sampleRate(1.0, r))
        assertEquals(0.1, ReplayPolicy.sampleRate(0.1, r))
        assertEquals(5000L, ReplayPolicy.minDurationMs(5000, r))
        assertEquals(3000L, ReplayPolicy.minDurationMs(0, r))
        assertEquals(0.0, ReplayPolicy.sampleRate(1.0, r.copy(sessionRecording = false)))
        assertEquals(1.0, ReplayPolicy.sampleRate(1.0, null))
        assertEquals(false, RemoteReplayConfig.parse("""{"session_recording":false}""")!!.sessionRecording)
    }

    @Test fun remoteKillSwitchDropsEverything() {
        val h = harness("""{"session_recording":false}""")
        val rec = ReplayRecorder(h.core)
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        assertTrue(replayPosts(h).isEmpty())
        assertTrue(h.http.calls.any { it == "GET https://ingest.test/v1/config?api_key=lc_pk_x" })
    }

    @Test fun remoteConfigOptOutKeepsLocal() {
        val h = harness("""{"session_recording":false}""")
        val rec = ReplayRecorder(h.core, ReplayOptions(useRemoteConfig = false))
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        assertEquals(1, replayPosts(h).size)
        assertTrue(h.http.calls.none { it.contains("/v1/config") })
    }

    @Test fun minDurationHoldsThenReleases() {
        val h = harness()
        val rec = ReplayRecorder(h.core, ReplayOptions(minDurationMs = 10_000))
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        assertTrue(replayPosts(h).isEmpty(), "held")
        h.now += 11_000
        rec.screen(tree, "B", 100, 200)
        rec.flush()
        assertEquals(listOf(0, 1), replayPosts(h).map { (it["seq"] as Number).toInt() })
    }

    @Test fun minDurationDropsWhenStoppedOrSessionRotates() {
        val h = harness()
        val rec = ReplayRecorder(h.core, ReplayOptions(minDurationMs = 10_000))
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        rec.stop()
        rec.start()
        h.now += 60_000
        rec.flush()
        assertTrue(replayPosts(h).isEmpty(), "stopped before the minimum: dropped")

        val h2 = harness()
        val r2 = ReplayRecorder(h2.core, ReplayOptions(minDurationMs = 10_000))
        r2.screen(tree, "A", 100, 200)
        r2.flush()
        h2.now += 31 * 60_000
        h2.core.capture("rotate")
        r2.screen(tree, "B", 100, 200)
        r2.flush()
        assertTrue(replayPosts(h2).isEmpty(), "first session never reached the minimum; second one is too short")
    }

    @Test fun remoteMinDurationApplies() {
        val h = harness("""{"session_recording":true,"replay_min_duration_ms":5000}""")
        val rec = ReplayRecorder(h.core)
        rec.screen(tree, "A", 100, 200)
        rec.flush()
        assertTrue(replayPosts(h).isEmpty())
    }

    @Test fun linkedFlagGatesRecording() {
        val h = harness()
        var on = false
        val rec = ReplayRecorder(h.core, ReplayOptions(linkedFlag = "f"), flagEnabled = { it == "f" && on })
        assertFalse(rec.isActive)
        rec.screen(tree, "A", 100, 200)
        assertTrue(rec.needsSnapshot)
        on = true
        assertTrue(rec.isActive)
        rec.screen(tree, "A", 100, 200)
        assertFalse(rec.needsSnapshot)
    }

    @Test fun deniedScreenPausesAndTapsAreDropped() {
        val h = harness()
        val rec = ReplayRecorder(h.core, ReplayOptions(screenDenyList = listOf("Checkout")))
        rec.screen(tree, "Home", 100, 200)
        assertFalse(rec.needsSnapshot)
        rec.screen(tree, "Checkout", 100, 200)
        assertTrue(rec.needsSnapshot, "fresh snapshot needed when leaving the denied screen")
        assertFalse(rec.shouldCapture("Checkout"))
        assertTrue(rec.shouldCapture("Home"))
        rec.tap(1.0, 1.0)
        rec.flush()
        val events = replayPosts(h).flatMap { @Suppress("UNCHECKED_CAST") (it["events"] as List<Obj>) }
        assertTrue(events.none { (it["type"] as? Number)?.toInt() == 3 })
    }

    @Test fun pauseResume() {
        val h = harness()
        val rec = ReplayRecorder(h.core)
        rec.pause()
        assertFalse(rec.isActive)
        rec.resume()
        assertTrue(rec.isActive)
    }

    @Test fun screenshotFrameBecomesImageNodeAndPlanMasksBeforeEncoding() {
        val h = harness()
        val rec = ReplayRecorder(h.core)
        rec.screen(tree, "A", 100, 200, ScreenshotFrame("/9gB", 100, 200))
        rec.flush()
        val json = h.http.bodies.first { it.contains("\"events\"") && it.contains("session_id") }
        assertContains(json, "data:image/jpeg;base64,/9gB")
        assertFalse(json.contains("Hello"))

        val root = ViewNode(
            ViewKind.CONTAINER, 0, 0, 100, 200,
            children = listOf(
                ViewNode(ViewKind.TEXT, 10, 10, 50, 20, text = "name"),
                ViewNode(ViewKind.INPUT, 10, 40, 50, 20, text = "pw", neverUnmask = true, maskInput = false),
                ViewNode(ViewKind.TEXT, 10, 70, 50, 20, text = "open", maskText = false),
                ViewNode(ViewKind.CONTAINER, 0, 100, 50, 50, block = true),
            ),
        )
        val plan = ScreenshotMasks.plan(root)
        assertEquals(listOf(MaskRect(10, 10, 50, 20)), plan.mask)
        assertEquals(listOf(MaskRect(10, 70, 50, 20)), plan.unmask)
        assertEquals(listOf(MaskRect(10, 40, 50, 20), MaskRect(0, 100, 50, 50)), plan.sensitive)
    }

    @Test fun composeStyleRegionsKeepMaskOverUnmaskAndPasswordsLocked() {
        val inner = ViewNode(ViewKind.TEXT, 5, 5, 10, 10, text = "secret", maskText = false)
        val free = ViewNode(ViewKind.TEXT, 80, 80, 10, 10, text = "free")
        val both = ViewNode(ViewKind.TEXT, 30, 30, 5, 5, text = "both", maskText = false)
        val pw = ViewNode(ViewKind.INPUT, 40, 40, 5, 5, text = "pw", neverUnmask = true)
        val root = ViewNode(ViewKind.CONTAINER, 0, 0, 100, 100, children = listOf(inner, free, both, pw))
        val regions = listOf(
            MarkerRegion(MarkerRole.MASK, MaskRect(0, 0, 40, 40)),
            MarkerRegion(MarkerRole.UNMASK, MaskRect(20, 20, 60, 60)),
        )
        val out = MarkerRegions.apply(root, regions).children
        assertTrue(out[0].maskText, "inside a mask region")
        assertTrue(out[1].maskText, "outside every region keeps the global mask")
        assertTrue(out[2].maskText, "mask wins over unmask")
        assertTrue(out[3].maskInput, "password never unmasked")
        val unmasked = MarkerRegions.apply(ViewNode(ViewKind.CONTAINER, 0, 0, 100, 100, children = listOf(free.copy(x = 45, y = 45))), regions).children[0]
        assertFalse(unmasked.maskText)
        val plan = ScreenshotMasks.plan(root, regions)
        assertTrue(MaskRect(0, 0, 40, 40) in plan.sensitive && MaskRect(20, 20, 60, 60) in plan.unmask)
    }

    @Test fun registryAddsAndRemoves() {
        MarkerRegistry.clear()
        val k = Any()
        MarkerRegistry.set(k, MarkerRegion(MarkerRole.MASK, MaskRect(0, 0, 1, 1)))
        assertEquals(1, MarkerRegistry.snapshot().size)
        MarkerRegistry.remove(k)
        assertTrue(MarkerRegistry.snapshot().isEmpty())
    }
}
