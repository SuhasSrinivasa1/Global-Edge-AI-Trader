package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import kotlin.math.abs
import kotlin.math.max

class MarketRegimeClassifier {
    fun classify(
        nifty:List<GlobalBar>,
        sensex:List<GlobalBar>,
        bank:List<GlobalBar>,
        indiaVix:GlobalQuoteSnapshot?=null,
        spy:GlobalQuoteSnapshot?=null,
        globalVix:GlobalQuoteSnapshot?=null
    ):RegimeAssessment{
        fun ret(x:List<GlobalBar>):Double = if(x.size<2||x.first().open<=0.0)0.0 else (x.last().close/x.first().open-1.0)*100.0
        fun range(x:List<GlobalBar>):Double {
            val first=x.firstOrNull()?.open?:return 0.0
            if(first<=0.0)return 0.0
            val hi=x.maxOfOrNull{it.high}?:first; val lo=x.minOfOrNull{it.low}?:first
            return (hi-lo)/first*100.0
        }
        val nr=ret(nifty); val sr=ret(sensex); val br=ret(bank); val rg=range(nifty)
        val firstLeg=if(nifty.size>=7&&nifty.first().open>0.0)(nifty[minOf(5,nifty.lastIndex)].close/nifty.first().open-1.0)*100.0 else nr
        val reversal=(firstLeg>0.55&&nr< -0.20)||(firstLeg< -0.55&&nr>0.20)
        val localVix=indiaVix?.dayPct?:0.0; val worldVix=globalVix?.dayPct?:0.0; val spyRet=spy?.dayPct?:0.0
        val evidence=mutableListOf("Nifty ${fmt(nr)}%", "Sensex ${fmt(sr)}%", "Bank Nifty ${fmt(br)}%", "Nifty range ${"%.2f".format(rg)}%")
        if(indiaVix!=null)evidence+="India VIX ${fmt(localVix)}%"
        if(spy!=null)evidence+="SPY ${fmt(spyRet)}%"
        val regime=when{
            (localVix>=5.0||worldVix>=7.0||spyRet<=-1.2)&&nr<0.0->MarketRegime.RISK_OFF
            (localVix<=-5.0||worldVix<=-7.0||spyRet>=1.2)&&nr>0.0->MarketRegime.RISK_ON
            reversal->MarketRegime.GAP_REVERSAL
            rg>=2.5->MarketRegime.HIGH_VOLATILITY
            nr>=0.70&&sr>=0.40->MarketRegime.TREND_UP
            nr<=-0.70&&sr<=-0.40->MarketRegime.TREND_DOWN
            abs(nr)<=0.35&&rg<=1.20->MarketRegime.RANGE_BOUND
            else->MarketRegime.MIXED
        }
        val conf=when(regime){
            MarketRegime.RISK_OFF,MarketRegime.RISK_ON->82.0
            MarketRegime.GAP_REVERSAL->78.0
            MarketRegime.HIGH_VOLATILITY->75.0
            MarketRegime.TREND_UP,MarketRegime.TREND_DOWN->80.0
            MarketRegime.RANGE_BOUND->70.0
            MarketRegime.MIXED->55.0
        }
        return RegimeAssessment(regime,conf,nr,sr,br,evidence)
    }
    private fun fmt(v:Double)="%+.2f".format(v)
}

class TradeAutopsyEngine(private val regimeClassifier:MarketRegimeClassifier=MarketRegimeClassifier()){
    data class Input(
        val sourceId:String,
        val engineLabel:String,
        val symbol:String,
        val outcome:String,
        val originalReturnPct:Double,
        val direction:TradeDirection,
        val entryPrice:Double,
        val stopPrice:Double,
        val targetPrice:Double,
        val openedAt:Long,
        val executionStartAt:Long,
        val closedAt:Long,
        val sessionDate:String,
        val stockCandles:List<Candle>,
        val niftyBars:List<GlobalBar>,
        val sensexBars:List<GlobalBar>,
        val bankBars:List<GlobalBar>,
        val indiaVix:GlobalQuoteSnapshot?,
        val spy:GlobalQuoteSnapshot?,
        val globalVix:GlobalQuoteSnapshot?,
        val news:List<NewsItem>
    )

