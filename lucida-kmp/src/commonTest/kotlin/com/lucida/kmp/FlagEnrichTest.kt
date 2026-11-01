package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Flag values on events (LC-FLG-28). */
class FlagEnrichTest {
    private fun flag(key: String, pct: Int): Obj = mapOf(
        "key" to key, "type" to "boolean", "active" to true, "salt" to "s", "aggregation" to mapOf("kind" to "person"),
        "conditions" to listOf(mapOf("rollout_pct" to pct)),
    )

    private fun core(): Core {
        var now = 1_760_000_000_000L
        return Core(
            CoreConfig(
                "k", "https://ingest.test", FakeHttp(), MemoryStore(), clock = { now++ },
                queue = { QueueConfig(it, "k", flushAt = 100, flushIntervalMs = 60_000, random = { 0.0 }) },
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun props(e: Obj?) = e!!["properties"] as Obj

    @Test fun eventsCarryFlagValuesCallerAndSnapshotExcepted() {
        val c = core()
        Flags(c, listOf(flag("on", 100), flag("off", 0))).reload()
        val p = props(c.capture("clicked", mapOf("\$feature/on" to "mine")))
        assertEquals("mine", p["\$feature/on"])
        assertEquals(false, p["\$feature/off"])
        assertEquals(listOf("on"), p["\$active_feature_flags"])
        assertFalse(props(c.capture("\$snapshot")).containsKey("\$active_feature_flags"))
    }

    @Test fun sendFeatureFlagsOffLeavesEventsAlone() {
        val c = core()
        Flags(c, listOf(flag("on", 100)), options = FlagOptions(sendFeatureFlags = false)).reload()
        assertFalse(props(c.capture("clicked")).containsKey("\$active_feature_flags"))
    }

    @Test fun emptyValuesAddNothing() {
        assertTrue(flagEventProps(emptyMap()).isEmpty())
    }
}
