package com.suhas.globaledgeai.data.repository

import com.suhas.globaledgeai.data.remote.GrowwClient
import com.suhas.globaledgeai.domain.model.Instrument

class InstrumentRepository(
    private val growwClient: GrowwClient
) {
    private var cache: List<Instrument> = emptyList()

    suspend fun refresh(): List<Instrument> {
        val csv = growwClient.downloadInstrumentCsv()
        cache = parse(csv)
        return cache
    }

    fun cached(): List<Instrument> = cache
    fun clearCache() { cache = emptyList() }

    private fun parse(csv: String): List<Instrument> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()

        val header = parseCsvLine(lines.first())
        val idx = header.withIndex().associate { it.value.trim() to it.index }

        fun field(row: List<String>, name: String): String =
            idx[name]?.let { i -> row.getOrNull(i) }.orEmpty().trim()

        return buildList {
            lines.drop(1).forEach { line ->
                val row = parseCsvLine(line)
                val exchange = field(row, "exchange")
                val segment = field(row, "segment")
                val series = field(row, "series")
                val type = field(row, "instrument_type")

                if (exchange != "NSE") return@forEach
                if (segment != "CASH") return@forEach
                if (field(row, "buy_allowed") !in setOf("1", "true", "TRUE")) return@forEach
                if (series.isBlank()) return@forEach

                val symbol = field(row, "trading_symbol")
                if (symbol.isBlank()) return@forEach

                add(
                    Instrument(
                        exchange = exchange,
                        exchangeToken = field(row, "exchange_token"),
                        tradingSymbol = symbol,
                        growwSymbol = field(row, "groww_symbol"),
                        name = field(row, "name").ifBlank { symbol },
                        instrumentType = type,
                        segment = segment,
                        series = series,
                        isin = field(row, "isin"),
                        buyAllowed = true,
                        sellAllowed = field(row, "sell_allowed") in setOf("1", "true", "TRUE")
                    )
                )
            }
        }.distinctBy { it.tradingSymbol }
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    token.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out.add(token.toString())
                    token.clear()
                }
                else -> token.append(ch)
            }
            i++
        }
        out.add(token.toString())
        return out
    }
}
