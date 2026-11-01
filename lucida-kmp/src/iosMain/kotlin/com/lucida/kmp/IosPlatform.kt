package com.lucida.kmp

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDefaults
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.create
import platform.UIKit.UIDevice
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

internal actual class Lock actual constructor() {
    private val l = NSRecursiveLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}

internal actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** A serial GCD queue with delayed tasks (the Apple counterpart of the JVM single-thread executor). */
internal class DispatchScheduler(name: String) : Scheduler {
    private val queue = dispatch_queue_create("dev.lucida.$name", null)

    private class Task : ScheduledTask {
        var cancelled = false
        var ran = false
        override val isDone get() = cancelled || ran
        override fun cancel() {
            cancelled = true
        }
    }

    override fun execute(task: () -> Unit) {
        dispatch_async(queue) { task() }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): ScheduledTask {
        val t = Task()
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000L), queue) {
            if (!t.cancelled) {
                t.ran = true
                task()
            }
        }
        return t
    }

    override fun idle() {
        val sem = dispatch_semaphore_create(0)
        dispatch_async(queue) { dispatch_semaphore_signal(sem) }
        dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
    }

    override fun shutdown() = Unit
}

internal actual fun newScheduler(name: String): Scheduler = DispatchScheduler(name)

/** [KeyValueStore] over NSUserDefaults (a dedicated suite; in-memory read, async disk write by the system). */
class UserDefaultsStore(suite: String = "dev.lucida.sdk") : KeyValueStore {
    private val defaults = NSUserDefaults(suiteName = suite)

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun set(key: String, value: String) = defaults.setObject(value, forKey = key)

    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/** NSURLSession adapter for [HttpPort]; blocks the caller on a semaphore (the SDK calls it off the main thread). */
class UrlSessionHttp(private val timeoutMs: Int = 15_000) : HttpPort {
    override fun send(method: String, url: String, body: String?, headers: Map<String, String>, gzip: Boolean): HttpResult {
        val nsUrl = NSURL.URLWithString(url) ?: return HttpResult(0)
        val req = NSMutableURLRequest(uRL = nsUrl)
        req.HTTPMethod = method
        req.setTimeoutInterval(timeoutMs / 1000.0)
        headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
        if (body != null) {
            var bytes = body.encodeToByteArray()
            if (gzip) {
                bytes = gzipStored(bytes)
                req.setValue("gzip", forHTTPHeaderField = "Content-Encoding")
            }
            req.HTTPBody = bytes.toNSData()
        }
        var status = 0
        var text = ""
        val sem = dispatch_semaphore_create(0)
        NSURLSession.sharedSession.dataTaskWithRequest(req) { data, resp, err ->
            if (err == null) {
                status = (resp as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                text = data?.toByteArray()?.decodeToString() ?: ""
            }
            dispatch_semaphore_signal(sem)
        }.resume()
        dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
        return HttpResult(status, text)
    }
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

private fun NSData.toByteArray(): ByteArray = if (length.toInt() == 0) ByteArray(0) else bytes!!.readBytes(length.toInt())

actual fun platformDefaults(requestTimeoutMs: Int): Platform {
    val info = NSBundle.mainBundle.infoDictionary
    val dev = UIDevice.currentDevice
    return Platform(
        UserDefaultsStore(),
        UrlSessionHttp(requestTimeoutMs),
        DeviceInfo(
            os = dev.systemName,
            osVersion = dev.systemVersion,
            model = dev.model,
            manufacturer = "Apple",
            appVersion = info?.get("CFBundleShortVersionString") as? String ?: "unknown",
            appBuild = info?.get("CFBundleVersion") as? String,
        ),
    )
}
