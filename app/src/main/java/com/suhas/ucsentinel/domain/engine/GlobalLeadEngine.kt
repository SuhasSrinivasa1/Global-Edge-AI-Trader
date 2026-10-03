package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import java.time.*
import kotlin.math.max
import kotlin.math.min

class GlobalLeadEngine {
    data class ForeignScore(val score:Double,val excessPct:Double,val reasons:List<String>)

    fun inferDirection(foreign:GlobalQuoteSnapshot,benchmark:GlobalQuoteSnapshot?):GlobalLeadDirection{
        val bench=benchmark?.dayPct?:0.0
        val excess=foreign.dayPct-bench
        val directionalImpulse=foreign.dayPct*0.60+foreign.gapPct*0.25+excess*0.15
        return if(directionalImpulse<0)GlobalLeadDirection.SHORT else GlobalLeadDirection.LONG
    }

    fun foreignScore(mapping:GlobalCounterpart,foreign:GlobalQuoteSnapshot,benchmark:GlobalQuoteSnapshot?,direction:GlobalLeadDirection=inferDirection(foreign,benchmark)):ForeignScore{
        val sign=if(direction==GlobalLeadDirection.LONG)1.0 else -1.0
        val bench=benchmark?.dayPct?:0.0
        val rawExcess=foreign.dayPct-bench
        val directionalExcess=rawExcess*sign
        val directionalGap=foreign.gapPct*sign
        val directionalDay=foreign.dayPct*sign
        val directionalClose=if(direction==GlobalLeadDirection.LONG)foreign.closeLocation else 1.0-foreign.closeLocation
        var score=28.0*mapping.relationshipWeight
        val reasons=mutableListOf<String>()
        if(mapping.mappingType==GlobalMappingType.EXACT_ADR){score+=7;reasons+="Exact ADR / same underlying company"}
        else if(mapping.relationshipWeight>=0.9)reasons+="High-confidence listed parent relationship"

        if(directionalGap>0){score+=min(18.0,directionalGap*6.0);reasons+="Foreign ${if(direction==GlobalLeadDirection.LONG)"gap-up" else "gap-down"} ${fmt(kotlin.math.abs(foreign.gapPct))}%"}
        else score-=min(14.0,-directionalGap*5.0)
        if(directionalDay>0){score+=min(13.0,directionalDay*4.0);reasons+="Foreign move ${fmt(kotlin.math.abs(foreign.dayPct))}% ${if(direction==GlobalLeadDirection.LONG)"up" else "down"}"}
        else score-=min(16.0,-directionalDay*4.0)
        if(directionalExcess>0.25){score+=min(12.0,directionalExcess*4.5);reasons+="Directional benchmark excess ${fmt(directionalExcess)}%"}
        if(foreign.volumeRatio>1.15){score+=min(10.0,(foreign.volumeRatio-1.0)*7.0);reasons+="Abnormal volume ${fmt(foreign.volumeRatio)}x"}
        score+=(directionalClose-0.5)*12.0
        if(directionalClose>=0.72)reasons+=if(direction==GlobalLeadDirection.LONG)"Holding near session high" else "Holding near session low"
        return ForeignScore(score.coerceIn(0.0,100.0),rawExcess,reasons)
    }

