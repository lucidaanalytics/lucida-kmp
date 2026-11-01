package com.lucida.kmp

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 else c ushr 1 } // 0xEDB88320
    c
}

private fun crc32(data: ByteArray): Int {
    var c = -1
    for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c.inv()
}

private fun le32(out: MutableList<Byte>, v: Int) {
    for (i in 0 until 4) out.add((v ushr (8 * i)).toByte())
}

/**
 * A valid RFC 1952 gzip stream built from DEFLATE "stored" blocks (no compression). Used where no platform gzip is
 * bound (Apple targets); the server only needs a gzip container, and JVM/Android use the real GZIPOutputStream.
 * shortcut: no size reduction on Apple; bind zlib via cinterop if upload size ever matters.
 */
fun gzipStored(data: ByteArray): ByteArray {
    val out = ArrayList<Byte>(data.size + data.size / 65_535 * 5 + 32)
    out.addAll(listOf(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff).map { it.toByte() }) // magic, deflate, no flags, no mtime, unknown OS
    if (data.isEmpty()) out.addAll(listOf(1, 0, 0, 0xff, 0xff).map { it.toByte() })
    var off = 0
    while (off < data.size) {
        val n = minOf(65_535, data.size - off)
        out.add((if (off + n >= data.size) 1 else 0).toByte()) // BFINAL, BTYPE=00
        out.add(n.toByte()); out.add((n shr 8).toByte())
        out.add(n.inv().toByte()); out.add((n.inv() shr 8).toByte())
        for (i in off until off + n) out.add(data[i])
        off += n
    }
    le32(out, crc32(data))
    le32(out, data.size)
    return out.toByteArray()
}
