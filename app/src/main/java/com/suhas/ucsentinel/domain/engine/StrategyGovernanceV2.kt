package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import java.time.*
import kotlin.math.*

class StrategyGovernanceV2 {
    companion object{
        const val MODEL_VERSION="STRAT-GOV-V2-1.7.0"
        val SESSION_BANDS=listOf("OPEN","MID","LATE")
    }

    private data class Stat(
        val n:Int=0,val wins:Int=0,val days:Int=0,val hit:Double=0.0,val recentHit:Double=0.0,
        val expectancyR:Double=0.0,val recentExpectancyR:Double=0.0,val floor:Double=0.0,
        val holdoutHit:Double=0.0,val holdoutExpectancyR:Double=0.0,val effectiveN:Int=0
    )

    fun sessionBand(time:LocalTime):String=when{
        time<LocalTime.of(10,0)->"OPEN"
        time<LocalTime.of(14,15)->"MID"
        else->"LATE"
    }

    fun buildFrozenRoster(
        sessionDate:LocalDate,
        definitions:List<TradingStrategyDefinition>,
        observations:List<StrategyLearningObservation>,
        priors:List<StrategyLegacyPrior>,
        settings:AppSettings,
        zone:ZoneId=ZoneId.of("Asia/Kolkata"),
        frozenAt:Long=System.currentTimeMillis()
    ):List<StrategyRosterDecision>{
        val cutoff=sessionDate.atTime(NseTradingCalendar2026.open).atZone(zone).toInstant().toEpochMilli()
        val eligibleHistory=observations.filter{
            it.closedAt>0L && it.closedAt<cutoff &&
                it.outcome in setOf(StrategyLearningOutcome.WIN,StrategyLearningOutcome.LOSS)
        }
        val priorMap=priors.associateBy{it.strategyId}
        return buildList{
            for(d in definitions){
                for(direction in TradeDirection.entries){
                    for(band in SESSION_BANDS){
                        for(regime in MarketRegime.entries){
                            add(decision(sessionDate,d,direction,band,regime,eligibleHistory,priorMap[d.id],settings,frozenAt))
                        }
                    }
                }
            }
        }
    }

    private fun decision(
        date:LocalDate,def:TradingStrategyDefinition,direction:TradeDirection,band:String,regime:MarketRegime,
        history:List<StrategyLearningObservation>,prior:StrategyLegacyPrior?,settings:AppSettings,frozenAt:Long
    ):StrategyRosterDecision{
        val same=history.filter{it.strategyId==def.id&&it.direction==direction}
        val v2Live=same.filter{!it.legacy&&it.source in setOf(StrategyLearningSource.LIVE_V2,StrategyLearningSource.COMPONENT_CREDIT)}
        val legacyLive=same.filter{it.legacy||it.source==StrategyLearningSource.LEGACY_LIVE}
        val shadow=same.filter{!it.legacy&&it.source==StrategyLearningSource.SHADOW_V2}

        val live=stats(v2Live,band,regime)
        val legacy=stats(legacyLive,band,regime)
        val sh=stats(shadow,band,regime)

        val priorN=prior?.observations?.coerceAtLeast(0)?:0
        val priorHit=if(priorN>0)(prior!!.wins.coerceIn(0,priorN)*100.0/priorN) else 0.0
        val priorAvg=prior?.avgReturnPct?:0.0
        val legacyEvidenceN=legacy.n+priorN
        val legacyHit=when{
            legacy.n>0&&priorN>0->(legacy.hit*legacy.n+priorHit*min(priorN,20))/(legacy.n+min(priorN,20))
            legacy.n>0->legacy.hit
            else->priorHit
        }
        val legacyPositive=legacyEvidenceN>=20&&legacyHit>=58.0&&
            ((legacy.n>0&&legacy.expectancyR>0.05)||(priorN>=20&&priorAvg>0.08))

        val championMin=settings.strategyMinChampionAccuracy.coerceIn(52.0,65.0)
        val decayed=live.n>=12&&(
            (live.recentHit<32.0&&live.recentExpectancyR<0.0) ||
            live.recentExpectancyR<=-0.20
        )
        val champion=live.n>=settings.strategyMinChampionSamples.coerceAtLeast(30)&&live.days>=8&&
            live.hit>=championMin&&live.expectancyR>=0.10&&live.floor>=40.0&&
            live.recentHit>=42.0&&live.recentExpectancyR>=0.0&&
            live.holdoutHit>=45.0&&live.holdoutExpectancyR>=0.0&&!decayed
        val liveQualified=live.n>=15&&live.days>=5&&live.hit>=48.0&&live.expectancyR>=0.05&&
            live.floor>=30.0&&live.recentHit>=38.0&&live.recentExpectancyR>=-0.05&&!decayed
        val shadowQualified=sh.n>=24&&sh.days>=6&&sh.hit>=55.0&&sh.expectancyR>=0.10&&
            sh.holdoutHit>=50.0&&sh.holdoutExpectancyR>0.0
        val clearlyBad=(live.n>=10&&live.expectancyR<=-0.05)||(legacyEvidenceN>=15&&legacyHit<35.0&&priorAvg<=0.0)
        val status=when{
            decayed->StrategyStatus.SUSPENDED
            champion->StrategyStatus.CHAMPION
            liveQualified||shadowQualified||legacyPositive->StrategyStatus.QUALIFIED
            clearlyBad->StrategyStatus.PROBATION
            else->StrategyStatus.CHALLENGER
        }

        val base=calibratedBase(live,sh,legacy,prior)
        val note=buildString{
            append("LIVE ${live.wins}/${live.n} across ${live.days} sessions • exp ${fmt(live.expectancyR)}R")
            append(" • SHADOW ${sh.wins}/${sh.n} ${fmt(sh.hit)}%")
            if(legacyEvidenceN>0)append(" • legacy prior n=$legacyEvidenceN")
            append(" • promotion frozen before open")
        }
        return StrategyRosterDecision(
            date.toString(),def.id,def.name,direction,band,regime,status,
            live.n,live.wins,live.days,live.hit,live.recentHit,live.expectancyR,live.floor,
            sh.n,sh.hit,base,frozenAt,note
        )
    }

