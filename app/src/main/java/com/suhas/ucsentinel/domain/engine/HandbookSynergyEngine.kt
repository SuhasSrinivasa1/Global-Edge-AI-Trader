package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

class HandbookSynergyEngine {
    companion object {
        const val VERSION="HANDBOOK-SYNERGY-2026.09.24"
        const val HANDBOOK_STRATEGIES=100
        const val CANDLE_PATTERNS=50
        const val INTELLIGENCE_FILTERS=50
        const val RESEARCH_VARIANTS=500
    }

    data class PatternHit(val name:String,val bias:TradeDirection?,val quality:Int)
    data class FilterEval(val id:Int,val name:String,val passed:Boolean?,val hardFail:Boolean=false,val note:String="")
    data class Result(
        val adjustedScore:Double,val qualityPct:Double,val hardFail:Boolean,
        val primaryPattern:String,val combination:String,val handbookAnchors:List<Int>,
        val signature:String,val evidence:String,val failedHardFilters:List<String>,
        val evaluatedFilters:Int,val unavailableFilters:Int
    )

    private val filterNames=listOf(
        "Primary market regime","Benchmark direction","Market breadth","Volatility regime","Opening gap condition",
        "Global cue alignment","Macro/event calendar","Rates, liquidity and FX context","Risk-on / risk-off confirmation","Time-of-day regime",
        "Sector trend","Sector breadth","Stock vs benchmark relative strength","Stock vs sector relative strength","Leadership persistence",
        "Peer confirmation","Index/ETF participation","Institutional-flow context","Revenue growth","EPS and earnings quality",
        "Margin trend","ROE / ROCE quality","Free cash flow","Leverage and interest coverage","Promoter holding, pledging and governance",
        "Institutional ownership and revisions","Valuation vs history and peers","Earnings guidance and revisions","Company catalyst quality","Corporate-action and event risk",
        "Market structure","Support and resistance location","VWAP / anchored VWAP","EMA / trend alignment","Volume and relative volume",
        "ATR and range context","Breakout / retest quality","Gap analysis","Opening range","Multi-timeframe and momentum alignment",
        "Liquidity and turnover gate","Bid-ask spread, depth and impact cost","Order type and price protection","Expected slippage and total transaction cost",
        "Reward/risk and room to obstacle","Position sizing from risk","Stop, invalidation and exit logic","Portfolio correlation and concentration",
        "Daily/weekly kill switches and drawdown throttle","No-trade and strategy-decay gate"
    )

    private fun ema(values:List<Double>,n:Int):Double{if(values.isEmpty())return 0.0;val k=2.0/(n+1.0);var e=values.first();values.forEach{e=it*k+e*(1.0-k)};return e}
    private fun body(c:Candle)=abs(c.close-c.open)
    private fun range(c:Candle)=(c.high-c.low).coerceAtLeast(1e-9)
    private fun upper(c:Candle)=c.high-max(c.open,c.close)
    private fun lower(c:Candle)=min(c.open,c.close)-c.low
    private fun bull(c:Candle)=c.close>c.open
    private fun bear(c:Candle)=c.close<c.open
    private fun doji(c:Candle)=body(c)<=range(c)*0.12
    private fun strongBull(c:Candle)=bull(c)&&body(c)>=range(c)*0.65
    private fun strongBear(c:Candle)=bear(c)&&body(c)>=range(c)*0.65
    private fun insideBody(inner:Candle,outer:Candle)=max(inner.open,inner.close)<=max(outer.open,outer.close)&&min(inner.open,inner.close)>=min(outer.open,outer.close)
    private fun closeNear(a:Double,b:Double,price:Double)=abs(a-b)/price.coerceAtLeast(1.0)<=0.0015

