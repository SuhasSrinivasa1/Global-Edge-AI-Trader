package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.AdaptiveRangeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRangeEngineTest {
    @Test
    fun pressureThresholdUsesCurrentScoreDistributionButKeepsSafetyFloor() {
        val d = AdaptiveRangeEngine.demandThreshold(70.0, listOf(81.0, 74.0, 68.0, 65.0, 63.0), 5)
        assertEquals(63.0, d.threshold, 0.001)
        val quiet = AdaptiveRangeEngine.demandThreshold(70.0, listOf(61.0, 59.0), 5)
        assertEquals(62.0, quiet.threshold, 0.001)
    }

    @Test
    fun dynamicTargetStaysInsideDeclaredBand() {
        val low = AdaptiveRangeEngine.demandTargetPct(62.0, 45.0, 48.0, 45.0)
        val high = AdaptiveRangeEngine.demandTargetPct(94.0, 90.0, 95.0, 88.0)
        assertTrue(low in 1.5..4.5)
        assertTrue(high in 1.5..4.5)
        assertTrue(high > low)
    }
}
