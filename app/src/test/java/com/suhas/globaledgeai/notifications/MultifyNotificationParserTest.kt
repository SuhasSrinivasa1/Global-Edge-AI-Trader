package com.suhas.globaledgeai.notifications

import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MultifyNotificationParserTest {
    @Test fun persistentCaptureStatusIsNoise(){
        val p=MultifyNotificationParser.parse(
            "Signal capture active",
            listOf("Listening for paid Multify equity notifications · shadow + app-owned live monitors ready"),
            ongoing=true
        )
        assertTrue(p.noise)
        assertEquals(MultifyEventType.UNKNOWN,p.eventType)
        assertTrue(p.symbol.isBlank())
    }

    @Test fun parsesBuyNseSymbolAndPrice(){
        val p=MultifyNotificationParser.parse("Multify paid signal",listOf("BUY NSE: TATAMOTORS @ 985.50"),ongoing=false)
        assertFalse(p.noise)
        assertEquals(MultifyEventType.ENTRY_LONG,p.eventType)
        assertEquals("TATAMOTORS",p.symbol)
        assertEquals(985.50,p.price,0.001)
        assertEquals(MultifyInstrumentClass.EQUITY,p.instrumentClass)
    }

    @Test fun parsesShortAndRejectsOptionAsEquity(){
        val short=MultifyNotificationParser.parse("Trade",listOf("SHORT RELIANCE CMP 2760"),false)
        assertEquals(MultifyEventType.ENTRY_SHORT,short.eventType)
        assertEquals("RELIANCE",short.symbol)
        assertEquals(MultifyInstrumentClass.EQUITY,short.instrumentClass)

        val option=MultifyNotificationParser.parse("Trade",listOf("BUY NIFTY 25000 CE @ 120"),false)
        assertEquals(MultifyInstrumentClass.DERIVATIVE_OR_NON_EQUITY,option.instrumentClass)
    }
}
