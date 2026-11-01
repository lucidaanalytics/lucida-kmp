package com.lucida.kmp

import kotlin.math.floor

/**
 * Port of go/internal/flageval (the reference) over the JSON wire shapes of testdata/flags.
 * Flags, contexts and results are plain maps so shared vectors load directly.
 */
private const val SCALE = 4503599627370496.0 // 16^13

/** Hash of a raw `key.salt.unit` string: first 13 hex chars of sha256 / 16^13. */
fun hashInput(s: String): Double {
    val d = sha256(s.encodeToByteArray())
    val digits = d.take(7).joinToString("") { hex(it) }.substring(0, 13)
    return digits.toLong(16) / SCALE
}

fun flagHash(flagKey: String, salt: String, bucketingKey: String, variant: Boolean = false): Double =
    hashInput(if (variant) "$flagKey.$salt.variant.$bucketingKey" else "$flagKey.$salt.$bucketingKey")

private fun off(reason: String, idx: Int = -1): Obj = mapOf("value" to false, "reason" to reason, "condition_index" to idx)

@Suppress("UNCHECKED_CAST")
private fun Obj.obj(k: String) = this[k] as? Obj

@Suppress("UNCHECKED_CAST")
private fun Obj.list(k: String) = (this[k] as? List<Obj>) ?: emptyList()
private fun Obj.str(k: String) = this[k] as? String
private fun Obj.num(k: String) = (this[k] as? Number)?.toDouble() ?: 0.0

private fun valueMatches(got: Any?, want: Any?): Boolean =
    if (want is Boolean) (if (got is Boolean) got == want else want) else got == want // variant string = "on"

private fun filterMatches(f: Obj, c: Obj, resolved: Map<String, Obj>): Boolean {
    val key = f.str("key") ?: return false
    val now = parseNow(c["now"])
    val bag: Obj? = when (f.str("type")) {
        "person" -> c.obj("person_properties")
        "group" -> c.obj("group_properties")?.obj(f.str("group_type") ?: "")
        "device" -> c.obj("device_properties")
        "geo" -> c.obj("geo_properties")
        "app_version" -> {
            val v = c["app_version"]
            return matchOp(f.str("op")!!, v, v != null && v != "", f["value"], true, now)
        }
        "flag" -> return resolved[key]?.let { matchOp(f.str("op")!!, it["value"], true, f["value"], false, now) } ?: false
        else -> return false
    }
    return matchOp(f.str("op")!!, bag?.get(key), bag != null && bag.containsKey(key), f["value"], false, now)
}

private fun pickVariant(f: Obj, cond: Obj, key: String): Obj {
    val vs = f.list("variants")
    cond.str("variant_override")?.let { o -> vs.firstOrNull { it["key"] == o }?.let { return it } }
    val h = flagHash(f.str("key")!!, f.str("salt")!!, key, variant = true) * 100
    var cum = 0.0
    for (v in vs) {
        cum += v.num("rollout_pct")
        if (h < cum) return v
    }
    return vs.last() // totals < 100: the last variant takes the remainder
}

/** A stored value of a sticky flag decides once a condition's filters passed (LC-EXT-29). */
private fun sticky(f: Obj, cond: Obj, i: Int, c: Obj): Obj? {
    val stored = (c.obj("sticky_assignments") ?: emptyMap())[f["key"]]
    if (f["sticky"] != true || stored == null) return null
    val res = linkedMapOf<String, Any?>("value" to true, "reason" to "sticky_bucket", "condition_index" to i)
    val variants = f.list("variants")
    if (f["type"] == "multivariate" && variants.isNotEmpty()) {
        if (variants.any { it["key"] == cond["variant_override"] }) return null // the override wins
        val v = variants.firstOrNull { stored is String && it["key"] == stored } ?: return null
        res["value"] = v["key"]
        res["variant"] = v["key"]
        if (v["payload"] != null) res["payload"] = v["payload"]
        return res
    }
    if (stored != true) return null
    if (f["type"] == "json" && f["payload"] != null) res["payload"] = f["payload"]
    return res
}

/** The bucketing unit of an aggregation (a flag's, or one condition's own); null when absent. */
private fun unitKey(agg: Obj, c: Obj): String? = when (agg["kind"]) {
    "group" -> c.obj("groups")?.get(agg.str("group_type") ?: "") as? String
    "device" -> c.str("device_id")
    else -> c.str("bucketing_key")?.takeIf { it.isNotEmpty() } ?: c.str("distinct_id")
}

