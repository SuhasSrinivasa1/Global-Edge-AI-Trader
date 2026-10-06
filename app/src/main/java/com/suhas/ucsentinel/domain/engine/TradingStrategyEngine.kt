package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import kotlin.math.*

class TradingStrategyEngine{
    data class Eval(val direction:TradeDirection,val score:Double,val targetPct:Double,val stopPct:Double,val evidence:String)

    private fun evaluateEarly(def:TradingStrategyDefinition,candles:List<Candle>,previousSessionClose:Double?=null,sameSlotHistoricalVolumes:List<Long> = emptyList()):Eval?{
        if(candles.size<2)return null
        val c=candles.last();val p=candles[candles.lastIndex-1]
        if(!c.open.isFinite()||!c.high.isFinite()||!c.low.isFinite()||!c.close.isFinite()||c.close<=0.0)return null
        val intradayVol=candles.dropLast(1).map{it.volume.toDouble()}.average().takeIf{it.isFinite()&&it>0.0}?:1.0
        val slotVol=sameSlotHistoricalVolumes.filter{it>0L}.map{it.toDouble()}.average().takeIf{it.isFinite()&&it>0.0}
        val rvol=(c.volume/(slotVol?:intradayVol)).takeIf{it.isFinite()}?:0.0
        val totalVol=candles.sumOf{it.volume.toDouble()}.coerceAtLeast(1.0)
        val vwap=(candles.sumOf{((it.high+it.low+it.close)/3.0)*it.volume}/totalVol).takeIf{it.isFinite()}?:c.close
        val green=c.close>c.open;val red=c.close<c.open
        val body=abs(c.close-c.open).coerceAtLeast(c.close*0.0001)
        val upperWick=(c.high-max(c.open,c.close)).coerceAtLeast(0.0)
        val lowerWick=(min(c.open,c.close)-c.low).coerceAtLeast(0.0)
        val prevRange=(p.high-p.low).coerceAtLeast(p.close*0.0005)
        val curRange=(c.high-c.low).coerceAtLeast(c.close*0.0005)
        fun mk(d:TradeDirection,score:Double,why:String,target:Double=0.8,stop:Double=0.55)=
            Eval(d,score.coerceIn(0.0,100.0),target,stop,"EARLY-OPEN ${candles.size}-bar • $why")
        return when(def.kind){
            "ORB_RVOL"->when{
                c.close>p.high&&rvol>=0.75->mk(TradeDirection.LONG,78.0+min(8.0,(rvol-0.75)*6.0),"opening-range expansion • RVOL %.2fx".format(rvol))
                c.close<p.low&&rvol>=0.75->mk(TradeDirection.SHORT,78.0+min(8.0,(rvol-0.75)*6.0),"opening-range breakdown • RVOL %.2fx".format(rvol))
                else->null
            }
            "VWAP_RECLAIM"->when{
                p.close<=vwap&&c.close>vwap&&green&&rvol>=0.70->mk(TradeDirection.LONG,76.0,"early VWAP reclaim • RVOL %.2fx".format(rvol))
                p.close>=vwap&&c.close<vwap&&red&&rvol>=0.70->mk(TradeDirection.SHORT,76.0,"early VWAP loss • RVOL %.2fx".format(rvol))
                else->null
            }
            "ENGULFING"->{
                val bull=green&&p.close<p.open&&c.open<=p.close&&c.close>=p.open
                val bear=red&&p.close>p.open&&c.open>=p.close&&c.close<=p.open
                when{bull->mk(TradeDirection.LONG,76.0,"bullish engulfing at the open");bear->mk(TradeDirection.SHORT,76.0,"bearish engulfing at the open");else->null}
            }
            "HAMMER"->when{
                lowerWick>body*2.0&&upperWick<body&&green->mk(TradeDirection.LONG,73.5,"bullish rejection candle")
                upperWick>body*2.0&&lowerWick<body&&red->mk(TradeDirection.SHORT,73.5,"bearish rejection candle")
                else->null
            }
            "ATR_BREAKOUT"->when{
                curRange>=prevRange*1.15&&green&&c.close>p.high->mk(TradeDirection.LONG,77.0,"range expansion above prior 5-minute high")
                curRange>=prevRange*1.15&&red&&c.close<p.low->mk(TradeDirection.SHORT,77.0,"range expansion below prior 5-minute low")
                else->null
            }
            "GAP_GO"->{
                val prevClose=previousSessionClose?.takeIf{it>0.0}?:return null
                val gap=(candles.first().open/prevClose-1.0)*100.0
                when{
                    gap>=0.60&&c.close>vwap&&green->mk(TradeDirection.LONG,78.0+min(8.0,gap),"true prior-session gap + VWAP hold • gap %.2f%% • RVOL %.2fx".format(gap,rvol))
                    gap<=-0.60&&c.close<vwap&&red->mk(TradeDirection.SHORT,78.0+min(8.0,abs(gap)),"true prior-session gap-down + VWAP reject • gap %.2f%% • RVOL %.2fx".format(gap,rvol))
                    else->null
                }
            }
            else->null
        }
    }

