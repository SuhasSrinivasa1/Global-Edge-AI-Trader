package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MultifyLearningEngineTest {
    private val engine=MultifyLearningEngine()

    private fun quote(price:Double,day:Double=2.0,volume:Long=500_000)=Quote(
        symbol="ABC",lastPrice=price,previousClose=100.0,dayChangePercent=day,upperCircuit=120.0,lowerCircuit=80.0,volume=volume,
        totalBuyQuantity=100_000,totalSellQuantity=80_000,bidPrice=price-0.05,bidQuantity=10_000,offerPrice=price+0.05,offerQuantity=10_000,
        marketCap=0.0,week52High=0.0,week52Low=0.0,ohlc=Ohlc(100.0,price+1,99.0,price),buyDepth=emptyList(),sellDepth=emptyList(),lastTradeTime=System.currentTimeMillis()
    )

    private fun bullishCandles():List<Candle> = (0 until 12).map{i->
        val open=100.0+i*0.18;val close=open+0.25;Candle(1_700_000_000L+i*300,open,close+0.12,open-0.08,close,if(i==11)90_000 else 25_000)
    }

    private fun bearishCandles():List<Candle> = (0 until 12).map{i->
        val open=104.0-i*0.18;val close=open-0.25;Candle(1_700_000_000L+i*300,open,open+0.08,close-0.12,close,if(i==11)100_000 else 25_000)
    }

    @Test fun multifyBuyProducesLongResearchDirection(){
        val d=engine.decide("e1","ABC",MultifyEventType.ENTRY_LONG,quote(103.0),bullishCandles(),null)
        assertEquals(MultifyShadowSide.LONG,d.direction)
        assertTrue(d.tier==MultifyDecisionTier.LIVE||d.tier==MultifyDecisionTier.DEVELOPING)
        assertTrue(d.score>=68.0)
    }

    @Test fun multifyExitTestsShortHypothesis(){
        val d=engine.decide("e2","ABC",MultifyEventType.EXIT,quote(101.0,-1.2),bearishCandles(),null)
        assertEquals(MultifyShadowSide.SHORT,d.direction)
        assertTrue(d.score>=58.0)
    }

    @Test fun directionalReturnHandlesShort(){
        assertEquals(2.0,engine.directionalReturn(MultifyShadowSide.SHORT,100.0,98.0),0.05)
        assertEquals(2.0,engine.directionalReturn(MultifyShadowSide.LONG,100.0,102.0),0.05)
    }
}
