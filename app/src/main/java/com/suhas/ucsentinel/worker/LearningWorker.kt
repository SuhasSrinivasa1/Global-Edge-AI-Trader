package com.suhas.globaledgeai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog

class LearningWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val repo=(applicationContext as GlobalEdgeApplication).repository
        if(!repo.settings().learningEnabled)return Result.success()
        DiagnosticLog.log(applicationContext,"LEARNING-WORKER","learning worker start")

        return try{
            val msg=repo.runAutonomousLearningPass()
            DiagnosticLog.log(applicationContext,"LEARNING-WORKER","learning worker success • "+msg)
            Result.success()
        }catch(t:Throwable){
            DiagnosticLog.log(applicationContext,"LEARNING-WORKER","learning worker failed",t)
            if(repo.isAuthenticationFailure(t)){
                repo.invalidateAccessToken()
                if(repo.ensureAutomationAuthentication()){
                    runCatching{repo.runAutonomousLearningPass()}.fold(
                        onSuccess={Result.success()},
                        onFailure={Result.retry()}
                    )
                }else Result.success()
            }else Result.retry()
        }
    }
}
