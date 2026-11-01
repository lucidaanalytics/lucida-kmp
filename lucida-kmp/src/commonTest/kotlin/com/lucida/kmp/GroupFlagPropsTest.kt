package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupFlagPropsTest {
    private val def = mapOf(
        "key" to "enterprise", "type" to "boolean", "active" to true, "salt" to "s",
        "aggregation" to mapOf("kind" to "person"),
        "conditions" to listOf(
            mapOf(
                "rollout_pct" to 100,
                "filters" to listOf(mapOf("type" to "group", "group_type" to "company", "key" to "tier", "op" to "gte", "value" to 2)),
            ),
        ),
    )

    @Test fun localEvalReadsGroupPropertiesWithoutAServerCall() {
        val h = Harness()
        val f = Flags(h.core, listOf(def))
        f.reload()
        assertFalse(f.isEnabled("enterprise"), "no group properties yet")
        f.setGroupPropertiesForFlags("company", mapOf("tier" to 3), reload = true)
        assertTrue(f.isEnabled("enterprise"))
        f.resetGroupPropertiesForFlags(reload = true)
        assertFalse(f.isEnabled("enterprise"))
        assertTrue(h.http.calls.isEmpty())
    }
}
