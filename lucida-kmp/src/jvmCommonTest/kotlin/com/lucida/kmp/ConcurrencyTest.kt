package com.lucida.kmp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Per-component locks (see the locking note in Platform.kt): concurrent use loses nothing and reads never wait on a write. */
class ConcurrencyTest {
    private fun runAll(vararg bodies: () -> Unit) {
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val threads = bodies.map { b -> thread { try { b() } catch (t: Throwable) { failures.add(t) } } }
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "a thread hung: deadlock")
        assertTrue(failures.isEmpty(), "thread failed: ${failures.firstOrNull()}")
    }

    private fun core(http: FakeHttp, store: KeyValueStore) = Core(
        CoreConfig(
            apiKey = "lc_pk_x", apiHost = "https://ingest.test/", http = http, store = store,
            queue = { QueueConfig(it, "lc_pk_x", flushAt = 50, flushIntervalMs = 60_000, maxQueueSize = 100_000, random = { 0.0 }) },
        ),
    )

    @Test fun concurrentCaptureIdentifyRegisterAndFlagReadsLoseNothing() {
        val http = FakeHttp()
        val c = core(http, MemoryStore())
        val flags = Flags(c)
        val captured = IntArray(4)
        val workers = (0 until 4).map { w ->
            { repeat(200) { i -> if (c.capture("e$w", mapOf("i" to i)) != null) captured[w]++ } }
        }
        val identify = { repeat(40) { i -> c.identify("user-${i % 5}") } }
        val register = { repeat(100) { i -> c.register(mapOf("k" to i)); c.group("company", "c${i % 3}") } }
        val flagReads = { repeat(60) { flags.reload(); flags.get("a"); flags.all(); flags.payload("a") } }
        runAll(*(workers + identify + register + flagReads).toTypedArray())

        c.queue.idle()
        c.queue.flush()
        val uuids = HashSet<String>()
        var total = 0
        for (b in http.bodies.filter { it.contains("\"batch\"") }) {
            @Suppress("UNCHECKED_CAST")
            for (e in Json.decodeObj(b)["batch"] as List<Obj>) {
                total++
                uuids.add(e["uuid"] as String)
            }
        }
        assertEquals(total, uuids.size, "no event delivered twice by the in-process queue")
        assertTrue(uuids.size >= captured.sum(), "every captured event reached the queue: ${uuids.size} < ${captured.sum()}")
        assertEquals(0, c.queue.size)
    }

    @Test fun consentRevokeRacingCaptureNeverQueuesAfterTheClear() {
        val store = MemoryStore()
        val c = core(FakeHttp(), store)
        runAll(
            { repeat(300) { c.capture("race") } },
            { repeat(20) { c.setConsent(mapOf("analytics" to false)); c.setConsent(mapOf("analytics" to true)) } },
        )
        c.setConsent(mapOf("analytics" to false))
        c.queue.idle()
        assertEquals(0, c.queue.size, "after a revoke settles nothing stays queued")
        assertEquals(null, store.get("lc_queue"))
    }

    private class BlockingStore(val inner: MemoryStore = MemoryStore()) : KeyValueStore {
        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile var block = false
        override fun get(key: String) = inner.get(key)
        override fun set(key: String, value: String) {
            if (block) {
                inWrite.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            inner.set(key, value)
        }
        override fun remove(key: String) = inner.remove(key)
    }

    @Test fun stateReadsNeverWaitOnASlowWrite() {
        val store = BlockingStore()
        val c = core(FakeHttp(), store)
        val flags = Flags(c)
        store.block = true
        val writer = thread { c.register(mapOf("a" to 1)) } // blocks inside store.set
        assertTrue(store.inWrite.await(5, TimeUnit.SECONDS), "the writer reached the store")
        val reader = thread {
            c.distinctId
            c.anonymousId
            c.groups
            c.consent.has("analytics")
            c.queue.size
            flags.all()
            flags.payload("a")
        }
        reader.join(3_000)
        val readerFinished = !reader.isAlive
        store.release.countDown()
        writer.join(5_000)
        assertTrue(readerFinished, "reads were blocked behind a store write")
    }
}