    fun analyze(i:Input):TradeAutopsyRecord{
        val executionSec=i.executionStartAt/1000L
        val pre=i.stockCandles.filter{it.epochSeconds<executionSec}.takeLast(12)
        val post=i.stockCandles.filter{it.epochSeconds>=executionSec}
        val nWindow=window(i.niftyBars,i.executionStartAt,i.closedAt)
        val sWindow=window(i.sensexBars,i.executionStartAt,i.closedAt)
        val bWindow=window(i.bankBars,i.executionStartAt,i.closedAt)
        val regime=regimeClassifier.classify(nWindow,sWindow,bWindow,i.indiaVix,i.spy,i.globalVix)

        val preMomentum=if(pre.size>=3&&pre[pre.size-3].close>0.0)(pre.last().close/pre[pre.size-3].close-1.0)*100.0 else 0.0
        val avgVol=pre.dropLast(1).takeLast(6).map{it.volume.toDouble()}.filter{it>0}.averageOrZero()
        val volumeRatio=if(avgVol>0.0)(pre.lastOrNull()?.volume?:0L)/avgVol else 1.0
        val vwap=weightedVwap(pre)
        val extendedPct=if(vwap>0.0)abs(i.entryPrice/vwap-1.0)*100.0 else 0.0
        val indexMove=regime.niftyReturnPct
        val opposingIndex=if(i.direction==TradeDirection.LONG)indexMove<=-0.55 else indexMove>=0.55
        val momentumSupports=if(i.direction==TradeDirection.LONG)preMomentum>=0.12 else preMomentum<=-0.12
        val relevantNews=i.news.filter{newsRelevant(it,i.symbol)}.take(4)
        val broadNews=i.news.filter{marketNewsRelevant(it)}.take(4)
        val isWin=i.outcome.equals("WIN",true)

        val evidence=mutableListOf<String>()
        evidence+=regime.evidence
        evidence+="Pre-call momentum ${"%+.2f".format(preMomentum)}%"
        evidence+="Pre-call volume ${"%.2f".format(volumeRatio)}x"
        if(vwap>0)evidence+="Entry distance from rolling VWAP ${"%.2f".format(extendedPct)}%"

        val cause:AutopsyCause
        val confidence:Double
        if(isWin){
            cause=AutopsyCause.WIN_CONFIRMED;confidence=75.0
        }else when{
            relevantNews.isNotEmpty()->{cause=AutopsyCause.COMPANY_NEWS;confidence=78.0}
            opposingIndex&&regime.regime in setOf(MarketRegime.TREND_UP,MarketRegime.TREND_DOWN,MarketRegime.RISK_ON,MarketRegime.RISK_OFF,MarketRegime.GAP_REVERSAL)->{cause=AutopsyCause.MARKET_REVERSAL;confidence=82.0}
            opposingIndex->{cause=AutopsyCause.INDEX_DIVERGENCE;confidence=72.0}
            broadNews.isNotEmpty()&&regime.regime in setOf(MarketRegime.RISK_ON,MarketRegime.RISK_OFF)->{cause=AutopsyCause.GLOBAL_RISK;confidence=70.0}
            volumeRatio<0.85->{cause=AutopsyCause.VOLUME_FAILURE;confidence=68.0}
            !momentumSupports->{cause=AutopsyCause.MOMENTUM_FAILURE;confidence=66.0}
            extendedPct>=1.50->{cause=AutopsyCause.ENTRY_TIMING;confidence=64.0}
            else->{cause=AutopsyCause.STATISTICAL_FAILURE;confidence=52.0}
        }
        if(relevantNews.isNotEmpty())evidence+="Company/news event detected near outcome window"
        if(opposingIndex)evidence+="Nifty moved against ${i.direction.name} thesis"

        val shadows=shadowLab(i,pre,post,nWindow,preMomentum,volumeRatio,vwap)
        val best=shadows.sortedWith(compareByDescending<ShadowStrategyResult>{it.outcome==ShadowOutcome.AVOIDED_LOSS}.thenByDescending{it.outcome==ShadowOutcome.WIN}.thenByDescending{it.returnPct}).firstOrNull()
        val recommendation=when{
            best?.outcome==ShadowOutcome.AVOIDED_LOSS->"Shadow ${best.strategyName} would have filtered this loss; keep in shadow until sample-size promotion gate is met."
            best?.outcome==ShadowOutcome.WIN&&!isWin->"Shadow ${best.strategyName} produced a better counterfactual; collect more regime-matched samples before promotion."
            cause==AutopsyCause.STATISTICAL_FAILURE->"No strong external cause identified; treat as statistical model miss rather than forcing a narrative."
            else->"Track ${cause.name.replace('_',' ').lowercase()} across similar regimes before changing production rules."
        }
        val newsEvidence=(relevantNews+broadNews).distinctBy{it.title}.take(6).map{"${it.source}: ${it.title.take(140)}${if(it.publishedAt.isNotBlank())" • ${it.publishedAt}" else ""}"}
        return TradeAutopsyRecord(
            id="AUTOPSY|${i.sourceId}",sourceId=i.sourceId,engineLabel=i.engineLabel,symbol=i.symbol,
            originalOutcome=i.outcome,originalReturnPct=i.originalReturnPct,openedAt=i.openedAt,closedAt=i.closedAt,generatedAt=System.currentTimeMillis(),
            regime=regime.regime,regimeConfidencePct=regime.confidencePct,dominantCause=cause,causeConfidencePct=confidence,
            evidence=evidence.take(10),newsEvidence=newsEvidence,shadowResults=shadows,recommendedRule=recommendation,sessionDate=i.sessionDate
        )
    }

