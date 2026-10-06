package com.suhas.globaledgeai.domain.model

enum class AuthMode { TOTP, APPROVAL }
enum class CandidateKind { POST_LISTING, SEASONED }
enum class ConfidenceBand { LOW, MEDIUM, HIGH, VERY_HIGH }
enum class ScannerSection { UC_CONTINUATION, DEMAND_SQUEEZE }
enum class PredictionPhase { EARLY_SETUP, BUILDING_PRESSURE, TRIGGER_READY, ALREADY_SQUEEZED }
enum class FeedHealthState { NEVER_LOADED, OK, EMPTY, ERROR }
enum class FreezeOutcome { PICKS, NO_SIGNAL, NO_DATA }
enum class MarketPhase { PRE_OPEN, OPEN, POST_CLOSE, WEEKEND }
enum class TradeDirection { LONG, SHORT }
enum class StrategyStatus { CHAMPION, QUALIFIED, ACTIVE, CHALLENGER, PROBATION, SUSPENDED }

data class Credentials(val mode: AuthMode = AuthMode.TOTP,val apiKeyOrTotpToken: String = "",val secret: String = "")

data class Instrument(val exchange:String,val exchangeToken:String,val tradingSymbol:String,val growwSymbol:String,val name:String,val instrumentType:String,val segment:String,val series:String,val isin:String,val buyAllowed:Boolean,val sellAllowed:Boolean)

data class ListedSecurity(val symbol:String,val companyName:String,val series:String,val listingDateIso:String,val isin:String,val daysListed:Long)

data class Ohlc(val open:Double,val high:Double,val low:Double,val close:Double)
data class DepthLevel(val price:Double,val quantity:Long)

data class Quote(
    val symbol:String,val lastPrice:Double,val previousClose:Double,val dayChangePercent:Double,
    val upperCircuit:Double,val lowerCircuit:Double,val volume:Long,val totalBuyQuantity:Long,val totalSellQuantity:Long,
    val bidPrice:Double,val bidQuantity:Long,val offerPrice:Double,val offerQuantity:Long,val marketCap:Double,
    val week52High:Double,val week52Low:Double,val ohlc:Ohlc,val buyDepth:List<DepthLevel>,val sellDepth:List<DepthLevel>,val lastTradeTime:Long
)

data class Candle(val epochSeconds:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Long)

data class SignalResult(val id:String,val name:String,val category:String,val passed:Boolean,val weight:Double,val evidence:String)

data class Candidate(
    val symbol:String,val companyName:String,val kind:CandidateKind,val section:ScannerSection=ScannerSection.UC_CONTINUATION,
    val price:Double,val upperCircuit:Double,val dayChangePercent:Double,val score:Double,val confidence:ConfidenceBand,
    val passedSignals:Int,val totalSignals:Int,val buySellRatio:Double,val volumeRatio:Double,val consecutiveCircuitLikeDays:Int,
    val signals:List<SignalResult>,val activeStrategies:List<String> = emptyList(),val listingAgeDays:Long?=null,
    val generatedAt:Long=System.currentTimeMillis(),val frozen:Boolean=false,val predictionPhase:PredictionPhase?=null,
    val modelVersion:String="",val predictionHorizonHours:Int=24,val targetMovePct:Double?=null,
    val setupScore:Double?=null,val accelerationScore:Double?=null,val microstructureScore:Double?=null,val riskPenalty:Double?=null
)

data class ScanSummary(
    val section:ScannerSection=ScannerSection.UC_CONTINUATION,val startedAt:Long,val completedAt:Long,val universeCount:Int,
    val preliminaryCount:Int,val quotedCount:Int,val candidates:List<Candidate>,val newListingsScanned:Int=0,val message:String
)

data class DualScanSummary(val uc:ScanSummary,val demand:ScanSummary,val newListings:List<ListedSecurity>)

data class StrategyMetric(val section:ScannerSection,val signalId:String,val signalName:String,val observations:Int,val wins:Int,val precision:Double,val modelVersion:String="")

data class SectionAccuracy(
    val section:ScannerSection,val evaluated:Int,val hits:Int,val accuracyPct:Double,val last24hEvaluated:Int,
    val last24hHits:Int,val last24hAccuracyPct:Double,val modelVersion:String=""
)

data class NewsItem(val source:String,val symbol:String,val title:String,val summary:String,val publishedAt:String,val url:String)

