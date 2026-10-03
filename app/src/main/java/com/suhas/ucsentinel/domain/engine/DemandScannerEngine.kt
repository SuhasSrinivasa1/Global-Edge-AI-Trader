package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.data.remote.GrowwClient
import com.suhas.globaledgeai.domain.model.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class DemandScannerEngine(
    private val growwClient: GrowwClient,
    private val signalEngine: DemandSignalEngine = DemandSignalEngine()
) {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun scan(
        accessToken: String,
        universe: List<Instrument>,
        newListings: List<ListedSecurity>,
        settings: AppSettings,
        adaptivePrecision: Map<String, Double> = emptyMap(),
        progress: suspend (String)->Unit = {},
        rejectedShadow: suspend (Candidate, String) -> Unit = { _, _ -> }
    ): ScanSummary {
        val started = System.currentTimeMillis()
        val newMap = newListings.associateBy { it.symbol }
        // Pressure recommendations are execution candidates, so keep only NSE EQ instruments.
        val cash = universe.filter { ExecutionQuality.eligibleInstrument(it) }
        val prelim = mutableListOf<Pair<Instrument,Ohlc>>()

        progress("Pre-pressure prediction: screening ${cash.size} NSE cash stocks")
        cash.chunked(50).forEachIndexed { idx,batch ->
            val map = growwClient.getOhlcBatch(accessToken,batch.map{it.tradingSymbol})
            batch.forEach { instrument ->
                val o=map[instrument.tradingSymbol]?:return@forEach
                if(o.close<=0||o.high<=0||o.open<=0) return@forEach
                if(o.close<20.0||o.close>20_000.0) return@forEach

                // Coarse all-market filter: look for stocks that are waking up, not stocks already fully extended.
                val highVsClose=(o.high/o.close-1.0)*100.0
                val openVsClose=(o.open/o.close-1.0)*100.0
                val isNew = newMap.containsKey(instrument.tradingSymbol)
                val wakingUp = highVsClose in 0.35..12.0 || openVsClose in -2.0..6.0
                if(isNew || wakingUp) prelim += instrument to o
            }
            progress("Prediction pre-screen ${((idx+1)*50).coerceAtMost(cash.size)}/${cash.size}")
        }

        val ranked = prelim.sortedByDescending { (instrument,o) ->
            val isNew=if(newMap.containsKey(instrument.tradingSymbol))4.0 else 0.0
            val move=(o.high/o.close-1.0)*100.0
            val earlyMoveScore = when {
                move in 1.0..6.0 -> 6.0
                move in 0.35..10.0 -> 3.0
                else -> 0.0
            }
            isNew + earlyMoveScore - (move-6.0).coerceAtLeast(0.0)*0.25
        }
        // Full NSE is screened cheaply first. Quote/history enrichment is a ranked fast lane; this
        // prevents pressure research from consuming the entire 5-minute scanner window.
        val confirmBudget=settings.maxQuotesPerScan.coerceIn(60,80)
        val confirmList=ranked.take(confirmBudget.coerceAtMost(ranked.size))
        progress("Pressure fast lane: confirming ${confirmList.size}/${ranked.size} entry-qualified stocks after full-NSE screen")

        val candidates=mutableListOf<Candidate>()
        for((index,pair) in confirmList.withIndex()) {
            val instrument=pair.first
            val quote=runCatching{growwClient.getQuote(accessToken,instrument.tradingSymbol)}.getOrNull()?:continue
            if(!ExecutionQuality.discoveryQuote(quote)){
                progress("Skipped ${instrument.tradingSymbol}: insufficient discovery price/turnover")
                continue
            }
            val ratio=if(quote.totalSellQuantity<=0) if(quote.totalBuyQuantity>0)99.0 else 0.0 else quote.totalBuyQuantity.toDouble()/quote.totalSellQuantity
            val ucDistance=if(quote.upperCircuit<=0)999.0 else abs(quote.upperCircuit-quote.lastPrice)/quote.upperCircuit*100.0
            val isNew = newMap[instrument.tradingSymbol]

            // Discovery keeps asymmetric/near-lock books visible; those conditions affect whether a
            // candidate can become LIVE rather than deleting it from the model entirely.
            val absoluteLateRatio = if(settings.adaptiveRangesEnabled) 15.0 else settings.demandPressureMaxBuySellRatio * 1.5
            val hardLocked = quote.totalSellQuantity<=0L && quote.offerQuantity<=0L && quote.sellDepth.none{it.price>0.0&&it.quantity>0L}
            var executionReady = ExecutionQuality.executableQuote(quote) && ucDistance>=0.35 && ratio<=absoluteLateRatio && !hardLocked && (quote.dayChangePercent<=12.0 || isNew!=null)

            val now=ZonedDateTime.now(ist)
            val dailyStart=now.minusDays(120).toLocalDate().atStartOfDay().format(fmt)
            val dailyEnd=now.plusDays(1).toLocalDate().atStartOfDay().format(fmt)
            val intraStart=now.toLocalDate().atTime(9,15).format(fmt)
            val intraEnd=now.toLocalDate().atTime(15,30).format(fmt)

            val daily=runCatching{
                growwClient.getHistoricalCandles(accessToken,instrument.tradingSymbol,dailyStart,dailyEnd,"1day")
            }.getOrDefault(emptyList())
            val intraday=runCatching{
                growwClient.getHistoricalCandles(accessToken,instrument.tradingSymbol,intraStart,intraEnd,"5minute")
            }.getOrDefault(emptyList())

            val listingAge=isNew?.daysListed ?: daily.size.takeIf{it in 1..45}?.toLong()
            if(intraday.size<4 && listingAge==null) continue

            val results=signalEngine.evaluate(DemandSignalEngine.Context(quote,daily,intraday,listingAge))
            val breakdown=signalEngine.scoreBreakdown(results,adaptivePrecision)
            var score=breakdown.finalScore

            // Cross-family bonuses require multiple independent precursors.
            if(breakdown.setup>=60 && breakdown.acceleration>=60) score += 4.0
            if(breakdown.acceleration>=65 && breakdown.microstructure>=55) score += 5.0
            if(listingAge!=null && listingAge<=15 && breakdown.acceleration>=55) score += 2.0

            val effectivePressureCutoff = if(settings.adaptiveRangesEnabled)
                AdaptiveRangeEngine.demandPressureCutoff(settings.demandPressureMaxBuySellRatio, breakdown.acceleration, breakdown.microstructure)
            else settings.demandPressureMaxBuySellRatio

            // Penalties for signals that suggest the move is already too obvious / too late.
            if(ratio>effectivePressureCutoff) score -= 8.0
            if(ucDistance<1.0) score -= 7.0
            if(quote.dayChangePercent>8.0) score -= 5.0
            score=score.coerceIn(0.0,100.0)

            // Adaptive mode may relax the final score threshold, but never the need for evidence
            // from more than one independent signal family for seasoned stocks.
            val independentFamilies=listOf(
                breakdown.setup>=45.0,
                breakdown.acceleration>=45.0,
                breakdown.microstructure>=45.0
            ).count{it}
            if(independentFamilies<1 && listingAge==null) continue
            executionReady = executionReady && (independentFamilies>=2 || listingAge!=null)

            val avgVol=Indicators.avgVolume(daily.dropLast(1),20)
            val volRatio=if(avgVol<=0)0.0 else quote.volume/avgVol
            val confidence=when{
                score>=90->ConfidenceBand.VERY_HIGH
                score>=82->ConfidenceBand.HIGH
                score>=70->ConfidenceBand.MEDIUM
                else->ConfidenceBand.LOW
            }

            val phase=when {
                ratio > effectivePressureCutoff || ucDistance < 0.75 -> PredictionPhase.ALREADY_SQUEEZED
                breakdown.acceleration>=72 && breakdown.microstructure>=62 -> PredictionPhase.TRIGGER_READY
                breakdown.setup>=60 && (breakdown.acceleration>=50 || breakdown.microstructure>=50) -> PredictionPhase.BUILDING_PRESSURE
                else -> PredictionPhase.EARLY_SETUP
            }
            if(phase==PredictionPhase.ALREADY_SQUEEZED) executionReady=false

            candidates += Candidate(
                symbol=instrument.tradingSymbol,
                companyName=instrument.name,
                kind=if(listingAge!=null&&listingAge<=45) CandidateKind.POST_LISTING else CandidateKind.SEASONED,
                section=ScannerSection.DEMAND_SQUEEZE,
                price=quote.lastPrice,
                upperCircuit=quote.upperCircuit,
                dayChangePercent=quote.dayChangePercent,
                score=score,
                confidence=confidence,
                passedSignals=results.count{it.passed},
                totalSignals=results.size,
                buySellRatio=ratio,
                volumeRatio=volRatio,
                consecutiveCircuitLikeDays=Indicators.consecutiveCircuitLikeDays(daily),
                signals=results,
                activeStrategies=buildList { if(executionReady)add("EXECUTION_READY"); addAll(signalEngine.activeStrategies(results)) },
                listingAgeDays=listingAge,
                predictionPhase=phase,
                modelVersion=DemandSignalEngine.MODEL_VERSION,
                predictionHorizonHours=settings.demandPredictionHorizonHours,
                targetMovePct=if(settings.adaptiveRangesEnabled)
                    AdaptiveRangeEngine.demandTargetPct(score,breakdown.setup,breakdown.acceleration,breakdown.microstructure)
                else settings.demandSpikeTargetPct,
                setupScore=breakdown.setup,
                accelerationScore=breakdown.acceleration,
                microstructureScore=breakdown.microstructure,
                riskPenalty=breakdown.riskPenalty
            )
            progress("Pre-pressure analysis ${index+1}/${confirmList.size}: ${instrument.tradingSymbol}")
        }

        val thresholdDecision = if(settings.adaptiveRangesEnabled)
            AdaptiveRangeEngine.demandThreshold(settings.demandMinScore,candidates.map{it.score},settings.maxDemandCandidates)
        else null
        val effectiveThreshold = thresholdDecision?.threshold ?: settings.demandMinScore
        val qualifiedAll=candidates.filter{it.score>=effectiveThreshold && "EXECUTION_READY" in it.activeStrategies}.sortedWith(
            compareByDescending<Candidate>{it.score}
                .thenByDescending{it.accelerationScore ?: 0.0}
                .thenByDescending{it.microstructureScore ?: 0.0}
                .thenByDescending{it.setupScore ?: 0.0}
        )
        val qualified=qualifiedAll.take(settings.maxDemandCandidates)
        candidates.filterNot { c -> qualified.any { it.symbol==c.symbol } }
            .sortedByDescending { it.score }.take(8).forEach { c ->
                val reason=when{
                    c.score<effectiveThreshold->"BELOW_THRESHOLD ${"%.1f".format(c.score)} < ${"%.1f".format(effectiveThreshold)}"
                    "EXECUTION_READY" !in c.activeStrategies->"EXECUTION_GATE"
                    else->"RANK_CAP"
                }
                rejectedShadow(c,reason)
            }
        val final=if(qualified.isNotEmpty()) qualified else candidates.sortedWith(
            compareByDescending<Candidate>{it.score}
                .thenByDescending{it.accelerationScore ?: 0.0}
                .thenByDescending{it.microstructureScore ?: 0.0}
                .thenByDescending{it.setupScore ?: 0.0}
        ).take(3)

        return ScanSummary(
            section=ScannerSection.DEMAND_SQUEEZE,
            startedAt=started,
            completedAt=System.currentTimeMillis(),
            universeCount=cash.size,
            preliminaryCount=ranked.size,
            quotedCount=confirmList.size,
            candidates=final,
            newListingsScanned=newListings.size,
            message=when{
                qualified.isNotEmpty()->"${qualified.size} pre-pressure candidate(s) • threshold ${"%.1f".format(effectiveThreshold)}${if(settings.adaptiveRangesEnabled) " adaptive" else ""}"
                final.isNotEmpty()->"WATCHLIST ONLY • no qualified pressure setup • best ${"%.1f".format(final.first().score)} vs threshold ${"%.1f".format(effectiveThreshold)}"
                settings.adaptiveRangesEnabled->"NO PRE-PRESSURE PRICE-SPIKE CANDIDATE • adaptive floor ${thresholdDecision?.floor?.toInt() ?: 62}"
                else->"NO PRE-PRESSURE PRICE-SPIKE CANDIDATE"
            }
        )
    }
}
