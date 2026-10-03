package com.suhas.globaledgeai.data.remote

import com.suhas.globaledgeai.domain.model.GrowwApiHealthSnapshot
import kotlinx.coroutines.delay
import java.util.ArrayDeque

/**
 * Process-local Groww API usage meter. Groww does not expose a remaining-request counter,
 * so the app tracks its own rolling windows and keeps a safety reserve below the published
 * Live Data ceiling. Counters reset if the Android process is restarted.
 */
object GrowwApiHealth {
    enum class Bucket { AUTHENTICATION, ORDERS, LIVE_DATA, NON_TRADING, HISTORICAL }

    private data class Event(val atMs: Long, val bucket: Bucket)

    private const val ONE_SECOND_MS = 1_000L
    private const val ONE_MINUTE_MS = 60_000L
    private const val ONE_HOUR_MS = 60L * ONE_MINUTE_MS

    // Groww-published limits. v1.6.1 uses an adaptive reserve: fast while healthy, conservative after a real 429.
    const val AUTH_PER_MINUTE = 30
    const val ORDERS_PER_MINUTE = 250
    const val LIVE_PER_SECOND = 10
    const val LIVE_PER_MINUTE = 300
    const val NON_TRADING_PER_MINUTE = 500
    const val INTERNAL_LIVE_PER_MINUTE = 285
    private const val COOLDOWN_LIVE_PER_MINUTE = 220
    private const val RATE_LIMIT_COOLDOWN_MS = 2L * ONE_MINUTE_MS

    private val lock = Any()
    private val requests = ArrayDeque<Event>()
    private val rateLimits = ArrayDeque<Event>()
    private var totalRequests: Long = 0L

    private fun pruneLocked(now: Long) {
        while (requests.isNotEmpty() && now - requests.peekFirst().atMs > ONE_HOUR_MS) requests.removeFirst()
        while (rateLimits.isNotEmpty() && now - rateLimits.peekFirst().atMs > ONE_HOUR_MS) rateLimits.removeFirst()
    }

    private fun liveMinuteCountLocked(now: Long): Int = requests.count {
        it.bucket == Bucket.LIVE_DATA && now - it.atMs < ONE_MINUTE_MS
    }

    private fun effectiveLiveBudgetLocked(now: Long): Int =
        if (rateLimits.any { now - it.atMs < RATE_LIMIT_COOLDOWN_MS }) COOLDOWN_LIVE_PER_MINUTE else INTERNAL_LIVE_PER_MINUTE

    /**
     * Adaptive reserve guard for Live Data. Healthy sessions may use up to 285/minute; after an
     * actual 429 the app automatically cools down to 220/minute for two minutes, then recovers.
     */
    suspend fun awaitLiveReserve() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = System.currentTimeMillis()
                pruneLocked(now)
                val budget = effectiveLiveBudgetLocked(now)
                val live = requests.filter { it.bucket == Bucket.LIVE_DATA && now - it.atMs < ONE_MINUTE_MS }
                if (live.size < budget) {
                    0L
                } else {
                    val oldest = live.minOfOrNull { it.atMs } ?: now
                    (ONE_MINUTE_MS - (now - oldest) + 25L).coerceAtLeast(50L)
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs.coerceAtMost(5_000L))
        }
    }

    fun recordRequest(bucket: Bucket) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneLocked(now)
            requests.addLast(Event(now, bucket))
            totalRequests++
        }
    }

    fun recordRateLimit(bucket: Bucket) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneLocked(now)
            rateLimits.addLast(Event(now, bucket))
        }
    }

    fun snapshot(): GrowwApiHealthSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        pruneLocked(now)
        fun minute(bucket: Bucket) = requests.count { it.bucket == bucket && now - it.atMs < ONE_MINUTE_MS }
        val liveMinute = minute(Bucket.LIVE_DATA)
        val liveSecond = requests.count { it.bucket == Bucket.LIVE_DATA && now - it.atMs < ONE_SECOND_MS }
        val recent429 = rateLimits.count { now - it.atMs < ONE_HOUR_MS }
        val last429 = rateLimits.lastOrNull()?.atMs ?: 0L
        val effectiveBudget = effectiveLiveBudgetLocked(now)
        val status = when {
            rateLimits.any { now - it.atMs < ONE_MINUTE_MS } -> "RATE LIMITED"
            effectiveBudget < INTERNAL_LIVE_PER_MINUTE -> "COOLDOWN"
            liveMinute >= effectiveBudget || liveSecond >= 9 -> "THROTTLING"
            liveMinute >= (effectiveBudget * 9 / 10) || liveSecond >= 8 -> "WATCH"
            else -> "HEALTHY"
        }
        GrowwApiHealthSnapshot(
            generatedAt = now,
            status = status,
            liveLastSecond = liveSecond,
            liveLastMinute = liveMinute,
            livePerSecondLimit = LIVE_PER_SECOND,
            livePerMinuteLimit = LIVE_PER_MINUTE,
            internalLiveMinuteBudget = effectiveBudget,
            liveHeadroom = (LIVE_PER_MINUTE - liveMinute).coerceAtLeast(0),
            liveReserveHeadroom = (effectiveBudget - liveMinute).coerceAtLeast(0),
            authLastMinute = minute(Bucket.AUTHENTICATION),
            ordersLastMinute = minute(Bucket.ORDERS),
            nonTradingLastMinute = minute(Bucket.NON_TRADING),
            historicalLastMinute = minute(Bucket.HISTORICAL),
            rateLimitLastHour = recent429,
            lastRateLimitAt = last429,
            totalRequestsSinceStart = totalRequests
        )
    }
}
