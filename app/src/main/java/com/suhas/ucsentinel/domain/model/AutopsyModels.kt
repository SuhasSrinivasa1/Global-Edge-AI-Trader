package com.suhas.globaledgeai.domain.model

enum class MarketRegime { TREND_UP, TREND_DOWN, RANGE_BOUND, HIGH_VOLATILITY, GAP_REVERSAL, RISK_ON, RISK_OFF, MIXED }
enum class AutopsyCause { COMPANY_NEWS, MARKET_REVERSAL, INDEX_DIVERGENCE, GLOBAL_RISK, VOLUME_FAILURE, MOMENTUM_FAILURE, ENTRY_TIMING, LIQUIDITY_NOISE, STATISTICAL_FAILURE, NO_DOMINANT_CAUSE, WIN_CONFIRMED }
enum class ShadowOutcome { WIN, LOSS, NO_TRADE, AVOIDED_LOSS, MISSED_WIN }

data class GlobalBar(
    val epochSeconds:Long,
    val open:Double,
    val high:Double,
    val low:Double,
    val close:Double,
    val volume:Long
)

data class RegimeAssessment(
    val regime:MarketRegime,
    val confidencePct:Double,
    val niftyReturnPct:Double,
    val sensexReturnPct:Double,
    val bankNiftyReturnPct:Double,
    val evidence:List<String>
)

data class ShadowStrategyResult(
    val strategyId:String,
    val strategyName:String,
    val traded:Boolean,
    val outcome:ShadowOutcome,
    val returnPct:Double,
    val evidence:String
)

data class TradeAutopsyRecord(
    val id:String,
    val sourceId:String,
    val engineLabel:String,
    val symbol:String,
    val originalOutcome:String,
    val originalReturnPct:Double,
    val openedAt:Long,
    val closedAt:Long,
    val generatedAt:Long,
    val regime:MarketRegime,
    val regimeConfidencePct:Double,
    val dominantCause:AutopsyCause,
    val causeConfidencePct:Double,
    val evidence:List<String>,
    val newsEvidence:List<String>,
    val shadowResults:List<ShadowStrategyResult>,
    val recommendedRule:String,
    val sessionDate:String
)