    fun evaluate(setup:StrategySetup,candles:List<Candle>,q:Quote):Result{
        if(candles.size<3)return Result(setup.score,50.0,false,"None","Base",handbookAnchors(setup),setup.strategyId+"|"+setup.direction.name+"|NO_CANDLE","Handbook overlay unavailable: fewer than 3 candles",emptyList(),0,50)
        val closes=candles.map{it.close};val c=candles.last();val p=candles[candles.lastIndex-1]
        val ema9=ema(closes,9);val ema21=ema(closes,21);val ema50=ema(closes,50)
        val trendLong=ema9>ema21&&ema21>=ema50;val trendShort=ema9<ema21&&ema21<=ema50
        val prior20=candles.dropLast(1).takeLast(20);val hi20=prior20.maxOfOrNull{it.high}?:p.high;val lo20=prior20.minOfOrNull{it.low}?:p.low
        val session=candles.takeLast(min(75,candles.size));val priorVol=session.dropLast(1).takeLast(20).map{it.volume.toDouble()}
        val volAvg=priorVol.takeIf{it.isNotEmpty()}?.average()?.takeIf{it.isFinite()&&it>0.0}?:1.0;val rvol=c.volume/volAvg
        val sessionVol=session.sumOf{it.volume.toDouble()}.coerceAtLeast(1.0);val vwap=session.sumOf{((it.high+it.low+it.close)/3.0)*it.volume}/sessionVol
        val atrPct=Indicators.atrPercent(candles,14).takeIf{it.isFinite()}?:0.0
        val openBars=session.take(min(3,session.size));val openingHigh=openBars.maxOfOrNull{it.high}?:c.high;val openingLow=openBars.minOfOrNull{it.low}?:c.low
        val tradedValue=q.volume*q.lastPrice
        val spreadPct=if(q.bidPrice>0.0&&q.offerPrice>0.0){val mid=(q.bidPrice+q.offerPrice)/2.0;((q.offerPrice-q.bidPrice).coerceAtLeast(0.0)/mid.coerceAtLeast(0.01))*100.0}else 99.0
        val direction=setup.direction;val alignedTrend=if(direction==TradeDirection.LONG)trendLong else trendShort;val oppositeTrend=if(direction==TradeDirection.LONG)trendShort else trendLong
        val hits=detectPatterns(candles,trendLong,trendShort);val compatible=hits.filter{it.bias==null||it.bias==direction}.sortedByDescending{it.quality};val opposing=hits.filter{it.bias!=null&&it.bias!=direction}.sortedByDescending{it.quality}
        val primary=compatible.firstOrNull()?.name?:"None";val combo=combination(setup,primary,alignedTrend,rvol,c,vwap,hi20,lo20)

        fun fe(id:Int,passed:Boolean?,hard:Boolean=false,note:String="")=FilterEval(id,filterNames[id-1],passed,hard,note)
        val filters=mutableListOf<FilterEval>()
        filters+=fe(1,when{alignedTrend->true;oppositeTrend->false;else->null},note="EMA 9/21/50 regime proxy")
        filters+=fe(2,null);filters+=fe(3,null)
        filters+=fe(4,when{atrPct in 0.20..5.0->true;atrPct>7.0->false;else->null},note="ATR "+"%.2f".format(atrPct)+"%")
        filters+=fe(5,null);filters+=fe(6,null);filters+=fe(7,null);filters+=fe(8,null);filters+=fe(9,null)
        val tod=runCatching{Instant.ofEpochSecond(c.epochSeconds).atZone(ZoneId.of("Asia/Kolkata")).toLocalTime()}.getOrNull()
        filters+=fe(10,tod?.let{it.hour in 9..14||(it.hour==15&&it.minute<10)},note=tod?.toString()?:"time unavailable")
        filters+=fe(11,null);filters+=fe(12,null);filters+=fe(13,null);filters+=fe(14,null)
        val lookback=candles.takeLast(min(8,candles.size));val leadership=if(lookback.size>=3&&lookback.first().close>0.0)(lookback.last().close/lookback.first().close-1.0)*100.0 else 0.0
        filters+=fe(15,if(direction==TradeDirection.LONG)leadership>0.15 else leadership< -0.15,note="recent "+"%+.2f".format(leadership)+"%")
        filters+=fe(16,null);filters+=fe(17,null);filters+=fe(18,null)
        for(id in 19..30)filters+=fe(id,null,note="fundamental/catalyst feed not wired into intraday scanner")
        val recent=candles.takeLast(min(6,candles.size));val upStructure=recent.zipWithNext().count{(x,y)->y.high>=x.high&&y.low>=x.low}>=max(2,recent.size/2);val downStructure=recent.zipWithNext().count{(x,y)->y.high<=x.high&&y.low<=x.low}>=max(2,recent.size/2)
        filters+=fe(31,if(direction==TradeDirection.LONG)upStructure else downStructure)
        val atLocation=if(direction==TradeDirection.LONG)c.low<=lo20*1.01||c.close>=hi20 else c.high>=hi20*0.99||c.close<=lo20
        filters+=fe(32,atLocation);filters+=fe(33,if(direction==TradeDirection.LONG)c.close>=vwap else c.close<=vwap,note="VWAP "+"%.2f".format(vwap));filters+=fe(34,alignedTrend)
        filters+=fe(35,when{rvol>=1.20->true;rvol<0.70->false;else->null},note="RVOL "+"%.2f".format(rvol)+"x");filters+=fe(36,atrPct in 0.20..5.0)
        val breakout=if(direction==TradeDirection.LONG)c.close>=hi20||(c.low<=ema21*1.005&&c.close>ema21) else c.close<=lo20||(c.high>=ema21*0.995&&c.close<ema21)
        filters+=fe(37,breakout);filters+=fe(38,null)
        filters+=fe(39,if(direction==TradeDirection.LONG)c.close>=openingHigh else c.close<=openingLow)
        val longRet=if(candles.size>=8&&candles[candles.lastIndex-7].close>0.0)(c.close/candles[candles.lastIndex-7].close-1.0)*100.0 else 0.0
        filters+=fe(40,if(direction==TradeDirection.LONG)longRet>0.0 else longRet<0.0,note="multi-bar momentum "+"%+.2f".format(longRet)+"%")
        filters+=fe(41,tradedValue>=1_000_000.0,hard=tradedValue<500_000.0,note="traded value ₹"+"%.1f".format(tradedValue/100000.0)+"L")
        filters+=fe(42,spreadPct<=0.45,hard=spreadPct>0.80||q.bidPrice<=0.0||q.offerPrice<=0.0,note="spread "+"%.2f".format(spreadPct)+"%")
        filters+=fe(43,spreadPct<=0.35,hard=spreadPct>0.70)
        val estimatedFriction=spreadPct/2.0+0.08
        filters+=fe(44,estimatedFriction<=setup.targetPct*0.25,hard=estimatedFriction>=setup.targetPct*0.50,note="friction proxy "+"%.2f".format(estimatedFriction)+"%")
        val rr=setup.targetPct/setup.stopPct.coerceAtLeast(0.01);filters+=fe(45,rr>=1.20,hard=rr<1.0,note="R:R "+"%.2f".format(rr))
        val qty=floor(20000.0/setup.entryPrice.coerceAtLeast(0.01)).toInt();filters+=fe(46,qty>0,hard=qty<=0,note="₹20k quantity "+qty)
        filters+=fe(47,setup.stopPct in 0.20..5.0&&setup.targetPct>0.0,hard=setup.stopPct<=0.0||setup.targetPct<=0.0)
        filters+=fe(48,null);filters+=fe(49,null);filters+=fe(50,true,note="strategy-decay gate applied by Champion governance")

        val hardFails=filters.filter{it.hardFail&&it.passed==false}.map{it.name};val evaluated=filters.count{it.passed!=null};val unavailable=filters.size-evaluated;val passCount=filters.count{it.passed==true}
        val contextPct=if(evaluated==0)50.0 else passCount*100.0/evaluated
        val patternQuality=compatible.firstOrNull()?.quality?:0;val oppositePenalty=if(opposing.firstOrNull()?.quality==2&&patternQuality==0)-3.0 else 0.0
        val patternAdj=when(patternQuality){2->3.0;1->1.5;else->0.0};val comboAdj=if(combo!="Base")2.0 else 0.0;val contextAdj=((contextPct-60.0)*0.10).coerceIn(-7.0,5.0)
        val adjusted=(setup.score+patternAdj+comboAdj+contextAdj+oppositePenalty).coerceIn(0.0,100.0);val anchors=handbookAnchors(setup)
        val signature=setup.strategyId+"|"+direction.name+"|"+primary.replace(" ","_")+"|"+combo.replace(" ","_")
        val evidence="HB#"+anchors.joinToString(",")+" • candle "+primary+" • combo "+combo+" • filters "+passCount+"/"+evaluated+" pass ("+unavailable+" unavailable) • quality "+"%.0f".format(contextPct)
        return Result(adjusted,contextPct,hardFails.isNotEmpty(),primary,combo,anchors,signature,evidence,hardFails,evaluated,unavailable)
    }