    fun calibrate(rawScore:Double,decision:StrategyRosterDecision):Double{
        val rawModifier=((rawScore.coerceIn(55.0,100.0)-75.0)/5.0).coerceIn(-3.0,3.0)
        val maturityPenalty=when(decision.status){
            StrategyStatus.CHAMPION->0.0
            StrategyStatus.QUALIFIED->-1.0
            else->-8.0
        }
        return (decision.calibratedBasePct+rawModifier+maturityPenalty).coerceIn(25.0,85.0)
    }

    fun expectedR(probabilityPct:Double,targetPct:Double,stopPct:Double,roundTripCostPct:Double=0.12):Double{
        if(stopPct<=0.0||targetPct<=0.0)return -9.0
        val p=(probabilityPct/100.0).coerceIn(0.0,1.0)
        val rewardR=targetPct/stopPct
        val frictionR=roundTripCostPct/stopPct
        return p*rewardR-(1.0-p)-frictionR
    }

    fun productionEligible(d:StrategyRosterDecision):Boolean=
        d.status==StrategyStatus.CHAMPION||d.status==StrategyStatus.QUALIFIED

    fun performanceRows(
        definitions:List<TradingStrategyDefinition>,
        roster:List<StrategyRosterDecision>,
        band:String,
        regime:MarketRegime
    ):List<StrategyPerformance>{
        fun rank(s:StrategyStatus)=when(s){
            StrategyStatus.CHAMPION->0
            StrategyStatus.QUALIFIED->1
            StrategyStatus.ACTIVE->2
            StrategyStatus.CHALLENGER->3
            StrategyStatus.PROBATION->4
            StrategyStatus.SUSPENDED->5
        }
        return definitions.map{d->
            val choices=roster.filter{it.strategyId==d.id&&it.sessionBand==band&&it.regime==regime}
            val x=choices.minWithOrNull(compareBy<StrategyRosterDecision>{rank(it.status)}.thenByDescending{it.expectancyR}.thenByDescending{it.hitRatePct})
                ?:StrategyRosterDecision("",d.id,d.name,TradeDirection.LONG,band,regime,StrategyStatus.CHALLENGER,0,0,0,0.0,0.0,0.0,0.0,0,0.0,50.0,0L)
            StrategyPerformance(
                d.id,d.name,x.samples,x.wins,x.hitRatePct,
                avgReturnPct=x.expectancyR,expectancyPct=x.expectancyR,maxDrawdownPct=0.0,
                confidenceFloorPct=x.confidenceFloorPct,status=x.status,recentAccuracyPct=x.recentHitRatePct,
                distinctSessions=x.distinctSessions,shadowAccuracyPct=x.shadowHitRatePct,netExpectancyR=x.expectancyR,
                contextLabel="$band • ${regime.name.replace('_',' ')} • ${x.direction.name}"
            )
        }.sortedWith(compareBy<StrategyPerformance>{rank(it.status)}.thenByDescending{it.netExpectancyR}.thenByDescending{it.accuracyPct})
    }

    fun insights(roster:List<StrategyRosterDecision>,band:String,regime:MarketRegime,limit:Int=8):List<String>{
        val rows=roster.filter{it.sessionBand==band&&it.regime==regime}
            .sortedWith(compareBy<StrategyRosterDecision>{statusRank(it.status)}.thenByDescending{it.expectancyR}.thenByDescending{it.hitRatePct})
        return rows.take(limit).map{
            "${it.status} • ${it.strategyName} ${it.direction} • LIVE ${it.wins}/${it.samples} (${fmt(it.hitRatePct)}%) • recent ${fmt(it.recentHitRatePct)}% • exp ${fmt(it.expectancyR)}R • days ${it.distinctSessions} • shadow ${fmt(it.shadowHitRatePct)}%"
        }
    }

