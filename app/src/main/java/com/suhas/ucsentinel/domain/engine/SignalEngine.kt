package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import kotlin.math.abs
import kotlin.math.max

class SignalEngine {
    companion object { const val MODEL_VERSION = "PRE_UC_PREDICTOR_V3_2026_09" }


    data class Context(
        val quote: Quote,
        val daily: List<Candle>,
        val intraday: List<Candle>,
        val isPostListing: Boolean
    )

    private data class Rule(
        val id: String,
        val name: String,
        val category: String,
        val weight: Double,
        val test: (Context) -> Pair<Boolean, String>
    )

    fun evaluate(context: Context): List<SignalResult> =
        rules().map { rule ->
            val (passed, evidence) = runCatching { rule.test(context) }
                .getOrElse { false to "Unavailable: ${it.message.orEmpty()}" }
            SignalResult(
                id = rule.id,
                name = rule.name,
                category = rule.category,
                passed = passed,
                weight = rule.weight,
                evidence = evidence
            )
        }

    fun score(
        results: List<SignalResult>,
        adaptivePrecision: Map<String, Double> = emptyMap()
    ): Double {
        fun effectiveWeight(result: SignalResult): Double {
            val precision = adaptivePrecision[result.id] ?: return result.weight
            // Daily learning is deliberately bounded so recent history cannot overpower
            // the structural base model. 50% precision ~= neutral; 100% adds 25%.
            val multiplier = (0.75 + (precision / 100.0) * 0.50).coerceIn(0.75, 1.25)
            return result.weight * multiplier
        }

        val total = results.sumOf { effectiveWeight(it) }.coerceAtLeast(1.0)
        val passed = results.filter { it.passed }.sumOf { effectiveWeight(it) }
        return (passed / total * 100.0).coerceIn(0.0, 100.0)
    }

