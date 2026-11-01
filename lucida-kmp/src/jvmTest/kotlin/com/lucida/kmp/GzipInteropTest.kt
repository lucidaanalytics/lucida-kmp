package com.lucida.kmp

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Apple transport's hand-built gzip must be readable by a real inflater (and the JVM's own Instant must agree with ours). */
class GzipInteropTest {
    @Test fun javaCanReadGzipStored() {
        val big = ByteArray(200_000) { (it % 251).toByte() } // spans several 65535-byte stored blocks
        assertEquals(big.toList(), GZIPInputStream(ByteArrayInputStream(gzipStored(big))).readBytes().toList())
        assertEquals("", GZIPInputStream(ByteArrayInputStream(gzipStored(ByteArray(0)))).readBytes().decodeToString())
    }

    @Test fun instantAgreesWithJavaTime() {
        for (ms in listOf(0L, 1L, 999L, 1_760_000_000_123L, -86_400_001L, 4_102_444_800_000L, 951_782_400_000L)) {
            assertEquals(java.time.Instant.ofEpochMilli(ms).toString(), Instant.ofEpochMilli(ms).toString(), "ms=$ms")
        }
        assertEquals(java.time.OffsetDateTime.parse("2025-10-09T10:53:20.250+02:00").toInstant().toEpochMilli(), Instant.parse("2025-10-09T10:53:20.250+02:00")!!.toEpochMilli())
    }
}
