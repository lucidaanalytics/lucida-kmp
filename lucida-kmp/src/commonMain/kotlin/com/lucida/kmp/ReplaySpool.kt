package com.lucida.kmp

/** One spilled replay chunk: the wire body minus the api key. */
data class SpoolEntry(
    val id: Int,
    val session: String,
    val seq: Int,
    /** Waiting for the minimum session duration (dropped if the session ends first); false = waiting for upload. */
    val held: Boolean,
    val bytes: Int,
)

/**
 * Durable, ordered spill for replay chunks (min duration, offline, upload backoff). `lc_replay` is the index, `lc_replay.<id>`
 * holds one chunk body. Hand it a store wrapped in `EncryptedStore(..., prefixes = ["lc_replay."])` to encrypt at rest. Over
 * [budgetBytes] the oldest whole session is dropped (never single chunks, so a kept session has no hole) and [onDrop] reports it.
 * Locks: `lock` guards the entry list (a leaf); `ioLock` serialises store writes and is never taken by readers.
 */
class ReplaySpool(
    private val store: KeyValueStore,
    private val budgetBytes: Long = 10L * 1024 * 1024,
    private val onDrop: (session: String, chunks: Int, bytes: Long) -> Unit = { _, _, _ -> },
) {
    private val lock = Lock()
    private val ioLock = Lock()
    private val items = ArrayList<SpoolEntry>()
    private var nextId = 0

    init {
        runCatching {
            store.get(INDEX)?.let { raw ->
                for (m in Json.decode(raw) as List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val o = m as Obj
                    val e = SpoolEntry((o["id"] as Number).toInt(), o["sid"] as String, (o["seq"] as Number).toInt(), o["held"] as Boolean, (o["n"] as Number).toInt())
                    if (store.get(key(e)) != null) items.add(e)
                    if (e.id >= nextId) nextId = e.id + 1
                }
            }
        }.onFailure { items.clear() } // a corrupt index starts empty
    }

    private fun key(e: SpoolEntry) = "$INDEX.${e.id}"

    fun entries(): List<SpoolEntry> = lock.withLock { ArrayList(items) }
    val isEmpty: Boolean get() = lock.withLock { items.isEmpty() }
    val totalBytes: Long get() = lock.withLock { items.sumOf { it.bytes.toLong() } }

    private fun saveIndex() {
        val json = lock.withLock {
            if (items.isEmpty()) null
            else Json.encode(items.map { mapOf("id" to it.id, "sid" to it.session, "seq" to it.seq, "held" to it.held, "n" to it.bytes) })
        }
        if (json == null) store.remove(INDEX) else store.set(INDEX, json)
    }

    fun add(session: String, seq: Int, held: Boolean, body: String) {
        val dropped = ArrayList<Triple<String, Int, Long>>()
        ioLock.withLock {
            val e = lock.withLock { SpoolEntry(nextId++, session, seq, held, body.encodeToByteArray().size) }
            store.set(key(e), body)
            val gone = ArrayList<SpoolEntry>()
            lock.withLock {
                items.add(e)
                while (items.isNotEmpty() && items.sumOf { it.bytes.toLong() } > budgetBytes) {
                    val first = items.first().session
                    val ofSession = items.filter { it.session == first }
                    items.removeAll(ofSession.toSet())
                    gone.addAll(ofSession)
                    dropped.add(Triple(first, ofSession.size, ofSession.sumOf { it.bytes.toLong() }))
                }
            }
            for (g in gone) store.remove(key(g))
            saveIndex()
        }
        for ((s, c, b) in dropped) onDrop(s, c, b) // user callback: outside every lock
    }

    fun read(e: SpoolEntry): String? = store.get(key(e))

    fun remove(e: SpoolEntry) {
        ioLock.withLock {
            lock.withLock { items.removeAll { it.id == e.id } }
            store.remove(key(e))
            saveIndex()
        }
    }

    /** Held chunks of every session except [exceptSession]; returns them. */
    fun removeHeld(exceptSession: String? = null): List<SpoolEntry> = ioLock.withLock {
        val gone = lock.withLock {
            items.filter { it.held && it.session != exceptSession }.also { g -> items.removeAll(g.toSet()) }
        }
        for (g in gone) store.remove(key(g))
        if (gone.isNotEmpty()) saveIndex()
        gone
    }

    /** The session reached its minimum duration: its held chunks become plain pending uploads. */
    fun release(session: String) {
        ioLock.withLock {
            lock.withLock { for (i in items.indices) if (items[i].held && items[i].session == session) items[i] = items[i].copy(held = false) }
            saveIndex()
        }
    }

    fun nextSeq(session: String): Int = lock.withLock { (items.filter { it.session == session }.maxOfOrNull { it.seq } ?: -1) + 1 }

    fun clear() {
        ioLock.withLock {
            val gone = lock.withLock { ArrayList(items).also { items.clear() } }
            for (g in gone) store.remove(key(g))
            saveIndex()
        }
    }

    private companion object {
        const val INDEX = "lc_replay"
    }
}
