package com.lucida.kmp

/**
 * Event enrichment with flag values (LC-FLG-28): `$feature/<key>` per flag plus sorted `$active_feature_flags`
 * (keys whose value is not false). Empty when no flag is known.
 */
fun flagEventProps(values: Obj): Obj {
    if (values.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in values) out["\$feature/$k"] = v
    out["\$active_feature_flags"] = values.filterValues { it != false }.keys.sorted()
    return out
}
