package com.lucida.kmp

/** Port: at-rest encryption for the persisted queue (plug in Android Keystore, SQLCipher, ...). */
interface QueueCipher {
    fun encrypt(plain: String): String

    /** null when the value cannot be decrypted (key rotated, corrupt): the queue then starts empty. */
    fun decrypt(cipher: String): String?
}

/** Decorator: values under [keys] (or a key starting with one of [prefixes]) are stored encrypted, everything else passes through. */
class EncryptedStore(
    private val inner: KeyValueStore,
    private val cipher: QueueCipher,
    private val keys: Set<String>,
    private val prefixes: List<String> = emptyList(),
) : KeyValueStore {
    private fun covers(key: String) = key in keys || prefixes.any { key.startsWith(it) }
    override fun get(key: String): String? = inner.get(key)?.let { if (covers(key)) cipher.decrypt(it) else it }
    override fun set(key: String, value: String) = inner.set(key, if (covers(key)) cipher.encrypt(value) else value)
    override fun remove(key: String) = inner.remove(key)
}