data class ReplayResult(val symbol:String,val sessionsAnalyzed:Int,val predictedUcContinuations:Int,val actualUcContinuations:Int,val truePositives:Int,val precision:Double,val notes:List<String>)

data class FeedHealth(
    val state:FeedHealthState=FeedHealthState.NEVER_LOADED,val itemCount:Int=0,val lastAttemptAt:Long=0L,
    val lastSuccessAt:Long=0L,val message:String="Not loaded yet"
)

data class FreezeRecord(
    val section:ScannerSection,val dateIso:String,val recorded:Boolean,val outcome:FreezeOutcome=FreezeOutcome.NO_DATA,
    val frozenAt:Long=0L,val sourceScanAt:Long=0L,val candidates:List<Candidate> = emptyList(),val message:String=""
)

data class MarketSessionInfo(val phase:MarketPhase,val isTradingDay:Boolean,val isOpen:Boolean,val label:String,val sessionDateIso:String)


data class TradingStrategyDefinition(
    val id:String,val name:String,val kind:String,val family:String,val description:String,val source:String,val priority:Int
)

data class StrategyPerformance(
    val strategyId:String,val name:String,val observations:Int,val wins:Int,val accuracyPct:Double,val avgReturnPct:Double,
    val expectancyPct:Double,val maxDrawdownPct:Double,val confidenceFloorPct:Double,val status:StrategyStatus,
    val recentAccuracyPct:Double=0.0,val distinctSessions:Int=0,val shadowAccuracyPct:Double=0.0,
    val netExpectancyR:Double=0.0,val contextLabel:String=""
)

enum class StrategyLearningSource { LIVE_V2, SHADOW_V2, LEGACY_LIVE, LEGACY_SHADOW, COMPONENT_CREDIT }
enum class StrategyLearningOutcome { PENDING, WIN, LOSS, AMBIGUOUS, INVALID }

data class StrategyLearningObservation(
    val eventId:String,val strategyId:String,val strategyName:String,val symbol:String,val direction:TradeDirection,
    val source:StrategyLearningSource,val sessionBand:String,val regime:MarketRegime,val rawScore:Double,
    val calibratedProbabilityPct:Double,val entryPrice:Double,val targetPct:Double,val stopPct:Double,
    val openedAt:Long,val closedAt:Long,val sessionDate:String,val outcome:StrategyLearningOutcome,
    val returnPct:Double,val rMultiple:Double,val researchSignature:String="",val componentStrategyIds:List<String> = emptyList(),
    val clusterKey:String="",val sampleWeight:Double=1.0,val legacy:Boolean=false
)

data class StrategyLegacyPrior(
    val strategyId:String,val strategyName:String,val observations:Int,val wins:Int,val avgReturnPct:Double
)

data class StrategyRosterDecision(
    val sessionDate:String,val strategyId:String,val strategyName:String,val direction:TradeDirection,
    val sessionBand:String,val regime:MarketRegime,val status:StrategyStatus,val samples:Int,val wins:Int,
    val distinctSessions:Int,val hitRatePct:Double,val recentHitRatePct:Double,val expectancyR:Double,
    val confidenceFloorPct:Double,val shadowSamples:Int,val shadowHitRatePct:Double,val calibratedBasePct:Double,
    val frozenAt:Long,val note:String=""
)

data class StrategySetup(
    val symbol:String,val companyName:String,val strategyId:String,val strategyName:String,val direction:TradeDirection,
    val score:Double,val entryPrice:Double,val targetPct:Double,val stopPct:Double,val evidence:String,
    val listingAgeDays:Long?=null,val generatedAt:Long=System.currentTimeMillis(),
    val researchSignature:String="",val handbookQualityPct:Double=0.0,val handbookPattern:String="",val handbookCombination:String="",
    val rawScore:Double=score,val calibratedProbabilityPct:Double=0.0,val expectedR:Double=0.0,
    val contextBand:String="",val contextRegime:String="",val componentStrategyIds:List<String> = emptyList(),
    val modelVersion:String="STRAT-GOV-V2-1.7.0"
)

enum class StrategyRecommendationStatus { LIVE, WIN, LOSS, EXPIRED, INVALIDATED }

