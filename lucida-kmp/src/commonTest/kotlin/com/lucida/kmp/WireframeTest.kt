package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class WireframeTest {
    private val tree = ViewNode(
        ViewKind.CONTAINER, 0, 0, 360, 640, name = "root",
        children = listOf(
            ViewNode(ViewKind.TEXT, 10, 10, 200, 20, text = "Hello Alice", name = "title"),
            ViewNode(ViewKind.INPUT, 10, 40, 200, 30, text = "secret@pw", name = "email"),
            ViewNode(ViewKind.BUTTON, 10, 80, 100, 40, text = "Buy now", name = "buy", maskText = false),
            ViewNode(ViewKind.CONTAINER, 0, 200, 360, 100, block = true, name = "card", children = listOf(ViewNode(ViewKind.TEXT, 0, 0, 50, 10, text = "4111 1111"))),
        ),
    )

    private fun dump(v: Any?): String = Json.encode(v)

    @Test fun shapeMatchesRrwebAsReadByLcReplay() {
        val s = Wireframe.snapshot(tree, "Checkout", 360, 640, 1000L)
        val (meta, full) = s.events
        assertEquals(4, meta["type"])
        assertEquals("app://Checkout", (meta["data"] as Obj)["href"])
        assertEquals(360, (meta["data"] as Obj)["width"])
        assertEquals(2, full["type"])
        val doc = (full["data"] as Obj)["node"] as Obj
        assertEquals(0, doc["type"])
        assertEquals(1, doc["id"])
        val html = (doc["childNodes"] as List<Obj>)[1]
        assertEquals("html", html["tagName"])
        val body = (html["childNodes"] as List<Obj>)[1]
        assertEquals(Snapshot.BODY_ID, body["id"])
        // node ids are unique
        val ids = Regex("\"id\":(\\d+)").findAll(dump(doc)).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun textMaskedByDefaultInputsAlwaysBlockedEmpty() {
        val json = dump(Wireframe.snapshot(tree, "Checkout", 360, 640, 1L).events)
        assertFalse(json.contains("Alice"), "default text must be starred")
        assertTrue(json.contains("***** *****"))
        assertFalse(json.contains("secret@pw"), "input values are never serialised")
        assertTrue(json.contains("\"value\":\"*********\""))
        assertTrue(json.contains("Buy now"), "explicit opt-out unmasks plain text")
        assertFalse(json.contains("4111"), "blocked subtree is dropped")
        assertTrue(json.contains("\"data-lc-block\":\"\""))
    }

    @Test fun tapHitsDeepestNode() {
        val s = Wireframe.snapshot(tree, "Checkout", 360, 640, 1L)
        val tap = Wireframe.tap(s, 20.0, 90.0, 5L)
        assertEquals(3, tap["type"])
        val d = tap["data"] as Obj
        assertEquals(2, d["source"])
        assertEquals(2, d["type"])
        // the button is the 3rd child: ids root=6, title=7 (+text 8), input=9, button=10
        assertEquals(10, d["id"])
        assertEquals(Snapshot.BODY_ID, s.nodeAt(-5.0, -5.0))
    }

    @Test fun recorderChunksWithSeqAndRequiresReplayConsent() {
        val http = FakeHttp()
        val h = Harness(ConsentMode.OPT_IN, http = http)
        val rec = ReplayRecorder(h.core, chunkEvents = 2)
        rec.screen(tree, "A", 360, 640)
        assertTrue(rec.enabled.not())
        rec.flush()
        assertTrue(http.bodies.isEmpty(), "nothing recorded without consent")
        h.core.setConsent(mapOf("analytics" to true, "replay" to true))
        rec.screen(tree, "A", 360, 640) // 2 events -> sealed chunk seq 0
        rec.tap(20.0, 90.0)
        rec.flush()
        h.now += 31 * 60_000
        h.core.capture("rotate") // new session
        rec.screen(tree, "B", 360, 640)
        rec.flush()
        val chunks = http.bodies.filter { it.contains("\"events\"") && it.contains("session_id") && !it.contains("\"batch\"") }.map { Json.decodeObj(it) }
        assertEquals(listOf(0, 1, 0), chunks.map { (it["seq"] as Number).toInt() })
        assertEquals("lc_pk_x", chunks[0]["api_key"])
        assertTrue(http.calls.any { it == "POST https://ingest.test/v1/replay?compression=gzip" })
        assertTrue(http.gzips.last())
    }
}