    private fun statusRank(s:StrategyStatus)=when(s){
        StrategyStatus.CHAMPION->0
        StrategyStatus.QUALIFIED->1
        StrategyStatus.ACTIVE->2
        StrategyStatus.CHALLENGER->3
        StrategyStatus.PROBATION->4
        StrategyStatus.SUSPENDED->5
    }

    private fun calibratedBase(live:Stat,shadow:Stat,legacy:Stat,prior:StrategyLegacyPrior?):Double{
        val selected=when{
            live.n>=8->live.hit to live.effectiveN
            shadow.n>=12->shadow.hit to shadow.effectiveN
            legacy.n>=8->legacy.hit to min(legacy.effectiveN,10)
            prior!=null&&prior.observations>=10->(prior.wins*100.0/prior.observations) to min(10,sqrt(prior.observations.toDouble()).roundToInt())
            else->50.0 to 0
        }
        val n=selected.second.coerceAtLeast(0)
        val pseudoWins=selected.first/100.0*n
        return ((pseudoWins+2.0)/(n+4.0)*100.0).coerceIn(30.0,80.0)
    }

    private fun stats(rows:List<StrategyLearningObservation>,band:String,regime:MarketRegime):Stat{
        val filtered=rows.filter{it.outcome==StrategyLearningOutcome.WIN||it.outcome==StrategyLearningOutcome.LOSS}
        if(filtered.isEmpty())return Stat()
        data class W(val o:StrategyLearningObservation,val w:Double)
        val weighted=filtered.mapNotNull{o->
            val contextWeight=when{
                o.sessionBand==band&&o.regime==regime->1.0
                o.sessionBand==band->0.35
                o.regime==regime->0.20
                else->0.10
            }
            val w=contextWeight*o.sampleWeight.coerceIn(0.05,1.0)
            if(w<=0.0)null else W(o,w)
        }
        if(weighted.isEmpty())return Stat()
        val perDay=weighted.groupBy{it.o.sessionDate.ifBlank{"UNKNOWN"}}.mapValues{(_,dayRows)->
            val total=dayRows.sumOf{it.w}.coerceAtLeast(1e-9)
            val hit=dayRows.sumOf{it.w*(if(it.o.outcome==StrategyLearningOutcome.WIN)1.0 else 0.0)}/total
            val er=dayRows.sumOf{it.w*it.o.rMultiple}/total
            hit to er
        }
        val hit=perDay.values.map{it.first}.average()*100.0
        val expectancy=perDay.values.map{it.second}.average()
        val chronological=weighted.sortedBy{if(it.o.closedAt>0)it.o.closedAt else it.o.openedAt}
        val recent=chronological.takeLast(min(20,chronological.size))
        val rw=recent.sumOf{it.w}.coerceAtLeast(1e-9)
        val recentHit=recent.sumOf{it.w*(if(it.o.outcome==StrategyLearningOutcome.WIN)1.0 else 0.0)}/rw*100.0
        val recentExp=recent.sumOf{it.w*it.o.rMultiple}/rw
        val days=perDay.size
        val effectiveN=min(filtered.size,max(1,days*3))
        val effectiveWins=(hit/100.0*effectiveN).roundToInt().coerceIn(0,effectiveN)
        val floor=wilsonLower(effectiveWins,effectiveN)*100.0

        val orderedDays=weighted.map{it.o.sessionDate}.filter{it.isNotBlank()}.distinct()
        val holdoutDayCount=max(1,ceil(orderedDays.size*0.25).toInt())
        val holdoutDays=orderedDays.takeLast(holdoutDayCount).toSet()
        val holdout=weighted.filter{it.o.sessionDate in holdoutDays}
        val hw=holdout.sumOf{it.w}.coerceAtLeast(1e-9)
        val holdoutHit=holdout.sumOf{it.w*(if(it.o.outcome==StrategyLearningOutcome.WIN)1.0 else 0.0)}/hw*100.0
        val holdoutExp=holdout.sumOf{it.w*it.o.rMultiple}/hw
        return Stat(filtered.size,filtered.count{it.outcome==StrategyLearningOutcome.WIN},days,hit,recentHit,expectancy,recentExp,floor,holdoutHit,holdoutExp,effectiveN)
    }

    private fun wilsonLower(wins:Int,n:Int,z:Double=1.96):Double{
        if(n<=0)return 0.0
        val p=wins.toDouble()/n
        val den=1.0+z*z/n
        val center=p+z*z/(2.0*n)
        val margin=z*sqrt((p*(1-p)+z*z/(4.0*n))/n)
        return ((center-margin)/den).coerceIn(0.0,1.0)
    }

    private fun fmt(v:Double)="%.1f".format(v)
}
