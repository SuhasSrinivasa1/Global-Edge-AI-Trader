package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.data.remote.GrowwClient
import com.suhas.globaledgeai.domain.model.Candle
import com.suhas.globaledgeai.domain.model.ReplayResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class ReplayEngine(
    private val growwClient: GrowwClient
) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun replaySymbol(
        accessToken: String,
        symbol: String,
        lookbackDays: Long = 30
    ): ReplayResult {
        val end = LocalDate.now().plusDays(1).atStartOfDay().format(fmt)
        val start = LocalDate.now().minusDays(lookbackDays + 20).atStartOfDay().format(fmt)

        val candles = growwClient.getHistoricalCandles(
            accessToken = accessToken,
            symbol = symbol,
            startTime = start,
            endTime = end,
            interval = "1day"
        )

        if (candles.size < 3) {
            return ReplayResult(symbol, candles.size, 0, 0, 0, 0.0, listOf("Not enough history"))
        }

        var predictions = 0
        var actual = 0
        var truePositives = 0

        fun isCircuitLike(prev: Candle, cur: Candle): Boolean {
            if (prev.close <= 0.0 || cur.close <= 0.0) return false
            val ret = (cur.close / prev.close - 1.0) * 100.0
            val closeAtHigh = abs(cur.close - cur.high) / cur.close < 0.0015
            return ret >= 4.7 && closeAtHigh
        }

        for (i in 1 until candles.lastIndex) {
            val todayUc = isCircuitLike(candles[i - 1], candles[i])
            val nextUc = isCircuitLike(candles[i], candles[i + 1])

            // Minimal historical continuation proxy:
            // today closes circuit-like + positive short trend + above rising average volume.
            val startIdx = (i - 5).coerceAtLeast(0)
            val recent = candles.subList(startIdx, i + 1)
            val positiveTrend = recent.size >= 2 && recent.last().close > recent.first().close
            val avgVol = recent.dropLast(1).map { it.volume }.average().takeIf { !it.isNaN() } ?: 0.0
            val volumeExpansion = avgVol <= 0.0 || candles[i].volume >= avgVol

            val predicted = todayUc && positiveTrend && volumeExpansion
            if (predicted) predictions++
            if (nextUc) actual++
            if (predicted && nextUc) truePositives++
        }

        val precision = if (predictions == 0) 0.0 else truePositives.toDouble() / predictions * 100.0

        return ReplayResult(
            symbol = symbol,
            sessionsAnalyzed = candles.size,
            predictedUcContinuations = predictions,
            actualUcContinuations = actual,
            truePositives = truePositives,
            precision = precision,
            notes = listOf(
                "Replay uses daily candles and a strict circuit-like close proxy.",
                "Live production score uses the full 60-signal engine plus depth and intraday data."
            )
        )
    }
}
