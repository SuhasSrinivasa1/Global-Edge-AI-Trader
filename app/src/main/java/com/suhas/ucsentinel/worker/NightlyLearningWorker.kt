package com.suhas.globaledgeai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog

class NightlyLearningWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val repo=(applicationContext as GlobalEdgeApplication).repository
        if(!repo.settings().learningEnabled)return Result.success()
        return try{
            DiagnosticLog.log(applicationContext,"NIGHTLY","deep replay start")
            val learning=repo.runAutonomousLearningPass(force=true)
            val multifyForensic=runCatching{repo.runMultifyForensicReplay()}.getOrElse{"Multify forensic replay failed: ${it.message}"}
            val challenger=runCatching{repo.resolveChallengerShadows()}.getOrDefault(0)
            runCatching{repo.scanUpperCircuitNextSession()}
            if(repo.settings().globalLeadEnabled)runCatching{repo.scanGlobalLead()}
            DiagnosticLog.log(applicationContext,"NIGHTLY","deep replay complete • $learning • $multifyForensic • challenger $challenger")
            Result.success()
        }catch(t:Throwable){
            DiagnosticLog.log(applicationContext,"NIGHTLY","deep replay failed",t)
            if(repo.isAuthenticationFailure(t))Result.retry() else Result.retry()
        }
    }
}
