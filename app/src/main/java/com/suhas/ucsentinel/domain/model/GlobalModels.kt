package com.suhas.globaledgeai.domain.model

enum class GlobalMappingType { EXACT_ADR, LISTED_PARENT, LISTED_GROUP_PARENT, STRATEGIC_OWNER, FRANCHISE_PROXY }
enum class GlobalLeadDirection { LONG, SHORT }
enum class GlobalLeadAction { ENTER_AFTER_OPEN, WAIT_FOR_CONFIRMATION, KEEP_NEXT_SESSION, EXIT_BY_3PM, NEXT_OPEN_WATCH, OBSERVE }

data class GlobalCounterpart(
    val indianSymbol:String,
    val indianCompany:String,
    val foreignTicker:String,
    val foreignCompany:String,
    val exchange:String,
    val region:String,
    val benchmarkTicker:String,
    val mappingType:GlobalMappingType,
    val relationshipWeight:Double,
    val officialSource:String="",
    val notes:String=""
)

data class GlobalQuoteSnapshot(
    val ticker:String,
    val last:Double,
    val previousClose:Double,
    val open:Double,
    val high:Double,
    val low:Double,
    val volume:Long,
    val averageVolume20:Double,
    val marketTimestamp:Long,
    val sessionOpenTimestamp:Long,
    val sessionDate:String
){
    val gapPct:Double get()=if(previousClose>0)(open/previousClose-1.0)*100.0 else 0.0
    val dayPct:Double get()=if(previousClose>0)(last/previousClose-1.0)*100.0 else 0.0
    val fromOpenPct:Double get()=if(open>0)(last/open-1.0)*100.0 else 0.0
    val volumeRatio:Double get()=if(averageVolume20>0)volume/averageVolume20 else 1.0
    val closeLocation:Double get()=if(high>low)((last-low)/(high-low)).coerceIn(0.0,1.0) else 0.5
}

data class GlobalLeadCandidate(
    val rank:Int,
    val direction:GlobalLeadDirection,
    val indianSymbol:String,
    val indianCompany:String,
    val foreignTicker:String,
    val foreignCompany:String,
    val exchange:String,
    val region:String,
    val mappingType:GlobalMappingType,
    val relationshipWeight:Double,
    val score:Double,
    val confidence:ConfidenceBand,
    val action:GlobalLeadAction,
    val foreignGapPct:Double,
    val foreignDayPct:Double,
    val foreignFromOpenPct:Double,
    val foreignExcessPct:Double,
    val foreignVolumeRatio:Double,
    val foreignCloseLocation:Double,
    val foreignSignalAt:Long,
    val indianPrice:Double,
    val indianOpen:Double,
    val indianFromOpenPct:Double,
    val indianDayPct:Double,
    val indianBuySellRatio:Double,
    val freshnessPct:Double,
    val pressureConfirmationScore:Double,
    val expectedTargetPct:Double,
    val reasons:List<String>,
    val officialSource:String="",
    val generatedAt:Long=System.currentTimeMillis()
)


data class GlobalLeadClosedRecord(
    val candidate:GlobalLeadCandidate,
    val closedAt:Long,
    val reason:String
)

data class GlobalLeadSummary(
    val generatedAt:Long,
    val mappingVersion:String,
    val mappingsScanned:Int,
    val foreignQuotesLoaded:Int,
    val candidates:List<GlobalLeadCandidate>,
    val message:String,
    val nextDecisionDeadline:String="15:00 IST",
    val droppedSincePrevious:List<String> = emptyList()
){
    val longCandidates:List<GlobalLeadCandidate> get()=candidates.filter{it.direction==GlobalLeadDirection.LONG}
    val shortCandidates:List<GlobalLeadCandidate> get()=candidates.filter{it.direction==GlobalLeadDirection.SHORT}
}
