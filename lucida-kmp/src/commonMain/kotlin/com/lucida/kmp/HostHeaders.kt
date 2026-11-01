package com.lucida.kmp

/**
 * Adds `X-Lucida-Distinct-Id` / `X-Lucida-Session-Id` to requests for your own backend hosts so server events
 * join this device's session. [hosts] entries match exactly, or as a suffix when they start with a dot
 * (`.example.com`). Never added for the Lucida api host, and only while analytics consent is granted.
 * Servers must treat these headers as client-controlled, never as authentication.
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

    fun headersFor(url: String): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        if (hosts.isEmpty() || host == api || !matches(host) || !consent()) return emptyMap()
        val (d, s) = ids()
        return mapOf("X-Lucida-Distinct-Id" to d, "X-Lucida-Session-Id" to s)
    }

    internal companion object {
        /** Lowercase host of an absolute URL (no userinfo, port or brackets), or null for a relative one. */
        fun hostOf(url: String): String? {
            val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
            return host.lowercase().takeIf { it.isNotEmpty() }
        }
    }
}

