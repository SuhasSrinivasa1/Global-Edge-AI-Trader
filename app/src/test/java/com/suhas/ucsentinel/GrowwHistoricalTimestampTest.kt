package com.suhas.globaledgeai

import com.suhas.globaledgeai.data.remote.GrowwClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GrowwHistoricalTimestampTest {
    private val ist=ZoneId.of("Asia/Kolkata")

    @Test
    fun parsesGrowwBacktestingStringTimestamp(){
        val expected=LocalDateTime.of(2026,10,7,12,30,0).atZone(ist).toEpochSecond()
        assertEquals(expected,GrowwClient.parseHistoricalEpochSeconds("2026-10-07T12:30:00"))
        assertEquals(expected,GrowwClient.parseHistoricalEpochSeconds("2026-10-07 12:30:00"))
    }

    @Test
    fun preservesEpochSecondsAndNormalizesMilliseconds(){
        val seconds=1791356400L
        assertEquals(seconds,GrowwClient.parseHistoricalEpochSeconds(seconds))
        assertEquals(seconds,GrowwClient.parseHistoricalEpochSeconds(seconds*1000L))
        assertEquals(seconds,GrowwClient.parseHistoricalEpochSeconds(seconds.toString()))
    }
}