    private fun shadowLab(i:Input,pre:List<Candle>,post:List<Candle>,nifty:List<GlobalBar>,momentum:Double,volRatio:Double,vwap:Double):List<ShadowStrategyResult>{
        val originalWin=i.outcome.equals("WIN",true)
        fun guarded(id:String,name:String,pass:Boolean,evidence:String):ShadowStrategyResult{
            if(!pass)return ShadowStrategyResult(id,name,false,if(originalWin)ShadowOutcome.MISSED_WIN else ShadowOutcome.AVOIDED_LOSS,0.0,evidence)
            val sim=simulate(i.entryPrice,i.stopPrice,i.targetPrice,i.direction,post)
            return ShadowStrategyResult(id,name,true,sim.first,sim.second,evidence)
        }
        val idxRet=if(nifty.size>=2&&nifty.first().open>0.0)(nifty.last().close/nifty.first().open-1.0)*100.0 else 0.0
        val momentumOk=if(i.direction==TradeDirection.LONG)momentum>=0.12 else momentum<=-0.12
        val indexOk=if(i.direction==TradeDirection.LONG)idxRet>=-0.10 else idxRet<=0.10
        val vwapOk=vwap<=0.0 || if(i.direction==TradeDirection.LONG)i.entryPrice>=vwap else i.entryPrice<=vwap
        val out=mutableListOf<ShadowStrategyResult>()
        out+=guarded("MOMENTUM_CONFIRM","Momentum confirmation",momentumOk,"momentum ${"%+.2f".format(momentum)}%")
        out+=guarded("VOLUME_CONFIRM","Relative-volume confirmation",volRatio>=1.20,"volume ${"%.2f".format(volRatio)}x")
        out+=guarded("INDEX_CONFIRM","Nifty direction confirmation",indexOk,"Nifty ${"%+.2f".format(idxRet)}%")
        out+=guarded("VWAP_CONFIRM","VWAP-side confirmation",vwapOk,"VWAP ${if(vwap>0)"₹${"%.2f".format(vwap)}" else "unavailable"}")
        out+=guarded("TRIPLE_CONFIRM","Momentum + volume + index",momentumOk&&volRatio>=1.20&&indexOk,"three independent confirmations")
        out+=waitFiveMinutes(i,post)
        out+=breakoutConfirm(i,pre,post)
        return out
    }

    private fun waitFiveMinutes(i:Input,post:List<Candle>):ShadowStrategyResult{
        val trigger=post.firstOrNull{it.epochSeconds*1000L>=i.executionStartAt+5*60_000L}
            ?:return ShadowStrategyResult("WAIT_5M_CONFIRM","Wait 5m confirmation",false,ShadowOutcome.NO_TRADE,0.0,"No 5-minute confirmation candle")
        val favorable=if(i.direction==TradeDirection.LONG)trigger.close>=trigger.open else trigger.close<=trigger.open
        if(!favorable)return ShadowStrategyResult("WAIT_5M_CONFIRM","Wait 5m confirmation",false,if(i.outcome.equals("WIN",true))ShadowOutcome.MISSED_WIN else ShadowOutcome.AVOIDED_LOSS,0.0,"First 5m confirmation disagreed with trade")
        val riskPct=abs(i.stopPrice/i.entryPrice-1.0);val rewardPct=abs(i.targetPrice/i.entryPrice-1.0)
        val entry=trigger.close;val stop=if(i.direction==TradeDirection.LONG)entry*(1-riskPct) else entry*(1+riskPct);val target=if(i.direction==TradeDirection.LONG)entry*(1+rewardPct) else entry*(1-rewardPct)
        val sim=simulate(entry,stop,target,i.direction,post.filter{it.epochSeconds>=trigger.epochSeconds})
        return ShadowStrategyResult("WAIT_5M_CONFIRM","Wait 5m confirmation",true,sim.first,sim.second,"Entry ${"%.2f".format(entry)} after first 5m directional confirmation")
    }

