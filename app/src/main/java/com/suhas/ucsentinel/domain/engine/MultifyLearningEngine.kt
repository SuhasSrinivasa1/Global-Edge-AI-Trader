package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import kotlin.math.abs

class MultifyLearningEngine {
    data class Features(
        val sessionVwap:Double,
        val anchoredVwap:Double,
        val volumeRatio:Double,
        val momentumPct:Double,
        val rsi:Double,
        val bullishCandle:Boolean,
        val bearishCandle:Boolean,
        val bullishEngulf:Boolean,
        val bearishEngulf:Boolean,
        val breakoutUp:Boolean,
        val breakoutDown:Boolean,
        val contextKey:String
    )

    fun features(quote:Quote,candles:List<Candle>,anchorEpochSeconds:Long=0L):Features{
        val all=candles.sortedBy{it.epochSeconds}
        val recent=all.takeLast(40)
        val last=recent.lastOrNull()
        val prev=recent.dropLast(1).lastOrNull()
        fun vwap(rows:List<Candle>):Double{
            val vol=rows.sumOf{it.volume.toDouble()}.coerceAtLeast(1.0)
            return rows.sumOf{((it.high+it.low+it.close)/3.0)*it.volume.toDouble()}/vol
        }
        val sessionVwap=vwap(all.ifEmpty{recent})
        val anchoredRows=if(anchorEpochSeconds>0L)all.filter{it.epochSeconds>=anchorEpochSeconds}.ifEmpty{recent} else recent
        val anchoredVwap=vwap(anchoredRows)
        // One-minute Multify lane: compare the latest minute with the preceding 20 minutes.
        val priorVol=recent.dropLast(1).takeLast(20).map{it.volume.toDouble()}.filter{it>0.0}
        val avgVol=priorVol.average().takeIf{it.isFinite()&&it>0.0}?:1.0
        val volumeRatio=(last?.volume?.toDouble()?:1.0)/avgVol
        val anchor=recent.getOrNull((recent.size-4).coerceAtLeast(0))?.close?:quote.lastPrice
        val momentum=if(anchor>0.0)(quote.lastPrice/anchor-1.0)*100.0 else 0.0
        val bullish=(last?.close?:quote.lastPrice)>=(last?.open?:quote.lastPrice)
        val bearish=(last?.close?:quote.lastPrice)<(last?.open?:quote.lastPrice)
        val bullishEngulf=if(last!=null&&prev!=null) bullish && prev.close<prev.open && last.open<=prev.close && last.close>=prev.open else false
        val bearishEngulf=if(last!=null&&prev!=null) bearish && prev.close>prev.open && last.open>=prev.close && last.close<=prev.open else false
        val prior=recent.dropLast(1).takeLast(12)
        val priorHigh=prior.maxOfOrNull{it.high}?:0.0
        val priorLow=prior.minOfOrNull{it.low}?:0.0
        val up=priorHigh>0.0 && quote.lastPrice>=priorHigh*0.999
        val down=priorLow>0.0 && quote.lastPrice<=priorLow*1.001
        val rsi=Indicators.rsi(recent,14)
        val context=listOf(
            if(volumeRatio>=1.8)"VOL_HIGH" else if(volumeRatio>=1.2)"VOL_MED" else "VOL_LOW",
            if(quote.lastPrice>=sessionVwap)"ABOVE_SESSION_VWAP" else "BELOW_SESSION_VWAP",
            if(quote.lastPrice>=anchoredVwap)"ABOVE_ALERT_VWAP" else "BELOW_ALERT_VWAP",
            if(momentum>=0.25)"MOM_UP" else if(momentum<=-0.25)"MOM_DOWN" else "MOM_FLAT",
            if(quote.dayChangePercent>=0.5)"DAY_UP" else if(quote.dayChangePercent<=-0.5)"DAY_DOWN" else "DAY_FLAT",
            when{bullishEngulf->"ENGULF_BULL";bearishEngulf->"ENGULF_BEAR";bullish->"GREEN";else->"RED"},
            when{up->"BREAK_UP";down->"BREAK_DOWN";else->"IN_RANGE"}
        ).joinToString("|")
        return Features(sessionVwap,anchoredVwap,volumeRatio.coerceAtMost(20.0),momentum.coerceIn(-20.0,20.0),rsi,bullish,bearish,bullishEngulf,bearishEngulf,up,down,context)
    }

