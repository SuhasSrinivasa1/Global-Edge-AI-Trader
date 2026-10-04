package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GlobalLeadSessionGateTest {
    private val engine=GlobalLeadEngine()
    private val mapping=GlobalCounterpart(
        indianSymbol="TEST",indianCompany="Test India",foreignTicker="TST",foreignCompany="Test Foreign",
        exchange="NYSE",region="US",benchmarkTicker="SPY",mappingType=GlobalMappingType.EXACT_ADR,relationshipWeight=1.0
    )

    @Test
    fun weekendStrongForeignSignalStaysNextResearchOnly(){
        val foreign=GlobalQuoteSnapshot(
            ticker="TST",last=108.0,previousClose=100.0,open=105.0,high=109.0,low=104.0,
            volume=2_000_000,averageVolume20=1_000_000.0,
            marketTimestamp=ZonedDateTime.of(2026,10,2,20,0,0,0,ZoneId.of("UTC")).toInstant().toEpochMilli(),
            sessionOpenTimestamp=ZonedDateTime.of(2026,10,2,13,30,0,0,ZoneId.of("UTC")).toInstant().toEpochMilli(),
            sessionDate="2026-10-02"
        )
        val now=ZonedDateTime.of(2026,10,4,12,0,0,0,ZoneId.of("Asia/Kolkata"))
        val c=engine.finalCandidate(mapping,foreign,null,null,null,null,AppSettings(),now,GlobalLeadDirection.LONG)
        assertFalse(c.action==GlobalLeadAction.ENTER_AFTER_OPEN || c.action==GlobalLeadAction.KEEP_NEXT_SESSION)
        assertEquals(GlobalLeadAction.NEXT_OPEN_WATCH,c.action)
    }
}
