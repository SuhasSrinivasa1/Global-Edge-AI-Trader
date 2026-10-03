package com.suhas.globaledgeai

import com.suhas.globaledgeai.data.remote.Totp
import org.junit.Assert.assertEquals
import org.junit.Test

class TotpTest {
    @Test
    fun generatesSixDigits() {
        val code = Totp.generate("JBSWY3DPEHPK3PXP", 0L)
        assertEquals(6, code.length)
        assert(code.all { it.isDigit() })
    }
}
