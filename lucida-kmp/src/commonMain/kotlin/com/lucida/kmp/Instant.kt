package com.lucida.kmp

import kotlin.math.floor

/** Minimal UTC instant (no kotlinx-datetime dependency): ISO-8601 output, RFC 3339 / date input, comparisons. */
class Instant private constructor(val epochSecond: Long, val nano: Int) : Comparable<Instant> {
    fun minusSeconds(s: Long) = Instant(epochSecond - s, nano)
    fun isBefore(o: Instant) = compareTo(o) < 0
    fun isAfter(o: Instant) = compareTo(o) > 0
    fun toEpochMilli(): Long = epochSecond * 1000 + nano / 1_000_000

    override fun compareTo(other: Instant): Int =
        if (epochSecond != other.epochSecond) epochSecond.compareTo(other.epochSecond) else nano.compareTo(other.nano)

    override fun equals(other: Any?) = other is Instant && compareTo(other) == 0
    override fun hashCode() = 31 * epochSecond.hashCode() + nano

    /** `2026-11-01T12:00:00Z`, with milliseconds (`.123`) when non-zero, like java.time.Instant. */
    override fun toString(): String {
        val days = floorDiv(epochSecond, 86_400L)
        val sod = (epochSecond - days * 86_400L).toInt()
        val (y, m, d) = civilFromDays(days)
        val frac = if (nano == 0) "" else "." + nano.toString().padStart(9, '0').trimEnd('0').padEnd(3, '0')
        return "${pad(y, 4)}-${pad(m, 2)}-${pad(d, 2)}T${pad(sod / 3600, 2)}:${pad(sod / 60 % 60, 2)}:${pad(sod % 60, 2)}${frac}Z"
    }

    companion object {
        fun ofEpochMilli(ms: Long): Instant {
            val s = floorDiv(ms, 1000L)
            return Instant(s, ((ms - s * 1000) * 1_000_000).toInt())
        }

        fun ofEpochSecond(seconds: Long, nanoAdjustment: Long = 0): Instant {
            val s = seconds + floorDiv(nanoAdjustment, 1_000_000_000L)
            return Instant(s, (nanoAdjustment - floorDiv(nanoAdjustment, 1_000_000_000L) * 1_000_000_000L).toInt())
        }

        fun now(): Instant = ofEpochMilli(currentTimeMillis())

        /** Seconds since the epoch as a JSON number (fractions kept). */
        fun ofEpochSeconds(v: Double): Instant {
            val f = floor(v)
            return ofEpochSecond(f.toLong(), ((v - f) * 1e9).toLong())
        }

        private val RFC3339 = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$")
        private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

        /** RFC 3339 timestamp with offset, or null when malformed or out of range. */
        fun parse(s: String): Instant? {
            val m = RFC3339.matchEntire(s) ?: return null
            val g = m.groupValues
            val days = daysOrNull(g[1].toInt(), g[2].toInt(), g[3].toInt()) ?: return null
            val h = g[4].toInt()
            val mi = g[5].toInt()
            val sec = g[6].toInt()
            if (h > 23 || mi > 59 || sec > 59) return null
            var off = 0
            if (g[8] != "Z") {
                val oh = g[8].substring(1, 3).toInt()
                val om = g[8].substring(4, 6).toInt()
                if (oh > 18 || om > 59) return null
                off = (oh * 3600 + om * 60) * (if (g[8][0] == '-') -1 else 1)
            }
            val nano = if (g[7].isEmpty()) 0 else g[7].padEnd(9, '0').toInt()
            return Instant(days * 86_400L + h * 3600 + mi * 60 + sec - off, nano)
        }

        /** `YYYY-MM-DD` as midnight UTC, or null. */
        fun parseDate(s: String): Instant? {
            val m = DATE.matchEntire(s) ?: return null
            val g = m.groupValues
            return Instant(daysOrNull(g[1].toInt(), g[2].toInt(), g[3].toInt())?.times(86_400L) ?: return null, 0)
        }

        private fun pad(v: Int, n: Int) = v.toString().padStart(n, '0')

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
        }

        private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

        private fun daysOrNull(y: Int, m: Int, d: Int): Long? {
            if (m !in 1..12 || d < 1) return null
            val dim = intArrayOf(31, if (leap(y)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
            if (d > dim) return null
            return daysFromCivil(y, m, d)
        }

        // Howard Hinnant's civil-calendar algorithms (public domain).
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = (if (m <= 2) y0 - 1 else y0).toLong()
            val era = floorDiv(y, 400L)
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
            val z = z0 + 719_468
            val era = floorDiv(z, 146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
            return Triple(y, m, d)
        }
    }
}
