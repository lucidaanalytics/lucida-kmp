package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ReplayBridgeTest {
    private val tree = ViewNode(
        ViewKind.CONTAINER, 0, 0, 360, 640,
        children = listOf(
            ViewNode(ViewKind.TEXT, 0, 0, 100, 20, text = "Hello Alice"),
            ViewNode(ViewKind.INPUT, 0, 30, 100, 20, text = "secret@pw"),
        ),
    )

    @Test fun capturesMetaAndFullSnapshotAsJsonWithMasking() {
        val json = ReplayBridge.capture(tree, "Checkout", 360, 640, 1000L)
        assertContains(json, "\"width\":360")
        assertContains(json, "app://Checkout")
        assertContains(json, "\"type\":4")
        assertContains(json, "\"type\":2")
        assertFalse(json.contains("Hello Alice"))
        assertFalse(json.contains("secret@pw"))
    }
}
