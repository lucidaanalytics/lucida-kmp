package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplaySpoolTest {
    private val tree = ViewNode(ViewKind.CONTAINER, 0, 0, 100, 200, children = listOf(ViewNode(ViewKind.TEXT, 10, 10, 50, 20, text = "Hello")))
    private val other = ViewNode(ViewKind.CONTAINER, 0, 0, 100, 200, children = listOf(ViewNode(ViewKind.TEXT, 10, 10, 50, 20, text = "Changed")))

    private class Rev : QueueCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(cipher: String) = if (cipher.startsWith("enc:")) cipher.substring(4).reversed() else null
    }

    private fun spooled(store: MemoryStore) = store.data.keys.filter { it.startsWith("lc_replay") }
    private fun posts(http: FakeHttp) = http.bodies.filter { it.contains("session_id") && !it.contains("\"batch\"") }.map { Json.decodeObj(it) }

    @Test fun offlineChunksSpillSurviveARestartAndUploadInOrder() {
        val store = MemoryStore()
        val h1 = Harness(http = FakeHttp().apply { statuses = mutableListOf(0) }, store = store)
        val r1 = ReplayRecorder(h1.core, ReplayOptions(useRemoteConfig = false))
        r1.screen(tree, "A", 100, 200)
        r1.flush()
        assertTrue(spooled(store).any { it.startsWith("lc_replay.") }, "the chunk is on disk")

        val h2 = Harness(http = FakeHttp(), store = store) // new process, same storage and session
        val r2 = ReplayRecorder(h2.core, ReplayOptions(useRemoteConfig = false))
        r2.screen(other, "B", 100, 200)
        r2.flush()
        assertEquals(listOf(0, 1), posts(h2.http).map { (it["seq"] as Number).toInt() })
        assertTrue(spooled(store).isEmpty(), "uploaded chunks leave the disk")
    }

    @Test fun rejectedChunkIsDroppedNotRetriedForever() {
        val store = MemoryStore()
        val h = Harness(http = FakeHttp().apply { statuses = mutableListOf(400) }, store = store)
        val r = ReplayRecorder(h.core, ReplayOptions(useRemoteConfig = false))
        r.screen(tree, "A", 100, 200)
        r.flush()
        assertTrue(spooled(store).isEmpty())
    }

    @Test fun spilledChunksAreEncryptedAndClearErasesThem() {
        val store = MemoryStore()
        val http = FakeHttp().apply { statuses = mutableListOf(0) }
        val core = Core(CoreConfig(apiKey = "lc_pk_x", apiHost = "https://ingest.test/", http = http, store = store, queueCipher = Rev()))
        val r = ReplayRecorder(core, ReplayOptions(useRemoteConfig = false))
        r.screen(tree, "A", 100, 200)
        r.flush()
        val body = store.data.entries.first { it.key.startsWith("lc_replay.") }.value
        assertTrue(body.startsWith("enc:") && !body.contains("session_id"))
        r.clear()
        assertTrue(spooled(store).isEmpty())
    }

    @Test fun moreThanTwentyChunksAreKeptWhileUnderBudget() {
        val s = ReplaySpool(MemoryStore(), 1_000_000)
        repeat(50) { s.add("a", it, false, "{\"x\":$it}") }
        assertEquals(50, s.entries().size)
    }

    @Test fun overBudgetDropsTheOldestWholeSessionAndReportsIt() {
        val store = MemoryStore()
        val dropped = ArrayList<String>()
        val s = ReplaySpool(store, 100) { sid, n, b -> dropped.add("$sid:$n:$b") }
        val body = "x".repeat(40)
        s.add("a", 0, false, body)
        s.add("a", 1, false, body)
        s.add("b", 0, false, body) // 120 > 100: all of a goes, not one chunk
        assertEquals(listOf("a:2:80"), dropped)
        assertEquals(listOf("b"), s.entries().map { it.session })
        assertEquals(1, store.data.keys.count { it.startsWith("lc_replay.") })
    }

    @Test fun reloadedSpoolResumesSeqAndIgnoresACorruptIndex() {
        val store = MemoryStore()
        ReplaySpool(store).apply { add("a", 0, true, "{}"); add("a", 1, true, "{}") }
        val s = ReplaySpool(store)
        assertEquals(2, s.entries().size)
        assertEquals(2, s.nextSeq("a"))
        assertEquals(0, s.nextSeq("z"))
        store.set("lc_replay", "garbage")
        assertEquals(0, ReplaySpool(store).entries().size)
    }
}
