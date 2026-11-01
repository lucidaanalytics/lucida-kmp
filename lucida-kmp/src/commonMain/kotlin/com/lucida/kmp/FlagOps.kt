package com.lucida.kmp

/** Operator semantics: port of go/internal/flageval/ops.go (the reference). */
private val NUM = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
private val INT = Regex("^[+-]?\\d+$")

internal fun toFloat(v: Any?): Double? = when {
    v is Number -> v.toDouble()
    v is String && NUM.matches(v.trim()) -> v.trim().toDouble()
    else -> null
}

/** Numbers compare numerically; a number never equals a string. */
private fun equal(a: Any?, b: Any?): Boolean =
    if (a is Number || b is Number) a is Number && b is Number && a.toDouble() == b.toDouble() else a == b

internal fun parseVersion(v: Any?): List<Int>? {
    if (v !is String) return null
    var s = v.trim().removePrefix("v")
    val i = s.indexOfFirst { it == '-' || it == '+' }
    if (i >= 0) s = s.substring(0, i)
    return s.split('.').map { p ->
        if (!INT.matches(p)) return null
        (p.toIntOrNull() ?: return null).also { if (it < 0) return null }
    }
}

internal fun cmpVersion(a: Any?, b: Any?): Int? {
    val pa = parseVersion(a) ?: return null
    val pb = parseVersion(b) ?: return null
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return if (x < y) -1 else 1
    }
    return 0
}

/** Missing/null properties match only is_not_set; every other op (even negations) is false. */
fun matchOp(op: String, prop: Any?, present: Boolean, want: Any?, semver: Boolean, now: Instant = Instant.now()): Boolean {
    val isSet = present && prop != null
    if (op == "is_set") return isSet
    if (op == "is_not_set") return !isSet
    if (!isSet) return false
    return when (op) {
        "eq", "neq" -> {
            val eq = if (semver) (cmpVersion(prop, want) ?: return false) == 0 else equal(prop, want)
            eq == (op == "eq")
        }
        "contains", "ncontains", "regex", "nregex" -> {
            if (prop !is String || want !is String) return false
            if (op == "contains" || op == "ncontains") return prop.contains(want) == (op == "contains")
            val re = runCatching { Regex(want) }.getOrNull() ?: return false // an invalid pattern never matches, even for nregex
            re.containsMatchIn(prop) == (op == "regex")
        }
        "gt", "gte", "lt", "lte" -> {
            val c = if (semver) cmpVersion(prop, want) else {
                val a = toFloat(prop)
                val b = toFloat(want)
                if (a == null || b == null) null else a.compareTo(b)
            } ?: return false
            when (op) {
                "gt" -> c > 0
                "gte" -> c >= 0
                "lt" -> c < 0
                else -> c <= 0
            }
        }
        "in", "not_in" -> want is List<*> && want.any { equal(prop, it) } == (op == "in")
        else -> matchV2(op, prop, want, now)
    }
}
