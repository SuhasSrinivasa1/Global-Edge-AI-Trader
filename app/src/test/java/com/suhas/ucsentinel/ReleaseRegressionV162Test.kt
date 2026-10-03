package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.TradingStrategyEngine
import com.suhas.globaledgeai.domain.model.Candle
import com.suhas.globaledgeai.domain.model.TradingStrategyDefinition
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReleaseRegressionV162Test {
    private val engine=TradingStrategyEngine()

    @Test fun coreOpeningBreakoutStillProducesResearchSignal(){
        val def=TradingStrategyDefinition("orb","Opening Range","ORB_RVOL","Breakout","regression","test",100)
        val bars=listOf(
            Candle(1,100.0,101.0,99.0,100.0,1000),
            Candle(2,100.0,101.2,99.8,100.4,1000),
            Candle(3,100.4,101.3,100.0,100.8,1000),
            Candle(4,100.8,103.0,100.7,102.8,3500)
        )
        assertNotNull(engine.evaluate(def,bars))
    }
}
