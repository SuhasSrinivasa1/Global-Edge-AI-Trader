package com.suhas.globaledgeai.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.notifications.AppNotifier
import com.suhas.globaledgeai.domain.engine.AutomationPolicy
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
        val nowMs=System.currentTimeMillis()
        val forceMarketPass=inputData.getBoolean("force_market_pass",false)
        val serviceFresh=repo.scannerHeartbeatFresh(nowMs)
        DiagnosticLog.log(applicationContext,"WORKER","ScanWorker start • phase="+session.phase+" • force="+forceMarketPass+" • serviceFresh="+serviceFresh)

        val inMarket=session.isOpen
        if(inMarket&&settings.autoScanEnabled&&now>=LocalTime.of(14,45)&&now<LocalTime.of(15,10)&&!repo.hasThreePmPrepToday()){
            if(repo.ensureAutomationAuthentication())runCatching{repo.prepareUpperCircuitThreePm()}
                .onFailure{DiagnosticLog.log(applicationContext,"UC-3PM-PREP","worker preparation failed",it)}
            if(repo.scannerHeartbeatFresh(nowMs)&&!forceMarketPass)return Result.success()
        }

        if(inMarket&&settings.autoScanEnabled&&AutomationPolicy.isThreePmPriorityWindow(now)){
            if(repo.ensureAutomationAuthentication()){
                val due=!repo.hasThreePmUcToday()&&(forceMarketPass||nowMs-repo.lastNearCloseAutoScanAt()>=4L*60_000L)
                if(due)runCatching{repo.scanUpperCircuitThreePm()}.onSuccess{picks->
                    if(picks.isNotEmpty())AppNotifier.notifyThreePmUc(applicationContext,picks)
                }.onFailure{DiagnosticLog.log(applicationContext,"UC-3PM","worker priority pass failed",it)}
            }
            if(serviceFresh&&!forceMarketPass){
                DiagnosticLog.log(applicationContext,"WORKER","3 PM backup checked; foreground service heartbeat fresh, skipping duplicate engines")
                return Result.success()
            }
        }else if(serviceFresh&&!forceMarketPass){
            DiagnosticLog.log(applicationContext,"WORKER","foreground service heartbeat fresh; stale-only worker skipped")
            return Result.success()
        }

        // Best-effort service recovery on OEM-killed processes; if Android blocks background FGS start,
        // this worker still performs the stale fallback below.
        runCatching{MarketScanService.start(applicationContext)}
            .onFailure{DiagnosticLog.log(applicationContext,"WORKER","foreground service recovery start not permitted",it)}

        if(settings.strategyTournamentEnabled)runCatching{repo.refreshStrategyCatalog(false)}
        if(settings.globalLeadEnabled)runCatching{repo.refreshGlobalMappings(false)}

        if(!inMarket){
            if(settings.autoScanEnabled&&repo.ensureAutomationAuthentication()&&nowMs-repo.lastMarketDataSuccessAt()>=12L*60_000L)
                runCatching{repo.scanUpperCircuitNextSession()}
            if(settings.globalLeadEnabled&&nowMs-repo.lastGlobalLeadScanAt()>=15L*60_000L)runCatching{repo.scanGlobalLead()}
            runCatching{repo.closeExpiredStrategyCalls()}
            runCatching{repo.reconcileTradeCallLedger()}
            repo.ensureTodayFreezeAudit(nowZ)
            DiagnosticLog.log(applicationContext,"WORKER","off-hours stale fallback complete")
            return Result.success()
        }

        if(!repo.ensureAutomationAuthentication()){repo.ensureTodayFreezeAudit(nowZ);return Result.success()}

        suspend fun automatedPass(){
            if(settings.autoScanEnabled&&nowMs-repo.lastPressureScanAt()>=12L*60_000L){
                val dual=repo.scanAll()
                val ucActionable=dual.uc.candidates.filter{"UC_LIVE" in it.activeStrategies}
                AppNotifier.notifyBuyableUc(applicationContext,ucActionable)
                repo.markPressureScanAt(System.currentTimeMillis())
            }else if(settings.pressureAutoScanEnabled&&!settings.autoScanEnabled){
                val intervalMs=settings.pressureScanIntervalMinutes.coerceIn(15,120).toLong()*60*1000
                if(nowMs-repo.lastPressureScanAt()>=intervalMs){repo.scanDemandOnly();repo.markPressureScanAt(System.currentTimeMillis())}
            }
            if(settings.strategyTournamentEnabled&&nowMs-repo.lastStrategyScanAt()>=15L*60_000L){
                repo.markStrategyScanAttempt(nowMs)
                runCatching{repo.scanTradingStrategies()}.onSuccess{summary->
                    val freshLive=repo.strategyLiveRecommendations().filter{it.openedAt>=summary.generatedAt-60_000L}.map{it.setup}
                    AppNotifier.notifyStrategySetups(applicationContext,freshLive)
                }.onFailure{repo.markStrategyScanError(it)}
            }
            if(settings.globalLeadEnabled&&nowMs-repo.lastGlobalLeadScanAt()>=12L*60_000L){
                runCatching{repo.scanGlobalLead()}.onSuccess{summary->AppNotifier.notifyGlobalLead(applicationContext,summary.candidates)}
            }
            repo.ensureTodayFreezeAudit(nowZ)
        }

        return try{
            automatedPass()
            DiagnosticLog.log(applicationContext,"WORKER","market stale fallback complete")
            Result.success()
        }catch(t:Throwable){
            DiagnosticLog.log(applicationContext,"WORKER","ScanWorker failed",t)
            if(repo.isAuthenticationFailure(t)){
                repo.invalidateAccessToken()
                if(repo.ensureAutomationAuthentication())runCatching{automatedPass()}.fold(onSuccess={Result.success()},onFailure={Result.retry()})
                else Result.success()
            }else{repo.ensureTodayFreezeAudit(nowZ);Result.retry()}
        }
    }
}
