package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.data.remote.GrowwClient
import com.suhas.globaledgeai.domain.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class ScannerEngine(
    private val growwClient: GrowwClient,
    private val signalEngine: SignalEngine = SignalEngine()
) {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun scan(
        accessToken: String,
        universe: List<Instrument>,
        newListings: List<ListedSecurity> = emptyList(),
        settings: AppSettings,
        adaptivePrecision: Map<String, Double> = emptyMap(),
        progress: suspend (String) -> Unit = {},
        nextSessionMode: Boolean = false,
        rejectedShadow: suspend (Candidate, String) -> Unit = { _, _ -> }
    ): ScanSummary {
        val started = System.currentTimeMillis()

        // Actionable UC candidates are NSE EQ only. BE/BZ/SM/ST can be visible on exchanges but
        // are frequently non-intraday / trade-to-trade / illiquid, so they are research-only and
        // must never occupy a LIVE/WATCH execution slot.
        val cash = universe
            .filter { ExecutionQuality.eligibleInstrument(it) }
            .filterNot { looksLikeEtf(it) }

        progress("Batch OHLC screening ${cash.size} NSE cash instruments")

        val prelim = mutableListOf<Pair<Instrument, Ohlc>>()

        cash.chunked(50).forEachIndexed { index, batch ->
            val map = growwClient.getOhlcBatch(accessToken, batch.map { it.tradingSymbol })
            batch.forEach { instrument ->
                val o = map[instrument.tradingSymbol] ?: return@forEach
                if (o.close <= 0.0 || o.high <= 0.0) return@forEach
                if (o.close < 20.0 || o.close > 20_000.0) return@forEach
                val belowHighPct=(o.high/o.close-1.0)*100.0
                val closeFromOpen=if(o.open>0.0)(o.close/o.open-1.0)*100.0 else 0.0
                val rangePct=if(o.low>0.0)(o.high/o.low-1.0)*100.0 else 0.0
                val holdingHigh=belowHighPct<=if(nextSessionMode)3.0 else 2.5
                val candidate=holdingHigh && (closeFromOpen>=(if(nextSessionMode)0.15 else 0.15) || rangePct>=0.75)
                if(candidate)prelim+=instrument to o
            }
            progress("Screened ${((index + 1) * 50).coerceAtMost(cash.size)}/${cash.size}")
        }

        val rankedPrelim=prelim.sortedByDescending{(_,o)->
            if(o.close<=0.0||o.open<=0.0)-999.0 else ((o.close/o.open-1.0)*150.0-(o.high/o.close-1.0)*100.0)
        }

        // Full NSE is still screened with batched OHLC. Expensive quote/history confirmation is
        // concentrated on the strongest UC-ranked names so the 5-minute cycle can actually finish.
        val confirmBudget = if (nextSessionMode) 120 else 80
        val confirmList = rankedPrelim.take(confirmBudget.coerceAtMost(rankedPrelim.size))
        progress("UC fast lane: confirming ${confirmList.size}/${rankedPrelim.size} entry-qualified stocks after full-NSE screen")

        val candidates = mutableListOf<Candidate>()

        for ((idx, pair) in confirmList.withIndex()) {
            val instrument = pair.first
            val quote = runCatching { growwClient.getQuote(accessToken, instrument.tradingSymbol) }.getOrNull()
                ?: continue

            // Discovery is deliberately broader than execution. A developing UC setup can have an
            // asymmetric book; rejecting it before scoring was starving the scanner.
            if (!ExecutionQuality.discoveryQuote(quote)) {
                progress("Skipped ${instrument.tradingSymbol}: insufficient discovery price/turnover")
                continue
            }
            val executableAsk = quote.totalSellQuantity > 0L && (
                (quote.offerPrice > 0.0 && quote.offerQuantity > 0L) ||
                    quote.sellDepth.any { it.price > 0.0 && it.quantity > 0L }
                )
            val strictExecutionReady = ExecutionQuality.executableQuote(quote) && executableAsk
            // Opening auction/first bars often have not accumulated the full-session 50k/₹25L execution
            // threshold yet. Permit a research/live-signal lane from the broader discovery floor until
            // 09:45, while keeping PLACE ORDER behind strictExecutionReady.
            val earlySession=ZonedDateTime.now(ist).toLocalTime()<LocalTime.of(9,45)
            val publicationLiquidity=if(earlySession) ExecutionQuality.discoveryQuote(quote)
                else ExecutionQuality.executableQuote(quote,requireTwoSided=false)
            val ucLiveReady = publicationLiquidity && executableAsk &&
                (ExecutionQuality.hasBid(quote) || quote.totalBuyQuantity>0L)

            val distanceToUc=if(quote.upperCircuit<=0.0){if(nextSessionMode)6.0 else 999.0}else((quote.upperCircuit-quote.lastPrice)/quote.upperCircuit*100.0)
            val pc=quote.previousClose.takeIf{it.isFinite()&&it>0.0}
            val band=if(pc!=null&&quote.upperCircuit>pc)((quote.upperCircuit/pc-1.0)*100.0).coerceIn(2.0,20.0) else 0.0
            val progress=if(band>0.0)quote.dayChangePercent/band else 0.0
            if((quote.upperCircuit>0.0&&quote.lastPrice>=quote.upperCircuit*0.998)||(band>0.0&&progress>=0.94)){
                progress("Skipped "+instrument.tradingSymbol+": PRE-UC late gate • band progress "+"%.0f".format(progress*100)+"%")
                continue
            }
            val minHead=if(nextSessionMode)0.75 else 0.25
            val maxHead=if(nextSessionMode)15.0 else 12.0
            val minMove=if(nextSessionMode)0.15 else 0.15
            if(quote.upperCircuit>0.0&&distanceToUc !in minHead..maxHead)continue
            if(quote.dayChangePercent<minMove)continue

            val now = ZonedDateTime.now(ist)
            val dailyStart = now.minusDays(100).toLocalDate().atStartOfDay().format(fmt)
            val dailyEnd = now.plusDays(1).toLocalDate().atStartOfDay().format(fmt)
            val intradayStart = now.toLocalDate().atTime(9, 15).format(fmt)
            val intradayEnd = now.toLocalDate().atTime(15, 30).format(fmt)

            val daily = runCatching {
                growwClient.getHistoricalCandles(
                    accessToken,
                    instrument.tradingSymbol,
                    dailyStart,
                    dailyEnd,
                    "1day"
                )
            }.getOrDefault(emptyList())

            val intraday = runCatching {
                growwClient.getHistoricalCandles(
                    accessToken,
                    instrument.tradingSymbol,
                    intradayStart,
                    intradayEnd,
                    "5minute"
                )
            }.getOrDefault(emptyList())

            val isPostListing = daily.size in 1..25
            val results = signalEngine.evaluate(
                SignalEngine.Context(
                    quote = quote,
                    daily = daily,
                    intraday = intraday,
                    isPostListing = isPostListing
                )
            )
            var score=signalEngine.score(results,adaptivePrecision)
            val avgVol=Indicators.avgVolume(daily.dropLast(1),20)
            val volumeRatio=if(avgVol<=0.0)0.0 else quote.volume/avgVol
            val buySellRatio=if(quote.totalSellQuantity<=0L){if(quote.totalBuyQuantity>0)99.0 else 0.0}else quote.totalBuyQuantity.toDouble()/quote.totalSellQuantity
            if(quote.upperCircuit>0.0&&distanceToUc in 1.0..3.5)score+=5.0 else if(quote.upperCircuit>0.0&&distanceToUc in 3.5..6.0)score+=2.5
            if(buySellRatio>=1.5&&quote.offerQuantity>0L)score+=2.5
            if(volumeRatio>=1.5)score+=2.0
            if(volumeRatio>=3.0)score+=1.5
            if(quote.dayChangePercent in 1.5..8.0)score+=1.5
            if(Indicators.consecutiveCircuitLikeDays(daily)>=1)score+=1.0
            score=score.coerceIn(0.0,100.0)

            val confidence = when {
                score >= 90 -> ConfidenceBand.VERY_HIGH
                score >= 82 -> ConfidenceBand.HIGH
                score >= 72 -> ConfidenceBand.MEDIUM
                else -> ConfidenceBand.LOW
            }

            val listingAge = newListings.firstOrNull { it.symbol == instrument.tradingSymbol }?.daysListed
                ?: daily.size.takeIf { it in 1..45 }?.toLong()
            val strategies = buildList {
                add("PRE_UC")
                if (ucLiveReady) add("UC_LIVE_READY")
                if (strictExecutionReady) add("EXECUTION_READY")
                if (nextSessionMode) add("NEXT_SESSION")
                addAll(results.filter { it.passed }
                    .groupBy { it.category }
                    .mapValues { (_, list) -> list.sumOf { it.weight } }
                    .entries.sortedByDescending { it.value }.take(5).map { it.key })
            }

            candidates += Candidate(
                symbol = instrument.tradingSymbol,
                companyName = instrument.name,
                kind = if (isPostListing || listingAge != null) CandidateKind.POST_LISTING else CandidateKind.SEASONED,
                section = ScannerSection.UC_CONTINUATION,
                price = quote.lastPrice,
                upperCircuit = quote.upperCircuit,
                dayChangePercent = quote.dayChangePercent,
                score = score,
                confidence = confidence,
                passedSignals = results.count { it.passed },
                totalSignals = results.size,
                buySellRatio = buySellRatio,
                volumeRatio = volumeRatio,
                consecutiveCircuitLikeDays = Indicators.consecutiveCircuitLikeDays(daily),
                signals = results,
                activeStrategies = strategies,
                listingAgeDays = listingAge,
                modelVersion = SignalEngine.MODEL_VERSION,
                predictionHorizonHours = 24
            )

            progress("Analyzed ${idx + 1}/${confirmList.size}: ${instrument.tradingSymbol}")
        }

        val thresholdDecision = if (settings.adaptiveRangesEnabled) {
            AdaptiveRangeEngine.ucThreshold(settings.minScore, candidates.map { it.score }, settings.maxFinalCandidates)
        } else null
        val effectiveThreshold = thresholdDecision?.threshold ?: settings.minScore
        val ranking=compareByDescending<Candidate> { it.score }
            .thenByDescending { it.buySellRatio }
            .thenByDescending { it.volumeRatio }
        val qualifiedAll = candidates
            .filter { it.score >= effectiveThreshold && (nextSessionMode || "UC_LIVE_READY" in it.activeStrategies) }
            .sortedWith(ranking)
        val qualified = qualifiedAll.take(settings.maxFinalCandidates)
            .map{it.copy(activeStrategies=it.activeStrategies+"UC_LIVE")}
        val liveSymbols=qualified.map{it.symbol}.toSet()
        val developingFloor=(effectiveThreshold-8.0).coerceAtLeast(52.0)
        val developing=candidates.filter{it.symbol !in liveSymbols && it.score>=developingFloor}
            .sortedWith(ranking).take(5)
            .map{it.copy(activeStrategies=it.activeStrategies+"UC_DEVELOPING")}
        val developingSymbols=developing.map{it.symbol}.toSet()
        val watch=candidates.filter{it.symbol !in liveSymbols && it.symbol !in developingSymbols}
            .sortedWith(ranking).take(5)
            .map{it.copy(activeStrategies=it.activeStrategies+"UC_WATCH")}
        val scoreQualifiedCount=candidates.count{it.score>=effectiveThreshold}
        val liveReadyCount=candidates.count{"UC_LIVE_READY" in it.activeStrategies}
        val executionReadyCount=candidates.count{"EXECUTION_READY" in it.activeStrategies}
        val funnel="funnel full ${cash.size} • prelim ${rankedPrelim.size} • fast ${confirmList.size} • scored ${candidates.size} • score ${scoreQualifiedCount} • UC-ready ${liveReadyCount} • execution-ready ${executionReadyCount} • LIVE ${qualified.size} • DEVELOPING ${developing.size} • WATCH ${watch.size}"
        // False-negative learning: keep the strongest candidates that the production gate rejected.
        // These are paper/shadow observations only and never become recommendations.
        candidates.filterNot { c -> qualified.any { it.symbol==c.symbol } }
            .sortedByDescending { it.score }.take(8).forEach { c ->
                val reason=when{
                    c.score<effectiveThreshold->"BELOW_THRESHOLD ${"%.1f".format(c.score)} < ${"%.1f".format(effectiveThreshold)}"
                    !nextSessionMode && "UC_LIVE_READY" !in c.activeStrategies->"UC_LIVE_LIQUIDITY_GATE"
                    else->"RANK_CAP"
                }
                rejectedShadow(c,reason)
            }
        // Keep the UI informative even on a quiet day. These fallback names are explicitly
        // WATCHLIST ONLY and are never sent through the actionable UC notification path.
        // Research visibility is never blank merely because strict publication has not cleared.
        // Only UC_LIVE is actionable/counted; DEVELOPING and WATCH are shadow-learning visibility.
        val final=(qualified+developing+watch).distinctBy{it.symbol}

        val completed = System.currentTimeMillis()

        return ScanSummary(
            section = ScannerSection.UC_CONTINUATION,
            startedAt = started,
            completedAt = completed,
            universeCount = cash.size,
            preliminaryCount = rankedPrelim.size,
            quotedCount = confirmList.size,
            candidates = final,
            newListingsScanned = newListings.size,
            message = when {
                nextSessionMode && qualified.isNotEmpty() -> "NEXT SESSION • ${qualified.size} UC candidate(s) passed research threshold ${"%.1f".format(effectiveThreshold)} • $funnel"
                nextSessionMode && developing.isNotEmpty() -> "NEXT SESSION DEVELOPING • best ${"%.1f".format(developing.first().score)} vs threshold ${"%.1f".format(effectiveThreshold)} • $funnel"
                nextSessionMode && watch.isNotEmpty() -> "NEXT SESSION WATCH • best ${"%.1f".format(watch.first().score)} vs threshold ${"%.1f".format(effectiveThreshold)} • $funnel"
                qualified.isNotEmpty() -> "${qualified.size} candidate(s) passed UC threshold ${"%.1f".format(effectiveThreshold)}${if(settings.adaptiveRangesEnabled) " • adaptive" else ""} • $funnel"
                developing.isNotEmpty() -> "DEVELOPING • no LIVE UC call yet • best ${"%.1f".format(developing.first().score)} vs threshold ${"%.1f".format(effectiveThreshold)} • $funnel"
                watch.isNotEmpty() -> "WATCHLIST ONLY • no qualified UC candidate • best ${"%.1f".format(watch.first().score)} vs threshold ${"%.1f".format(effectiveThreshold)} • $funnel"
                settings.adaptiveRangesEnabled -> "NO QUALIFIED UC CANDIDATE • adaptive floor ${thresholdDecision?.floor?.toInt() ?: 66} • $funnel"
                else -> "NO QUALIFIED UC CANDIDATE • $funnel"
            }
        )
    }

    private fun looksLikeEtf(i: Instrument): Boolean {
        val text = "${i.name} ${i.instrumentType} ${i.tradingSymbol}".uppercase()
        return listOf(" ETF", "ETF ", "BEES", "GOLD", "SILVER").any { text.contains(it) } &&
                !text.contains("LIMITED")
    }
}
