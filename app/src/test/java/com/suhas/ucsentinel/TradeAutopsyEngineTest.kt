package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.MarketRegimeClassifier
import com.suhas.globaledgeai.domain.engine.TradeAutopsyEngine
import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeAutopsyEngineTest {
    @Test fun classifiesTrendDown(){
        val bars=(0 until 12).map{i->GlobalBar(1_000L+i*300,100.0-i*0.15,100.2-i*0.15,99.7-i*0.15,99.9-i*0.15,1000)}
        val r=MarketRegimeClassifier().classify(bars,bars,bars)
        assertEquals(MarketRegime.TREND_DOWN,r.regime)
    }

    @Test fun losingLongCanIdentifyIndexReversalAndShadowFilter(){
        val start=1_700_000_000_000L
        val candles=buildList{
            for(i in 0 until 12)add(Candle(start/1000-3600+i*300,100.0,100.2,99.8,100.0+i*0.02,1000))
            for(i in 0 until 10)add(Candle(start/1000+i*300,100.0-i*0.25,100.1-i*0.25,99.6-i*0.25,99.8-i*0.25,900))
        }
        val idx=(0 until 12).map{i->GlobalBar(start/1000+i*300,100.0-i*0.15,100.1-i*0.15,99.7-i*0.15,99.8-i*0.15,1000)}
        val a=TradeAutopsyEngine().analyze(TradeAutopsyEngine.Input(
            sourceId="x",engineLabel="GLOBAL",symbol="TEST",outcome="LOSS",originalReturnPct=-1.0,direction=TradeDirection.LONG,
            entryPrice=100.0,stopPrice=99.0,targetPrice=102.0,openedAt=start,executionStartAt=start,closedAt=start+45*60_000L,sessionDate="2026-09-22",
            stockCandles=candles,niftyBars=idx,sensexBars=idx,bankBars=idx,indiaVix=null,spy=null,globalVix=null,news=emptyList()
        ))
        assertTrue(a.dominantCause==AutopsyCause.MARKET_REVERSAL||a.dominantCause==AutopsyCause.INDEX_DIVERGENCE)
        assertTrue(a.shadowResults.any{it.outcome==ShadowOutcome.AVOIDED_LOSS})
    }
}
