package com.suhas.globaledgeai.worker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.MainActivity
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.notifications.AppNotifier
import com.suhas.globaledgeai.domain.engine.AutomationPolicy
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime

class MarketScanService: Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ist = ZoneId.of("Asia/Kolkata")
    private var lastMarketPass = 0L
    private var lastStrategyPass = 0L
    private var lastGlobalPass = 0L
    private var lastOffHoursUcPass = 0L
    private var lastLearningPass = 0L
    private var lastGovernancePass = 0L
    private var lastEvidencePass = 0L
    private var lastMultifyPass = 0L
    private var marketJob:Job?=null
    private var strategyJob:Job?=null
    private var globalJob:Job?=null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification("Automatic scanner armed"))
        DiagnosticLog.log(this,"SERVICE","Market scanner service created")
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { DiagnosticLog.log(this,"SERVICE","Market scanner service destroyed"); scope.cancel(); super.onDestroy() }

    private suspend fun loop() {
        val repo = (application as GlobalEdgeApplication).repository
        // Restore cadence from durable timestamps after OEM/process recreation instead of firing every engine again.
        lastMarketPass=repo.lastPressureScanAt()
        lastStrategyPass=repo.lastStrategyScanAt()
        lastGlobalPass=repo.lastGlobalLeadScanAt()
        lastLearningPass=repo.lastAutonomousLearningAt()
        while (scope.isActive) {
            val nowZ = ZonedDateTime.now(ist)
            val nowMs = System.currentTimeMillis()
            val session = repo.marketSessionInfo(nowZ)
            val settings = repo.settings()
            var status = if (session.isOpen) "Market open • 5 min synchronized scanner active" else "Off-hours • UC next-session + Global 24h research active"
            repo.markScannerHeartbeat(status,nowMs)
            if(repo.learningVaultConfigured())repo.backupLearningVaultIfDue()
            try {
                // Absolute 3 PM priority: no Strategy/Global/normal UC job may occupy the 15:10–15:30 window
                // before the next-session UC predictor gets its attempt.
                if(session.isOpen&&settings.autoScanEnabled&&AutomationPolicy.isThreePmPriorityWindow(nowZ.toLocalTime())){
                    status="3 PM UC PRIORITY • next-session LONG predictor"
                    marketJob?.cancel(CancellationException("3 PM UC priority"))
                    strategyJob?.cancel(CancellationException("3 PM UC priority"))
                    globalJob?.cancel(CancellationException("3 PM UC priority"))
                    if(repo.ensureAutomationAuthentication()){
                        val due=!repo.hasThreePmUcToday()&&(nowMs-repo.lastNearCloseAutoScanAt()>=4L*60_000L)
                        if(due){
                            runCatching{withTimeout(270_000L){repo.scanUpperCircuitThreePm()}}
                                .onSuccess{picks->
                                    if(picks.isNotEmpty())AppNotifier.notifyThreePmUc(this,picks)
                                    DiagnosticLog.log(this,"UC-3PM","priority pass completed • picks=${picks.size}")
                                }
                                .onFailure{DiagnosticLog.log(this,"UC-3PM","priority pass failed",it)}
                        }
                    }else status="3 PM UC PRIORITY • Groww authentication required"
                    repo.markScannerHeartbeat(status,System.currentTimeMillis())
                    updateNotification(status)
                    delay(60_000L)
                    continue
                }
                // Call governance is independent from model learning: it closes intraday ledgers,
                // resolves WIN/LOSS outcomes and prevents stale LIVE calls from surviving overnight.
                if (nowMs-lastGovernancePass >= 5L*60_000L) {
                    lastGovernancePass=nowMs
                    runCatching { repo.closeExpiredStrategyCalls() }
                        .onFailure { DiagnosticLog.log(this,"GOVERNANCE","strategy reconciliation failed",it) }
                    runCatching { repo.reconcileTradeCallLedger() }
                        .onFailure { DiagnosticLog.log(this,"GOVERNANCE","call-ledger reconciliation failed",it) }
                }

                if (nowMs-lastEvidencePass >= 30L*60_000L) {
                    lastEvidencePass=nowMs
                    scope.launch{
                        runCatching{repo.refreshNews()}
                            .onSuccess{DiagnosticLog.log(this@MarketScanService,"EVIDENCE","prospective NSE/BSE refresh • ${it.size} items")}
                            .onFailure{DiagnosticLog.log(this@MarketScanService,"EVIDENCE","prospective evidence refresh failed",it)}
                    }
                }

                // Independent self-learning/audit cadence. This is deliberately separate from scans:
                // it evaluates closed outcomes, adapts learned weights/thresholds and writes a weekly-audit line.
                if (settings.learningEnabled && nowMs-lastLearningPass >= 15L*60_000L) {
                    lastLearningPass=nowMs
                    runCatching { repo.runAutonomousLearningPass() }
                        .onSuccess { DiagnosticLog.log(this,"LEARNING","15-minute autonomous pass • $it") }
                        .onFailure { DiagnosticLog.log(this,"LEARNING","autonomous pass failed",it) }
                }

                if (session.isOpen) {
                    if (!repo.ensureAutomationAuthentication()) {
                        status = "Authentication required • background market scan paused"
                        DiagnosticLog.log(this,"AUTH","Automation authentication unavailable")
                    } else {
                        // Multify is the highest-priority intraday reaction lane. A notification worker handles
                        // the first decision immediately; this one-minute pass manages shadow exits/reversals and
                        // later waves for recently alerted equity symbols before the broader scanners consume API budget.
                        if(nowMs-lastMultifyPass>=60_000L){
                            lastMultifyPass=nowMs
                            runCatching{repo.monitorMultifyShadowLane()}
                                .onSuccess{n->if(n>0)DiagnosticLog.log(this,"MULTIFY-WAVE","shadow lane changed $n positions/waves")}
                                .onFailure{DiagnosticLog.log(this,"MULTIFY-WAVE","shadow monitor failed",it)}
                        }
                        // v1.6.2 coordinates the three market engines instead of launching them into the same
                        // Groww request window. UC/Pressure gets first claim, Strategy second, Global third.
                        // The service heartbeat remains independent, while API-heavy jobs are serialized.
                        if (settings.strategyTournamentEnabled && nowMs-lastStrategyPass >= 5L*60_000L && strategyJob?.isActive!=true && marketJob?.isActive!=true && globalJob?.isActive!=true && !(settings.autoScanEnabled && nowMs-lastMarketPass >= 5L*60_000L)) {
                            lastStrategyPass = nowMs
                            repo.markStrategyScanAttempt(nowMs)
                            strategyJob=scope.launch{
                                suspend fun once() = runCatching { withTimeout(5L*60_000L){repo.scanTradingStrategies()} }
                                var result=once()
                                val firstError=result.exceptionOrNull()
                                if(firstError!=null && repo.isAuthenticationFailure(firstError)){
                                    repo.invalidateAccessToken()
                                    if(repo.ensureAutomationAuthentication())result=once()
                                }
                                result.onSuccess { summary ->
                                    val fresh = repo.strategyLiveRecommendations().filter { it.openedAt >= summary.generatedAt-120_000L }.map { it.setup }
                                    AppNotifier.notifyStrategySetups(this@MarketScanService,fresh)
                                    DiagnosticLog.log(this@MarketScanService,"STRATEGY","pass live=${repo.strategyLiveRecommendations().size} watch=${summary.topSetups.size} enriched=${summary.symbolsEnriched} strategies=${summary.strategiesRun} msg=${summary.message}")
                                }.onFailure { t ->
                                    repo.markStrategyScanError(t)
                                    DiagnosticLog.log(this@MarketScanService,"STRATEGY","scan failed",t)
                                }
                            }
                        }
                        if (settings.autoScanEnabled && nowMs-lastMarketPass >= 5L*60_000L && marketJob?.isActive!=true && strategyJob?.isActive!=true && globalJob?.isActive!=true) {
                            lastMarketPass = nowMs
                            marketJob=scope.launch{
                                runCatching { withTimeout(5L*60_000L){repo.scanAll()} }
                                    .onSuccess { dual ->
                                        repo.markPressureScanAt(System.currentTimeMillis())
                                        val uc = dual.uc.candidates.filter{"UC_LIVE" in it.activeStrategies}
                                        AppNotifier.notifyBuyableUc(this@MarketScanService,uc)
                                        if(nowZ.toLocalTime()>=LocalTime.of(15,15)&&nowZ.toLocalTime()<=LocalTime.of(15,30)){
                                            val threePm=runCatching{repo.scanUpperCircuitThreePm()}.getOrDefault(emptyList())
                                            if(threePm.isNotEmpty())AppNotifier.notifyThreePmUc(this@MarketScanService,threePm)
                                        }
                                        DiagnosticLog.log(this@MarketScanService,"UC/PRESSURE","pass uc=${dual.uc.candidates.size} pressure=${dual.demand.candidates.size} ucMsg=${dual.uc.message.take(140)} pressureMsg=${dual.demand.message.take(140)}")
                                    }
                                    .onFailure { DiagnosticLog.log(this@MarketScanService,"UC/PRESSURE","scan failed",it) }
                            }
                        }
                        if (settings.globalLeadEnabled && nowMs-lastGlobalPass >= 5L*60_000L && globalJob?.isActive!=true && marketJob?.isActive!=true && strategyJob?.isActive!=true && !(settings.autoScanEnabled && nowMs-lastMarketPass >= 5L*60_000L) && !(settings.strategyTournamentEnabled && nowMs-lastStrategyPass >= 5L*60_000L)) {
                            lastGlobalPass = nowMs
                            globalJob=scope.launch{
                                runCatching { withTimeout(5L*60_000L){repo.scanGlobalLead()} }
                                    .onSuccess { summary ->
                                        AppNotifier.notifyGlobalLead(this@MarketScanService,summary.candidates)
                                        DiagnosticLog.log(this@MarketScanService,"GLOBAL","live pass candidates=${summary.candidates.size} msg=${summary.message}")
                                    }
                                    .onFailure { DiagnosticLog.log(this@MarketScanService,"GLOBAL","scan failed",it) }
                            }
                        }
                    }
                } else {
                    // Upper Circuit is a 24-hour research engine: after NSE closes it keeps rebuilding
                    // a NEXT SESSION list from the latest completed Indian session. It does not pretend
                    // that stale depth is a live executable order book; live execution is revalidated at 09:15.
                    if (settings.autoScanEnabled && nowMs-lastOffHoursUcPass >= 15L*60_000L) {
                        lastOffHoursUcPass=nowMs
                        runCatching { repo.scanUpperCircuitNextSession() }
                            .onSuccess { DiagnosticLog.log(this,"UC-NEXT","off-hours candidates=${it.candidates.size} msg=${it.message}") }
                            .onFailure { DiagnosticLog.log(this,"UC-NEXT","off-hours pass failed",it) }
                    }
                    // Global markets keep moving while India is closed. Re-rank them every 15 minutes;
                    // Groww Indian data is cached/last-session context until live revalidation at the open.
                    if (settings.globalLeadEnabled && nowMs-lastGlobalPass >= 15L*60_000L) {
                        lastGlobalPass = nowMs
                        runCatching { repo.refreshGlobalMappings(false); repo.scanGlobalLead() }
                            .onSuccess { DiagnosticLog.log(this,"GLOBAL","24h pass candidates=${it.candidates.size} msg=${it.message}") }
                            .onFailure { DiagnosticLog.log(this,"GLOBAL","24h pass failed",it) }
                    }
                }
            } catch (t: Throwable) {
                DiagnosticLog.log(this,"SERVICE","loop error",t)
            }
            repo.markScannerHeartbeat(status,System.currentTimeMillis())
            updateNotification(status)
            delay(60_000L)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,"Automatic market scanner",NotificationManager.IMPORTANCE_LOW).apply {
                    description="Keeps Global Edge market scanning and learning active while the app is closed."
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(status: String): Notification {
        val pi = PendingIntent.getActivity(this,2601,Intent(this,MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Global Edge automatic scanner")
            .setContentText(status)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    private fun updateNotification(status: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID,notification(status)) }
    }

    companion object {
        private const val CHANNEL = "global_edge_market_service"
        private const val NOTIFICATION_ID = 2601
        fun start(context: Context) {
            ContextCompat.startForegroundService(context,Intent(context,MarketScanService::class.java))
        }
    }
}