    private fun breakoutConfirm(i:Input,pre:List<Candle>,post:List<Candle>):ShadowStrategyResult{
        if(pre.isEmpty())return ShadowStrategyResult("BREAKOUT_CONFIRM","Breakout confirmation",false,ShadowOutcome.NO_TRADE,0.0,"Insufficient pre-call candles")
        val hi=pre.takeLast(6).maxOf{it.high};val lo=pre.takeLast(6).minOf{it.low}
        val trigger=post.take(6).firstOrNull{if(i.direction==TradeDirection.LONG)it.high>=hi else it.low<=lo}
        if(trigger==null)return ShadowStrategyResult("BREAKOUT_CONFIRM","Breakout confirmation",false,if(i.outcome.equals("WIN",true))ShadowOutcome.MISSED_WIN else ShadowOutcome.AVOIDED_LOSS,0.0,"No breakout in first 30m")
        val entry=if(i.direction==TradeDirection.LONG)hi else lo;val riskPct=abs(i.stopPrice/i.entryPrice-1.0);val rewardPct=abs(i.targetPrice/i.entryPrice-1.0)
        val stop=if(i.direction==TradeDirection.LONG)entry*(1-riskPct) else entry*(1+riskPct);val target=if(i.direction==TradeDirection.LONG)entry*(1+rewardPct) else entry*(1-rewardPct)
        val sim=simulate(entry,stop,target,i.direction,post.filter{it.epochSeconds>=trigger.epochSeconds})
        return ShadowStrategyResult("BREAKOUT_CONFIRM","Breakout confirmation",true,sim.first,sim.second,"Confirmed beyond prior 30m ${if(i.direction==TradeDirection.LONG)"high" else "low"}")
    }

    private fun simulate(entry:Double,stop:Double,target:Double,direction:TradeDirection,candles:List<Candle>):Pair<ShadowOutcome,Double>{
        if(entry<=0.0||candles.isEmpty())return ShadowOutcome.NO_TRADE to 0.0
        for(c in candles){
            val th=if(direction==TradeDirection.LONG)c.high>=target else c.low<=target
            val sh=if(direction==TradeDirection.LONG)c.low<=stop else c.high>=stop
            if(th&&sh||sh)return ShadowOutcome.LOSS to returnPct(direction,entry,stop)
            if(th)return ShadowOutcome.WIN to returnPct(direction,entry,target)
        }
        val exit=candles.last().close
        return (if(returnPct(direction,entry,exit)>0)ShadowOutcome.WIN else ShadowOutcome.LOSS) to returnPct(direction,entry,exit)
    }

    private fun returnPct(d:TradeDirection,entry:Double,exit:Double)=if(entry<=0||exit<=0)0.0 else if(d==TradeDirection.LONG)(exit/entry-1)*100 else (entry/exit-1)*100
    private fun weightedVwap(c:List<Candle>):Double{var pv=0.0;var v=0.0;for(x in c){if(x.volume<=0)continue;val p=(x.high+x.low+x.close)/3.0;pv+=p*x.volume;v+=x.volume};return if(v>0)pv/v else 0.0}
    private fun window(bars:List<GlobalBar>,start:Long,end:Long):List<GlobalBar>{val a=bars.filter{it.epochSeconds*1000L in (start-30*60_000L)..(max(start,end)+5*60_000L)};return if(a.size>=2)a else bars.takeLast(24)}
    private fun newsRelevant(n:NewsItem,symbol:String):Boolean{val t=(n.symbol+" "+n.title+" "+n.summary).uppercase();return symbol.uppercase() in t}
    private fun marketNewsRelevant(n:NewsItem):Boolean{val t=(n.title+" "+n.summary).lowercase();return listOf("nifty","sensex","rbi","fed","war","geopolit","oil","crude","tariff","rate cut","rate hike","inflation","global market").any{it in t}}
    private fun List<Double>.averageOrZero()=if(isEmpty())0.0 else average()
}
