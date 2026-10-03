package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.Indicators
import com.suhas.globaledgeai.domain.model.Candle
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorTest {
    @Test
    fun detectsPositiveCircuitLikeStreak() {
        val candles = listOf(
            Candle(1,100.0,100.0,99.0,100.0,1000),
            Candle(2,105.0,105.0,105.0,105.0,2000),
            Candle(3,110.25,110.25,110.25,110.25,3000)
        )
        assertTrue(Indicators.consecutiveCircuitLikeDays(candles) >= 2)
    }
}
