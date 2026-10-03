package com.suhas.globaledgeai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.notifications.AppNotifier
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId

class ScanWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val repo=(applicationContext as GlobalEdgeApplication).repository
        val settings=repo.settings()
        val nowZ=ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val session=repo.marketSessionInfo(nowZ)
        val now=nowZ.toLocalTime()
        val forceMarketPass=inputData.getBoolean("force_market_pass",false)
        DiagnosticLog.log(applicationContext,"WORKER","ScanWorker start • phase="+session.phase+" • force="+forceMarketPass)

        if(settings.strategyTournamentEnabled){ runCatching{repo.refreshStrategyCatalog(false)} }

        // Global Lead is cross-time-zone work and continues outside NSE hours.
        if(settings.globalLeadEnabled){
            runCatching{repo.refreshGlobalMappings(false)}
            val intervalMinutes=settings.globalLeadScanIntervalMinutes.coerceIn(15,120)
            if(forceMarketPass||System.currentTimeMillis()-repo.lastGlobalLeadScanAt()>=intervalMinutes.toLong()*60*1000){
                runCatching{repo.scanGlobalLead()}
            }
        }

        val inMarket=session.isTradingDay&&now>=LocalTime.of(9,15)&&now<=LocalTime.of(15,30)
        if(!inMarket){
            // 15-minute WorkManager safety net for the two 24h research engines.
            if(settings.autoScanEnabled&&repo.ensureAutomationAuthentication())runCatching{repo.scanUpperCircuitNextSession()}
            runCatching{repo.closeExpiredStrategyCalls()}
            runCatching{repo.reconcileTradeCallLedger()}
            runCatching{repo.runAutonomousLearningPass()}
            repo.ensureTodayFreezeAudit(nowZ)
            DiagnosticLog.log(applicationContext,"WORKER","off-hours safety-net pass complete")
            return Result.success()
        }

        if(!repo.ensureAutomationAuthentication()){repo.ensureTodayFreezeAudit(nowZ);return Result.success()}

        suspend fun automatedPass(){
            val nowMs=System.currentTimeMillis()

            // WorkManager's supported periodic minimum is 15 minutes. Every heartbeat now runs
            // the complete UC + pressure scan, so the Upper Circuit tab no longer depends on
            // the manual Scan all button. A foreground loop in MainViewModel adds 5-minute
            // refreshes while the app is open.
            if(settings.autoScanEnabled){
                val due=forceMarketPass||nowMs-repo.lastPressureScanAt()>=12L*60*1000
                if(due){
                    val dual=repo.scanAll()
                    val ucActionable=if(dual.uc.message.startsWith("WATCHLIST ONLY")) emptyList() else dual.uc.candidates
                    AppNotifier.notifyBuyableUc(applicationContext,ucActionable)
                    repo.markPressureScanAt(nowMs)
                }
            }else if(settings.pressureAutoScanEnabled){
                val intervalMs=settings.pressureScanIntervalMinutes.coerceIn(15,120).toLong()*60*1000
                if(nowMs-repo.lastPressureScanAt()>=intervalMs){
                    repo.scanDemandOnly()
                    repo.markPressureScanAt(nowMs)
                }
            }
            if(settings.strategyTournamentEnabled){
                // v1.1.0: strategy discovery is an all-session engine, not a near-3-PM engine.
                val strategyInterval=15L
                if(forceMarketPass||nowMs-repo.lastStrategyScanAt()>=strategyInterval*60*1000){
                    repo.markStrategyScanAttempt(nowMs)
                    runCatching{repo.scanTradingStrategies()}.onSuccess{summary->
                        val freshLive=repo.strategyLiveRecommendations()
                            .filter{it.openedAt>=summary.generatedAt-60_000L}
                            .map{it.setup}
                        AppNotifier.notifyStrategySetups(applicationContext,freshLive)
                    }.onFailure{repo.markStrategyScanError(it)}
                }
            }
            if(settings.globalLeadEnabled){
                runCatching{repo.scanGlobalLead()}.onSuccess{summary->
                    AppNotifier.notifyGlobalLead(applicationContext,summary.longCandidates+summary.shortCandidates)
                }
            }
            // Close the daily snapshot only after the market session has ended.
            repo.ensureTodayFreezeAudit(nowZ)
        }

        return try{
            automatedPass()
            DiagnosticLog.log(applicationContext,"WORKER","market safety-net pass complete")
            Result.success()
        }catch(t:Throwable){
            DiagnosticLog.log(applicationContext,"WORKER","ScanWorker failed",t)
            if(repo.isAuthenticationFailure(t)){
                repo.invalidateAccessToken()
                if(repo.ensureAutomationAuthentication()){
                    runCatching{automatedPass()}.fold(onSuccess={Result.success()},onFailure={Result.retry()})
                }else Result.success()
            }else{
                repo.ensureTodayFreezeAudit(nowZ)
                Result.retry()
            }
        }
    }
}
