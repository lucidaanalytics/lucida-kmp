package com.lucida.kmp

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

actual fun sleepMs(ms: Long) = NSThread.sleepForTimeInterval(ms / 1000.0)

/** The Gradle iOS test task passes the repo's testdata dir as `LUCIDA_TESTDATA` (see build.gradle.kts). */
actual fun readTestdata(relative: String): String {
    val root = NSProcessInfo.processInfo.environment["LUCIDA_TESTDATA"] as? String ?: error("LUCIDA_TESTDATA is not set")
    return NSString.stringWithContentsOfFile("$root/$relative", NSUTF8StringEncoding, null) ?: error("cannot read $root/$relative")
}
