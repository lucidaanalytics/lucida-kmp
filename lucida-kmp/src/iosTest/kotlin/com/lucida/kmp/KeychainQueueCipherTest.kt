package com.lucida.kmp

import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The test host carries the keychain entitlement (`ios-test.entitlements`, linked in by build.gradle.kts), so the real
 * Keychain is used and an unavailable one FAILS the test. The only way out is the explicit
 * `LUCIDA_SKIP_KEYCHAIN_TEST=1` environment variable (README "iOS keychain test").
 */
class KeychainQueueCipherTest {
    private val skip = (NSProcessInfo.processInfo.environment["LUCIDA_SKIP_KEYCHAIN_TEST"] as? String) == "1"

    @Test fun roundTripsAndRejectsTampering() {
        val c = KeychainQueueCipher("dev.lucida.queue.test")
        if (skip && !c.available) return println("LUCIDA_SKIP_KEYCHAIN_TEST=1: keychain unavailable, cipher round trip skipped")
        assertTrue(
            c.available,
            "Keychain refused the test host (missing keychain-access-groups entitlement?). Fix the runner, or set LUCIDA_SKIP_KEYCHAIN_TEST=1 to opt out explicitly.",
        )
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
