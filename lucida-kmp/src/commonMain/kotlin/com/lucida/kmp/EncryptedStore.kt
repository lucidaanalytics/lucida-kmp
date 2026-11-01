package com.lucida.kmp

/** Port: at-rest encryption for the persisted queue (plug in Android Keystore, SQLCipher, ...). */
interface QueueCipher {
    fun encrypt(plain: String): String

    /** null when the value cannot be decrypted (key rotated, corrupt): the queue then starts empty. */
    fun decrypt(cipher: String): String?
}

/** Decorator: values under [keys] are stored encrypted, everything else passes through. */
class EncryptedStore(private val inner: KeyValueStore, private val cipher: QueueCipher, private val keys: Set<String>) : KeyValueStore {
    override fun get(key: String): String? = inner.get(key)?.let { if (key in keys) cipher.decrypt(it) else it }
    override fun set(key: String, value: String) = inner.set(key, if (key in keys) cipher.encrypt(value) else value)
    override fun remove(key: String) = inner.remove(key)
}
