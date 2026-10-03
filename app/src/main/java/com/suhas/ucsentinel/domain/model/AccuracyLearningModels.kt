package com.suhas.globaledgeai.domain.model

enum class RejectedShadowOutcome { PENDING, WOULD_WIN, WOULD_LOSE }

data class RejectedCandidateRecord(
    val id:String,
    val engineLabel:String,
    val symbol:String,
    val direction:TradeDirection,
    val score:Double,
    val entryPrice:Double,
    val stopPrice:Double,
    val targetPrice:Double,
    val capturedAt:Long,
    val targetSessionDate:String,
    val reason:String,
    val outcome:RejectedShadowOutcome=RejectedShadowOutcome.PENDING,
    val closedAt:Long=0L,
    val exitPrice:Double=0.0,
    val returnPct:Double=0.0
)

data class ConfidenceCalibration(
    val engineLabel:String,
    val rawScore:Double,
    val calibratedPct:Double,
    val observations:Int,
    val wins:Int,
    val bucketLabel:String,
    val reliable:Boolean
)

data class WalkForwardValidation(
    val engineLabel:String,
    val trainObservations:Int,
    val validationObservations:Int,
    val trainWinRatePct:Double,
    val validationWinRatePct:Double,
    val stable:Boolean,
    val note:String
)
