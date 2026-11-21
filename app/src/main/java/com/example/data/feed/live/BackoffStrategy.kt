package com.example.data.feed.live

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter:
 * attempt 0 -> ~1s, attempt 1 -> ~2s, attempt 2 -> ~4s ... capped at 60s.
 */
class BackoffStrategy(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val jitterFraction: Double = 0.25,
    private val randomDouble: () -> Double = { Random.nextDouble() }
) {
    fun nextDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 20)
        val exponential = initialDelayMs shl safeAttempt
        val cappedBase = min(exponential, maxDelayMs)
        val jitter = (cappedBase * jitterFraction * randomDouble()).toLong()
        return min(cappedBase + jitter, maxDelayMs)
    }
}