    private fun handbookAnchors(setup:StrategySetup):List<Int>{
        val x=(setup.strategyId+" "+setup.strategyName).lowercase();val ids=mutableListOf<Int>()
        when{
            "orb" in x||"opening range" in x->ids+=95
            "vwap" in x->ids+=72
            "donchian" in x->ids+=23
            "trend_pullback" in x||"trend pullback" in x->ids+=26
            "squeeze" in x||"nr7" in x->ids+=27
            "gap" in x->ids+=78
            "ema" in x||"macd" in x||"trend" in x->ids+=24
            "rsi" in x||"stoch" in x||"reversal" in x->ids+=96
            "volume" in x->ids+=95
            "engulf" in x||"hammer" in x||"star" in x||"inside" in x->ids+=26
            else->ids+=100
        }
        ids+=99;ids+=100;return ids.distinct()
    }

    private fun combination(setup:StrategySetup,pattern:String,trendAligned:Boolean,rvol:Double,c:Candle,vwap:Double,hi20:Double,lo20:Double):String{
        val n=(setup.strategyId+" "+setup.strategyName).lowercase();val reversal=pattern in setOf("Bullish Engulfing","Bearish Engulfing","Hammer","Shooting Star","Bullish Pin Bar / Rejection Candle","Bearish Pin Bar / Rejection Candle","Morning Star","Evening Star")
        return when{
            ("pullback" in n)&&trendAligned&&reversal&&rvol>=0.9->"Trend pullback continuation"
            ("vwap" in n)&&reversal->"VWAP reclaim/reject"
            ("inside" in n||"squeeze" in n||"nr7" in n)&&rvol>=1.2->"Compression breakout"
            ("breakout" in n||"donchian" in n||"volume" in n)&&(c.close>=hi20||c.close<=lo20)&&reversal->"Breakout-retest"
            reversal&&!trendAligned->"Exhaustion reversal"
            "gap" in n->"Gap continuation"
            trendAligned&&pattern!="None"->"Multi-timeframe alignment"
            else->"Base"
        }
    }

