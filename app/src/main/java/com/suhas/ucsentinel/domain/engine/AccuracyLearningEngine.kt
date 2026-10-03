package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.ConfidenceCalibration
import com.suhas.globaledgeai.domain.model.WalkForwardValidation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Conservative accuracy learning helpers.
 * They never use future observations to score an older call. Calibration and validation operate
 * only on already-closed outcomes, and confidence changes are deliberately bounded.
 */
class AccuracyLearningEngine {
    data class Observation(val score:Double,val win:Boolean,val closedAt:Long,val sessionDate:String)

    fun calibrate(engineLabel:String,rawScore:Double,observations:List<Observation>):ConfidenceCalibration{
        val lo=(floor(rawScore/10.0)*10.0).coerceIn(0.0,90.0)
        val hi=(lo+9.999).coerceAtMost(100.0)
        val bucket=observations.filter{it.score>=lo&&it.score<=hi}
        val wins=bucket.count{it.win};val n=bucket.size
        // Beta(2,2) shrinkage prevents a handful of calls from turning 100%/0% into production truth.
        val bayes=(wins+2.0)/(n+4.0)*100.0
        val evidenceWeight=min(1.0,n/20.0)
        val blended=rawScore*(1.0-evidenceWeight)+bayes*evidenceWeight
        val bounded=blended.coerceIn((rawScore-15.0).coerceAtLeast(5.0),(rawScore+15.0).coerceAtMost(95.0))
        val days=bucket.map{it.sessionDate}.filter{it.isNotBlank()}.distinct().size
        return ConfidenceCalibration(engineLabel,rawScore,bounded,n,wins,"${lo.toInt()}–${hi.toInt()}",n>=12&&days>=4)
    }

    fun walkForward(engineLabel:String,observations:List<Observation>):WalkForwardValidation{
        val sorted=observations.sortedBy{it.closedAt}.filter{it.sessionDate.isNotBlank()}
        val days=sorted.map{it.sessionDate}.distinct()
        if(sorted.size<12||days.size<4)return WalkForwardValidation(engineLabel,0,0,0.0,0.0,false,"INSUFFICIENT • need 12 closed calls across 4 days")
        val splitDayCount=(days.size*0.70).toInt().coerceIn(2,days.size-1)
        val trainDays=days.take(splitDayCount).toSet();val validationDays=days.drop(splitDayCount).toSet()
        val train=sorted.filter{it.sessionDate in trainDays};val validation=sorted.filter{it.sessionDate in validationDays}
        fun rate(x:List<Observation>)=if(x.isEmpty())0.0 else x.count{it.win}*100.0/x.size
        val tr=rate(train);val vr=rate(validation)
        val stable=validation.size>=4&&abs(tr-vr)<=20.0&&vr>=40.0
        val note=if(stable)"PASS • unseen-period performance is within 20pp of training" else "HOLD • validation drift or sample size is not strong enough"
        return WalkForwardValidation(engineLabel,train.size,validation.size,tr,vr,stable,note)
    }

    fun thresholdAdjustment(engineLabel:String,observations:List<Observation>):Double{
        val wf=walkForward(engineLabel,observations)
        if(!wf.stable)return 0.0
        val recent=observations.sortedByDescending{it.closedAt}.take(60)
        if(recent.size<12)return 0.0
        val avgScore=recent.map{it.score}.average();val actual=recent.count{it.win}*100.0/recent.size
        val overConfidence=avgScore-actual
        return when{
            overConfidence>=20.0->2.0
            overConfidence>=10.0->1.0
            overConfidence<=-15.0&&wf.validationWinRatePct>=65.0->-0.5
            else->0.0
        }
    }
}