    private fun rules(): List<Rule> {
        fun r(
            id: String,
            name: String,
            category: String,
            weight: Double = 1.0,
            test: (Context) -> Pair<Boolean, String>
        ) = Rule(id, name, category, weight, test)

        return listOf(
            r("UC001","Pre-UC headroom 1-6%","Circuit",4.0) { c ->
                val d=pctDistance(c.quote.lastPrice,c.quote.upperCircuit)
                (c.quote.upperCircuit>c.quote.lastPrice && d in 1.0..6.0) to ("UC headroom "+fmt(d)+"%")
            },
            r("UC002","Pre-UC sweet spot 1-3.5%","Circuit",3.0) { c ->
                val d=pctDistance(c.quote.lastPrice,c.quote.upperCircuit)
                (c.quote.upperCircuit>c.quote.lastPrice && d in 1.0..3.5) to ("UC headroom "+fmt(d)+"%")
            },
            r("UC003","Not locked at circuit","Circuit",2.0) { c ->
                val d=pctDistance(c.quote.lastPrice,c.quote.upperCircuit)
                (c.quote.upperCircuit<=0.0 || d>=0.75) to ("UC headroom "+fmt(d)+"%")
            },
            r("UC004","Approaching UC with room to enter","Circuit",1.0) { c ->
                val d=pctDistance(c.quote.lastPrice,c.quote.upperCircuit)
                (c.quote.upperCircuit>c.quote.lastPrice && d in 0.75..8.0) to ("UC headroom "+fmt(d)+"%")
            },
            r("UC005","Close equals day high","Circuit",2.0) { c ->
                val d = pctDistance(c.quote.lastPrice, c.quote.ohlc.high)
                (d <= 0.05) to "High distance ${fmt(d)}%"
            },
            r("UC006","Sell-side liquidity still available","Depth",4.0) { c ->
                (c.quote.totalSellQuantity>0L && (c.quote.offerQuantity>0L || c.quote.sellDepth.any{it.quantity>0L})) to
                    ("Buy "+c.quote.totalBuyQuantity+", Sell "+c.quote.totalSellQuantity)
            },
            r("UC007","Buy/Sell ratio >= 1.5","Depth",3.0) { c ->
                val x=ratio(c.quote.totalBuyQuantity,c.quote.totalSellQuantity)
                (x>=1.5) to ("Ratio "+fmt(x)+"x")
            },
            r("UC008","Buy/Sell ratio >= 2.5","Depth",3.0) { c ->
                val x=ratio(c.quote.totalBuyQuantity,c.quote.totalSellQuantity)
                (x>=2.5) to ("Ratio "+fmt(x)+"x")
            },
            r("UC009","Best bid supports current price","Depth",2.5) { c ->
                val d=pctDistance(c.quote.bidPrice,c.quote.lastPrice)
                (c.quote.bidPrice>0.0 && d<=0.50) to ("Bid support "+fmt(d)+"%")
            },
            r("UC010","Executable offer still present","Depth",2.5) { c ->
                (c.quote.offerPrice>0.0 && c.quote.offerQuantity>0L) to ("Offer qty "+c.quote.offerQuantity)
            },
            r("UC011","Top depth sell side tradable","Depth",2.0) { c ->
                val q=c.quote.sellDepth.sumOf{it.quantity}
                (q>0L) to ("Depth sell qty "+q)
            },
            r("UC012","Top depth buy queue present","Depth",1.5) { c ->
                (c.quote.buyDepth.sumOf { it.quantity } > 0) to
                        "Depth buy qty ${c.quote.buyDepth.sumOf { it.quantity }}"
            },
            r("UC013","Early momentum >= 1.5%","Momentum",2.0) { c ->
                (c.quote.dayChangePercent>=1.5) to ("Change "+fmt(c.quote.dayChangePercent)+"%")
            },
            r("UC014","Strong early momentum >= 3%","Momentum",2.2) { c ->
                (c.quote.dayChangePercent>=3.0) to ("Change "+fmt(c.quote.dayChangePercent)+"%")
            },
            r("UC015","Acceleration >= 5%","Momentum",2.4) { c ->
                (c.quote.dayChangePercent>=5.0) to ("Change "+fmt(c.quote.dayChangePercent)+"%")
            },
            r("UC016","Positive open-to-close","Momentum",1.0) { c ->
                (c.quote.lastPrice >= c.quote.ohlc.open) to "Open ${c.quote.ohlc.open}"
            },
            r("UC017","Strong close location","Momentum",1.2) { c ->
                val loc = if (c.quote.ohlc.high == c.quote.ohlc.low) 1.0
                else (c.quote.lastPrice - c.quote.ohlc.low) / (c.quote.ohlc.high - c.quote.ohlc.low)
                (loc >= 0.95) to "CLV ${fmt(loc)}"
            },
            r("UC018","20-day breakout","Breakout",2.2) { c ->
                val h = Indicators.highest(c.daily.dropLast(1), 20)
                (h > 0 && c.quote.lastPrice >= h) to "20D high ${fmt(h)}"
            },
            r("UC019","50-day breakout","Breakout",2.2) { c ->
                val h = Indicators.highest(c.daily.dropLast(1), 50)
                (h > 0 && c.quote.lastPrice >= h) to "50D high ${fmt(h)}"
            },
            r("UC020","Near 52-week high","Breakout",1.7) { c ->
                val d = pctDistance(c.quote.lastPrice, c.quote.week52High)
                (c.quote.week52High > 0 && d <= 3.0) to "52W distance ${fmt(d)}%"
            },
            r("UC021","Above SMA5","Trend",1.0) { c ->
                val s = Indicators.sma(c.daily, 5)
                (s > 0 && c.quote.lastPrice > s) to "SMA5 ${fmt(s)}"
            },
            r("UC022","Above SMA10","Trend",1.0) { c ->
                val s = Indicators.sma(c.daily, 10)
                (s > 0 && c.quote.lastPrice > s) to "SMA10 ${fmt(s)}"
            },
            r("UC023","Above SMA20","Trend",1.0) { c ->
                val s = Indicators.sma(c.daily, 20)
                (s > 0 && c.quote.lastPrice > s) to "SMA20 ${fmt(s)}"
            },
            r("UC024","SMA5 > SMA10","Trend",1.2) { c ->
                val a = Indicators.sma(c.daily, 5)
                val b = Indicators.sma(c.daily, 10)
                (a > b && b > 0) to "SMA5 ${fmt(a)}, SMA10 ${fmt(b)}"
            },
            r("UC025","SMA10 > SMA20","Trend",1.2) { c ->
                val a = Indicators.sma(c.daily, 10)
                val b = Indicators.sma(c.daily, 20)
                (a > b && b > 0) to "SMA10 ${fmt(a)}, SMA20 ${fmt(b)}"
            },
            r("UC026","3-day positive return","Trend",1.1) { c ->
                val data = c.daily.takeLast(4)
                val ret = if (data.size < 2) 0.0 else (data.last().close / data.first().close - 1) * 100
                (ret > 0) to "3D ${fmt(ret)}%"
            },
            r("UC027","5-day positive return","Trend",1.1) { c ->
                val data = c.daily.takeLast(6)
                val ret = if (data.size < 2) 0.0 else (data.last().close / data.first().close - 1) * 100
                (ret > 0) to "5D ${fmt(ret)}%"
            },
            r("UC028","10-day positive return","Trend",1.0) { c ->
                val data = c.daily.takeLast(11)
                val ret = if (data.size < 2) 0.0 else (data.last().close / data.first().close - 1) * 100
                (ret > 0) to "10D ${fmt(ret)}%"
            },
            r("UC029","Volume > 1.5x 20D avg","Volume",2.0) { c ->
                val avg = Indicators.avgVolume(c.daily.dropLast(1), 20)
                val vr = if (avg <= 0) 0.0 else c.quote.volume / avg
                (vr >= 1.5) to "Volume ${fmt(vr)}x"
            },
            r("UC030","Volume > 2x 20D avg","Volume",2.2) { c ->
                val avg = Indicators.avgVolume(c.daily.dropLast(1), 20)
                val vr = if (avg <= 0) 0.0 else c.quote.volume / avg
                (vr >= 2.0) to "Volume ${fmt(vr)}x"
            },
            r("UC031","Volume > 4x 20D avg","Volume",2.5) { c ->
                val avg = Indicators.avgVolume(c.daily.dropLast(1), 20)
                val vr = if (avg <= 0) 0.0 else c.quote.volume / avg
                (vr >= 4.0) to "Volume ${fmt(vr)}x"
            },
            r("UC032","Volume above 5D avg","Volume",1.2) { c ->
                val avg = Indicators.avgVolume(c.daily.dropLast(1), 5)
                (avg > 0 && c.quote.volume > avg) to "5D avg ${fmt(avg)}"
            },
            r("UC033","RSI > 60","Momentum",1.0) { c ->
                val rsi = Indicators.rsi(c.daily)
                (rsi > 60) to "RSI ${fmt(rsi)}"
            },
            r("UC034","RSI > 70","Momentum",1.0) { c ->
                val rsi = Indicators.rsi(c.daily)
                (rsi > 70) to "RSI ${fmt(rsi)}"
            },
            r("UC035","RSI below 95","Risk",0.8) { c ->
                val rsi = Indicators.rsi(c.daily)
                (rsi < 95) to "RSI ${fmt(rsi)}"
            },
            r("UC036","ATR < 8%","Risk",0.8) { c ->
                val atr = Indicators.atrPercent(c.daily)
                (atr in 0.01..8.0) to "ATR ${fmt(atr)}%"
            },
            r("UC037","Recent candles green >= 70%","Intraday",1.5) { c ->
                val x = Indicators.greenCandleRatio(c.intraday, 10)
                (x >= 0.7) to "Green ${fmt(x * 100)}%"
            },
            r("UC038","Recent candles green >= 60%","Intraday",2.0) { c ->
                val x=Indicators.greenCandleRatio(c.intraday,8)
                (x>=0.60) to ("Green "+fmt(x*100)+"%")
            },
            r("UC039","Recent candles green >= 75%","Intraday",2.5) { c ->
                val x=Indicators.greenCandleRatio(c.intraday,8)
                (x>=0.75) to ("Green "+fmt(x*100)+"%")
            },
            r("UC040","Last candle at high","Intraday",1.5) { c ->
                val x = c.intraday.lastOrNull()
                val pass = x != null && pctDistance(x.close, x.high) <= 0.08
                pass to if (x == null) "No candle" else "Last ${fmt(x.close)} / ${fmt(x.high)}"
            },
            r("UC041","Last 3 candles non-declining","Intraday",1.3) { c ->
                val x = c.intraday.takeLast(3)
                val pass = x.size == 3 && x.zipWithNext().all { (a,b) -> b.close >= a.close * 0.999 }
                pass to "Closes ${x.joinToString { fmt(it.close) }}"
            },
            r("UC042","No deep intraday fade","Intraday",1.4) { c ->
                val dayHigh = c.intraday.maxOfOrNull { it.high } ?: 0.0
                val dayLow = c.intraday.minOfOrNull { it.low } ?: 0.0
                val drop = if (dayHigh <= 0) 100.0 else (dayHigh - dayLow) / dayHigh * 100
                (drop <= 8.0) to "Range drawdown ${fmt(drop)}%"
            },
            r("UC043","1+ circuit-like prior days","Circuit history",2.0) { c ->
                val n = Indicators.consecutiveCircuitLikeDays(c.daily)
                (n >= 1) to "Streak $n"
            },
            r("UC044","2+ circuit-like prior days","Circuit history",2.2) { c ->
                val n = Indicators.consecutiveCircuitLikeDays(c.daily)
                (n >= 2) to "Streak $n"
            },
            r("UC045","3+ circuit-like prior days","Circuit history",2.0) { c ->
                val n = Indicators.consecutiveCircuitLikeDays(c.daily)
                (n >= 3) to "Streak $n"
            },
            r("UC046","Positive last 2 daily candles","Candles",1.1) { c ->
                val x = c.daily.takeLast(2)
                (x.size == 2 && x.all { it.close >= it.open }) to "2-candle check"
            },
            r("UC047","Positive last 3 daily candles","Candles",1.2) { c ->
                val x = c.daily.takeLast(3)
                (x.size == 3 && x.count { it.close >= it.open } >= 2) to "Green ${x.count { it.close >= it.open }}/3"
            },
            r("UC048","Latest daily close in top 10% range","Candles",1.2) { c ->
                val x = c.daily.lastOrNull()
                val loc = if (x == null) 0.0 else Indicators.closeLocation(x)
                (loc >= 0.9) to "Location ${fmt(loc)}"
            },
            r("UC049","Latest daily candle not bearish wide-range","Candles",1.0) { c ->
                val x = c.daily.lastOrNull()
                val pass = x != null && !(x.close < x.open && (x.high - x.low) / x.close.coerceAtLeast(0.01) > 0.08)
                pass to "Daily candle risk"
            },
            r("UC050","Market cap present","Quality",0.5) { c ->
                (c.quote.marketCap > 0) to "Market cap ${fmt(c.quote.marketCap)}"
            },
            r("UC051","Non-zero traded volume","Quality",0.7) { c ->
                (c.quote.volume > 0) to "Volume ${c.quote.volume}"
            },
            r("UC052","Recent trade timestamp present","Quality",0.6) { c ->
                (c.quote.lastTradeTime > 0L) to "Trade time ${c.quote.lastTradeTime}"
            },
            r("UC053","Post-listing model match","IPO",2.5) { c ->
                c.isPostListing to if (c.isPostListing) "Post-listing model" else "Seasoned"
            },
            r("UC054","Seasoned model match","Seasoned",1.0) { c ->
                (!c.isPostListing) to if (!c.isPostListing) "Seasoned model" else "Post-listing"
            },
            r("UC055","Price above previous close","Momentum",1.0) { c ->
                (c.quote.lastPrice > c.quote.previousClose) to "Prev close ${fmt(c.quote.previousClose)}"
            },
            r("UC056","Opening gap non-negative","Momentum",1.0) { c ->
                val prev = c.quote.previousClose
                val gap = if (prev <= 0) 0.0 else (c.quote.ohlc.open / prev - 1) * 100
                (gap >= 0) to "Gap ${fmt(gap)}%"
            },
            r("UC057","Not >15% below 52W high","Quality",0.6) { c ->
                val d = pctDistance(c.quote.lastPrice, c.quote.week52High)
                (c.quote.week52High <= 0 || d <= 15.0) to "52W distance ${fmt(d)}%"
            },
            r("UC058","Buy quantity > bid quantity","Depth",0.9) { c ->
                (c.quote.totalBuyQuantity >= c.quote.bidQuantity) to
                        "Total buy ${c.quote.totalBuyQuantity}, bid ${c.quote.bidQuantity}"
            },
            r("UC059","Upper circuit is valid positive price","Validation",1.5) { c ->
                (c.quote.upperCircuit > 0 && c.quote.upperCircuit >= c.quote.lastPrice * 0.999) to
                        "UC ${fmt(c.quote.upperCircuit)}"
            },
            r("UC060","No abnormal quote inversion","Validation",1.5) { c ->
                (c.quote.lowerCircuit <= c.quote.upperCircuit && c.quote.lastPrice > 0) to
                        "LC ${fmt(c.quote.lowerCircuit)}, UC ${fmt(c.quote.upperCircuit)}"
            }
        )
    }

    private fun ratio(a: Long, b: Long): Double =
        if (b <= 0L) if (a > 0L) 99.0 else 0.0 else a.toDouble() / b.toDouble()

    private fun pctDistance(a: Double, b: Double): Double =
        if (b == 0.0) 999.0 else abs(a - b) / abs(b) * 100.0

    private fun fmt(x: Double): String = "%.2f".format(x)
}