    fun finalCandidate(
        mapping:GlobalCounterpart,foreign:GlobalQuoteSnapshot,benchmark:GlobalQuoteSnapshot?,indian:Quote?,
        priorIndianReturnPct:Double?,pressureScore:Double?,settings:AppSettings,now:ZonedDateTime,direction:GlobalLeadDirection=inferDirection(foreign,benchmark)
    ):GlobalLeadCandidate{
        val sign=if(direction==GlobalLeadDirection.LONG)1.0 else -1.0
        val fs=foreignScore(mapping,foreign,benchmark,direction);var score=fs.score;val reasons=fs.reasons.toMutableList()
        val priorDirectional=max(0.0,(priorIndianReturnPct?:0.0)*sign)
        val foreignDirectional=foreign.dayPct*sign
        val freshness=foreignDirectional-priorDirectional*0.65
        if(freshness>=1.0){score+=10;reasons+="Fresh foreign ${if(direction==GlobalLeadDirection.LONG)"strength" else "weakness"} vs prior India session"}
        else if(freshness>=0.35){score+=5}
        else if(freshness<=0.0){score-=12;reasons+="Foreign move may be catch-up to India"}

        val p=pressureScore?:0.0
        if(direction==GlobalLeadDirection.LONG&&p>=70){score+=min(10.0,(p-60.0)*0.35);reasons+="Indian pre-pressure confirms (${fmt(p)})"}

        val indianOpen=indian?.ohlc?.open?:0.0;val indianPrice=indian?.lastPrice?:0.0
        val indianFromOpen=if(indianOpen>0&&indianPrice>0)(indianPrice/indianOpen-1.0)*100.0 else 0.0
        val indianDay=indian?.dayChangePercent?:0.0
        val bsr=if((indian?.totalSellQuantity?:0)>0)(indian!!.totalBuyQuantity.toDouble()/indian.totalSellQuantity) else 0.0
        val directionalFromOpen=indianFromOpen*sign
        if(indian!=null){
            if(directionalFromOpen>=0.25){score+=min(8.0,directionalFromOpen*4.0);reasons+="India continuing ${fmt(kotlin.math.abs(indianFromOpen))}% ${if(direction==GlobalLeadDirection.LONG)"above" else "below"} open"}
            if(direction==GlobalLeadDirection.LONG&&bsr>=1.25){score+=min(6.0,(bsr-1.0)*3.0);reasons+="Indian buy/sell ${fmt(bsr)}x"}
            if(direction==GlobalLeadDirection.SHORT&&bsr in 0.01..0.80){score+=min(6.0,(1.0/bsr-1.0)*2.0);reasons+="Indian sell pressure dominates"}
            val openGap=if(indian.previousClose>0)(indianOpen/indian.previousClose-1.0)*100.0 else 0.0
            val directionalOpenGap=openGap*sign
            if(directionalOpenGap>=2.5&&directionalFromOpen<0.15){score-=13;reasons+="Opening gap already priced; weak continuation"}
            if(directionalFromOpen>=2.8){score-=8;reasons+="Already extended from open"}
        }

        val ist=ZoneId.of("Asia/Kolkata");val t=now.withZoneSameInstant(ist).toLocalTime();val date=now.withZoneSameInstant(ist).toLocalDate()
        val todayOpen=date.atTime(9,15).atZone(ist).toInstant().toEpochMilli()
        val previousClose=previousTradingDay(date).atTime(15,30).atZone(ist).toInstant().toEpochMilli()
        val latestIndiaCloseDate=when{
            date.dayOfWeek==DayOfWeek.SATURDAY||date.dayOfWeek==DayOfWeek.SUNDAY->previousTradingDay(date)
            t<LocalTime.of(9,15)->previousTradingDay(date)
            else->date
        }
        val latestIndiaClose=latestIndiaCloseDate.atTime(15,30).atZone(ist).toInstant().toEpochMilli()
        val signalFreshForToday=foreign.marketTimestamp>previousClose
        val newAfterTodayOpen=foreign.marketTimestamp>todayOpen
        if(!signalFreshForToday){score-=9;reasons+="Foreign signal is older than the latest India session boundary"}

        score=score.coerceIn(0.0,100.0)
        val foreignDirectionOk=foreignDirectional>0
        val overnightImpulse=maxOf(kotlin.math.abs(foreign.dayPct),kotlin.math.abs(foreign.gapPct),kotlin.math.abs(fs.excessPct))
        val action=when{
            // Before India opens there is no meaningful Indian bid/ask confirmation yet. Rank from the
            // completed/active foreign session and revalidate the Indian entry only after 09:15 IST.
            t<LocalTime.of(9,15) -> if(score>=68&&overnightImpulse>=settings.globalMinForeignGapPct&&signalFreshForToday)GlobalLeadAction.NEXT_OPEN_WATCH else GlobalLeadAction.OBSERVE
            t<LocalTime.of(9,30) -> if(score>=76&&signalFreshForToday&&directionalFromOpen>=0.20&&foreignDirectionOk)GlobalLeadAction.ENTER_AFTER_OPEN else if(score>=72&&signalFreshForToday)GlobalLeadAction.WAIT_FOR_CONFIRMATION else GlobalLeadAction.OBSERVE
            t<LocalTime.of(14,45) -> if(score>=76&&directionalFromOpen>=0.25&&foreignDirectionOk)GlobalLeadAction.ENTER_AFTER_OPEN else if(score>=68)GlobalLeadAction.WAIT_FOR_CONFIRMATION else GlobalLeadAction.OBSERVE
            t<=LocalTime.of(15,5) -> if(score>=76&&((direction==GlobalLeadDirection.LONG&&p>=70)||directionalFromOpen>=0.50||newAfterTodayOpen))GlobalLeadAction.KEEP_NEXT_SESSION else if(score<64||(!newAfterTodayOpen&&directionalFromOpen<0.10))GlobalLeadAction.EXIT_BY_3PM else GlobalLeadAction.OBSERVE
            else -> if(score>=76&&foreign.marketTimestamp>latestIndiaClose)GlobalLeadAction.NEXT_OPEN_WATCH else GlobalLeadAction.OBSERVE
        }
        val confidence=when{score>=84->ConfidenceBand.VERY_HIGH;score>=74->ConfidenceBand.HIGH;score>=62->ConfidenceBand.MEDIUM;else->ConfidenceBand.LOW}
        return GlobalLeadCandidate(
            rank=0,direction=direction,indianSymbol=mapping.indianSymbol,indianCompany=mapping.indianCompany,foreignTicker=mapping.foreignTicker,
            foreignCompany=mapping.foreignCompany,exchange=mapping.exchange,region=mapping.region,mappingType=mapping.mappingType,
            relationshipWeight=mapping.relationshipWeight,score=score,confidence=confidence,action=action,foreignGapPct=foreign.gapPct,
            foreignDayPct=foreign.dayPct,foreignFromOpenPct=foreign.fromOpenPct,foreignExcessPct=fs.excessPct,
            foreignVolumeRatio=foreign.volumeRatio,foreignCloseLocation=foreign.closeLocation,foreignSignalAt=foreign.marketTimestamp,
            indianPrice=indianPrice,indianOpen=indianOpen,indianFromOpenPct=indianFromOpen,indianDayPct=indianDay,indianBuySellRatio=bsr,
            freshnessPct=freshness,pressureConfirmationScore=p,expectedTargetPct=settings.globalContinuationTargetPct,reasons=reasons.take(6),officialSource=mapping.officialSource
        )
    }

    private fun previousTradingDay(d:LocalDate):LocalDate{var x=d.minusDays(1);while(x.dayOfWeek==DayOfWeek.SATURDAY||x.dayOfWeek==DayOfWeek.SUNDAY)x=x.minusDays(1);return x}
    private fun fmt(v:Double)=String.format("%.2f",v)
}
