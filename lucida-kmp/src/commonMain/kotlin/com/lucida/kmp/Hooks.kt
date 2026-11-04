package com.lucida.kmp

import kotlin.random.Random

/** A `beforeSend` hook: return the (possibly amended) event, or null to drop it. */
typealias BeforeSend = (Obj) -> Obj?

/**
 * Ordered hook chain. Each hook sees the previous hook's output; null stops the chain and drops the event.
 * A hook that throws is logged and skipped: the event continues unchanged (as it was before that hook).
 */
class HookChain(private val hooks: List<BeforeSend>, private val log: (String) -> Unit) {
    fun run(event: Obj): Obj? {
        var cur = event
        for (h in hooks) {
            cur = try {
                h(cur) ?: return null
            } catch (t: Throwable) {
                log("beforeSend hook threw, keeping event: ${t.message}")
                cur
            }
        }
        return cur
    }
}

/** Keep roughly [rate] (0..1) of events and stamp survivors with `$sample_rate`. Compose it with your own filter hook. */
fun sampleHook(rate: Double, random: () -> Double = { Random.nextDouble() }): BeforeSend = { e ->
    if (rate >= 1.0) {
        e
    } else if (rate <= 0.0 || random() >= rate) {
        null
    } else {
        val p = e["properties"] as? Map<*, *> ?: emptyMap<String, Any?>()
        e + ("properties" to (p + ("\$sample_rate" to rate)))
    }
}

enum class PersonProfiles { ALWAYS, IDENTIFIED_ONLY, NEVER }

private const val IDENTIFIED_KEY = "lc_person_identified"
private val ALWAYS_PROCESSED = setOf("\$identify", "\$create_alias", "\$groupidentify")

/** `personProfiles` state: IDENTIFIED_ONLY flips to processing on the first identify/alias/group/person-property write. */
class PersonMode(private val store: KeyValueStore, val mode: PersonProfiles) {
    private val lock = Lock()
    private val ioLock = Lock()
    private var identified = store.get(IDENTIFIED_KEY) == "1"

    val blocksPersonWrites get() = mode == PersonProfiles.NEVER

    fun markIdentified() {
        val flipped = lock.withLock {
            val f = mode == PersonProfiles.IDENTIFIED_ONLY && !identified
            if (f) identified = true
            f
        }
        if (flipped) persist()
    }

    /** True when an event of this name should carry `$process_person_profile: false`. */
    fun personless(event: String): Boolean = lock.withLock {
        when (mode) {
            PersonProfiles.ALWAYS -> false
            PersonProfiles.NEVER -> event !in ALWAYS_PROCESSED
            PersonProfiles.IDENTIFIED_ONLY -> !identified && event !in ALWAYS_PROCESSED
        }
    }

    fun reset() {
        lock.withLock { identified = false }
        persist()
    }

    private fun persist() = ioLock.withLock {
        if (lock.withLock { identified }) store.set(IDENTIFIED_KEY, "1") else store.remove(IDENTIFIED_KEY)
    }
}
