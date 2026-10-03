package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import kotlin.math.abs
import kotlin.math.max

class DemandSignalEngine {
    companion object {
        const val MODEL_VERSION = "PRE_PRESSURE_SPIKE_V3_ADAPTIVE_2026_09"
    }

    data class Context(
        val quote: Quote,
        val daily: List<Candle>,
        val intraday: List<Candle>,
        val listingAgeDays: Long?
    )

    data class ScoreBreakdown(
        val setup: Double,
        val acceleration: Double,
        val microstructure: Double,
        val context: Double,
        val riskQuality: Double,
        val riskPenalty: Double,
        val finalScore: Double
    )

    private data class Rule(
        val id: String,
        val name: String,
        val category: String,
        val weight: Double,
        val test: (Context) -> Pair<Boolean, String>
    )

    fun evaluate(c: Context): List<SignalResult> = rules().map { r ->
        val (passed, evidence) = runCatching { r.test(c) }.getOrElse { false to "Unavailable" }
        SignalResult(r.id, r.name, r.category, passed, r.weight, evidence)
    }

    fun scoreBreakdown(
        results: List<SignalResult>,
        adaptivePrecision: Map<String, Double> = emptyMap()
    ): ScoreBreakdown {
        fun effectiveWeight(r: SignalResult): Double {
            val p = adaptivePrecision[r.id] ?: return r.weight
            // Bound adaptive learning so a short hot streak cannot dominate the structural model.
            val multiplier = (0.80 + p / 100.0 * 0.40).coerceIn(0.80, 1.20)
            return r.weight * multiplier
        }

        fun categoryScore(categories: Set<String>): Double {
            val subset = results.filter { it.category in categories }
            val total = subset.sumOf(::effectiveWeight).coerceAtLeast(0.001)
            val passed = subset.filter { it.passed }.sumOf(::effectiveWeight)
            return (passed / total * 100.0).coerceIn(0.0, 100.0)
        }

        val setup = categoryScore(setOf("Compression", "Breakout setup", "Trend context"))
        val acceleration = categoryScore(setOf("Pressure acceleration", "Volume ignition", "Intraday trigger"))
        val micro = categoryScore(setOf("Supply thinning", "Microstructure trigger"))
        val context = categoryScore(setOf("New listing", "Quality"))
        val risk = categoryScore(setOf("Risk control"))
        val penalty = ((100.0 - risk) * 0.14).coerceIn(0.0, 14.0)

        // Main formula: predict the transition INTO a pressure zone, not a stock already locked in one.
        val raw = setup * 0.30 + acceleration * 0.34 + micro * 0.26 + context * 0.10
        val final = (raw - penalty).coerceIn(0.0, 100.0)
        return ScoreBreakdown(setup, acceleration, micro, context, risk, penalty, final)
    }

