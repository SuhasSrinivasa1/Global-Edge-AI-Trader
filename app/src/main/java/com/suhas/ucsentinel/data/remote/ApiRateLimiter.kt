package com.suhas.globaledgeai.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small process-wide style serial rate gate. The caller still controls endpoint-specific
 * instances, but every request using the same limiter is spaced by at least minIntervalMs.
 * This intentionally runs below broker-published ceilings to leave headroom for retries,
 * authentication, background work and user-triggered scans.
 */
class ApiRateLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastPermitNs: Long = 0L

    suspend fun awaitPermit() {
        mutex.withLock {
            val now = System.nanoTime()
            if (lastPermitNs != 0L) {
                val elapsedMs = (now - lastPermitNs) / 1_000_000L
                val waitMs = minIntervalMs - elapsedMs
                if (waitMs > 0L) delay(waitMs)
            }
            lastPermitNs = System.nanoTime()
        }
    }
}
