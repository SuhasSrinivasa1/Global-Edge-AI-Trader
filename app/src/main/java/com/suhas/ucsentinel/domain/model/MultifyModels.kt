package com.suhas.globaledgeai.domain.model

enum class MultifyEventType { ENTRY_LONG, ENTRY_SHORT, EXIT, UNKNOWN }
enum class MultifyInstrumentClass { EQUITY, DERIVATIVE_OR_NON_EQUITY, UNKNOWN }
enum class MultifyShadowSide { LONG, SHORT }
enum class MultifyShadowStatus { OPEN, CLOSED }
enum class MultifyDecisionTier { LIVE, DEVELOPING, WATCH, NO_TRADE }

data class MultifyShadowTrade(
    val id:String,
    val sourceEventId:String,
    val symbol:String,
    val side:MultifyShadowSide,
    val strategyTag:String,
    val contextKey:String,
    val wave:Int,
    val openedAt:Long,
    val entryPrice:Double,
    val quantity:Int,
    val allocatedCapital:Double,
    val score:Double,
    val status:MultifyShadowStatus=MultifyShadowStatus.OPEN,
    val closedAt:Long=0L,
    val exitPrice:Double=0.0,
    val grossPnl:Double=0.0,
    val estimatedCosts:Double=0.0,
    val netPnl:Double=0.0,
    val closeReason:String="",
    val mfePct:Double=0.0,
    val maePct:Double=0.0,
    val peakPrice:Double=entryPrice,
    val troughPrice:Double=entryPrice,
    val lastPrice:Double=entryPrice,
    val lastUpdatedAt:Long=openedAt,
    val liveEntryReference:String="",
    val liveExitReference:String="",
    val liveProtectionReference:String="",
    val liveProtectionId:String="",
    val liveFilledQuantity:Int=0,
    val liveAverageEntryPrice:Double=0.0,
    val liveExecutionNote:String=""
)

data class MultifyStrategyStat(
    val strategyTag:String,
    val side:MultifyShadowSide,
    val samples:Int,
    val wins:Int,
    val losses:Int,
    val winRatePct:Double,
    val netPnl:Double,
    val avgReturnPct:Double,
    val bayesianWinRatePct:Double=0.0,
    val recencyWeightedNet:Double=0.0
)

data class MultifyStockProfile(
    val symbol:String,
    val samples:Int,
    val wins:Int,
    val losses:Int,
    val netPnl:Double,
    val bestLongStrategy:String="",
    val bestShortStrategy:String="",
    val longStats:List<MultifyStrategyStat> = emptyList(),
    val shortStats:List<MultifyStrategyStat> = emptyList(),
    val exitFallSamples:Int=0,
    val exitFallWins:Int=0,
    val avgExitFall5mPct:Double=0.0,
    val avgExitFall15mPct:Double=0.0
)

data class MultifyDecision(
    val eventId:String,
    val symbol:String,
    val eventType:MultifyEventType,
    val tier:MultifyDecisionTier,
    val direction:MultifyShadowSide?,
    val score:Double,
    val price:Double,
    val strategyTag:String,
    val contextKey:String,
    val reason:String,
    val generatedAt:Long=System.currentTimeMillis()
)

data class MultifyDashboard(
    val generatedAt:Long=System.currentTimeMillis(),
    val capitalBudget:Double=200_000.0,
    val dailyNetTarget:Double=5_000.0,
    val todayRealizedNet:Double=0.0,
    val todayUnrealizedNet:Double=0.0,
    val todayNet:Double=0.0,
    val openExposure:Double=0.0,
    val availableCapital:Double=200_000.0,
    val openTrades:Int=0,
    val closedTradesToday:Int=0,
    val alertsToday:Int=0,
    val fiveSessionAverageNet:Double=0.0,
    val daysAtOrAboveTarget:Int=0,
    val exitFallSamples:Int=0,
    val exitFallWins:Int=0,
    val exitFallRatePct:Double=0.0,
    val targetBand:String="BUILDING",
    val lastDecision:MultifyDecision?=null,
    val automationMode:String="SHADOW_ONLY"
)
