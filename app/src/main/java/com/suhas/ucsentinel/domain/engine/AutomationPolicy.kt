package com.suhas.globaledgeai.domain.engine

import java.time.LocalTime

object AutomationPolicy {
    const val SERVICE_HEARTBEAT_FRESH_MS=3L*60_000L

    fun serviceHeartbeatFresh(nowMs:Long,heartbeatAt:Long,maxAgeMs:Long=SERVICE_HEARTBEAT_FRESH_MS):Boolean =
        heartbeatAt>0L && nowMs>=heartbeatAt && nowMs-heartbeatAt<=maxAgeMs

    fun isThreePmPriorityWindow(time:LocalTime):Boolean =
        time>=LocalTime.of(15,10) && time<=LocalTime.of(15,30)

    fun strategySessionCold(observations:Int,wins:Int,avgReturnPct:Double):Boolean {
        if(observations<4)return false
        val hitRate=wins.coerceIn(0,observations)*100.0/observations
        return hitRate<35.0 && avgReturnPct<=0.0
    }
}
