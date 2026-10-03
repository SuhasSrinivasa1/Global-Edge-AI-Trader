package com.suhas.globaledgeai.domain.engine

import kotlin.math.round

/**
 * Chooses scan thresholds from the quality distribution of the current market rather than
 * forcing one fixed score every day. Safety floors remain in place so the app can return
 * NO SIGNAL instead of manufacturing weak picks.
 */
object AdaptiveRangeEngine {
    data class RangeDecision(
        val threshold: Double,
        val floor: Double,
        val ceiling: Double,
        val targetCount: Int
    )

    fun ucThreshold(base: Double, scores: List<Double>, targetCount: Int): RangeDecision =
        chooseThreshold(base, scores, floor = 60.0, ceiling = 84.0, targetCount = targetCount.coerceIn(1, 5))

    fun demandThreshold(base: Double, scores: List<Double>, targetCount: Int): RangeDecision =
        chooseThreshold(base, scores, floor = 62.0, ceiling = 86.0, targetCount = targetCount.coerceIn(1, 10))

    private fun chooseThreshold(
        base: Double,
        scores: List<Double>,
        floor: Double,
        ceiling: Double,
        targetCount: Int
    ): RangeDecision {
        if (scores.isEmpty()) return RangeDecision(base.coerceIn(floor, ceiling), floor, ceiling, targetCount)
        val sorted = scores.filter { it.isFinite() }.sortedDescending()
        if (sorted.isEmpty()) return RangeDecision(base.coerceIn(floor, ceiling), floor, ceiling, targetCount)
        val anchor = sorted[(targetCount - 1).coerceAtMost(sorted.lastIndex)]
        // The target-count anchor makes the threshold stricter when the market is rich in strong setups
        // and relaxes it only as far as the safety floor when the market is quiet.
        val dynamic = anchor.coerceIn(floor, ceiling)
        return RangeDecision(round(dynamic * 10.0) / 10.0, floor, ceiling, targetCount)
    }

    fun demandPressureCutoff(base: Double, acceleration: Double, microstructure: Double): Double {
        val readiness = ((acceleration + microstructure) / 2.0).coerceIn(0.0, 100.0)
        val dynamic = 6.0 + ((readiness - 45.0).coerceIn(0.0, 45.0) / 45.0) * 4.0
        // Keep the learned/manual value as a soft centre while still allowing the current setup to move it.
        return ((dynamic * 0.7) + (base.coerceIn(6.0, 10.0) * 0.3)).coerceIn(6.0, 10.0)
    }

    fun demandTargetPct(score: Double, setup: Double, acceleration: Double, microstructure: Double): Double {
        val composite = score * 0.45 + acceleration * 0.30 + setup * 0.15 + microstructure * 0.10
        val target = 1.5 + ((composite - 58.0).coerceIn(0.0, 34.0) / 34.0) * 3.0
        return round(target.coerceIn(1.5, 4.5) * 10.0) / 10.0
    }
}
