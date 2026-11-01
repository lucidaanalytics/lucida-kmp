package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class KeychainQueueCipherTest {
    @Test fun roundTripsAndRejectsTampering() {
        val c = KeychainQueueCipher("dev.lucida.queue.test")
        // shortcut: a simulator test binary without a keychain entitlement may be refused; the cipher reports it instead of crashing.
        if (!c.available) return println("keychain unavailable on this runner: cipher round trip skipped")
        val plain = """[{"event":"e","properties":{"k":"v"}}]"""
        val a = c.encrypt(plain)
        assertNotEquals(a, c.encrypt(plain), "a fresh IV per message")
        assertEquals(plain, c.decrypt(a))
        assertEquals("", c.decrypt(c.encrypt("")))
        val flipped = a.replaceRange(20, 21, if (a[20] == 'A') "B" else "A")
        assertNull(c.decrypt(flipped), "a changed byte fails the HMAC")
        assertNull(c.decrypt("not base64"))
        assertEquals(plain, KeychainQueueCipher("dev.lucida.queue.test").decrypt(a), "the key persists in the keychain")
    }
}
