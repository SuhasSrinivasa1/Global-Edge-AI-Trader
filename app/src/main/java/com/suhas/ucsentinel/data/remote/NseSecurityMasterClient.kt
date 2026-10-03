package com.suhas.globaledgeai.data.remote

import com.suhas.globaledgeai.domain.model.ListedSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

class NseSecurityMasterClient {
    companion object {
        const val EQUITY_MASTER_URL = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun recentListings(maxAgeDays: Int): List<ListedSecurity> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(EQUITY_MASTER_URL)
            .header("User-Agent", "Mozilla/5.0 (Android 16; Mobile)")
            .get()
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NSE security master failed: HTTP ${response.code}")
            val csv = response.body?.string().orEmpty()
            parse(csv, maxAgeDays)
        }
    }

    private fun parse(csv: String, maxAgeDays: Int): List<ListedSecurity> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val header = parseCsvLine(lines.first()).map { it.trim().uppercase(Locale.ENGLISH) }
        val index = header.withIndex().associate { it.value to it.index }
        fun col(row: List<String>, name: String): String = row.getOrNull(index[name] ?: -1).orEmpty().trim()

        val formats = listOf(
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)
        )
        val today = LocalDate.now()

        return buildList {
            lines.drop(1).forEach { line ->
                val row = parseCsvLine(line)
                val rawDate = col(row, "DATE OF LISTING")
                val date = formats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(rawDate, f) }.getOrNull() }
                    ?: return@forEach
                val age = ChronoUnit.DAYS.between(date, today)
                if (age !in 0..maxAgeDays.toLong()) return@forEach
                val symbol = col(row, "SYMBOL")
                if (symbol.isBlank()) return@forEach
                add(
                    ListedSecurity(
                        symbol = symbol,
                        companyName = col(row, "NAME OF COMPANY").ifBlank { symbol },
                        series = col(row, "SERIES"),
                        listingDateIso = date.toString(),
                        isin = col(row, "ISIN NUMBER"),
                        daysListed = age
                    )
                )
            }
        }.sortedBy { it.daysListed }
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { token.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += token.toString(); token.clear() }
                else -> token.append(ch)
            }
            i++
        }
        out += token.toString()
        return out
    }
}
