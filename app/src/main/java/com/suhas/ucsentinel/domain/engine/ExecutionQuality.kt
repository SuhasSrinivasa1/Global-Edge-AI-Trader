package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.Instrument
import com.suhas.globaledgeai.domain.model.Quote
import kotlin.math.abs

/** Shared execution-quality gates used by every actionable scanner. */
object ExecutionQuality {
    const val MIN_PRICE = 20.0
    const val MIN_VOLUME = 50_000L
    const val MIN_TRADED_VALUE = 2_500_000.0 // ₹25 lakh
    const val MAX_SPREAD_PCT = 1.50
    const val MIN_PLAN_SEPARATION_PCT = 0.25
    const val MIN_DISCOVERY_VOLUME = 10_000L
    const val MIN_DISCOVERY_TRADED_VALUE = 500_000.0 // ₹5 lakh: discovery only, not LIVE execution

    fun eligibleInstrument(i: Instrument): Boolean =
        i.exchange == "NSE" && i.segment == "CASH" && i.series == "EQ" && i.buyAllowed

    fun bestBid(q: Quote): Double = q.bidPrice.takeIf { it > 0.0 }
        ?: q.buyDepth.filter { it.price > 0.0 && it.quantity > 0L }.maxOfOrNull { it.price } ?: 0.0

    fun bestAsk(q: Quote): Double = q.offerPrice.takeIf { it > 0.0 }
        ?: q.sellDepth.filter { it.price > 0.0 && it.quantity > 0L }.minOfOrNull { it.price } ?: 0.0

    fun hasBid(q: Quote): Boolean = bestBid(q) > 0.0 && (q.bidQuantity > 0L || q.buyDepth.any { it.price > 0.0 && it.quantity > 0L })

    fun hasAsk(q: Quote): Boolean = bestAsk(q) > 0.0 && (q.offerQuantity > 0L || q.sellDepth.any { it.price > 0.0 && it.quantity > 0L })

    fun spreadPct(q: Quote): Double {
        val bid=bestBid(q); val ask=bestAsk(q)
        if (bid <= 0.0 || ask <= 0.0 || ask < bid) return 999.0
        val mid = (bid + ask) / 2.0
        return if (mid > 0.0) ((ask - bid) / mid * 100.0).coerceAtLeast(0.0) else 999.0
    }

    /** Broad discovery gate. UC/Pressure may legitimately have an asymmetric book, so
     * two-sided depth is evaluated only when a candidate is promoted to LIVE. */
    fun discoveryQuote(q: Quote): Boolean {
        if (!q.lastPrice.isFinite() || q.lastPrice < MIN_PRICE) return false
        if (q.volume < MIN_DISCOVERY_VOLUME) return false
        val tradedValue = q.lastPrice * q.volume
        if (!tradedValue.isFinite() || tradedValue < MIN_DISCOVERY_TRADED_VALUE) return false
        return true
    }

    fun executableQuote(q: Quote, requireTwoSided: Boolean = true): Boolean {
        if (!q.lastPrice.isFinite() || q.lastPrice < MIN_PRICE) return false
        if (q.volume < MIN_VOLUME) return false
        val tradedValue = q.lastPrice * q.volume
        if (!tradedValue.isFinite() || tradedValue < MIN_TRADED_VALUE) return false
        if (requireTwoSided && (!hasBid(q) || !hasAsk(q))) return false
        if (requireTwoSided && spreadPct(q) > MAX_SPREAD_PCT) return false
        return true
    }

    fun usablePlan(entry: Double, stop: Double, target: Double): Boolean {
        if (!entry.isFinite() || !stop.isFinite() || !target.isFinite() || entry <= 0.0 || stop <= 0.0 || target <= 0.0) return false
        val stopGap = abs(stop - entry) / entry * 100.0
        val targetGap = abs(target - entry) / entry * 100.0
        return stopGap >= MIN_PLAN_SEPARATION_PCT && targetGap >= MIN_PLAN_SEPARATION_PCT
    }
}