    fun decide(eventId:String,symbol:String,eventType:MultifyEventType,quote:Quote,candles:List<Candle>,profile:MultifyStockProfile?,anchorEpochSeconds:Long=0L):MultifyDecision{
        val f=features(quote,candles,anchorEpochSeconds)
        val desired=when(eventType){
            MultifyEventType.ENTRY_LONG->MultifyShadowSide.LONG
            MultifyEventType.ENTRY_SHORT,MultifyEventType.EXIT->MultifyShadowSide.SHORT
            else->null
        }
        if(desired==null)return MultifyDecision(eventId,symbol,eventType,MultifyDecisionTier.NO_TRADE,null,0.0,quote.lastPrice,"NO_SIGNAL",f.contextKey,"Unrecognized Multify event")
        if(!ExecutionQuality.discoveryQuote(quote))return MultifyDecision(eventId,symbol,eventType,MultifyDecisionTier.NO_TRADE,desired,20.0,quote.lastPrice,"LIQUIDITY_WAIT",f.contextKey,"Discovery liquidity gate not satisfied")

        var score=if(eventType==MultifyEventType.EXIT)44.0 else 48.0
        val long=desired==MultifyShadowSide.LONG
        if(long){
            if(quote.lastPrice>=f.sessionVwap)score+=8 else score-=7
            if(quote.lastPrice>=f.anchoredVwap)score+=5 else score-=4
            if(f.momentumPct>=0.25)score+=10 else if(f.momentumPct<=-0.25)score-=10
            if(f.volumeRatio>=1.8)score+=14 else if(f.volumeRatio>=1.2)score+=7
            if(f.bullishCandle)score+=5
            if(f.bullishEngulf)score+=8
            if(f.breakoutUp)score+=8
            if(quote.dayChangePercent>=0.5)score+=5
            if(f.rsi in 52.0..78.0)score+=4
        }else{
            if(quote.lastPrice<=f.sessionVwap)score+=8 else score-=6
            if(quote.lastPrice<=f.anchoredVwap)score+=5 else score-=4
            if(f.momentumPct<=-0.25)score+=10 else if(f.momentumPct>=0.25)score-=8
            if(f.volumeRatio>=1.8)score+=14 else if(f.volumeRatio>=1.2)score+=7
            if(f.bearishCandle)score+=5
            if(f.bearishEngulf)score+=8
            if(f.breakoutDown)score+=8
            if(quote.dayChangePercent<=-0.5)score+=5
            if(f.rsi in 22.0..48.0)score+=4
            if(eventType==MultifyEventType.EXIT)score+=4 // learned hypothesis: exit often precedes a decay leg; still needs market confirmation
        }

        val champion=if(long)profile?.bestLongStrategy.orEmpty() else profile?.bestShortStrategy.orEmpty()
        val naturalTag=when{
            long&&f.breakoutUp&&f.volumeRatio>=1.5->"VOLUME_BREAKOUT_LONG"
            long&&quote.lastPrice>=f.sessionVwap&&f.momentumPct>0.0->"VWAP_MOMENTUM_LONG"
            long->"MULTIFY_REACTION_LONG"
            !long&&eventType==MultifyEventType.EXIT&&f.breakoutDown&&f.volumeRatio>=1.4->"EXIT_VOLUME_DECAY_SHORT"
            !long&&eventType==MultifyEventType.EXIT&&f.bearishEngulf->"EXIT_REVERSAL_SHORT"
            !long&&eventType==MultifyEventType.EXIT->"MULTIFY_EXIT_SHORT"
            !long&&f.breakoutDown&&f.volumeRatio>=1.5->"VOLUME_BREAKDOWN_SHORT"
            else->"MULTIFY_REACTION_SHORT"
        }
        val strategy=if(champion.isNotBlank() && championCompatible(champion,f,long)){score+=5;champion}else naturalTag
        score=score.coerceIn(0.0,100.0)
        val tier=when{
            score>=78.0->MultifyDecisionTier.LIVE
            score>=68.0->MultifyDecisionTier.DEVELOPING
            score>=58.0->MultifyDecisionTier.WATCH
            else->MultifyDecisionTier.NO_TRADE
        }
        val reason="${eventType} • ${if(long)"LONG" else "SHORT"} • RVOL ${"%.2f".format(f.volumeRatio)}x • momentum ${"%+.2f".format(f.momentumPct)}% • ${if(quote.lastPrice>=f.sessionVwap)"above" else "below"} session VWAP • ${if(quote.lastPrice>=f.anchoredVwap)"above" else "below"} alert VWAP • RSI ${"%.0f".format(f.rsi)}"+
            (if(champion.isNotBlank()&&strategy==champion)" • stock Champion reused" else "")
        return MultifyDecision(eventId,symbol,eventType,tier,desired,score,quote.lastPrice,strategy,f.contextKey,reason)
    }

