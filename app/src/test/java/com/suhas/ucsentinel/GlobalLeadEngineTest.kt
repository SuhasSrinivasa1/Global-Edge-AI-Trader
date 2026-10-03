package com.suhas.globaledgeai

import com.suhas.globaledgeai.domain.engine.GlobalLeadEngine
import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GlobalLeadEngineTest {
    private val engine=GlobalLeadEngine()
    private val mapping=GlobalCounterpart(
        indianSymbol="WIPRO",indianCompany="Wipro",foreignTicker="WIT",foreignCompany="Wipro ADR",
        exchange="NYSE",region="US",benchmarkTicker="SPY",mappingType=GlobalMappingType.EXACT_ADR,
        relationshipWeight=1.0
    )

    @Test fun preOpenForeignOnlyCanBecomeNextSession(){
        val ist=ZoneId.of("Asia/Kolkata")
        val now=ZonedDateTime.of(2026,9,22,7,30,0,0,ist)
        val previousClose=ZonedDateTime.of(2026,9,21,15,30,0,0,ist).toInstant().toEpochMilli()
        val foreign=GlobalQuoteSnapshot("WIT",96.0,100.0,98.0,99.0,95.0,2_000_000,1_000_000.0,previousClose+6*60*60*1000,previousClose+60_000,"2026-09-21")
        val c=engine.finalCandidate(mapping,foreign,null,null,null,null,AppSettings(),now,GlobalLeadDirection.SHORT)
        assertEquals(GlobalLeadAction.NEXT_OPEN_WATCH,c.action)
        assertTrue(c.score>=68.0)
        assertEquals(0.0,c.indianPrice,0.0)
    }
}
