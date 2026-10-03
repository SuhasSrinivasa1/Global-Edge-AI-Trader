package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.ExecutionQuality
import com.suhas.globaledgeai.domain.model.DepthLevel
import com.suhas.globaledgeai.domain.model.Ohlc
import com.suhas.globaledgeai.domain.model.Quote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionQualityTest {
    private fun quote(price:Double=100.0, volume:Long=100_000L, bid:Double=99.8, ask:Double=100.2)=Quote(
        symbol="TEST",lastPrice=price,previousClose=99.0,dayChangePercent=1.0,upperCircuit=120.0,lowerCircuit=80.0,
        volume=volume,totalBuyQuantity=10_000,totalSellQuantity=10_000,bidPrice=bid,bidQuantity=500,offerPrice=ask,offerQuantity=500,
        marketCap=1_000_000_000.0,week52High=120.0,week52Low=70.0,ohlc=Ohlc(99.0,101.0,98.0,100.0),
        buyDepth=listOf(DepthLevel(bid,500)),sellDepth=listOf(DepthLevel(ask,500)),lastTradeTime=System.currentTimeMillis()
    )

    @Test fun pennyStockIsRejected(){ assertFalse(ExecutionQuality.executableQuote(quote(price=0.15,volume=5_000_000))) }
    @Test fun lowVolumeIsRejected(){ assertFalse(ExecutionQuality.executableQuote(quote(volume=10_000))) }
    @Test fun wideSpreadIsRejected(){ assertFalse(ExecutionQuality.executableQuote(quote(bid=98.0,ask=102.0))) }
    @Test fun liquidTwoSidedQuotePasses(){ assertTrue(ExecutionQuality.executableQuote(quote())) }
    @Test fun flatPlanIsRejected(){ assertFalse(ExecutionQuality.usablePlan(0.15,0.15,0.15)) }
    @Test fun separatedPlanPasses(){ assertTrue(ExecutionQuality.usablePlan(100.0,98.5,102.0)) }
}
