package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fallbacks + details (N-EXP-16). */
class FlagFallbackTest {
    private val served = """{"flags":{"live":{"value":"b","variant":"b","reason":"condition_match","condition_index":2}},"request_id":"r9"}"""

    private fun flags(h: Harness, fb: Obj) = Flags(h.core, options = FlagOptions(fallbacks = fb))

    @Test fun missingFlagServesFallbackWithoutEvent() {
        val h = Harness().apply { http.response = served }
        val f = flags(h, mapOf("gone" to true))
        f.reload()
        assertEquals(true, f.get("gone"))
        val d = f.getFeatureFlagDetails("gone")!!
        assertEquals("fallback", d.reason)
        assertEquals(true, d.value)
        h.core.queue.idle()
        assertTrue(h.http.bodies.none { it.contains("feature_flag_called") })
        assertNull(f.getFeatureFlagDetails("nofallback"))
        assertEquals(false, f.get("nofallback"))
    }

    @Test fun networkFailureServesFallback() {
        val h = Harness(http = FakeHttp().apply { statuses = mutableListOf(500) })
        val f = flags(h, mapOf("live" to "fb"))
        val seen = mutableListOf<FlagsStatus>()
        f.onFlagsStatus { seen.add(it) }
        f.reload()
        assertEquals("fb", f.get("live"))
        assertEquals("fallback", f.getFeatureFlagDetails("live")!!.reason)
        assertEquals(listOf(FlagsStatus.ERROR), seen)
    }

    @Test fun servedFlagKeepsRealReason() {
        val h = Harness().apply { http.response = served }
        val f = flags(h, mapOf("live" to "fb"))
        f.reload()
        val d = f.getFeatureFlagDetails("live")!!
        assertEquals("b", d.value)
        assertEquals("condition_match", d.reason)
        assertEquals(2, d.conditionIndex)
        assertEquals("r9", d.requestId)
    }
}