enum class TradeCallEngine { UPPER_CIRCUIT, PRESSURE, GLOBAL }
enum class TradeCallBucket { NEXT_SESSION, LIVE, THREE_PM }
enum class TradeCallOutcome { OPEN, WIN, LOSS, INVALID }

data class TradeCallRecord(
    val id:String,
    val engine:TradeCallEngine,
    val bucket:TradeCallBucket,
    val symbol:String,
    val companyName:String,
    val direction:TradeDirection,
    val score:Double,
    val entryPrice:Double,
    val stopPrice:Double,
    val targetPrice:Double,
    val target2Price:Double?=null,
    val openedAt:Long,
    val targetSessionDate:String,
    val sourceLabel:String,
    val detail:String,
    val learningKey:String="",
    val outcome:TradeCallOutcome=TradeCallOutcome.OPEN,
    val closedAt:Long=0L,
    val exitPrice:Double=0.0,
    val returnPct:Double=0.0,
    val closeReason:String=""
)

data class StrategyRecommendation(
    val id:String,
    val setup:StrategySetup,
    val openedAt:Long,
    val lastSeenAt:Long,
    val lastPrice:Double,
    val closedAt:Long=0L,
    val exitPrice:Double=0.0,
    val status:StrategyRecommendationStatus=StrategyRecommendationStatus.LIVE,
    val returnPct:Double=0.0,
    val closeReason:String="",
    val tradedValue:Double=0.0,
    val volume:Long=0L,
    val spreadPct:Double=0.0
)

data class StrategyTournamentSummary(
    val generatedAt:Long,val universeCount:Int,val strategiesRun:Int,val symbolsEnriched:Int,
    val topSetups:List<StrategySetup>,val activeStrategies:List<TradingStrategyDefinition>,
    val performances:List<StrategyPerformance>,val catalogVersion:String,val message:String,
    val championInsights:List<String> = emptyList(),val rejectedJournalCount:Int=0,val handbookVersion:String="",
    val productionRosterDate:String="",val productionContext:String="",val strategyV2EventCount:Int=0
)

data class GrowwApiHealthSnapshot(
    val generatedAt:Long=0L,
    val status:String="HEALTHY",
    val liveLastSecond:Int=0,
    val liveLastMinute:Int=0,
    val livePerSecondLimit:Int=10,
    val livePerMinuteLimit:Int=300,
    val internalLiveMinuteBudget:Int=270,
    val liveHeadroom:Int=300,
    val liveReserveHeadroom:Int=270,
    val authLastMinute:Int=0,
    val ordersLastMinute:Int=0,
    val nonTradingLastMinute:Int=0,
    val historicalLastMinute:Int=0,
    val rateLimitLastHour:Int=0,
    val lastRateLimitAt:Long=0L,
    val totalRequestsSinceStart:Long=0L
)

data class AppSettings(
    val minScore:Double=72.0,val demandMinScore:Double=70.0,val demandSpikeTargetPct:Double=3.0,val adaptiveRangesEnabled:Boolean=true,
    val demandPredictionHorizonHours:Int=24,val demandPressureMaxBuySellRatio:Double=8.0,val maxFinalCandidates:Int=3,
    val maxDemandCandidates:Int=5,val maxQuotesPerScan:Int=120,val excludeFnoLinked:Boolean=true,val includeSmeSeries:Boolean=true,
    val newListingDays:Int=45,val freezeHour:Int=15,val freezeMinute:Int=30,val autoScanEnabled:Boolean=true,
    val pressureAutoScanEnabled:Boolean=true,val pressureScanIntervalMinutes:Int=15,val learningEnabled:Boolean=true,
    val learningIntervalHours:Int=24,val memoryRetentionDays:Int=90,val demoMode:Boolean=false,
    val globalLeadEnabled:Boolean=true,val globalLeadScanIntervalMinutes:Int=15,val globalMappingRefreshDays:Int=7,
    val globalTopCandidates:Int=10,val globalMinForeignGapPct:Double=0.75,val globalContinuationTargetPct:Double=0.5,
    val strategyTournamentEnabled:Boolean=true,val strategyActiveCount:Int=20,val strategyTopCandidates:Int=10,
    val strategyMinChampionAccuracy:Double=60.0,val strategyMinChampionSamples:Int=30,val strategyCatalogRefreshDays:Int=7,
    val strategyHistorySymbolsPerPass:Int=80,
    val multifyLiveTradingEnabled:Boolean=false
)
