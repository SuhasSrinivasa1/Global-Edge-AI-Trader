package com.suhas.globaledgeai.domain.model

enum class EvidenceKind { FUNDAMENTAL, ANALYST, COMPANY_EVENT, MACRO_EVENT, SECTOR, EXECUTION }
enum class EventRiskLevel { LOW, MEDIUM, HIGH }
enum class ChallengerShadowOutcome { PENDING, WIN, LOSS, AMBIGUOUS, UNRESOLVED_DATA }

data class PointInTimeEvidence(
    val id:String,
    val symbol:String,
    val kind:EvidenceKind,
    val metric:String,
    val value:String,
    val source:String,
    val sourceUrl:String="",
    val observedAt:Long,
    val effectiveAt:Long,
    val publishedAt:Long=effectiveAt,
    val revisionId:String="",
    val notes:String=""
)

data class MacroEventRecord(
    val id:String,
    val title:String,
    val startAt:Long,
    val endAt:Long,
    val risk:EventRiskLevel,
    val source:String,
    val sourceUrl:String="",
    val symbol:String="",
    val observedAt:Long,
    val prospective:Boolean=true
)

data class SectorIntelligence(
    val symbol:String,
    val industry:String,
    val peersObserved:Int,
    val directionalBreadthPct:Double,
    val industryReturnPct:Double,
    val stockReturnPct:Double,
    val relativeStrengthPct:Double,
    val peerConfirmed:Boolean,
    val source:String,
    val observedAt:Long
)

data class ChallengerShadowRecord(
    val id:String,
    val strategyId:String,
    val strategyName:String,
    val symbol:String,
    val direction:TradeDirection,
    val score:Double,
    val entryPrice:Double,
    val openedAt:Long,
    val resolveAt:Long,
    val scheduledSessionDate:String,
    val researchSignature:String="",
    val evidence:String="",
    val outcome:ChallengerShadowOutcome=ChallengerShadowOutcome.PENDING,
    val resolvedAt:Long=0L,
    val horizonPrice:Double=0.0,
    val returnPct:Double=0.0,
    val note:String="",
    val targetPct:Double=0.0,
    val stopPct:Double=0.0,
    val sessionBand:String="",
    val regime:String="",
    val componentStrategyIds:List<String> = emptyList(),
    val modelVersion:String="LEGACY-V1"
)

data class BrokerFillRecord(
    val growwTradeId:String,
    val exchangeTradeId:String,
    val exchangeOrderId:String,
    val quantity:Int,
    val price:Double,
    val tradeStatus:String,
    val tradeDateTime:String,
    val remark:String=""
)

data class BrokerOrderRecord(
    val growwOrderId:String,
    val referenceId:String,
    val symbol:String,
    val side:String,
    val product:String,
    val requestedQuantity:Int,
    val submittedAt:Long,
    val signalEntryPrice:Double=0.0,
    val status:String="SUBMITTED",
    val remark:String="",
    val filledQuantity:Int=0,
    val remainingQuantity:Int=requestedQuantity,
    val averageFillPrice:Double=0.0,
    val lastReconciledAt:Long=0L,
    val fills:List<BrokerFillRecord> = emptyList(),
    val reconciliationError:String=""
)

data class DecisionSnapshot(
    val id:String,
    val hash:String,
    val symbol:String,
    val direction:TradeDirection,
    val strategyId:String,
    val decision:String,
    val reason:String,
    val score:Double,
    val decisionAt:Long,
    val calendarVersion:String,
    val handbookVersion:String,
    val researchSignature:String="",
    val evidence:String="",
    val pointInTimeEvidenceCount:Int=0,
    val sectorIndustry:String="",
    val macroRisk:String="NONE"
)

data class EvidenceFabricSummary(
    val calendarVersion:String,
    val remainingWeekSessions:Int,
    val remainingMonthSessions:Int,
    val pointInTimeEvidenceCount:Int,
    val macroEventsNext7Days:Int,
    val challengerPending:Int,
    val challengerResolved:Int,
    val brokerOrders:Int,
    val decisionSnapshots:Int,
    val sectorMapVersion:String=""
)

data class BrokerOrderPlacement(
    val growwOrderId:String,
    val orderReferenceId:String,
    val orderStatus:String,
    val remark:String
)



data class BrokerPosition(
    val tradingSymbol:String,
    val exchange:String,
    val product:String,
    val quantity:Int,
    val netPrice:Double,
    val realisedPnl:Double
)

data class BrokerMarginSnapshot(
    val clearCash:Double,
    val misBalanceAvailable:Double,
    val netEquityMarginUsed:Double
)

data class BrokerOrderMargin(
    val totalRequirement:Double,
    val cashMisMarginRequired:Double,
    val brokerageAndCharges:Double
)

data class BrokerSmartOrderPlacement(
    val smartOrderId:String,
    val referenceId:String,
    val status:String
)
data class BrokerOrderDetail(
    val growwOrderId:String,
    val orderStatus:String,
    val remark:String,
    val quantity:Int,
    val filledQuantity:Int,
    val remainingQuantity:Int,
    val averageFillPrice:Double,
    val orderReferenceId:String
)