    fun waveDecision(eventId:String,symbol:String,quote:Quote,candles:List<Candle>,profile:MultifyStockProfile?):MultifyDecision{
        val f=features(quote,candles)
        if(!ExecutionQuality.discoveryQuote(quote))return MultifyDecision(eventId,symbol,MultifyEventType.UNKNOWN,MultifyDecisionTier.NO_TRADE,null,20.0,quote.lastPrice,"WAVE_LIQUIDITY_WAIT",f.contextKey,"Wave discovery liquidity gate not satisfied")
        var longScore=42.0
        var shortScore=42.0
        if(quote.lastPrice>=f.sessionVwap){longScore+=10;shortScore-=5}else{shortScore+=10;longScore-=5}
        if(f.momentumPct>=0.25){longScore+=12;shortScore-=6}else if(f.momentumPct<=-0.25){shortScore+=12;longScore-=6}
        if(f.volumeRatio>=1.8){longScore+=8;shortScore+=8}else if(f.volumeRatio>=1.2){longScore+=4;shortScore+=4}
        if(f.bullishEngulf){longScore+=10}else if(f.bearishEngulf){shortScore+=10}
        if(f.breakoutUp)longScore+=10
        if(f.breakoutDown)shortScore+=10
        if(f.bullishCandle)longScore+=4 else shortScore+=4
        if(f.rsi in 52.0..78.0)longScore+=4
        if(f.rsi in 22.0..48.0)shortScore+=4
        val longChampion=profile?.bestLongStrategy.orEmpty();val shortChampion=profile?.bestShortStrategy.orEmpty()
        if(longChampion.isNotBlank()&&championCompatible(longChampion,f,true))longScore+=5
        if(shortChampion.isNotBlank()&&championCompatible(shortChampion,f,false))shortScore+=5
        val side=if(longScore>=shortScore)MultifyShadowSide.LONG else MultifyShadowSide.SHORT
        val score=maxOf(longScore,shortScore).coerceIn(0.0,100.0)
        val separation=abs(longScore-shortScore)
        val tier=when{
            score>=80.0&&separation>=6.0->MultifyDecisionTier.LIVE
            score>=70.0->MultifyDecisionTier.DEVELOPING
            score>=60.0->MultifyDecisionTier.WATCH
            else->MultifyDecisionTier.NO_TRADE
        }
        val champion=if(side==MultifyShadowSide.LONG)longChampion else shortChampion
        val natural=when{
            side==MultifyShadowSide.LONG&&f.breakoutUp&&f.volumeRatio>=1.4->"WAVE_BREAKOUT_LONG"
            side==MultifyShadowSide.LONG->"WAVE_VWAP_LONG"
            side==MultifyShadowSide.SHORT&&f.breakoutDown&&f.volumeRatio>=1.4->"WAVE_BREAKDOWN_SHORT"
            else->"WAVE_VWAP_SHORT"
        }
        val strategy=if(champion.isNotBlank()&&championCompatible(champion,f,side==MultifyShadowSide.LONG))champion else natural
        val reason="Intraday wave • ${side.name} • RVOL ${"%.2f".format(f.volumeRatio)}x • momentum ${"%+.2f".format(f.momentumPct)}% • score split L${longScore.toInt()}/S${shortScore.toInt()}"
        return MultifyDecision(eventId,symbol,MultifyEventType.UNKNOWN,tier,side,score,quote.lastPrice,strategy,f.contextKey,reason)
    }