    private fun detectPatterns(cs:List<Candle>,trendLong:Boolean,trendShort:Boolean):List<PatternHit>{
        val out=mutableListOf<PatternHit>();if(cs.size<3)return out
        val c=cs.last();val p=cs[cs.lastIndex-1];val a=cs[cs.lastIndex-2];val b=cs.getOrNull(cs.lastIndex-3);val d=cs.getOrNull(cs.lastIndex-4)
        fun add(ok:Boolean,name:String,bias:TradeDirection?,quality:Int=1){if(ok)out+=PatternHit(name,bias,quality)}
        val midP=(p.open+p.close)/2.0;val bullEng=bull(c)&&bear(p)&&c.open<=p.close&&c.close>=p.open;val bearEng=bear(c)&&bull(p)&&c.open>=p.close&&c.close<=p.open
        val hammer=lower(c)>=body(c)*2.0&&upper(c)<=max(body(c),range(c)*0.12)&&body(c)<=range(c)*0.45;val invHammer=upper(c)>=body(c)*2.0&&lower(c)<=max(body(c),range(c)*0.12)&&body(c)<=range(c)*0.45
        val morning=bear(a)&&body(p)<=body(a)*0.55&&bull(c)&&c.close>=(a.open+a.close)/2.0;val evening=bull(a)&&body(p)<=body(a)*0.55&&bear(c)&&c.close<=(a.open+a.close)/2.0
        val haramiBull=bear(p)&&bull(c)&&insideBody(c,p);val haramiBear=bull(p)&&bear(c)&&insideBody(c,p)
        val pHaramiBull=bear(a)&&bull(p)&&insideBody(p,a);val pHaramiBear=bull(a)&&bear(p)&&insideBody(p,a);val pBullEng=bull(p)&&bear(a)&&p.open<=a.close&&p.close>=a.open;val pBearEng=bear(p)&&bull(a)&&p.open>=a.close&&p.close<=a.open

        add(bullEng,"Bullish Engulfing",TradeDirection.LONG,2);add(bearEng,"Bearish Engulfing",TradeDirection.SHORT,2)
        add(hammer&&!trendLong,"Hammer",TradeDirection.LONG,2);add(hammer&&trendLong,"Hanging Man",TradeDirection.SHORT,1)
        add(invHammer&&trendShort,"Inverted Hammer",TradeDirection.LONG,1);add(invHammer&&trendLong,"Shooting Star",TradeDirection.SHORT,2)
        add(morning,"Morning Star",TradeDirection.LONG,2);add(evening,"Evening Star",TradeDirection.SHORT,2);add(morning&&doji(p),"Morning Doji Star",TradeDirection.LONG,2);add(evening&&doji(p),"Evening Doji Star",TradeDirection.SHORT,2)
        add(bear(p)&&bull(c)&&c.open<=p.close&&c.close>midP&&c.close<p.open,"Piercing Line",TradeDirection.LONG,2);add(bull(p)&&bear(c)&&c.open>=p.close&&c.close<midP&&c.close>p.open,"Dark Cloud Cover",TradeDirection.SHORT,2)
        add(listOf(a,p,c).all(::bull)&&p.close>a.close&&c.close>p.close,"Three White Soldiers",TradeDirection.LONG,2);add(listOf(a,p,c).all(::bear)&&p.close<a.close&&c.close<p.close,"Three Black Crows",TradeDirection.SHORT,2)
        add(haramiBull,"Bullish Harami",TradeDirection.LONG);add(haramiBear,"Bearish Harami",TradeDirection.SHORT);add(bear(p)&&doji(c)&&insideBody(c,p),"Bullish Harami Cross",TradeDirection.LONG);add(bull(p)&&doji(c)&&insideBody(c,p),"Bearish Harami Cross",TradeDirection.SHORT)
        add(doji(c),"Doji",null);add(doji(c)&&lower(c)>=range(c)*0.60&&upper(c)<=range(c)*0.12,"Dragonfly Doji",TradeDirection.LONG,2);add(doji(c)&&upper(c)>=range(c)*0.60&&lower(c)<=range(c)*0.12,"Gravestone Doji",TradeDirection.SHORT,2)
        add(doji(c)&&upper(c)>=range(c)*0.30&&lower(c)>=range(c)*0.30,"Long-Legged Doji",null);add(range(c)/c.close.coerceAtLeast(1.0)<0.0006,"Four-Price Doji",null);add(body(c)<=range(c)*0.30&&upper(c)>=body(c)&&lower(c)>=body(c),"Spinning Top",null)
        add(strongBull(c)&&upper(c)<=range(c)*0.10&&lower(c)<=range(c)*0.10,"Bullish Marubozu",TradeDirection.LONG,2);add(strongBear(c)&&upper(c)<=range(c)*0.10&&lower(c)<=range(c)*0.10,"Bearish Marubozu",TradeDirection.SHORT,2)
        add(pHaramiBull&&bull(c)&&c.close>a.open,"Three Inside Up",TradeDirection.LONG,2);add(pHaramiBear&&bear(c)&&c.close<a.open,"Three Inside Down",TradeDirection.SHORT,2);add(pBullEng&&bull(c)&&c.close>p.close,"Three Outside Up",TradeDirection.LONG,2);add(pBearEng&&bear(c)&&c.close<p.close,"Three Outside Down",TradeDirection.SHORT,2)
        add(bear(p)&&bull(c)&&closeNear(p.low,c.low,c.close),"Bullish Tweezer Bottom",TradeDirection.LONG);add(bull(p)&&bear(c)&&closeNear(p.high,c.high,c.close),"Bearish Tweezer Top",TradeDirection.SHORT)
        if(b!=null&&d!=null){val five=listOf(d,b,a,p,c);add(strongBull(d)&&five.slice(1..3).all{it.low>d.low&&it.high<d.high*1.01}&&strongBull(c)&&c.close>d.high,"Rising Three Methods",TradeDirection.LONG,2);add(strongBear(d)&&five.slice(1..3).all{it.high<d.high&&it.low>d.low*0.99}&&strongBear(c)&&c.close<d.low,"Falling Three Methods",TradeDirection.SHORT,2);add(strongBull(d)&&five.slice(1..3).count(::bear)>=2&&strongBull(c)&&c.close>d.high,"Mat Hold",TradeDirection.LONG,2)}
        add(bear(p)&&bull(c)&&closeNear(p.open,c.open,c.close)&&c.close>p.open,"Bullish Separating Lines",TradeDirection.LONG);add(bull(p)&&bear(c)&&closeNear(p.open,c.open,c.close)&&c.close<p.open,"Bearish Separating Lines",TradeDirection.SHORT)
        add(strongBull(c)&&lower(c)<=range(c)*0.08,"Bullish Belt Hold",TradeDirection.LONG);add(strongBear(c)&&upper(c)<=range(c)*0.08,"Bearish Belt Hold",TradeDirection.SHORT)
        val bullBodyGap=min(c.open,c.close)>max(p.open,p.close);val bearBodyGap=max(c.open,c.close)<min(p.open,p.close)
        add(bear(p)&&strongBull(c)&&bullBodyGap,"Bullish Kicker",TradeDirection.LONG,2);add(bull(p)&&strongBear(c)&&bearBodyGap,"Bearish Kicker",TradeDirection.SHORT,2)
        add(bear(a)&&doji(p)&&p.high<a.low&&bull(c)&&c.low>p.high,"Bullish Abandoned Baby",TradeDirection.LONG,2);add(bull(a)&&doji(p)&&p.low>a.high&&bear(c)&&c.high<p.low,"Bearish Abandoned Baby",TradeDirection.SHORT,2)
        add(bull(a)&&bull(p)&&p.low>a.high&&bear(c)&&c.close>a.high&&c.close<p.open,"Upside Tasuki Gap",TradeDirection.LONG);add(bear(a)&&bear(p)&&p.high<a.low&&bull(c)&&c.close<a.low&&c.close>p.open,"Downside Tasuki Gap",TradeDirection.SHORT)
        add(bear(p)&&bull(c)&&closeNear(p.close,c.close,c.close)&&c.open<p.close,"Bullish Counterattack Line",TradeDirection.LONG);add(bull(p)&&bear(c)&&closeNear(p.close,c.close,c.close)&&c.open>p.close,"Bearish Counterattack Line",TradeDirection.SHORT)
        add(hammer&&c.close>=c.low+range(c)*0.65,"Bullish Pin Bar / Rejection Candle",TradeDirection.LONG,2);add(invHammer&&c.close<=c.low+range(c)*0.35,"Bearish Pin Bar / Rejection Candle",TradeDirection.SHORT,2)
        val inside=p.high<a.high&&p.low>a.low;add(inside&&(c.close>p.high||c.close<p.low),"Inside Bar Breakout",if(c.close>p.high)TradeDirection.LONG else TradeDirection.SHORT,2)
        return out.distinctBy{it.name}
    }
}
