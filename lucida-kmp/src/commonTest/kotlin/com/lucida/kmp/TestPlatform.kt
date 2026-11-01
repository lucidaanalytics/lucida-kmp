package com.lucida.kmp

/** Block the test thread (backoff timers fire on the queue thread). */
expect fun sleepMs(ms: Long)

/** Text of `<repo>/testdata/<relative>`: the shared cross-language conformance vectors. */
expect fun readTestdata(relative: String): String
