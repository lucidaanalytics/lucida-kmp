package com.lucida.kmp

import kotlin.math.abs
import kotlin.math.floor

/** Minimal JSON codec (common code: no org.json / Gson). Numbers decode to Long when integral, else Double. */
object Json {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    fun decode(s: String): Any? = Parser(s).run {
        val v = value()
        ws()
        require(i == s.length) { "trailing JSON at $i" }
        v
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeObj(s: String): Obj = decode(s) as Obj

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v)
            is Double -> sb.append(if (v.isFinite()) (if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()) else "null")
            is Float -> write(sb, v.toDouble())
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u${hex4(c.code)}")
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("bad JSON at $i")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad JSON at $i" }
            i += w.length
            return v
        }

        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (s[i] != '"') {
                var c = s[i++]
                if (c == '\\') {
                    c = s[i++]
                    when (c) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(c)
                    }
                } else {
                    sb.append(c)
                }
            }
            i++
            return sb.toString()
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') return out.also { i++ }
            while (true) {
                out.add(value())
                ws()
                if (s[i++] == ']') return out
            }
        }

        fun obj(): Obj {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') return out.also { i++ }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i++] == ':') { "expected ':' at $i" }
                out[k] = value()
                ws()
                if (s[i++] == '}') return out
            }
        }
    }
}
