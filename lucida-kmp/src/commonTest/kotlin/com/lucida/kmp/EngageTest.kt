package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
private fun props(e: Obj?) = e!!["properties"] as Obj

class AutocaptureTest {
    private val chain = listOf(
        Element(ViewKind.INPUT, "email", label = "me@x.io"),
        Element(ViewKind.BUTTON, "submit", label = "Send", props = mapOf("id" to "submit", "secret" to "s")),
        Element(ViewKind.CONTAINER, "root"),
    )

    @Test fun offByDefaultAndInputsNeverExposeText() {
        val t = Harness()
        assertNull(Autocapture(t.core, AutocaptureConfig()).tap(chain, 1.0, 1.0))
        val e = Autocapture(t.core, AutocaptureConfig(enabled = true, propsAllowList = setOf("id"))).tap(chain, 1.0, 1.0)

        @Suppress("UNCHECKED_CAST")
        val els = props(e)["\$elements"] as List<Obj>
        assertEquals(mapOf("tag_name" to "email"), els[0], "input label never leaves the device")
        assertEquals(mapOf("tag_name" to "submit", "text" to "Send", "attr__id" to "submit"), els[1])
        assertEquals("Send", props(e)["\$el_text"])
    }

    @Test fun noCaptureMarkerAndRageTap() {
        val t = Harness()
        val a = Autocapture(t.core, AutocaptureConfig(enabled = true))
        assertNull(a.tap(listOf(Element(ViewKind.BUTTON, "b", tag = "lc-no-capture")), 1.0, 1.0))
        repeat(3) { a.tap(chain, 10.0, 10.0) }
        t.core.flush()
        t.core.queue.idle()
        assertTrue(t.http.bodies.joinToString().contains("\$rageclick"))
    }
}

class GuidesTest {
    private class Recorder : GuidePresenter {
        val shown = ArrayList<String>()
        var next: ((GuideButton?) -> Unit)? = null
        override fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit) {
            shown.add("${guide.id}:$step")
            next = onAction
        }
        override fun open(url: String) = Unit
    }

    private val body = """{"guides":[
        {"id":"g1","name":"Tour","kind":"tour","conditions":{"url":"Cart","url_match":"exact"},"steps":[{"id":"s1","title":"a"},{"id":"s2","title":"b"}]},
        {"id":"g2","name":"Other","kind":"modal","conditions":{"css_selector":"#x"},"steps":[{"id":"s1"}]},
        {"id":"g3","name":"Later","kind":"modal","conditions":{"seen_guides":["g1"]},"steps":[{"id":"s1"}]}
    ]}"""

    @Test fun targetingStepsSeenAndUnknownKeysSkipped() {
        val t = Harness(http = FakeHttp().apply { response = body })
        val p = Recorder()
        val g = GuidesService(t.core, GuidesConfig(enabled = true, presenter = p)) { false }
        g.reload()
        assertTrue(t.http.calls.single().startsWith("GET https://ingest.test/v1/guides?api_key=lc_pk_x&distinct_id="))
        g.show("Home")
        assertEquals(emptyList(), p.shown, "url targets Cart only; g2 uses an unknown key; g3 needs g1 seen")
        g.show("Cart")
        assertEquals(listOf("g1:0"), p.shown)
        p.next!!(GuideButton("Next", "next"))
        p.next!!(GuideButton("Done", "next"))
        // g1 is now seen, so g3 becomes eligible right after the tour completes.
        assertEquals(listOf("g1:0", "g1:1", "g3:0"), p.shown)
        p.next!!(null)
        g.show("Cart")
        assertEquals(3, p.shown.size, "each guide shows once per device")
        t.core.flush()
        t.core.queue.idle()
        val sent = t.http.bodies.joinToString()
        listOf("\$guide_shown", "\$guide_step", "\$guide_completed", "\$guide_dismissed").forEach { assertTrue(sent.contains(it), it) }
    }

    @Test fun offByDefaultMakesNoRequest() {
        val t = Harness()
        GuidesService(t.core, GuidesConfig()) { false }.reload()
        assertTrue(t.http.calls.isEmpty())
    }
}

class ScreenshotFacadeTest {
    private fun lucida(http: FakeHttp) = Lucida(
        CoreConfig(
            apiKey = "lc_pk_x", apiHost = "https://ingest.test/", http = http, store = MemoryStore(), clock = { 1_760_000_000_000L },
            queue = { QueueConfig(it, "lc_pk_x", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
        ),
        appVersion = "1.0",
        replayOptions = ReplayOptions(useRemoteConfig = false),
    )

    private val tree = ViewNode(ViewKind.CONTAINER, 0, 0, 100, 100, children = listOf(ViewNode(ViewKind.TEXT, 1, 1, 10, 10, text = "secret")))

    @Test fun screenshotFrameReplacesWireframeAndNullFrameSkips() {
        val http = FakeHttp()
        val l = lucida(http)
        var planned: ScreenshotMaskPlan? = null
        l.captureScreen("A", 100, 100, shot = { null }) { tree }
        l.captureScreen("A", 100, 100, shot = { t -> planned = ScreenshotMasks.plan(t); ScreenshotFrame("SlBFRw==", 100, 100) }) { tree }
        l.flush()
        l.idle()
        val replay = http.bodies.single { it.contains("\"events\"") && !it.contains("\"batch\"") }
        assertTrue(replay.contains("lc-screenshot") && !replay.contains("secret"), replay)
        assertEquals(listOf(MaskRect(1, 1, 10, 10)), planned!!.mask, "text is masked in the plan before encoding")
    }
}
