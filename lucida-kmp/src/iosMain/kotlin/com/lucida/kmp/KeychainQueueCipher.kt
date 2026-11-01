package com.lucida.kmp

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import platform.posix.size_tVar

/**
 * [QueueCipher] for iOS (pass as `LucidaOptions.queueCipher`): AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC) with CommonCrypto,
 * because CryptoKit's AES-GCM is Swift-only and not reachable from Kotlin/Native. The 64-byte key (32 AES + 32 HMAC) is
 * random, created on first use and kept in the Keychain (this device only, readable after first unlock so background
 * flushes work). Ciphertext is base64 of iv(16) + ciphertext + tag(32). A lost key or a bad tag makes [decrypt] return
 * null, so the persisted queue then starts empty. [available] is false when the Keychain refuses (no entitlement).
 */
class KeychainQueueCipher(private val service: String = "dev.lucida.queue") : QueueCipher {
    private val key: ByteArray? by lazy { load() ?: create() }

    val available: Boolean get() = key != null

    override fun encrypt(plain: String): String {
        val k = key ?: error("Keychain key unavailable")
        val iv = random(IV) ?: error("no randomness")
        val ct = aes(true, k.copyOfRange(0, 32), iv, plain.encodeToByteArray()) ?: error("encrypt failed")
        val body = iv + ct
        return toNSData(body + hmac(k.copyOfRange(32, 64), body)).base64EncodedStringWithOptions(0u)
    }

    override fun decrypt(cipher: String): String? {
        val k = key ?: return null
        val all = NSData.create(base64EncodedString = cipher, options = 0u)?.let(::toBytes) ?: return null
        if (all.size < IV + 16 + TAG) return null
        val body = all.copyOfRange(0, all.size - TAG)
        if (!constantTimeEquals(hmac(k.copyOfRange(32, 64), body), all.copyOfRange(all.size - TAG, all.size))) return null
        return aes(false, k.copyOfRange(0, 32), body.copyOfRange(0, IV), body.copyOfRange(IV, body.size))?.decodeToString()
    }

    private fun query(vararg extra: Pair<COpaquePointer?, COpaquePointer?>): CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val owned = listOf(CFBridgingRetain(service), CFBridgingRetain("queue-key"))
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, owned[0])
        CFDictionaryAddValue(q, kSecAttrAccount, owned[1])
        owned.forEach { CFRelease(it) } // the dictionary retains its own references
        extra.forEach { (k, v) -> CFDictionaryAddValue(q, k, v) }
        return q
    }

    private fun load(): ByteArray? = memScoped {
        val q = query(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, out.ptr)
        CFRelease(q)
        if (status != errSecSuccess) return null
        (CFBridgingRelease(out.value) as? NSData)?.let(::toBytes)?.takeIf { it.size == 64 }
    }

    private fun create(): ByteArray? {
        val k = random(64) ?: return null
        val data = CFBridgingRetain(toNSData(k))
        val q = query(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(q, null)
        CFRelease(q)
        CFRelease(data)
        return if (status == errSecSuccess) k else null
    }

    private companion object {
        const val IV = 16
        const val TAG = 32

        fun random(n: Int): ByteArray? {
            val b = ByteArray(n)
            val ok = b.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
            return if (ok == 0) b else null
        }

        fun aes(encrypt: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray? = memScoped {
            val src = if (input.isEmpty()) ByteArray(1) else input // a pinned empty array has no address
            val out = ByteArray(input.size + 16)
            val moved = alloc<size_tVar>()
            val status = key.usePinned { k ->
                iv.usePinned { v ->
                    src.usePinned { i ->
                        out.usePinned { o ->
                            CCCrypt(
                                (if (encrypt) kCCEncrypt else kCCDecrypt).convert(), kCCAlgorithmAES.convert(), kCCOptionPKCS7Padding.convert(),
                                k.addressOf(0), key.size.convert(), v.addressOf(0),
                                i.addressOf(0), input.size.convert(), o.addressOf(0), out.size.convert(), moved.ptr,
                            )
                        }
                    }
                }
            }
            if (status.toInt() != kCCSuccess.toInt()) null else out.copyOf(moved.value.toInt())
        }

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = ByteArray(TAG)
            key.usePinned { k ->
                data.usePinned { d ->
                    mac.usePinned { m -> CCHmac(kCCHmacAlgSHA256.convert(), k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), m.addressOf(0)) }
                }
            }
            return mac
        }

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        fun toNSData(b: ByteArray): NSData = b.usePinned { NSData.create(bytes = it.addressOf(0), length = b.size.convert()) }

        fun toBytes(d: NSData): ByteArray {
            val b = ByteArray(d.length.toInt())
            if (b.isNotEmpty()) b.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
            return b
        }
    }
}
