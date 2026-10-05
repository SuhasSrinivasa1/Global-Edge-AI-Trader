package com.suhas.globaledgeai.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AutomationPolicyTest {
    @Test fun heartbeatFreshnessIsBounded(){
        val now=1_000_000L
        assertTrue(AutomationPolicy.serviceHeartbeatFresh(now,now-120_000L))
        assertFalse(AutomationPolicy.serviceHeartbeatFresh(now,now-240_000L))
        assertFalse(AutomationPolicy.serviceHeartbeatFresh(now,0L))
    }

    @Test fun threePmPriorityStartsAt1510AndEndsAt1530(){
        assertFalse(AutomationPolicy.isThreePmPriorityWindow(LocalTime.of(15,9)))
        assertTrue(AutomationPolicy.isThreePmPriorityWindow(LocalTime.of(15,10)))
        assertTrue(AutomationPolicy.isThreePmPriorityWindow(LocalTime.of(15,20)))
        assertTrue(AutomationPolicy.isThreePmPriorityWindow(LocalTime.of(15,30)))
        assertFalse(AutomationPolicy.isThreePmPriorityWindow(LocalTime.of(15,31)))
    }

    @Test fun coldStrategyRequiresEnoughBadSameDayEvidence(){
        assertFalse(AutomationPolicy.strategySessionCold(3,0,-1.0))
        assertTrue(AutomationPolicy.strategySessionCold(4,1,-0.2))
        assertFalse(AutomationPolicy.strategySessionCold(4,2,-0.2))
        assertFalse(AutomationPolicy.strategySessionCold(4,1,0.1))
    }
}
