package com.lucida.kmp

import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** java.net adapter for [HttpPort]; gzips the body when asked. Network errors become status 0. Blocking. */
class UrlConnectionHttp(private val timeoutMs: Int = 15_000) : HttpPort {
    override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (body != null) {
                c.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (gzip) {
                    c.setRequestProperty("Content-Encoding", "gzip")
                    GZIPOutputStream(c.outputStream).use { it.write(bytes) }
                } else {
                    c.outputStream.use { it.write(bytes) }
                }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            HttpResult(status, stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } catch (_: Exception) {
            HttpResult(0)
        } finally {
            c?.disconnect()
        }
    }
}

/** HttpURLConnection helper: call before connecting. */
fun TracingHeaders.apply(conn: HttpURLConnection) = headersFor(conn.url.toString()).forEach { (k, v) -> conn.setRequestProperty(k, v) }
