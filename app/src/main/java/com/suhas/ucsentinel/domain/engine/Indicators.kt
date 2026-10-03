package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max

object Indicators {
    fun returns(candles: List<Candle>): List<Double> =
        candles.zipWithNext().map { (a, b) ->
            if (a.close == 0.0) 0.0 else (b.close / a.close - 1.0) * 100.0
        }

    fun sma(candles: List<Candle>, period: Int): Double {
        val data = candles.takeLast(period)
        return if (data.isEmpty()) 0.0 else data.sumOf { it.close } / data.size
    }

    fun avgVolume(candles: List<Candle>, period: Int): Double {
        val data = candles.takeLast(period)
        return if (data.isEmpty()) 0.0 else data.sumOf { it.volume.toDouble() } / data.size
    }

    fun highest(candles: List<Candle>, period: Int): Double =
        candles.takeLast(period).maxOfOrNull { it.high } ?: 0.0

    fun lowest(candles: List<Candle>, period: Int): Double =
        candles.takeLast(period).minOfOrNull { it.low } ?: 0.0

    fun atrPercent(candles: List<Candle>, period: Int = 14): Double {
        val data = candles.takeLast(period + 1)
        if (data.size < 2) return 0.0
        val trs = data.zipWithNext().map { (prev, cur) ->
            max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close)))
        }
        val atr = trs.average()
        val close = data.last().close
        return if (close == 0.0) 0.0 else atr / close * 100.0
    }

    fun rsi(candles: List<Candle>, period: Int = 14): Double {
        val data = candles.takeLast(period + 1)
        if (data.size < 2) return 50.0
        var gains = 0.0
        var losses = 0.0
        data.zipWithNext().forEach { (a, b) ->
            val d = b.close - a.close
            if (d >= 0) gains += d else losses -= d
        }
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun consecutiveCircuitLikeDays(candles: List<Candle>): Int {
        if (candles.size < 2) return 0
        var count = 0
        for (i in candles.lastIndex downTo 1) {
            val prev = candles[i - 1].close
            val cur = candles[i]
            if (prev <= 0) break
            val ret = (cur.close / prev - 1.0) * 100.0
            val closesAtHigh = abs(cur.close - cur.high) / cur.close.coerceAtLeast(0.01) < 0.0015
            val nearCommonBand = ret >= 4.7
            if (closesAtHigh && nearCommonBand) count++ else break
        }
        return count
    }

    fun lockedCandleRatio(candles: List<Candle>, lookback: Int = 12): Double {
        val data = candles.takeLast(lookback)
        if (data.isEmpty()) return 0.0
        val locked = data.count {
            val span = (it.high - it.low).coerceAtLeast(0.0001)
            val closeNearHigh = (it.high - it.close) / it.close.coerceAtLeast(0.01) < 0.001
            val tinyRange = span / it.close.coerceAtLeast(0.01) < 0.002
            closeNearHigh && tinyRange
        }
        return locked.toDouble() / data.size
    }

    fun greenCandleRatio(candles: List<Candle>, lookback: Int = 12): Double {
        val data = candles.takeLast(lookback)
        if (data.isEmpty()) return 0.0
        return data.count { it.close >= it.open }.toDouble() / data.size
    }

    fun closeLocation(candle: Candle): Double {
        val range = candle.high - candle.low
        return if (range <= 0) 1.0 else (candle.close - candle.low) / range
    }
}