/** A flag scoped to app contexts is dropped when the request declares contexts and shares none. */
private fun appContextAllowed(f: Obj, c: Obj): Boolean {
    val want = (f["app_contexts"] as? List<*>) ?: emptyList<Any?>()
    val have = (c["app_contexts"] as? List<*>) ?: emptyList<Any?>()
    return want.isEmpty() || have.isEmpty() || want.any { it in have }
}

/** Evaluate one flag; prerequisites and `flag` filters read [resolved] (see [evaluateAll]). */
fun evaluateFlag(f: Obj, c: Obj, resolved: Map<String, Obj> = emptyMap()): Obj {
    if (!appContextAllowed(f, c)) return off("app_context_excluded")
    if (f["active"] != true) return off("inactive")
    if (f["kill_switch"] == true) return off("kill_switch")
    var server = false
    for (p in f.list("prerequisites")) {
        val r = resolved[p["flag"]] ?: return off("prerequisite_missing")
        if (r["reason"] == "requires_server") server = true
        else if (r["reason"] == "prerequisite_cycle" || !valueMatches(r["value"], p["value"])) return off("prerequisite_failed")
    }
    if (server) return off("requires_server")
    val agg = f.obj("aggregation") ?: return off("no_bucketing_key")
    val key = unitKey(agg, c)
    val conds = f.list("conditions")
    val perCondition = conds.any { it.obj("aggregation") != null }
    if (key.isNullOrEmpty() && (!perCondition || f.obj("holdout") != null || f.obj("namespace") != null)) return off("no_bucketing_key")
    f.obj("holdout")?.let { if (flagHash("holdout", it.str("salt")!!, key!!) * 100 < it.num("percentage")) return off("holdout") }
    f.obj("namespace")?.let { ns ->
        if (ns.num("total") > 0) {
            val b = floor(flagHash("namespace", ns.str("salt")!!, key!!) * ns.num("total"))
            if (b < ns.num("start") || b >= ns.num("end")) return off("namespace_excluded")
        }
    }
    var skipped = 0
    conds.forEachIndexed { i, cond ->
        val own = cond.obj("aggregation")
        val ckey = if (own == null) key else unitKey(own, c)
        if (ckey.isNullOrEmpty()) {
            skipped++
            return@forEachIndexed
        }
        var cohort = false
        var ok = true
        for (fl in cond.list("filters")) {
            if (fl["type"] == "cohort" || (fl["type"] == "flag" && resolved[fl["key"]]?.get("reason") == "requires_server")) cohort = true
            else if (!filterMatches(fl, c, resolved)) {
                ok = false
                break
            }
        }
        if (!ok) return@forEachIndexed
        if (cohort) return off("requires_server", i)
        if (own == null) sticky(f, cond, i, c)?.let { return it } // the store keys on the flag-level unit
        if (flagHash(f.str("key")!!, f.str("salt")!!, ckey) * 100 >= cond.num("rollout_pct")) {
            if (f["early_exit"] == true) return off("out_of_rollout", i)
            return@forEachIndexed
        }
        val res = linkedMapOf<String, Any?>("value" to true, "reason" to "condition_match", "condition_index" to i)
        if (f["type"] == "multivariate" && f.list("variants").isNotEmpty()) {
            val v = pickVariant(f, cond, ckey)
            res["value"] = v["key"]
            res["variant"] = v["key"]
            if (v["payload"] != null) res["payload"] = v["payload"]
        } else if (f["type"] == "json" && f["payload"] != null) res["payload"] = f["payload"]
        return res
    }
    return off(if (conds.isNotEmpty() && skipped == conds.size) "no_bucketing_key" else "no_condition_match")
}

private fun deps(f: Obj): List<String> =
    f.list("prerequisites").map { it["flag"] as String } +
        f.list("conditions").flatMap { c -> c.list("filters").filter { it["type"] == "flag" }.map { it["key"] as String } }

/** Evaluate all flags, resolving prerequisites depth-first; cycles get prerequisite_cycle. */
fun evaluateAll(flags: List<Obj>, c: Obj): Map<String, Obj> {
    val byKey = flags.associateBy { it["key"] as String }
    val out = LinkedHashMap<String, Obj>()
    val state = HashMap<String, Int>()
    fun visit(key: String) {
        val f = byKey[key] ?: return
        if (state.containsKey(key)) return
        state[key] = 1
        var cyc = false
        for (d in deps(f)) {
            visit(d)
            if (state[d] == 1 || out[d]?.get("reason") == "prerequisite_cycle") cyc = true
        }
        out[key] = if (cyc) off("prerequisite_cycle") else evaluateFlag(f, c, out)
        state[key] = 2
    }
    flags.forEach { visit(it["key"] as String) }
    return out
}

fun requiresServer(r: Obj) = r["reason"] == "requires_server"
