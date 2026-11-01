package com.lucida.kmp

/**
 * Adds `X-Lucida-Distinct-Id` / `X-Lucida-Session-Id` to requests for your own backend hosts so server events
 * join this device's session. [hosts] entries match exactly, or as a suffix when they start with a dot
 * (`.example.com`). Never added for the Lucida api host, and only while analytics consent is granted.
 * Servers must treat these headers as client-controlled, never as authentication.
 * The same requests carry W3C `baggage: lucida.session_id=<id>` (merged into the app's own baggage) so Watchtower links its
 * spans, network timings and logs to this session: the Watchtower seam, Lucida itself captures no network or log data.
 */
class TracingHeaders(
    private val hosts: List<String>,
    private val apiHost: String,
    private val consent: () -> Boolean,
    private val ids: () -> Pair<String, String>,
) {
    private val api = hostOf(apiHost)

    private fun matches(host: String) = hosts.any { h ->
        val x = h.lowercase()
        if (x.startsWith(".")) host.endsWith(x) else host == x
    }

    /** [baggage] is the request's existing `baggage` value, kept (minus a stale Lucida entry) in the returned one. */
    fun headersFor(url: String, baggage: String? = null): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        if (hosts.isEmpty() || host == api || !matches(host) || !consent()) return emptyMap()
        val (d, s) = ids()
        return mapOf("X-Lucida-Distinct-Id" to d, "X-Lucida-Session-Id" to s, "baggage" to withBaggage(baggage, s))
    }

    companion object {
        /** `lucida.session_id=<id>` appended to [existing] W3C baggage, replacing a stale Lucida entry. */
        fun withBaggage(existing: String?, sessionId: String): String =
            ((existing ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("lucida.session_id=") } + "lucida.session_id=$sessionId").joinToString(",")

        /** Lowercase host of an absolute URL (no userinfo, port or brackets), or null for a relative one. */
        internal fun hostOf(url: String): String? {
            val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
            return host.lowercase().takeIf { it.isNotEmpty() }
        }
    }
}