    fun shouldClose(trade:MultifyShadowTrade,quote:Quote,candles:List<Candle>,nowMs:Long):Pair<String,MultifyShadowSide?>?{
        if(trade.status!=MultifyShadowStatus.OPEN||trade.entryPrice<=0.0||quote.lastPrice<=0.0)return null
        val f=features(quote,candles)
        val ret=directionalReturn(trade.side,trade.entryPrice,quote.lastPrice)
        val ageMin=(nowMs-trade.openedAt).coerceAtLeast(0L)/60_000.0
        val peakRet=if(trade.side==MultifyShadowSide.LONG)directionalReturn(trade.side,trade.entryPrice,maxOf(trade.peakPrice,quote.lastPrice)) else directionalReturn(trade.side,trade.entryPrice,minOf(trade.troughPrice,quote.lastPrice))
        val trailGiveback=(peakRet-ret).coerceAtLeast(0.0)
        if(ret<=-0.70)return "HARD_STOP ${"%+.2f".format(ret)}%" to null
        if(peakRet>=1.25&&trailGiveback>=0.25)return "TRAIL_025 peak ${"%+.2f".format(peakRet)}%" to null
        if(peakRet>=0.75&&trailGiveback>=0.38)return "TRAIL_038 peak ${"%+.2f".format(peakRet)}%" to null
        if(ret>=1.60)return "PROFIT_CAPTURE ${"%+.2f".format(ret)}%" to null
        if(ageMin>=35.0&&ret<0.20)return "TIME_DECAY ${"%+.2f".format(ret)}%" to null
        if(trade.side==MultifyShadowSide.LONG){
            val reversal=f.momentumPct<=-0.30&&quote.lastPrice<f.sessionVwap&&(f.volumeRatio>=1.25||f.bearishEngulf||f.breakoutDown)
            if(reversal)return "REVERSAL_SHORT • ${f.contextKey}" to MultifyShadowSide.SHORT
        }else{
            val reversal=f.momentumPct>=0.30&&quote.lastPrice>f.sessionVwap&&(f.volumeRatio>=1.25||f.bullishEngulf||f.breakoutUp)
            if(reversal)return "REVERSAL_LONG • ${f.contextKey}" to MultifyShadowSide.LONG
        }
        return null
    }

    fun directionalReturn(side:MultifyShadowSide,entry:Double,exit:Double):Double{
        if(entry<=0.0||exit<=0.0)return 0.0
        return if(side==MultifyShadowSide.LONG)(exit/entry-1.0)*100.0 else (entry/exit-1.0)*100.0
    }

    private fun championCompatible(tag:String,f:Features,long:Boolean):Boolean=when(tag){
        "VOLUME_BREAKOUT_LONG"->long&&f.breakoutUp&&f.volumeRatio>=1.3
        "VWAP_MOMENTUM_LONG"->long&&f.momentumPct>=0.0&&"ABOVE_SESSION_VWAP" in f.contextKey
        "EXIT_VOLUME_DECAY_SHORT"->!long&&f.breakoutDown&&f.volumeRatio>=1.2
        "EXIT_REVERSAL_SHORT"->!long&&(f.bearishEngulf||f.momentumPct<0.0)
        "VOLUME_BREAKDOWN_SHORT"->!long&&f.breakoutDown&&f.volumeRatio>=1.3
        "MULTIFY_REACTION_LONG"->long&&f.momentumPct>=-0.15
        "MULTIFY_REACTION_SHORT","MULTIFY_EXIT_SHORT"->!long&&f.momentumPct<=0.15
        else->false
    }
}
