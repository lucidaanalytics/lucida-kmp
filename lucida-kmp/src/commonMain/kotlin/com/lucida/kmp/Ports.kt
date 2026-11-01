package com.lucida.kmp

/** JSON-shaped object: the SDK keeps wire data as plain maps so shared vectors load directly. */
typealias Obj = Map<String, Any?>

/** Port: synchronous key/value persistence (SharedPreferences, file, memory). */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

class MemoryStore : KeyValueStore {
    val data = LinkedHashMap<String, String>()

    override fun get(key: String) = locked { data[key] }

    override fun set(key: String, value: String) {
        locked {
            data[key] = value
        }
    }

    override fun remove(key: String) {
        locked {
            data.remove(key)
        }
    }
}

/** status 0 means a network failure. */
class HttpResult(val status: Int, val body: String = "")

/** Port: how bytes leave the SDK. `gzip` asks the adapter to compress the body. */
interface HttpPort {
    fun send(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), gzip: Boolean = false): HttpResult
}

fun interface Clock {
    fun nowMs(): Long
}

val SystemClock = Clock { currentTimeMillis() }