    fun evaluate(def:TradingStrategyDefinition,candles:List<Candle>,previousSessionClose:Double?=null,sameSlotHistoricalVolumes:List<Long> = emptyList()):Eval?{
        if(candles.size<2)return null
        if(candles.size<4)return evaluateEarly(def,candles,previousSessionClose,sameSlotHistoricalVolumes)
        val c=candles.last();val p=candles[candles.lastIndex-1]
        if(!c.open.isFinite()||!c.high.isFinite()||!c.low.isFinite()||!c.close.isFinite()||c.close<=0.0)return null
        val closes=candles.map{it.close};val vols=candles.map{it.volume.toDouble()}
        fun sma(n:Int)=closes.takeLast(n.coerceAtMost(closes.size)).average()
        fun ema(n:Int):Double{val k=2.0/(n+1);var e=closes.first();closes.forEach{e=it*k+e*(1-k)};return e}
        fun avgv(n:Int)=vols.takeLast(n.coerceAtMost(vols.size)).average().coerceAtLeast(1.0)
        val slotVol=sameSlotHistoricalVolumes.filter{it>0L}.map{it.toDouble()}.average().takeIf{it.isFinite()&&it>0.0}
        val rvol=(c.volume/(slotVol?:avgv(20))).takeIf{it.isFinite()}?:0.0
        val ema9=ema(9);val ema21=ema(21);val ema50=ema(50)
        val rsi14=Indicators.rsi(candles,14);val rsi2=Indicators.rsi(candles,2)
        val atr=(Indicators.atrPercent(candles,14).takeIf{it.isFinite()}?:0.05).coerceAtLeast(0.05)
        val prior20=candles.dropLast(1).takeLast(20);val hi20=prior20.maxOfOrNull{it.high}?:p.high;val lo20=prior20.minOfOrNull{it.low}?:p.low
        val ranges=candles.takeLast(8).map{(it.high-it.low).coerceAtLeast(0.0)}
        val lastRange=(c.high-c.low).coerceAtLeast(0.0001)
        val avgRange=ranges.dropLast(1).average().coerceAtLeast(0.0001)
        val body=abs(c.close-c.open);val upperWick=c.high-max(c.open,c.close);val lowerWick=min(c.open,c.close)-c.low
        val session=candles.takeLast(min(75,candles.size));val totalVol=session.sumOf{it.volume.toDouble()}.coerceAtLeast(1.0)
        val vwap=(session.sumOf{((it.high+it.low+it.close)/3.0)*it.volume}/totalVol).takeIf{it.isFinite()}?:c.close
        val sessionOpen=candles.first().open
        val gapPct=previousSessionClose?.takeIf{it>0.0}?.let{(sessionOpen/it-1.0)*100.0}?:0.0
        val trendLong=ema9>ema21 && ema21>ema50;val trendShort=ema9<ema21 && ema21<ema50
        val green=c.close>c.open;val red=c.close<c.open
        fun emaSeries(values:List<Double>,n:Int):List<Double>{if(values.isEmpty())return emptyList();val k=2.0/(n+1.0);var e=values.first();return values.map{v->e=v*k+e*(1-k);e}}
        val ema12s=emaSeries(closes,12);val ema26s=emaSeries(closes,26);val macdSeries=ema12s.zip(ema26s){a,b->a-b};val signalSeries=emaSeries(macdSeries,9)
        val macd=macdSeries.lastOrNull()?:0.0;val macdSignal=signalSeries.lastOrNull()?:0.0;val prevMacd=macdSeries.dropLast(1).lastOrNull()?:macd;val prevSignal=signalSeries.dropLast(1).lastOrNull()?:macdSignal
        fun stochK(end:Int,period:Int=14):Double{val from=(end-period+1).coerceAtLeast(0);val w=candles.subList(from,end+1);val lo=w.minOf{it.low};val hi=w.maxOf{it.high};return if(hi>lo)(candles[end].close-lo)/(hi-lo)*100.0 else 50.0}
        val kValues=(max(0,candles.lastIndex-4)..candles.lastIndex).map{stochK(it)};val stochK=kValues.lastOrNull()?:50.0;val stochD=kValues.takeLast(3).average();val prevStochK=kValues.dropLast(1).lastOrNull()?:stochK;val prevStochD=kValues.dropLast(1).takeLast(3).average().takeIf{it.isFinite()}?:stochD
        fun adx(period:Int=14):Triple<Double,Double,Double>{
            val bars=candles.takeLast(period+1);if(bars.size<3)return Triple(0.0,0.0,0.0);var tr=0.0;var pdm=0.0;var ndm=0.0
            for(i in 1 until bars.size){val cur=bars[i];val prev=bars[i-1];tr+=max(cur.high-cur.low,max(abs(cur.high-prev.close),abs(cur.low-prev.close)));val up=cur.high-prev.high;val dn=prev.low-cur.low;if(up>dn&&up>0)pdm+=up;if(dn>up&&dn>0)ndm+=dn}
            if(tr<=0)return Triple(0.0,0.0,0.0);val pdi=100*pdm/tr;val ndi=100*ndm/tr;val dx=if(pdi+ndi>0)100*abs(pdi-ndi)/(pdi+ndi) else 0.0;return Triple(dx,pdi,ndi)
        }
        val (adx14,plusDi,minusDi)=adx(14)
        val b20=closes.takeLast(20);val bmean=b20.average();val bstd=sqrt(b20.map{(it-bmean).pow(2)}.average().coerceAtLeast(0.0));val bUpper=bmean+2*bstd;val bLower=bmean-2*bstd;val bWidth=if(bmean>0)(bUpper-bLower)/bmean*100 else 999.0
        fun mk(d:TradeDirection,raw:Number,why:String,target:Double=max(0.5,min(3.0,atr*1.2)),stop:Double=max(0.4,min(2.0,atr*0.9))):Eval{
            val safeScore=raw.toDouble().takeIf{it.isFinite()}?.coerceIn(0.0,100.0)?:0.0
            val safeTarget=target.takeIf{it.isFinite()}?.coerceIn(0.1,20.0)?:1.0
            val safeStop=stop.takeIf{it.isFinite()}?.coerceIn(0.1,20.0)?:1.0
            return Eval(d,safeScore,safeTarget,safeStop,why)
        }
        return when(def.kind){
            "PD_FVG_SWEEP"->{
                val trio=candles.takeLast(4);val a=trio[0];val b=trio[1];val d=trio[2];val e=trio[3]
                val bullSweep=b.low<a.low && b.close>a.low;val bearSweep=b.high>a.high && b.close<a.high
                val bullDisp=d.close>d.open && (d.close-d.open)>(d.high-d.low)*0.55 && d.high>b.high
                val bearDisp=d.close<d.open && (d.open-d.close)>(d.high-d.low)*0.55 && d.low<b.low
                val bullFvg=d.low>a.high*0.999 && e.low<=d.low*1.004 && e.close>d.low
                val bearFvg=d.high<a.low*1.001 && e.high>=d.high*0.996 && e.close<d.high
                when{bullSweep&&bullDisp&&(bullFvg||e.close>d.close)->mk(TradeDirection.LONG,88+min(8.0,rvol*2),"Liquidity sweep → displacement → bullish FVG/retest; RVOL %.1fx".format(rvol));bearSweep&&bearDisp&&(bearFvg||e.close<d.close)->mk(TradeDirection.SHORT,88+min(8.0,rvol*2),"Liquidity sweep → displacement → bearish FVG/retest; RVOL %.1fx".format(rvol));else->null}
            }
            "ORB_RVOL"->{val openBars=session.take(min(3,session.size));val oh=openBars.maxOf{it.high};val ol=openBars.minOf{it.low};when{c.close>oh&&rvol>=1.5->mk(TradeDirection.LONG,78+min(16.0,rvol*4),"Opening-range breakout with %.1fx RVOL".format(rvol));c.close<ol&&rvol>=1.5->mk(TradeDirection.SHORT,78+min(16.0,rvol*4),"Opening-range breakdown with %.1fx RVOL".format(rvol));else->null}}
            "VWAP_RECLAIM"->when{p.close<=vwap&&c.close>vwap&&rvol>=1.2->mk(TradeDirection.LONG,76+min(18.0,rvol*5),"VWAP reclaim + volume %.1fx".format(rvol));p.close>=vwap&&c.close<vwap&&rvol>=1.2->mk(TradeDirection.SHORT,76+min(18.0,rvol*5),"VWAP loss + volume %.1fx".format(rvol));else->null}
            "VWAP_PULLBACK"->when{trendLong&&c.low<=vwap*1.003&&c.close>vwap&&green->mk(TradeDirection.LONG,80+min(12.0,rvol*3),"Trend pullback held VWAP");trendShort&&c.high>=vwap*0.997&&c.close<vwap&&red->mk(TradeDirection.SHORT,80+min(12.0,rvol*3),"Downtrend pullback rejected VWAP");else->null}
            "BOLL_SQUEEZE"->{val squeeze=b20.size>=20&&bWidth<2.2;when{squeeze&&c.close>bUpper&&rvol>1.3->mk(TradeDirection.LONG,82,"Bollinger bandwidth squeeze expanded above upper band");squeeze&&c.close<bLower&&rvol>1.3->mk(TradeDirection.SHORT,82,"Bollinger bandwidth squeeze expanded below lower band");else->null}}
            "DONCHIAN"->when{c.close>hi20&&rvol>1.2->mk(TradeDirection.LONG,81+min(12.0,rvol*3),"20-bar Donchian breakout");c.close<lo20&&rvol>1.2->mk(TradeDirection.SHORT,81+min(12.0,rvol*3),"20-bar Donchian breakdown");else->null}
            "EMA_CROSS"->when{ema9>ema21&&p.close<=ema21&&c.close>ema9&&rvol>1.1->mk(TradeDirection.LONG,77,"9/21 EMA momentum cross");ema9<ema21&&p.close>=ema21&&c.close<ema9&&rvol>1.1->mk(TradeDirection.SHORT,77,"9/21 EMA bearish cross");else->null}
            "TREND_PULLBACK"->when{trendLong&&c.low<=ema21*1.004&&c.close>ema21&&green->mk(TradeDirection.LONG,79,"20/50 trend pullback held");trendShort&&c.high>=ema21*0.996&&c.close<ema21&&red->mk(TradeDirection.SHORT,79,"20/50 downtrend pullback rejected");else->null}
            "MACD"->when{candles.size>=26&&prevMacd<=prevSignal&&macd>macdSignal&&macd>0->mk(TradeDirection.LONG,78,"MACD bullish signal-line crossover above zero");candles.size>=26&&prevMacd>=prevSignal&&macd<macdSignal&&macd<0->mk(TradeDirection.SHORT,78,"MACD bearish signal-line crossover below zero");else->null}
            "RSI2_TREND"->when{trendLong&&rsi2<20&&green->mk(TradeDirection.LONG,75,"RSI(2) pullback exhaustion in uptrend");trendShort&&rsi2>80&&red->mk(TradeDirection.SHORT,75,"RSI(2) rebound exhaustion in downtrend");else->null}
            "RSI14_REVERSAL"->when{rsi14<32&&green&&lowerWick>body->mk(TradeDirection.LONG,74,"RSI(14) oversold + bullish rejection");rsi14>68&&red&&upperWick>body->mk(TradeDirection.SHORT,74,"RSI(14) overbought + bearish rejection");else->null}
            "STOCH_REVERSAL"->when{prevStochK<=prevStochD&&stochK>stochD&&stochK<30&&green->mk(TradeDirection.LONG,74,"Stochastic %K bullish crossover from oversold zone");prevStochK>=prevStochD&&stochK<stochD&&stochK>70&&red->mk(TradeDirection.SHORT,74,"Stochastic %K bearish crossover from overbought zone");else->null}
            "INSIDE_BAR"->{val q=candles[candles.lastIndex-2];val inside=p.high<q.high&&p.low>q.low;when{inside&&c.close>p.high&&rvol>1.2->mk(TradeDirection.LONG,76,"Inside-bar upside expansion");inside&&c.close<p.low&&rvol>1.2->mk(TradeDirection.SHORT,76,"Inside-bar downside expansion");else->null}}
            "ENGULFING"->{val bull=green&&p.close<p.open&&c.open<=p.close&&c.close>=p.open;val bear=red&&p.close>p.open&&c.open>=p.close&&c.close<=p.open;when{bull->mk(TradeDirection.LONG,74+if(trendLong)8 else 0,"Bullish engulfing with trend context");bear->mk(TradeDirection.SHORT,74+if(trendShort)8 else 0,"Bearish engulfing with trend context");else->null}}
            "HAMMER"->when{lowerWick>body*2&&upperWick<body&&green->mk(TradeDirection.LONG,73,"Hammer-style lower-wick rejection");upperWick>body*2&&lowerWick<body&&red->mk(TradeDirection.SHORT,73,"Shooting-star upper-wick rejection");else->null}
            "MORNING_STAR"->{val t=candles.takeLast(3);val a=t[0];val b=t[1];val d=t[2];when{a.close<a.open&&abs(b.close-b.open)<abs(a.close-a.open)*0.5&&d.close>d.open&&d.close>(a.open+a.close)/2->mk(TradeDirection.LONG,75,"Morning-star reversal");a.close>a.open&&abs(b.close-b.open)<abs(a.close-a.open)*0.5&&d.close<d.open&&d.close<(a.open+a.close)/2->mk(TradeDirection.SHORT,75,"Evening-star reversal");else->null}}
            "THREE_SOLDIERS"->{val t=candles.takeLast(3);when{t.all{it.close>it.open}&&t.zipWithNext().all{(a,b)->b.close>a.close}->mk(TradeDirection.LONG,76,"Three-candle bullish staircase");t.all{it.close<it.open}&&t.zipWithNext().all{(a,b)->b.close<a.close}->mk(TradeDirection.SHORT,76,"Three-candle bearish staircase");else->null}}
            "GAP_GO"->when{
                previousSessionClose!=null&&gapPct>=0.60&&c.close>vwap&&green->mk(TradeDirection.LONG,80+min(10.0,abs(gapPct)+rvol),"True prior-session gap accepted above VWAP • gap %.2f%%".format(gapPct))
                previousSessionClose!=null&&gapPct<=-0.60&&c.close<vwap&&red->mk(TradeDirection.SHORT,80+min(10.0,abs(gapPct)+rvol),"True prior-session gap-down accepted below VWAP • gap %.2f%%".format(gapPct))
                else->null
            }
            "NR7_EXPANSION"->{val prev7=candles.dropLast(1).takeLast(7);val narrow=prev7.isNotEmpty()&&(p.high-p.low)<=prev7.minOf{it.high-it.low}+1e-9;when{narrow&&lastRange>avgRange*1.3&&green->mk(TradeDirection.LONG,77,"NR7 compression expanded upward");narrow&&lastRange>avgRange*1.3&&red->mk(TradeDirection.SHORT,77,"NR7 compression expanded downward");else->null}}
            "VOLUME_BREAKOUT"->when{c.close>hi20&&rvol>=1.8->mk(TradeDirection.LONG,84+min(12.0,(rvol-1.8)*5),"Price breakout + %.1fx relative volume".format(rvol));c.close<lo20&&rvol>=1.8->mk(TradeDirection.SHORT,84+min(12.0,(rvol-1.8)*5),"Price breakdown + %.1fx relative volume".format(rvol));else->null}
            "ATR_BREAKOUT"->when{lastRange/c.close*100>atr*1.4&&green&&c.close>p.high->mk(TradeDirection.LONG,78,"ATR-normalized upside impulse");lastRange/c.close*100>atr*1.4&&red&&c.close<p.low->mk(TradeDirection.SHORT,78,"ATR-normalized downside impulse");else->null}
            "SUPPORT_RESISTANCE"->when{c.low<=lo20*1.003&&lowerWick>body&&green->mk(TradeDirection.LONG,74,"20-bar support rejection");c.high>=hi20*0.997&&upperWick>body&&red->mk(TradeDirection.SHORT,74,"20-bar resistance rejection");else->null}
            "ADX_TREND"->when{adx14>=25&&plusDi>minusDi&&trendLong&&green->mk(TradeDirection.LONG,78,"ADX trend strength with +DI > -DI");adx14>=25&&minusDi>plusDi&&trendShort&&red->mk(TradeDirection.SHORT,78,"ADX trend strength with -DI > +DI");else->null}
            else->null
        }
    }
}
