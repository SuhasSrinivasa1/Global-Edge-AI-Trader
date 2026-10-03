package com.suhas.globaledgeai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.notifications.MultifyEventStore

class MultifySignalWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val eventId=inputData.getString("event_id").orEmpty()
        if(eventId.isBlank())return Result.success()
        val repo=(applicationContext as GlobalEdgeApplication).repository
        val existing=MultifyEventStore.find(applicationContext,eventId)
        if(existing?.processedAt?:0L>0L)return Result.success()
        return try{
            val decision=repo.processMultifyEvent(eventId)
            DiagnosticLog.log(applicationContext,"MULTIFY-FAST","${decision?.symbol?:"?"} • ${decision?.tier?:"SKIP"} • ${decision?.direction?:"NONE"} • score=${decision?.score?:0.0} • ${decision?.strategyTag.orEmpty()}")
            Result.success()
        }catch(t:Throwable){
            DiagnosticLog.log(applicationContext,"MULTIFY-FAST","event $eventId immediate analysis failed • attempt=$runAttemptCount",t)
            val m=t.message.orEmpty().lowercase()
            val transient=repo.isAuthenticationFailure(t)||"timeout" in m||"timed out" in m||"429" in m||"502" in m||"503" in m||"network" in m||"connection" in m||"quote" in m
            if(transient&&runAttemptCount<2)Result.retry() else {
                DiagnosticLog.log(applicationContext,"MULTIFY-MISSED","event $eventId marked missed/stale after immediate path failure: ${t.message.orEmpty().take(180)}")
                Result.success()
            }
        }
    }
}
