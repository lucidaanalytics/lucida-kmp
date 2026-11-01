package com.lucida.kmp

import kotlin.math.min
import kotlin.random.Random
import kotlin.math.roundToLong

/** Exponential backoff with jitter: 50%..100% of the capped exponential. */
fun backoffDelay(attempt: Int, baseMs: Long = 1000, maxMs: Long = 30_000, random: () -> Double = { Random.nextDouble() }): Long {
    val cap = min(maxMs, baseMs shl min(attempt, 20))
    return (cap * (0.5 + 0.5 * random())).roundToLong()
}

/** Network failure, timeout, rate limit, server errors. */
fun isRetryable(status: Int) = status == 0 || status == 408 || status == 429 || status >= 500
