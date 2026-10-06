package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class StrategyGovernanceV2Test {
    private val engine=StrategyGovernanceV2()
    private val def=TradingStrategyDefinition("x","X","TEST","TEST","","",1)
    private val zone=ZoneId.of("Asia/Kolkata")

    private fun row(day:LocalDate,win:Boolean,source:StrategyLearningSource=StrategyLearningSource.LIVE_V2,raw:Double=80.0)=StrategyLearningObservation(
        eventId="${day}-$win-${source.name}-${Math.random()}",strategyId="x",strategyName="X",symbol="ABC",direction=TradeDirection.LONG,
        source=source,sessionBand="OPEN",regime=MarketRegime.TREND_UP,rawScore=raw,calibratedProbabilityPct=0.0,
        entryPrice=100.0,targetPct=1.0,stopPct=0.5,openedAt=day.atTime(9,30).atZone(zone).toInstant().toEpochMilli(),
        closedAt=day.atTime(10,15).atZone(zone).toInstant().toEpochMilli(),sessionDate=day.toString(),
        outcome=if(win)StrategyLearningOutcome.WIN else StrategyLearningOutcome.LOSS,returnPct=if(win)1.0 else -0.5,
        rMultiple=if(win)2.0 else -1.0
    )

    @Test fun correlatedSameDayCallsCannotCreateChampion(){
        val date=LocalDate.of(2026,10,8)
        val rows=(1..60).map{row(date.minusDays(1),it<=48)}
        val roster=engine.buildFrozenRoster(date,listOf(def),rows,emptyList(),AppSettings(strategyMinChampionAccuracy=55.0))
        val x=roster.first{it.direction==TradeDirection.LONG&&it.sessionBand=="OPEN"&&it.regime==MarketRegime.TREND_UP}
        assertNotEquals(StrategyStatus.CHAMPION,x.status)
        assertEquals(1,x.distinctSessions)
    }

    @Test fun strongMultiSessionLiveEvidenceCanBecomeChampion(){
        val date=LocalDate.of(2026,10,20)
        val rows=buildList{
            for(d in 1..10){
                val day=date.minusDays(d.toLong())
                repeat(4){i->add(row(day,i<3))}
            }
        }
        val roster=engine.buildFrozenRoster(date,listOf(def),rows,emptyList(),AppSettings(strategyMinChampionAccuracy=55.0,strategyMinChampionSamples=30))
        val x=roster.first{it.direction==TradeDirection.LONG&&it.sessionBand=="OPEN"&&it.regime==MarketRegime.TREND_UP}
        assertEquals(StrategyStatus.CHAMPION,x.status)
        assertTrue(x.distinctSessions>=8)
        assertTrue(x.expectancyR>0.0)
    }

    @Test fun shadowAndLiveAccuracyRemainSeparate(){
        val date=LocalDate.of(2026,10,20)
        val live=(1..10).map{row(date.minusDays(it.toLong()),it<=4)}
        val shadow=(1..30).map{row(date.minusDays((it%8+1).toLong()),true,StrategyLearningSource.SHADOW_V2)}
        val roster=engine.buildFrozenRoster(date,listOf(def),live+shadow,emptyList(),AppSettings())
        val x=roster.first{it.direction==TradeDirection.LONG&&it.sessionBand=="OPEN"&&it.regime==MarketRegime.TREND_UP}
        assertTrue(x.shadowHitRatePct>x.hitRatePct)
        assertEquals(10,x.samples)
        assertEquals(30,x.shadowSamples)
    }
}
