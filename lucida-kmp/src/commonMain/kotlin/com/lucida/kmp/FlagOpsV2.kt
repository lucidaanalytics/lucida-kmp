package com.lucida.kmp

/** Spec v2 operators (testdata/flags/README.md, "Spec v2"); port of go/internal/flageval/ops_v2.go. */
private val REL = Regex("^-?(\\d{1,6})([hdwmy])$")
private val RFC3339 = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$")
private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** Fixed unit lengths so every evaluator agrees: m = 30 days, y = 365 days. */
private fun relativeSeconds(s: String): Long? {
    val m = REL.matchEntire(s.trim()) ?: return null
    val unit = when (m.groupValues[2]) {
        "h" -> 3600L
        "d" -> 86_400L
        "w" -> 7 * 86_400L
        "m" -> 30 * 86_400L
        else -> 365 * 86_400L
    }
    return m.groupValues[1].toLong() * unit
}

private fun rfc(s: String): Instant? = if (!RFC3339.matches(s)) null else Instant.parse(s)

/** The evaluation clock: the context's `now` (RFC 3339) when it parses, else the wall clock. */
fun parseNow(v: Any?): Instant = (v as? String)?.let { rfc(it) } ?: Instant.now()

private fun instant(v: Any?, now: Instant, rel: Boolean): Instant? {
    if (v is Number) {
        return Instant.ofEpochSeconds(v.toDouble())
    }
    if (v !is String) return null
    val s = v.trim()
    if (rel) relativeSeconds(s)?.let { return now.minusSeconds(it) }
    rfc(s)?.let { return it }
    return if (DATE.matches(s)) Instant.parseDate(s) else null
}

private fun cmpParts(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x < y) -1 else 1
    }
    return 0
}

/** Half-open [lo, hi) of a tilde, caret or wildcard value. */
private fun semverRange(op: String, want: Any?): Pair<List<Int>, List<Int>>? {
    if (want !is String) return null
    val parts = want.trim().removePrefix("v").split('.')
    if (op == "semver_wildcard") {
        if (parts.last() !in setOf("*", "x", "X")) return null
        if (parts.size == 1) return listOf(0) to listOf(1 shl 30)
        val lo = parseVersion(parts.dropLast(1).joinToString(".")) ?: return null
        return lo to (lo.dropLast(1) + (lo.last() + 1))
    }
    val p = (parseVersion(want) ?: return null).let { it + List(maxOf(0, 3 - it.size)) { 0 } }
    val hi = when {
        op == "semver_tilde" && parts.size == 1 -> listOf(p[0] + 1, 0, 0)
        op == "semver_tilde" || (p[0] == 0 && p[1] > 0) -> listOf(p[0], p[1] + 1, 0)
        p[0] > 0 -> listOf(p[0] + 1, 0, 0)
        else -> listOf(0, 0, p[2] + 1)
    }
    return p to hi
}

/** Applies a v2 operator to a set property. Unknown operators never match. */
internal fun matchV2(op: String, prop: Any?, want: Any?, now: Instant): Boolean = when (op) {
    "icontains", "not_icontains", "starts_with", "not_starts_with", "ends_with", "not_ends_with" -> when {
        prop !is String || want !is String -> false
        op.endsWith("icontains") -> prop.lowercase().contains(want.lowercase()) == (op == "icontains")
        op.endsWith("starts_with") -> prop.startsWith(want) == (op == "starts_with")
        else -> prop.endsWith(want) == (op == "ends_with")
    }
    "between", "not_between" -> {
        val x = toFloat(prop)
        if (x == null || want !is List<*> || want.size != 2 || want[0] !is Number || want[1] !is Number) false
        else (x >= (want[0] as Number).toDouble() && x <= (want[1] as Number).toDouble()) == (op == "between")
    }
    "is_date_before", "is_date_after" -> {
        val p = instant(prop, now, false)
        val w = instant(want, now, true)
        if (p == null || w == null) false else if (op == "is_date_before") p.isBefore(w) else p.isAfter(w)
    }
    "semver_eq", "semver_neq", "semver_gt", "semver_gte", "semver_lt", "semver_lte" -> {
        val c = cmpVersion(prop, want)
        when {
            c == null -> false
            op == "semver_eq" -> c == 0
            op == "semver_neq" -> c != 0
            op == "semver_gt" -> c > 0
            op == "semver_gte" -> c >= 0
            op == "semver_lt" -> c < 0
            else -> c <= 0
        }
    }
    "semver_tilde", "semver_caret", "semver_wildcard" -> {
        val v = parseVersion(prop)
        val r = semverRange(op, want)
        v != null && r != null && cmpParts(v, r.first) >= 0 && cmpParts(v, r.second) < 0
    }
    else -> false
}
