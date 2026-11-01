package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure-Kotlin replacements for java.* APIs: they must agree with the JVM versions on every target. */
class SupportTest {
    @Test fun sha256KnownVectors() {
        fun h(s: String) = sha256(s.encodeToByteArray()).joinToString("") { hex(it) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h("abc"))
        // 56 bytes: the padding spills into a second block
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", h("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
    }

    @Test fun instantFormatsAndParses() {
        assertEquals("1970-01-01T00:00:00Z", Instant.ofEpochMilli(0).toString())
        assertEquals("2025-10-09T08:53:20.123Z", Instant.ofEpochMilli(1_760_000_000_123).toString())
        assertEquals("1969-12-31T23:59:59.999Z", Instant.ofEpochMilli(-1).toString())
        assertEquals(1_760_000_000_000, Instant.parse("2025-10-09T08:53:20Z")!!.toEpochMilli())
        assertEquals(1_760_000_000_000, Instant.parse("2025-10-09T10:53:20+02:00")!!.toEpochMilli())
        assertEquals(1_760_000_000_500, Instant.parse("2025-10-09T08:53:20.5Z")!!.toEpochMilli())
        assertNull(Instant.parse("2025-02-30T00:00:00Z"))
        assertNull(Instant.parse("2025-10-09 08:53:20Z"))
        assertEquals(1_709_164_800_000, Instant.parseDate("2024-02-29")!!.toEpochMilli())
        assertNull(Instant.parseDate("2025-02-29"))
        assertTrue(Instant.parseDate("2024-01-01")!!.isBefore(Instant.parseDate("2024-01-02")!!))
    }

    @Test fun percentCodecRoundTrips() {
        assertEquals("a%20b%26c%2Fd", percentEncode("a b&c/d"))
        assertEquals("caf%C3%A9", percentEncode("café"))
        assertEquals("a b&c/d", percentDecode("a%20b%26c%2Fd"))
        assertEquals("café x", percentDecode("caf%C3%A9+x"))
        assertEquals("100%", percentDecode("100%"))
    }

    @Test fun uuidV7Shape() {
        val u = uuidv7(1_760_000_000_000)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(u), u)
        // first 48 bits are the millisecond timestamp
        assertEquals(1_760_000_000_000, (u.substring(0, 8) + u.substring(9, 13)).toLong(16))
    }

    @Test fun jsonRoundTripsAndEscapes() {
        val v = mapOf("a" to listOf(1, 2.5, "x\"\n\u0001"), "b" to null, "c" to true)
        assertEquals("""{"a":[1,2.5,"x\"\n\u0001"],"b":null,"c":true}""", Json.encode(v))
        assertEquals(mapOf("a" to listOf(1L, 2.5, "x\"\n\u0001"), "b" to null, "c" to true), Json.decodeObj(Json.encode(v)))
    }

    @Test fun gzipStoredIsAValidGzipContainer() {
        val z = gzipStored("hello".encodeToByteArray())
        assertEquals(0x1f, z[0].toInt())
        assertEquals(0x8b, z[1].toInt() and 0xff)
        // header 10 + stored block header 5 + data 5 + crc 4 + size 4; CRC-32("hello") = 3610a686
        assertEquals(10 + 5 + 5 + 8, z.size)
        assertContentEquals(byteArrayOf(0x86.toByte(), 0xa6.toByte(), 0x10, 0x36), z.copyOfRange(z.size - 8, z.size - 4))
        assertContentEquals(byteArrayOf(5, 0, 0, 0), z.copyOfRange(z.size - 4, z.size))
    }
}
