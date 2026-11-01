package com.lucida.kmp

private const val HEX = "0123456789abcdef"

internal fun hex(b: Byte): String {
    val v = b.toInt() and 0xff
    return "${HEX[v shr 4]}${HEX[v and 15]}"
}

/** Four lowercase hex digits (JSON `\u` escapes). */
internal fun hex4(v: Int): String = buildString { for (s in intArrayOf(12, 8, 4, 0)) append(HEX[(v shr s) and 15]) }

/** RFC 3986 percent-encoding of UTF-8 (unreserved characters stay). */
internal fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) append(c.toChar()) else append('%').append(hex(b).uppercase())
    }
}

/** Inverse of [percentEncode]; `+` is a space (query strings). Malformed escapes stay literal. */
internal fun percentDecode(s: String): String {
    val out = StringBuilder()
    val bytes = ArrayList<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
        bytes.clear()
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val code = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (code != null) {
            bytes.add(code.toByte())
            i += 3
        } else {
            flush()
            out.append(if (c == '+') ' ' else c)
            i++
        }
    }
    flush()
    return out.toString()
}
