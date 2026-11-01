package com.lucida.kmp

import java.io.File

/** [KeyValueStore] over one JSON file (JVM apps, CLIs, tests). Writes are atomic (temp file + rename). */
class FileStore(private val file: File) : KeyValueStore {
    private val data = LinkedHashMap<String, String>()

    init {
        if (file.exists()) {
            runCatching { Json.decodeObj(file.readText()).forEach { (k, v) -> (v as? String)?.let { data[k] = it } } }
        }
    }

    override fun get(key: String): String? = locked { data[key] }

    override fun set(key: String, value: String) {
        locked {
            data[key] = value
            persist()
        }
    }

    override fun remove(key: String) {
        locked {
            if (data.remove(key) != null) persist()
        }
    }

    private fun persist() {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(Json.encode(data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

/** Store under `$LUCIDA_HOME` (or `~/.lucida`), HttpURLConnection transport, device info from system properties. */
actual fun platformDefaults(requestTimeoutMs: Int): Platform {
    val dir = System.getenv("LUCIDA_HOME") ?: "${System.getProperty("user.home")}/.lucida"
    return Platform(
        FileStore(File(dir, "store.json")),
        UrlConnectionHttp(requestTimeoutMs),
        DeviceInfo(
            os = System.getProperty("os.name") ?: "JVM",
            osVersion = System.getProperty("os.version") ?: "unknown",
            model = System.getProperty("os.arch") ?: "jvm",
            appVersion = System.getProperty("lucida.appVersion") ?: "unknown",
        ),
    )
}