    fun activeStrategies(results: List<SignalResult>): List<String> =
        results.filter { it.passed }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.weight } }
            .entries.sortedByDescending { it.value }
            .take(6)
            .map { it.key }

    private fun rules(): List<Rule> {
        fun r(
            id: String,
            name: String,
            category: String,
            w: Double = 1.0,
            test: (Context) -> Pair<Boolean, String>
        ) = Rule(id, name, category, w, test)

        return listOf(
            // --- Early microstructure: positive, but deliberately not yet extreme ---
            r("PP001", "Buy/Sell ratio above 1.15x", "Microstructure trigger", 1.5) { c ->
                val x = ratio(c.quote.totalBuyQuantity, c.quote.totalSellQuantity); (x >= 1.15) to "${f(x)}x"
            },
            r("PP002", "Buy/Sell ratio in early-pressure zone 1.3–5x", "Microstructure trigger", 2.5) { c ->
                val x = ratio(c.quote.totalBuyQuantity, c.quote.totalSellQuantity); (x in 1.3..5.0) to "${f(x)}x"
            },
            r("PP003", "Buy/Sell ratio not yet extreme", "Risk control", 3.0) { c ->
                val x = ratio(c.quote.totalBuyQuantity, c.quote.totalSellQuantity); (x < 8.0) to "${f(x)}x"
            },
            r("PP004", "Sell queue still exists", "Risk control", 2.0) { c ->
                (c.quote.totalSellQuantity > 10L) to "Sell ${c.quote.totalSellQuantity}"
            },
            r("PP005", "Visible supply thinner than demand", "Supply thinning", 2.6) { c ->
                val b = c.quote.buyDepth.sumOf { it.quantity }; val s = c.quote.sellDepth.sumOf { it.quantity }
                (b > 0 && s > 0 && b >= s * 1.4) to "Depth ${b}/${s}"
            },
            r("PP006", "Visible supply 35–70% of demand", "Supply thinning", 2.8) { c ->
                val b = c.quote.buyDepth.sumOf { it.quantity }.toDouble(); val s = c.quote.sellDepth.sumOf { it.quantity }.toDouble()
                val q = if (b <= 0) 99.0 else s / b
                (q in 0.35..0.70) to "Supply/Demand ${f(q)}"
            },
            r("PP007", "Bid size leads offer 1.2–4x", "Microstructure trigger", 2.4) { c ->
                val x = ratio(c.quote.bidQuantity, c.quote.offerQuantity); (x in 1.2..4.0) to "Bid/Offer ${f(x)}x"
            },
            r("PP008", "Offer is present but light", "Supply thinning", 2.0) { c ->
                val q = c.quote.offerQuantity; (q in 1..max(25L, c.quote.bidQuantity)) to "Offer $q"
            },
            r("PP009", "Tight bid/offer spread", "Microstructure trigger", 1.8) { c ->
                val x = spreadPct(c.quote); (x in 0.0..0.25) to "Spread ${f(x)}%"
            },
            r("PP010", "Best bid close to LTP", "Microstructure trigger", 1.4) { c ->
                val x = dist(c.quote.bidPrice, c.quote.lastPrice); (x <= 0.20) to "${f(x)}%"
            },

            // --- Volume ignition / pressure acceleration ---
            r("PP011", "Recent 3-bar volume accelerating >1.2x", "Volume ignition", 2.4) { c ->
                val x = recentVolumeAcceleration(c.intraday); (x >= 1.2) to "${f(x)}x"
            },
            r("PP012", "Recent 3-bar volume accelerating >1.5x", "Volume ignition", 2.8) { c ->
                val x = recentVolumeAcceleration(c.intraday); (x >= 1.5) to "${f(x)}x"
            },
            r("PP013", "Recent 3-bar volume accelerating >2x", "Volume ignition", 3.0) { c ->
                val x = recentVolumeAcceleration(c.intraday); (x >= 2.0) to "${f(x)}x"
            },
            r("PP014", "Latest bar volume > prior six average", "Volume ignition", 2.0) { c ->
                val x = lastVolumeRatio(c.intraday); (x >= 1.25) to "${f(x)}x"
            },
            r("PP015", "Daily volume > 1.2x 20D", "Volume ignition", 1.5) { c ->
                val a = Indicators.avgVolume(c.daily.dropLast(1),20); val x = if(a<=0)0.0 else c.quote.volume/a
                (x >= 1.2) to "${f(x)}x"
            },
            r("PP016", "Daily volume > 1.8x 20D", "Volume ignition", 1.7) { c ->
                val a = Indicators.avgVolume(c.daily.dropLast(1),20); val x = if(a<=0)0.0 else c.quote.volume/a
                (x >= 1.8) to "${f(x)}x"
            },
            r("PP017", "Recent price velocity positive", "Pressure acceleration", 2.2) { c ->
                val x = recentReturn(c.intraday,3); (x >= 0.20) to "3-bar ${f(x)}%"
            },
            r("PP018", "Recent price velocity >0.5%", "Pressure acceleration", 2.5) { c ->
                val x = recentReturn(c.intraday,3); (x >= 0.50) to "3-bar ${f(x)}%"
            },
            r("PP019", "Recent return exceeds prior return", "Pressure acceleration", 2.4) { c ->
                val a = recentReturn(c.intraday,3); val b = priorReturn(c.intraday,3)
                (a > b + 0.15) to "Recent ${f(a)}% vs prior ${f(b)}%"
            },
            r("PP020", "Higher closes in last four bars", "Pressure acceleration", 2.0) { c ->
                val n = higherCloses(c.intraday,4); (n >= 2) to "$n higher closes"
            },
            r("PP021", "Higher lows in last four bars", "Pressure acceleration", 2.1) { c ->
                val n = higherLows(c.intraday,4); (n >= 2) to "$n higher lows"
            },
            r("PP022", "Green-bar ratio >=67%", "Intraday trigger", 1.8) { c ->
                val x = Indicators.greenCandleRatio(c.intraday,9); (x >= 0.67) to "${f(x*100)}%"
            },
            r("PP023", "Latest candle closes near high", "Intraday trigger", 2.2) { c ->
                val x = c.intraday.lastOrNull(); val loc = if(x==null)0.0 else Indicators.closeLocation(x)
                (loc >= 0.80) to "CLV ${f(loc)}"
            },
            r("PP024", "Latest candle positive body", "Intraday trigger", 1.4) { c ->
                val x=c.intraday.lastOrNull(); (x!=null && x.close>x.open) to "Latest ${x?.open ?: 0.0}/${x?.close ?: 0.0}"
            },

            // --- Compression before expansion ---
            r("PP025", "Prior range compression", "Compression", 2.5) { c ->
                val x = compressionRatio(c.intraday); (x <= 0.80) to "Compression ${f(x)}"
            },
            r("PP026", "Strong prior range compression", "Compression", 2.8) { c ->
                val x = compressionRatio(c.intraday); (x <= 0.65) to "Compression ${f(x)}"
            },
            r("PP027", "Recent range re-expansion", "Compression", 2.2) { c ->
                val x = expansionAfterCompression(c.intraday); (x >= 1.15) to "Expansion ${f(x)}x"
            },
            r("PP028", "ATR controlled below 7%", "Risk control", 1.2) { c ->
                val x=Indicators.atrPercent(c.daily); (x in 0.01..7.0) to "ATR ${f(x)}%"
            },
            r("PP029", "Intraday lock ratio below 35%", "Risk control", 1.8) { c ->
                val x=Indicators.lockedCandleRatio(c.intraday,12); (x < 0.35) to "Locked ${f(x*100)}%"
            },

            // --- Breakout setup: close to trigger, not already fully extended ---
            r("PP030", "Within 1.5% below intraday breakout", "Breakout setup", 2.5) { c ->
                val x = breakoutDistance(c.intraday); (x in 0.0..1.5) to "${f(x)}% below trigger"
            },
            r("PP031", "Within 0.75% below intraday breakout", "Breakout setup", 2.8) { c ->
                val x = breakoutDistance(c.intraday); (x in 0.0..0.75) to "${f(x)}% below trigger"
            },
            r("PP032", "20D breakout proximity <=3%", "Breakout setup", 1.8) { c ->
                val h=Indicators.highest(c.daily.dropLast(1),20); val x=if(h<=0)999.0 else (h-c.quote.lastPrice)/h*100
                (x in -0.5..3.0) to "20D distance ${f(x)}%"
            },
            r("PP033", "50D breakout proximity <=4%", "Breakout setup", 1.4) { c ->
                val h=Indicators.highest(c.daily.dropLast(1),50); val x=if(h<=0)999.0 else (h-c.quote.lastPrice)/h*100
                (x in -0.5..4.0) to "50D distance ${f(x)}%"
            },
            r("PP034", "Day high within 2%", "Breakout setup", 1.8) { c ->
                val x=dist(c.quote.lastPrice,c.quote.ohlc.high); (x<=2.0) to "${f(x)}%"
            },

            // --- Trend context, used as context not as the trigger itself ---
            r("PP035", "Price above SMA5", "Trend context", 1.0) { c ->
                val s=Indicators.sma(c.daily,5); (s>0&&c.quote.lastPrice>s) to "SMA5 ${f(s)}"
            },
            r("PP036", "Price above SMA10", "Trend context", 1.0) { c ->
                val s=Indicators.sma(c.daily,10); (s>0&&c.quote.lastPrice>s) to "SMA10 ${f(s)}"
            },
            r("PP037", "SMA5 above SMA10", "Trend context", 1.2) { c ->
                val a=Indicators.sma(c.daily,5); val b=Indicators.sma(c.daily,10); (a>b&&b>0) to "${f(a)} > ${f(b)}"
            },
            r("PP038", "5D return positive but not runaway", "Trend context", 1.2) { c ->
                val x=periodReturn(c.daily,5); (x in 0.0..25.0) to "5D ${f(x)}%"
            },
            r("PP039", "RSI constructive 52–82", "Trend context", 1.1) { c ->
                val x=Indicators.rsi(c.daily); (x in 52.0..82.0) to "RSI ${f(x)}"
            },

            // --- Explicit early-stage / anti-late-entry rules ---
            r("PP040", "Price not already at upper circuit", "Risk control", 3.5) { c ->
                val x=dist(c.quote.lastPrice,c.quote.upperCircuit); (x >= 0.75) to "UC distance ${f(x)}%"
            },
            r("PP041", "UC still 1–10% away", "Risk control", 2.3) { c ->
                val x=dist(c.quote.lastPrice,c.quote.upperCircuit); (x in 1.0..10.0) to "UC distance ${f(x)}%"
            },
            r("PP042", "Day move below 10%", "Risk control", 2.8) { c ->
                (c.quote.dayChangePercent < 10.0) to "Day ${f(c.quote.dayChangePercent)}%"
            },
            r("PP043", "Day move already positive", "Pressure acceleration", 1.1) { c ->
                (c.quote.dayChangePercent >= 0.3) to "Day ${f(c.quote.dayChangePercent)}%"
            },
            r("PP044", "Day move in pre-spike zone 0.5–6%", "Pressure acceleration", 2.1) { c ->
                (c.quote.dayChangePercent in 0.5..6.0) to "Day ${f(c.quote.dayChangePercent)}%"
            },
            r("PP045", "RSI below 90", "Risk control", 1.8) { c ->
                val x=Indicators.rsi(c.daily); (x < 90) to "RSI ${f(x)}"
            },
            r("PP046", "Sell depth not completely empty", "Risk control", 1.6) { c ->
                (c.quote.sellDepth.sumOf{it.quantity} > 0) to "Sell depth ${c.quote.sellDepth.sumOf{it.quantity}}"
            },

            // --- New listing regime ---
            r("PP047", "New listing <=45 days", "New listing", 1.8) { c ->
                val a=c.listingAgeDays; (a!=null&&a<=45) to "Age ${a?:-1}d"
            },
            r("PP048", "New listing <=15 days", "New listing", 2.3) { c ->
                val a=c.listingAgeDays; (a!=null&&a<=15) to "Age ${a?:-1}d"
            },
            r("PP049", "Fresh listing <=7 days", "New listing", 2.6) { c ->
                val a=c.listingAgeDays; (a!=null&&a<=7) to "Age ${a?:-1}d"
            },
            r("PP050", "Fresh listing has positive intraday velocity", "New listing", 2.0) { c ->
                val a=c.listingAgeDays; val x=recentReturn(c.intraday,3); (a!=null&&a<=15&&x>0.2) to "Age ${a?:-1}d • ${f(x)}%"
            },

            // --- Quality / data integrity ---
            r("PP051", "Valid traded volume", "Quality", 0.8) { c ->
                (c.quote.volume>0) to "Volume ${c.quote.volume}"
            },
            r("PP052", "Valid quote band", "Quality", 0.8) { c ->
                (c.quote.upperCircuit>c.quote.lowerCircuit&&c.quote.lastPrice>0) to "Band ${f(c.quote.lowerCircuit)}–${f(c.quote.upperCircuit)}"
            },
            r("PP053", "Recent intraday history available", "Quality", 1.3) { c ->
                (c.intraday.size>=6) to "Bars ${c.intraday.size}"
            },
            r("PP054", "Daily history available", "Quality", 0.9) { c ->
                (c.daily.size>=5 || (c.listingAgeDays?:999)<=7) to "Daily bars ${c.daily.size}"
            },
            r("PP055", "Last trade timestamp available", "Quality", 0.5) { c ->
                (c.quote.lastTradeTime>0) to "Trade ${c.quote.lastTradeTime}"
            },

            // --- Strategy combinations: intentionally cross-family ---
            r("PP056", "Compression + volume ignition combo", "Pressure acceleration", 3.0) { c ->
                val comp=compressionRatio(c.intraday); val vol=recentVolumeAcceleration(c.intraday)
                (comp<=0.8&&vol>=1.35) to "Comp ${f(comp)} • Vol ${f(vol)}x"
            },
            r("PP057", "Breakout proximity + higher lows combo", "Breakout setup", 3.0) { c ->
                val d=breakoutDistance(c.intraday); val hl=higherLows(c.intraday,4)
                (d in 0.0..1.25&&hl>=2) to "Trigger ${f(d)}% • HL $hl"
            },
            r("PP058", "Supply thinning + bid lead combo", "Supply thinning", 3.2) { c ->
                val depthRatio=ratio(c.quote.buyDepth.sumOf{it.quantity},c.quote.sellDepth.sumOf{it.quantity})
                val topRatio=ratio(c.quote.bidQuantity,c.quote.offerQuantity)
                (depthRatio>=1.4&&topRatio>=1.2&&topRatio<=5.0) to "Depth ${f(depthRatio)}x • Top ${f(topRatio)}x"
            },
            r("PP059", "Velocity + volume acceleration combo", "Intraday trigger", 3.3) { c ->
                val ret=recentReturn(c.intraday,3); val vol=recentVolumeAcceleration(c.intraday)
                (ret>=0.25&&vol>=1.4) to "Return ${f(ret)}% • Vol ${f(vol)}x"
            },
            r("PP060", "Pre-pressure sweet spot", "Microstructure trigger", 4.0) { c ->
                val bs=ratio(c.quote.totalBuyQuantity,c.quote.totalSellQuantity)
                val uc=dist(c.quote.lastPrice,c.quote.upperCircuit)
                val vol=recentVolumeAcceleration(c.intraday)
                (bs in 1.3..6.0 && uc in 1.0..10.0 && vol>=1.2 && c.quote.totalSellQuantity>10) to
                    "B/S ${f(bs)}x • UC ${f(uc)}% • Vol ${f(vol)}x"
            }
        )
    }

    private fun ratio(a:Long,b:Long)=if(b<=0) if(a>0)99.0 else 0.0 else a.toDouble()/b.toDouble()
    private fun dist(a:Double,b:Double)=if(b<=0)999.0 else abs(a-b)/abs(b)*100
    private fun f(x:Double)="%.2f".format(x)

    private fun spreadPct(q:Quote):Double {
        if(q.lastPrice<=0 || q.bidPrice<=0 || q.offerPrice<=0 || q.offerPrice<q.bidPrice) return 999.0
        return (q.offerPrice-q.bidPrice)/q.lastPrice*100.0
    }

    private fun avgRangePct(candles:List<Candle>):Double = if(candles.isEmpty())0.0 else candles.map {
        if(it.close<=0)0.0 else (it.high-it.low)/it.close*100.0
    }.average()

    private fun recentVolumeAcceleration(candles:List<Candle>):Double {
        if(candles.size<9) return 0.0
        val recent=candles.takeLast(3).map{it.volume.toDouble()}.average()
        val prior=candles.dropLast(3).takeLast(6).map{it.volume.toDouble()}.average()
        return if(prior<=0)0.0 else recent/prior
    }

    private fun lastVolumeRatio(candles:List<Candle>):Double {
        if(candles.size<7) return 0.0
        val prior=candles.dropLast(1).takeLast(6).map{it.volume.toDouble()}.average()
        return if(prior<=0)0.0 else candles.last().volume/prior
    }

    private fun recentReturn(candles:List<Candle>,bars:Int):Double {
        val x=candles.takeLast(bars+1)
        if(x.size<2||x.first().close<=0) return 0.0
        return (x.last().close/x.first().close-1.0)*100.0
    }

    private fun priorReturn(candles:List<Candle>,bars:Int):Double {
        if(candles.size<bars*2+1) return 0.0
        val x=candles.dropLast(bars).takeLast(bars+1)
        if(x.size<2||x.first().close<=0) return 0.0
        return (x.last().close/x.first().close-1.0)*100.0
    }

    private fun higherCloses(candles:List<Candle>,bars:Int):Int =
        candles.takeLast(bars).zipWithNext().count{(a,b)->b.close>a.close}

    private fun higherLows(candles:List<Candle>,bars:Int):Int =
        candles.takeLast(bars).zipWithNext().count{(a,b)->b.low>a.low}

    private fun compressionRatio(candles:List<Candle>):Double {
        if(candles.size<15) return 1.0
        val recentPrior=avgRangePct(candles.dropLast(3).takeLast(6))
        val baseline=avgRangePct(candles.dropLast(9).takeLast(6))
        return if(baseline<=0)1.0 else recentPrior/baseline
    }

    private fun expansionAfterCompression(candles:List<Candle>):Double {
        if(candles.size<9) return 0.0
        val recent=avgRangePct(candles.takeLast(3))
        val prior=avgRangePct(candles.dropLast(3).takeLast(6))
        return if(prior<=0)0.0 else recent/prior
    }

    private fun breakoutDistance(candles:List<Candle>):Double {
        if(candles.size<8) return 999.0
        val trigger=candles.dropLast(2).takeLast(12).maxOfOrNull{it.high} ?: return 999.0
        val price=candles.last().close
        if(trigger<=0||price<=0) return 999.0
        return (trigger-price)/trigger*100.0
    }

    private fun periodReturn(candles:List<Candle>,days:Int):Double {
        val x=candles.takeLast(days+1)
        if(x.size<2||x.first().close<=0) return 0.0
        return (x.last().close/x.first().close-1.0)*100.0
    }
}
