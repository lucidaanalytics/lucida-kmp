package com.lucida.kmp

import java.io.File

actual fun sleepMs(ms: Long) = Thread.sleep(ms)

/** Walks up from the working directory to the repo's `testdata` folder (module dir, sdks/kmp, repo root all work). */
actual fun readTestdata(relative: String): String {
    System.getenv("LUCIDA_TESTDATA")?.let { return File(it, relative).readText() }
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val f = File(dir, "testdata/$relative")
        if (f.isFile) return f.readText()
        dir = dir.parentFile
    }
    error("testdata/$relative not found above ${System.getProperty("user.dir")}")
}
