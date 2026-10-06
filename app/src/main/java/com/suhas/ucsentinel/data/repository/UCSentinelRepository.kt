package com.suhas.globaledgeai.data.repository

import android.content.Context
import android.net.Uri
import com.suhas.globaledgeai.data.local.*
import com.suhas.globaledgeai.data.remote.*
import com.suhas.globaledgeai.domain.engine.*
import com.suhas.globaledgeai.domain.model.*
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.notifications.MultifyEvent
import com.suhas.globaledgeai.notifications.MultifyEventStore
import com.suhas.globaledgeai.notifications.AppNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class GlobalEdgeAITraderRepository(context:Context){
    companion object{
        const val EXPECTED_TRADING_STATIC_IP="169.150.209.215"
        const val MULTIFY_CAPITAL_BUDGET=200_000.0
        const val MULTIFY_DAILY_NET_TARGET=5_000.0
        const val MULTIFY_ESTIMATED_COST_RATE_PER_LEG=0.00075
        const val MANUAL_MULTIFY_SOURCE="manual:in-app"
    }
    private data class MultifyLiveSubmission(
        val referenceId:String,
        val filledQuantity:Int,
        val averagePrice:Double,
        val protectionReference:String="",
        val protectionId:String=""
    )

    private val appContext=context.applicationContext
    private val secureStore=SecureCredentialStore(context)
    private val prefs=AppPreferences(context)
    private val strategyDb=StrategyLearningDatabase.get(appContext)
    private val strategyLearning=strategyDb.dao()
    private val strategyGovernance=StrategyGovernanceV2()
    private val regimeClassifier=MarketRegimeClassifier()
    private val learningVault=PersistentLearningVault(appContext,strategyDb)
    private val groww=GrowwClient()
    private val news=ExchangeNewsClient()
    private val nseMaster=NseSecurityMasterClient()
    private val instruments=InstrumentRepository(groww)
    private val ucScanner=ScannerEngine(groww)
    private val demandScanner=DemandScannerEngine(groww)
    private val replay=ReplayEngine(groww)
    private val globalMarket=GlobalMarketClient(context)
    private val globalLeadEngine=GlobalLeadEngine()
    private val strategyEngine=TradingStrategyEngine()
    private val handbookSynergy=HandbookSynergyEngine()
    private val evidenceFabric=EvidenceFabricEngine()
    private val industryClient=NiftyIndustryClient(context)
    private val autopsyEngine=TradeAutopsyEngine()
    private val multifyTrading=MultifyTradingStore(context)
    private val multifyEngine=MultifyLearningEngine()
    private val strategyCatalogClient=StrategyCatalogClient(context)
    private var strategyCatalog:StrategyCatalogClient.Bundle?=null
    private var globalMappings:GlobalMarketClient.MappingBundle?=null
    private val globalMutex=Mutex()
    private val multifyMutex=Mutex()
    private val strategyMutex=Mutex()
    private val ist=ZoneId.of("Asia/Kolkata")
    private val dateTimeFmt=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var newListingsCache:List<ListedSecurity> = prefs.loadNewListings()
    private val scanMutex=Mutex()
    private var lastDualSummary:DualScanSummary?=loadLastDualFromDisk()
    private var lastDualScanAt:Long=maxOf(lastDualSummary?.uc?.completedAt?:0L,lastDualSummary?.demand?.completedAt?:0L)
    private val dualScanReuseMs=4L*60_000L

    init {
        prefs.mergeMacroEvents(evidenceFabric.seededMacroEvents())
        DiagnosticLog.log(appContext,"BOOT","v1.5 evidence fabric • calendar="+NseTradingCalendar2026.VERSION+" • handbook="+HandbookSynergyEngine.VERSION)
    }

    fun credentials()=secureStore.loadCredentials()
    fun saveCredentials(c:Credentials)=secureStore.saveCredentials(c)
    fun accessToken()=secureStore.accessToken()
    fun tokenExpiry()=secureStore.accessTokenExpiry()
    fun growwApiHealth()=groww.apiHealthSnapshot()
    fun settings()=prefs.loadSettings()
    fun saveSettings(s:AppSettings)=prefs.saveSettings(s)
    fun tradingStaticIp()=EXPECTED_TRADING_STATIC_IP
    fun multifyEvents(limit:Int=200):List<MultifyEvent> = MultifyEventStore.recent(appContext,limit)
    fun multifyListenerEnabled():Boolean = MultifyEventStore.listenerEnabled(appContext)
    fun multifyCandidatePackage():String = MultifyEventStore.candidatePackage(appContext)
    fun multifyTrustedPackage():String = MultifyEventStore.trustedPackage(appContext)
    fun trustMultifyCandidatePackage():Boolean = MultifyEventStore.trustCandidatePackage(appContext)
    fun clearMultifyTrustedPackage() = MultifyEventStore.clearTrustedPackage(appContext)
    fun multifyShadowTrades(limit:Int=1000):List<MultifyShadowTrade> = multifyTrading.loadTrades(limit)
    fun multifyDecisions(limit:Int=400):List<MultifyDecision> = multifyTrading.loadDecisions(limit)
    fun multifyStockProfile(symbol:String):MultifyStockProfile = buildMultifyStockProfile(symbol.trim().uppercase())
    fun multifyProfiles(limit:Int=40):List<MultifyStockProfile> = MultifyEventStore.recent(appContext,1000).map{it.symbol}.filter{it.isNotBlank()}.distinct().take(limit).map{buildMultifyStockProfile(it)}.filter{it.samples>0||it.exitFallSamples>0}
    fun multifyDashboard():MultifyDashboard = buildMultifyDashboard()

    suspend fun submitManualMultifyEvent(symbol:String,eventType:MultifyEventType,signalPrice:Double=0.0):MultifyDecision?{
        val normalizedSymbol=symbol.trim().uppercase()
        require(normalizedSymbol.matches(Regex("""[A-Z][A-Z0-9&.-]{1,19}"""))){"Enter a valid NSE cash-equity symbol."}
        require(eventType in setOf(MultifyEventType.ENTRY_LONG,MultifyEventType.ENTRY_SHORT,MultifyEventType.EXIT)){"Manual event must be BUY, SELL or EXIT."}
        require(signalPrice.isFinite()&&signalPrice>=0.0){"Signal price must be zero/blank or a positive number."}
        val now=System.currentTimeMillis()
        val action=when(eventType){MultifyEventType.ENTRY_LONG->"BUY";MultifyEventType.ENTRY_SHORT->"SELL";MultifyEventType.EXIT->"EXIT";else->"UNKNOWN"}
        val event=MultifyEvent(
            id=MultifyEventStore.idFor(MANUAL_MULTIFY_SOURCE,now,"Manual Multify $action","$normalizedSymbol|$signalPrice"),
            capturedAt=now,packageName=MANUAL_MULTIFY_SOURCE,title="Manual Multify $action",
            text="$action $normalizedSymbol"+(if(signalPrice>0.0)" @ $signalPrice" else ""),
            direction=action,symbol=normalizedSymbol,signalPrice=signalPrice,eventType=eventType,
            instrumentClass=MultifyInstrumentClass.EQUITY,listenerReceivedAt=now,parsedAt=now
        )
        require(MultifyEventStore.capture(appContext,event)){"This manual event was already captured. Try again."}
        DiagnosticLog.log(appContext,"MULTIFY-MANUAL","captured $action $normalizedSymbol • signalPrice=$signalPrice • same processing pipeline")
        return processMultifyEvent(event.id)
    }
    fun saveTradingStaticIp(value:String){ /* v1.2.8: route is intentionally pinned in this build */ }
    suspend fun currentPublicIpv4():String=withContext(Dispatchers.IO){
        val connection=(URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply{
            connectTimeout=5_000
            readTimeout=5_000
            requestMethod="GET"
            setRequestProperty("Accept","text/plain")
            useCaches=false
        }
        try{
            val code=connection.responseCode
            require(code in 200..299){"Public IP check failed: HTTP $code"}
            connection.inputStream.bufferedReader().use{it.readText().trim()}
                .also{ip->require(ip.matches(Regex("""^(?:\d{1,3}\.){3}\d{1,3}$"""))){"Invalid IPv4 response"}}
        }finally{connection.disconnect()}
    }
    fun strategyMetrics()=prefs.signalMetrics()
    fun accuracies()=mapOf(
        ScannerSection.UC_CONTINUATION to prefs.sectionAccuracy(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION),
        ScannerSection.DEMAND_SQUEEZE to prefs.sectionAccuracy(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION)
    )
    fun learningVaultConfigured()=learningVault.configured()
    fun learningVaultLastBackupAt()=learningVault.lastBackupAt()
    fun learningVaultLastRestoreAt()=learningVault.lastRestoreAt()
    fun configureLearningVault(uriText:String):Int{
        val count=learningVault.configure(Uri.parse(uriText))
        DiagnosticLog.log(appContext,"LEARNING-VAULT","configured + initial backup • entries=$count")
        return count
    }
    fun restoreLearningVault(uriText:String):Int{
        val count=learningVault.restoreAndConfigure(Uri.parse(uriText))
        newListingsCache=prefs.loadNewListings()
        lastDualSummary=loadLastDualFromDisk()
        strategyCatalog=null
        globalMappings=null
        DiagnosticLog.log(appContext,"LEARNING-VAULT","restored • entries=$count • live trading remains disarmed")
        return count
    }
    fun backupLearningVaultNow():Int{
        val count=learningVault.backupNow()
        DiagnosticLog.log(appContext,"LEARNING-VAULT","manual backup • entries=$count")
        return count
    }
    fun backupLearningVaultIfDue():Int=runCatching{learningVault.backupIfDue()}.onFailure{
        DiagnosticLog.log(appContext,"LEARNING-VAULT","automatic backup failed",it)
    }.getOrDefault(0)

    fun newListings()=newListingsCache
    fun listingFeedHealth()=prefs.listingFeedHealth()
    fun lastMarketDataSuccessAt()=prefs.lastMarketDataSuccessAt()
    fun scannerHeartbeatAt()=prefs.scannerHeartbeatAt()
    fun scannerHeartbeatStatus()=prefs.scannerHeartbeatStatus()
    fun markScannerHeartbeat(status:String,at:Long=System.currentTimeMillis())=prefs.setScannerHeartbeat(at,status)
    fun scannerHeartbeatFresh(nowMs:Long=System.currentTimeMillis())=AutomationPolicy.serviceHeartbeatFresh(nowMs,prefs.scannerHeartbeatAt())
    fun hasThreePmUcToday():Boolean{
        val today=LocalDate.now(ist)
        return prefs.loadTradeCalls(1500).any{it.engine==TradeCallEngine.UPPER_CIRCUIT&&it.bucket==TradeCallBucket.THREE_PM&&
            Instant.ofEpochMilli(it.openedAt).atZone(ist).toLocalDate()==today&&it.outcome!=TradeCallOutcome.INVALID}
    }
    fun lastSavedDualSummary()=lastDualSummary ?: loadLastDualFromDisk()
    fun lastPressureScanAt()=prefs.lastPressureScanAt()
    fun markPressureScanAt(value:Long=System.currentTimeMillis())=prefs.setLastPressureScanAt(value)
    fun lastNearCloseAutoScanAt()=prefs.lastNearCloseAutoScanAt()
    fun markNearCloseAutoScanAt(value:Long=System.currentTimeMillis())=prefs.setLastNearCloseAutoScanAt(value)
    fun lastLearningAt()=prefs.lastLearningAt()
    fun lastAutonomousLearningAt()=prefs.lastAutonomousLearningAt()
    fun globalLeadSummary()=prefs.loadGlobalLeadSummary()
    fun globalLeadClosedRecommendations()=prefs.loadGlobalLeadClosed()
    fun lastGlobalLeadScanAt()=prefs.lastGlobalLeadScanAt()
    fun lastGlobalMappingRefreshAt()=prefs.lastGlobalMappingRefreshAt()
    fun globalMappingVersion()=prefs.globalMappingVersion()
    fun strategyTournamentSummary()=prefs.loadStrategySummary()
    fun strategyLiveRecommendations()=prefs.loadStrategyLive()
    fun strategyClosedRecommendations()=prefs.loadStrategyClosed()
    fun lastStrategyScanAt()=prefs.lastStrategyScanAt()
    fun lastStrategyAttemptAt()=prefs.lastStrategyAttemptAt()
    fun lastStrategyErrorAt()=prefs.lastStrategyErrorAt()
    fun lastStrategyError()=prefs.lastStrategyError()
    fun markStrategyScanAttempt(value:Long=System.currentTimeMillis())=prefs.markStrategyAttempt(value)
    fun markStrategyScanError(t:Throwable){prefs.markStrategyError(t.message.orEmpty().ifBlank{t::class.java.simpleName})}
    fun lastStrategyCatalogRefreshAt()=prefs.lastStrategyCatalogRefreshAt()
    fun strategyCatalogVersion()=strategyCatalog?.version?:prefs.strategyCatalogVersion()
    fun freezeHistory(section:ScannerSection,limit:Int=20)=prefs.freezeHistory(section,limit)
    fun freezeRecordToday(section:ScannerSection)=prefs.freezeRecord(LocalDate.now(ist).toString(),section)
    fun latestFreezeRecord(section:ScannerSection)=prefs.freezeHistory(section,1).firstOrNull()

    fun tradeCalls():List<TradeCallRecord> = prefs.loadTradeCalls(1500)
    fun tradeAutopsies():List<TradeAutopsyRecord> = prefs.loadAutopsies(800)
    fun challengerShadows():List<ChallengerShadowRecord> = prefs.loadChallengerShadows(4000)
    fun brokerOrders():List<BrokerOrderRecord> = prefs.loadBrokerOrders(500)
    fun decisionSnapshots():List<DecisionSnapshot> = prefs.loadDecisionSnapshots(3000)
    fun pointInTimeEvidence():List<PointInTimeEvidence> = prefs.loadPointInTimeEvidence(5000)
    fun evidenceFabricSummary(now:ZonedDateTime=ZonedDateTime.now(ist)):EvidenceFabricSummary{
        val d=now.toLocalDate();val shadows=prefs.loadChallengerShadows(4000)
        val next7=now.plusDays(7).toInstant().toEpochMilli()
        return EvidenceFabricSummary(
            calendarVersion=NseTradingCalendar2026.VERSION,
            remainingWeekSessions=NseTradingCalendar2026.remainingSessionsInWeek(d),
            remainingMonthSessions=NseTradingCalendar2026.remainingSessionsInMonth(d),
            pointInTimeEvidenceCount=prefs.loadPointInTimeEvidence(5000).size,
            macroEventsNext7Days=prefs.loadMacroEvents(1000).count{it.endAt>=now.toInstant().toEpochMilli()&&it.startAt<=next7},
            challengerPending=shadows.count{it.outcome==ChallengerShadowOutcome.PENDING},
            challengerResolved=shadows.count{it.outcome!=ChallengerShadowOutcome.PENDING},
            brokerOrders=prefs.loadBrokerOrders(500).size,
            decisionSnapshots=prefs.loadDecisionSnapshots(3000).size,
            sectorMapVersion=prefs.sectorMapVersion()
        )
    }
    fun openTradeCalls(engine:TradeCallEngine?=null):List<TradeCallRecord> = tradeCalls().filter{it.outcome==TradeCallOutcome.OPEN&&(engine==null||it.engine==engine)}
    fun closedTradeCalls(engine:TradeCallEngine?=null):List<TradeCallRecord> = tradeCalls().filter{it.outcome!=TradeCallOutcome.OPEN&&(engine==null||it.engine==engine)}.sortedByDescending{it.closedAt}

    fun marketSessionInfo(now:ZonedDateTime=ZonedDateTime.now(ist)):MarketSessionInfo{
        val x=NseTradingCalendar2026.phase(now)
        val phase=when{
            !x.tradingDate->MarketPhase.WEEKEND
            x.open->MarketPhase.OPEN
            now.toLocalTime()<NseTradingCalendar2026.open->MarketPhase.PRE_OPEN
            else->MarketPhase.POST_CLOSE
        }
        return MarketSessionInfo(phase,x.tradingDate,x.open,x.label,x.sessionDate.toString())
    }

    suspend fun authenticate():String{
        val(token,expiry)=groww.authenticate(secureStore.loadCredentials())
        secureStore.saveAccessToken(token,expiry)
        return expiry
    }

    suspend fun placeManualMarketOrder(symbol:String,side:String,product:String,quantity:Int,signalEntryPrice:Double=0.0):String{
        val now=ZonedDateTime.now(ist)
        require(marketSessionInfo(now).isOpen){"Market is closed. Manual live orders are enabled only during the NSE regular 09:15–15:30 IST session."}
        require(!evidenceFabric.shouldHardWaitForMacro(now,prefs.loadMacroEvents(1000))){"WAIT / NO TRADE: macro hard-risk window is active."}
        require(quantity>0){"Quantity must be greater than zero"}
        val normalizedSide=side.trim().uppercase();val normalizedProduct=product.trim().uppercase();val normalizedSymbol=symbol.trim().uppercase()
        require((normalizedSide=="BUY"&&normalizedProduct=="CNC")||(normalizedSide=="SELL"&&normalizedProduct=="MIS")){"Order mapping rejected. LONG must be BUY/CNC; SHORT must be SELL/MIS."}

        val uncertain=prefs.loadBrokerOrders(500).firstOrNull{
            it.symbol==normalizedSymbol&&it.side==normalizedSide&&it.status=="SUBMIT_UNCERTAIN"&&System.currentTimeMillis()-it.submittedAt<30L*60_000L
        }
        require(uncertain==null){"A recent submission for $normalizedSymbol has an uncertain broker outcome. Run Broker Reconcile before trying again."}

        val actualIp=currentPublicIpv4()
        require(actualIp==EXPECTED_TRADING_STATIC_IP){"Static IP mismatch. Expected $EXPECTED_TRADING_STATIC_IP but current public IPv4 is $actualIp."}
        if(!accessTokenIsCurrent())require(ensureAutomationAuthentication()){"Groww authentication is required before placing an order."}
        val token=accessToken();require(token.isNotBlank()){"Groww access token is unavailable. Authenticate again."}

        val referenceId=multifyReference(normalizedSymbol,normalizedSide,"G")
        val pending=BrokerOrderRecord(
            growwOrderId="PENDING-"+referenceId,referenceId=referenceId,symbol=normalizedSymbol,side=normalizedSide,product=normalizedProduct,
            requestedQuantity=quantity,submittedAt=System.currentTimeMillis(),signalEntryPrice=signalEntryPrice,status="SUBMITTING",remainingQuantity=quantity
        )
        prefs.upsertBrokerOrder(pending)
        DiagnosticLog.log(appContext,"ORDER-SUBMIT","manual confirmation • ref=$referenceId • $normalizedSide $normalizedSymbol • $normalizedProduct • qty=$quantity • signalEntry=$signalEntryPrice")
        try{
            val placed=groww.placeMarketOrder(token,normalizedSymbol,quantity,normalizedProduct,normalizedSide,referenceId)
            val accepted=BrokerOrderRecord(
                growwOrderId=placed.growwOrderId.ifBlank{"PENDING-"+referenceId},referenceId=placed.orderReferenceId.ifBlank{referenceId},
                symbol=normalizedSymbol,side=normalizedSide,product=normalizedProduct,requestedQuantity=quantity,submittedAt=pending.submittedAt,
                signalEntryPrice=signalEntryPrice,status=placed.orderStatus.ifBlank{"ACCEPTED"},remark=placed.remark,remainingQuantity=quantity
            )
            prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+accepted)
            DiagnosticLog.log(appContext,"ORDER-SUBMIT","accepted • ref=$referenceId • growwId=${accepted.growwOrderId} • status=${accepted.status}")
            runCatching{reconcileBrokerOrders(accepted.growwOrderId)}
            val refreshed=prefs.loadBrokerOrders(500).firstOrNull{it.referenceId==referenceId}?:accepted
            return "Groww order ${refreshed.growwOrderId} • ${refreshed.status} • filled ${refreshed.filledQuantity}/${refreshed.requestedQuantity}"+if(refreshed.averageFillPrice>0)" • avg ₹${"%.2f".format(refreshed.averageFillPrice)}" else ""
        }catch(t:Throwable){
            val definite=t.message.orEmpty().contains(Regex("""Groww order failed \(4\d\d\)"""))||t.message.orEmpty().contains("order rejected",true)
            if(definite){
                prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+pending.copy(status="REJECTED",reconciliationError=t.message.orEmpty()))
                DiagnosticLog.log(appContext,"ORDER-SUBMIT","definitive rejection • ref=$referenceId",t)
                throw t
            }
            val recovered=runCatching{groww.getOrderStatusByReference(token,referenceId)}.getOrNull()
            if(recovered!=null&&recovered.growwOrderId.isNotBlank()){
                val accepted=pending.copy(growwOrderId=recovered.growwOrderId,status=recovered.orderStatus.ifBlank{"ACCEPTED"},remark=recovered.remark,reconciliationError="")
                prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+accepted)
                runCatching{reconcileBrokerOrders(accepted.growwOrderId)}
                DiagnosticLog.log(appContext,"ORDER-SUBMIT","recovered ambiguous submission by reference • ref=$referenceId • growwId=${accepted.growwOrderId}")
                return "Groww order ${accepted.growwOrderId} • recovered by reference • ${accepted.status}"
            }
            prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+pending.copy(status="SUBMIT_UNCERTAIN",reconciliationError=t.message.orEmpty()))
            DiagnosticLog.log(appContext,"ORDER-SUBMIT","ambiguous result • ref=$referenceId • DO NOT RETRY until reconcile",t)
            throw IllegalStateException("Order submission outcome is uncertain. Do not press PLACE ORDER again yet; use Broker Reconcile. Reference: $referenceId")
        }
    }

    private fun multifyReference(symbol:String,side:String,prefix:String="M"):String{
        val t=System.currentTimeMillis().toString(36).uppercase().takeLast(8)
        val h=symbol.uppercase().hashCode().toUInt().toString(36).uppercase().takeLast(5).padStart(5,'0')
        val sd=if(side.equals("BUY",true))"B" else "S"
        return (prefix.take(2).uppercase()+t+sd+h).filter{it.isLetterOrDigit()}.take(20).padEnd(8,'0')
    }

    private fun quoteAgeMs(q:Quote,now:Long=System.currentTimeMillis()):Long{
        val raw=q.lastTradeTime
        if(raw<=0L)return Long.MAX_VALUE
        val ms=if(raw<10_000_000_000L)raw*1000L else raw
        return (now-ms).coerceAtLeast(0L)
    }

    private suspend fun strictMultifyExecutionQuote(token:String,symbol:String,side:String,quantity:Int,signalPrice:Double,isExit:Boolean):Quote{
        val q=groww.getQuote(token,symbol,fresh=true)
        require(ExecutionQuality.executableQuote(q,true)){"Multify execution gate: liquidity/two-sided-book/spread requirements are not satisfied"}
        require(ExecutionQuality.spreadPct(q)<=0.35){"Multify execution gate: spread ${"%.2f".format(ExecutionQuality.spreadPct(q))}% exceeds 0.35%"}
        require(quoteAgeMs(q)<=15_000L){"Multify execution gate: quote is stale (${quoteAgeMs(q)} ms)"}
        val visible=if(side.equals("BUY",true)) q.sellDepth.sumOf{it.quantity}.coerceAtLeast(q.offerQuantity) else q.buyDepth.sumOf{it.quantity}.coerceAtLeast(q.bidQuantity)
        if(!isExit)require(visible>=maxOf(1L,(quantity*0.20).toLong())){"Multify execution gate: visible opposite-side depth is too small for requested quantity"}
        if(!isExit&&signalPrice>0.0){
            val move=abs(q.lastPrice/signalPrice-1.0)*100.0
            require(move<=0.50){"Multify execution gate: price moved ${"%.2f".format(move)}% since decision; refusing to chase"}
        }
        return q
    }

    private suspend fun armMultifyProtection(token:String,symbol:String,side:String,expectedQty:Int,entryPrice:Double):Pair<String,String>{
        if(expectedQty<=0||entryPrice<=0.0)return "" to ""
        var position:BrokerPosition?=null
        for(attempt in 0 until 4){
            position=runCatching{groww.getPositionBySymbol(token,symbol)}.getOrNull()
            if(position?.product=="MIS"&&position?.quantity!=0)break
            if(attempt<3)kotlinx.coroutines.delay(350L)
        }
        val p=position?:return "" to ""
        if(p.product!="MIS"||p.quantity==0)return "" to ""
        val q=abs(p.quantity).coerceAtMost(expectedQty)
        if(q<=0)return "" to ""
        val exitSide=if(p.quantity>0)"SELL" else "BUY"
        val actualEntry=p.netPrice.takeIf{it>0.0}?:entryPrice
        val target=if(p.quantity>0)actualEntry*1.016 else actualEntry*0.984
        val stop=if(p.quantity>0)actualEntry*0.993 else actualEntry*1.007
        val ref=multifyReference(symbol,exitSide,"P")
        val oco=groww.createCashMisOco(token,symbol,q,p.quantity,exitSide,target,stop,ref)
        DiagnosticLog.log(appContext,"MULTIFY-PROTECTION","OCO armed • $symbol qty=$q side=$exitSide target=${"%.2f".format(target)} stop=${"%.2f".format(stop)} id=${oco.smartOrderId}")
        return ref to oco.smartOrderId
    }

    private suspend fun placeMultifyLiveOrder(symbol:String,side:String,quantity:Int,signalPrice:Double,reason:String,isExit:Boolean=false,protectionId:String="",sourceEventId:String=""):MultifyLiveSubmission{
        val now=ZonedDateTime.now(ist)
        require(marketSessionInfo(now).isOpen){"Market is closed. Multify live orders are intraday-only."}
        require(now.toLocalTime()<LocalTime.of(15,20)||isExit){"New Multify live entries stop at 15:20 IST."}
        require(quantity>0){"Quantity must be greater than zero"}
        val normalizedSymbol=symbol.trim().uppercase();val normalizedSide=side.trim().uppercase()
        require(normalizedSide in setOf("BUY","SELL")){"Unsupported Multify transaction side"}
        if(!accessTokenIsCurrent())require(ensureAutomationAuthentication()){"Groww authentication is required before Multify live execution."}
        val token=accessToken();require(token.isNotBlank()){"Groww access token is unavailable."}
        if(!isExit){
            require(prefs.loadSettings().multifyLiveTradingEnabled){"Multify REAL ORDERS toggle is OFF"}
            val actualPnl=runCatching{multifyActualBrokerPnl(token)}.getOrDefault(0.0)
            require(actualPnl>-5_000.0){"Multify live-entry circuit breaker: actual broker P&L is below -₹5,000"}
            require(actualPnl<5_000.0){"Multify profit lock: actual broker P&L reached ₹5,000; new REAL entries are locked while Shadow learning continues"}
            val actualIp=currentPublicIpv4()
            require(actualIp==EXPECTED_TRADING_STATIC_IP){"Static IP mismatch. Expected $EXPECTED_TRADING_STATIC_IP but current public IPv4 is $actualIp."}
            val existing=runCatching{groww.getPositionBySymbol(token,normalizedSymbol)}.getOrNull()
            require(existing==null||existing.quantity==0){"Broker already has a position in $normalizedSymbol; reconcile before a new Multify entry"}
        }
        val uncertain=prefs.loadBrokerOrders(500).firstOrNull{
            it.symbol==normalizedSymbol&&it.product=="MIS"&&it.status in setOf("SUBMITTING","SUBMIT_UNCERTAIN")&&System.currentTimeMillis()-it.submittedAt<30L*60_000L
        }
        require(uncertain==null){"A recent Multify submission for $normalizedSymbol is unresolved. No BUY/SELL is allowed until broker reconciliation."}

        var actualQty=quantity
        if(isExit){
            if(protectionId.isNotBlank())runCatching{groww.cancelCashOco(token,protectionId)}.onFailure{DiagnosticLog.log(appContext,"MULTIFY-EXIT","OCO cancellation failed; continuing risk-reducing broker exit",it)}
            val pos=groww.getPositionBySymbol(token,normalizedSymbol)
            if(pos==null||pos.quantity==0)return MultifyLiveSubmission("BROKER_FLAT",0,0.0)
            require(pos.product=="MIS"){"Refusing Multify auto-exit because broker position is not MIS"}
            val requiredSide=if(pos.quantity>0)"SELL" else "BUY"
            require(normalizedSide==requiredSide){"Exit side mismatch with actual broker position"}
            actualQty=abs(pos.quantity)
        }
        val fresh=if(isExit)runCatching{groww.getQuote(token,normalizedSymbol,fresh=true)}.getOrNull() else strictMultifyExecutionQuote(token,normalizedSymbol,normalizedSide,actualQty,signalPrice,false)
        val executionPrice=fresh?.lastPrice?.takeIf{it>0.0}?:signalPrice
        if(!isExit){
            val margin=groww.getIntradayOrderMargin(token,normalizedSymbol,actualQty,normalizedSide,executionPrice)
            val available=groww.getMarginSnapshot(token)
            val required=maxOf(margin.totalRequirement,margin.cashMisMarginRequired)
            val spendable=maxOf(available.misBalanceAvailable,available.clearCash)
            require(required<=0.0||spendable+1.0>=required){"Multify margin gate: Groww requires ₹${"%.0f".format(required)} but only ₹${"%.0f".format(spendable)} is available for MIS"}
        }
        val referenceId=multifyReference(normalizedSymbol,normalizedSide,if(isExit)"X" else "M")
        val pending=BrokerOrderRecord(
            growwOrderId="PENDING-"+referenceId,referenceId=referenceId,symbol=normalizedSymbol,side=normalizedSide,product="MIS",
            requestedQuantity=actualQty,submittedAt=System.currentTimeMillis(),signalEntryPrice=executionPrice,status="SUBMITTING",remainingQuantity=actualQty
        )
        prefs.upsertBrokerOrder(pending)
        if(sourceEventId.isNotBlank())MultifyEventStore.markLatency(appContext,sourceEventId,orderSubmittedAt=System.currentTimeMillis())
        DiagnosticLog.log(appContext,"MULTIFY-ORDER","$reason • ref=$referenceId • $normalizedSide $normalizedSymbol • MIS • qty=$actualQty • decision=$signalPrice executionRef=$executionPrice")
        try{
            val placed=groww.placeIntradayMarketOrder(token,normalizedSymbol,actualQty,normalizedSide,referenceId)
            if(sourceEventId.isNotBlank())MultifyEventStore.markLatency(appContext,sourceEventId,brokerAcknowledgedAt=System.currentTimeMillis())
            val accepted=pending.copy(growwOrderId=placed.growwOrderId.ifBlank{"PENDING-"+referenceId},referenceId=placed.orderReferenceId.ifBlank{referenceId},status=placed.orderStatus.ifBlank{"ACCEPTED"},remark=placed.remark)
            prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+accepted)
            runCatching{reconcileBrokerOrders(accepted.growwOrderId)}
            var filled=0;var avg=0.0
            repeat(4){
                val rec=prefs.loadBrokerOrders(500).firstOrNull{it.referenceId==referenceId}
                if(rec!=null){filled=rec.filledQuantity;avg=rec.averageFillPrice}
                if(filled>0)return@repeat
                kotlinx.coroutines.delay(300L);runCatching{reconcileBrokerOrders(accepted.growwOrderId)}
            }
            val latestOrder=prefs.loadBrokerOrders(500).firstOrNull{it.referenceId==referenceId}
            if(latestOrder!=null&&latestOrder.remainingQuantity>0&&accepted.growwOrderId.isNotBlank()&&!accepted.growwOrderId.startsWith("PENDING-")){
                runCatching{groww.cancelOrder(token,accepted.growwOrderId)}.onFailure{DiagnosticLog.log(appContext,"MULTIFY-ORDER","Unable to cancel unfilled remainder for $normalizedSymbol",it)}
                runCatching{reconcileBrokerOrders(accepted.growwOrderId)}
            }
            val pos=runCatching{groww.getPositionBySymbol(token,normalizedSymbol)}.getOrNull()
            if(filled<=0&&pos!=null)filled=abs(pos.quantity).coerceAtMost(actualQty)
            if(avg<=0.0&&pos!=null)avg=pos.netPrice
            if(sourceEventId.isNotBlank()&&filled>0)MultifyEventStore.markLatency(appContext,sourceEventId,firstFillAt=System.currentTimeMillis(),fullFillAt=System.currentTimeMillis().takeIf{filled>=actualQty})
            var protectionRef="";var protectionSmartId=""
            if(!isExit&&filled>0){
                val protection=runCatching{armMultifyProtection(token,normalizedSymbol,normalizedSide,filled,avg.takeIf{it>0.0}?:executionPrice)}
                protection.onSuccess{protectionRef=it.first;protectionSmartId=it.second}.onFailure{t->
                    DiagnosticLog.log(appContext,"MULTIFY-PROTECTION","Protection arm failed for $normalizedSymbol; attempting immediate broker flatten",t)
                    val posNow=runCatching{groww.getPositionBySymbol(token,normalizedSymbol)}.getOrNull()
                    if(posNow!=null&&posNow.product=="MIS"&&posNow.quantity!=0){
                        val flattenSide=if(posNow.quantity>0)"SELL" else "BUY"
                        val flattenRef=multifyReference(normalizedSymbol,flattenSide,"F")
                        runCatching{groww.placeIntradayMarketOrder(token,normalizedSymbol,abs(posNow.quantity),flattenSide,flattenRef)}
                            .onFailure{e->DiagnosticLog.log(appContext,"MULTIFY-PROTECTION","EMERGENCY FLATTEN FAILED $normalizedSymbol • manual broker attention required",e)}
                    }
                }
                require(protectionSmartId.isNotBlank()){"Broker-side protection could not be armed; entry was flattened/flagged and is not accepted as protected LIVE"}
            }
            DiagnosticLog.log(appContext,"MULTIFY-ORDER","accepted • ref=$referenceId • growwId=${accepted.growwOrderId} • fill=$filled/$actualQty avg=$avg protection=$protectionSmartId")
            return MultifyLiveSubmission(referenceId,filled,avg,protectionRef,protectionSmartId)
        }catch(t:Throwable){
            if(t.message.orEmpty().contains("Broker-side protection",true)||t.message.orEmpty().contains("broker position",true))throw t
            val definite=t.message.orEmpty().contains(Regex("""Groww intraday order failed \(4\d\d\)"""))||t.message.orEmpty().contains("order rejected",true)
            if(definite){prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+pending.copy(status="REJECTED",reconciliationError=t.message.orEmpty()));throw t}
            val recovered=runCatching{groww.getOrderStatusByReference(token,referenceId)}.getOrNull()
            if(recovered!=null&&recovered.growwOrderId.isNotBlank()){
                prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+pending.copy(growwOrderId=recovered.growwOrderId,status=recovered.orderStatus.ifBlank{"ACCEPTED"},remark=recovered.remark,reconciliationError=""))
                DiagnosticLog.log(appContext,"MULTIFY-ORDER","recovered ambiguous submission by reference • ref=$referenceId • growwId=${recovered.growwOrderId}")
                return MultifyLiveSubmission(referenceId,0,0.0)
            }
            prefs.saveBrokerOrders(prefs.loadBrokerOrders(500).filterNot{it.referenceId==referenceId}+pending.copy(status="SUBMIT_UNCERTAIN",reconciliationError=t.message.orEmpty()))
            throw IllegalStateException("Multify order submission is uncertain. ALL directions for this symbol are blocked until broker reconciliation. Reference: $referenceId")
        }
    }

    private suspend fun multifyActualBrokerPnl(token:String):Double{
        val today=LocalDate.now(ist)
        val managed=multifyTrading.loadTrades(2500).filter{it.liveEntryReference.isNotBlank()&&multifyDate(it.openedAt)==today}.map{it.symbol}.toSet()
        if(managed.isEmpty())return 0.0
        val positions=groww.getPositions(token,"CASH").filter{it.product=="MIS"&&it.tradingSymbol in managed}
        var total=positions.sumOf{it.realisedPnl}
        for(p in positions.filter{it.quantity!=0&&it.netPrice>0.0}){
            val q=runCatching{groww.getQuote(token,p.tradingSymbol,fresh=true)}.getOrNull()?:continue
            total+=if(p.quantity>0)(q.lastPrice-p.netPrice)*p.quantity else (p.netPrice-q.lastPrice)*abs(p.quantity)
        }
        return total
    }

    suspend fun reconcileBrokerOrders(onlyGrowwOrderId:String?=null):Int{
        if(!ensureAutomationAuthentication())return 0
        val token=accessToken();if(token.isBlank())return 0
        val all=prefs.loadBrokerOrders(500).toMutableList();if(all.isEmpty())return 0
        var changed=0
        val updated=all.map{record->
            if(onlyGrowwOrderId!=null&&record.growwOrderId!=onlyGrowwOrderId)return@map record
            if(record.status in setOf("COMPLETE","COMPLETED","CANCELLED","REJECTED")&&record.lastReconciledAt>0L&&System.currentTimeMillis()-record.lastReconciledAt<60L*60_000L)return@map record
            var base=record
            try{
                if(base.growwOrderId.startsWith("PENDING-")){
                    val byRef=groww.getOrderStatusByReference(token,base.referenceId)
                    if(byRef.growwOrderId.isNotBlank())base=base.copy(growwOrderId=byRef.growwOrderId,status=byRef.orderStatus,remark=byRef.remark)
                }
                if(base.growwOrderId.startsWith("PENDING-"))return@map base.copy(lastReconciledAt=System.currentTimeMillis(),reconciliationError="Broker order id still unavailable")
                val d=groww.getOrderDetail(token,base.growwOrderId)
                val fills=runCatching{groww.getOrderTrades(token,base.growwOrderId)}.getOrDefault(base.fills)
                changed++
                DiagnosticLog.log(appContext,"BROKER-RECON","${base.symbol} • ${base.growwOrderId} • status=${d.orderStatus} • fill=${d.filledQuantity}/${d.quantity} • avg=${d.averageFillPrice}")
                fills.forEach{f->DiagnosticLog.log(appContext,"FILL","order=${base.growwOrderId} • trade=${f.growwTradeId} • qty=${f.quantity} • price=${f.price} • ${f.tradeStatus}")}
                base.copy(
                    growwOrderId=d.growwOrderId,status=d.orderStatus,remark=d.remark,requestedQuantity=if(d.quantity>0)d.quantity else base.requestedQuantity,
                    filledQuantity=d.filledQuantity,remainingQuantity=d.remainingQuantity,averageFillPrice=d.averageFillPrice,lastReconciledAt=System.currentTimeMillis(),
                    fills=fills,reconciliationError=""
                )
            }catch(t:Throwable){
                DiagnosticLog.log(appContext,"BROKER-RECON","reconcile failed • ref=${base.referenceId}",t)
                base.copy(lastReconciledAt=System.currentTimeMillis(),reconciliationError=t.message.orEmpty().take(240))
            }
        }
        prefs.saveBrokerOrders(updated)
        return changed
    }

    fun canAutoRenewGroww():Boolean{
        val c=secureStore.loadCredentials()
        return c.mode==AuthMode.TOTP && c.apiKeyOrTotpToken.isNotBlank() && c.secret.isNotBlank()
    }

    fun accessTokenIsCurrent(now:ZonedDateTime=ZonedDateTime.now(ist)):Boolean{
        if(secureStore.accessToken().isBlank())return false
        val savedAt=secureStore.accessTokenSavedAt()
        if(savedAt<=0L)return false
        val cutoffDate=if(now.toLocalTime()>=LocalTime.of(6,0))now.toLocalDate() else now.toLocalDate().minusDays(1)
        val cutoff=cutoffDate.atTime(6,0).atZone(ist).toInstant().toEpochMilli()
        return savedAt>=cutoff
    }

    suspend fun ensureAutomationAuthentication():Boolean{
        if(accessTokenIsCurrent())return true
        if(!canAutoRenewGroww())return false
        return runCatching{authenticate();true}.getOrElse{
            secureStore.clearAccessToken()
            false
        }
    }

    fun invalidateAccessToken(){secureStore.clearAccessToken()}

    fun isAuthenticationFailure(t:Throwable):Boolean{
        val m=t.message.orEmpty().lowercase()
        return "(401)" in m || "(403)" in m || "unauthorized" in m || "invalid token" in m || "access token" in m && "expired" in m
    }

    suspend fun bootstrapAfterAuthentication(progress:suspend(String)->Unit={}):String{
        progress("Automation: refreshing instrument universe")
        runCatching{refreshUniverse()}
        progress("Automation: refreshing new listings")
        runCatching{refreshNewListings()}
        progress("Automation: refreshing point-in-time NSE/BSE evidence")
        runCatching{refreshNews()}
        if(prefs.loadSettings().globalLeadEnabled){
            progress("Automation: refreshing weekly global counterpart map")
            runCatching{refreshGlobalMappings(false)}
            progress("Automation: scanning foreign-market leads")
            runCatching{scanGlobalLead(progress)}
        }
        if(prefs.loadSettings().strategyTournamentEnabled){
            progress("Automation: refreshing weekly strategy catalogue")
            runCatching{refreshStrategyCatalog(false)}
        }
        if(prefs.loadSettings().learningEnabled){
            progress("Automation: checking due learning outcomes")
            runCatching{runLearningCycle()}
        }
        val session=marketSessionInfo()
        if(session.isOpen){
            progress("Automation: starting live discovery")
            runCatching{scanAll(progress)}
            if(prefs.loadSettings().strategyTournamentEnabled) runCatching{scanTradingStrategies(progress=progress)}
            ensureTodayFreezeAudit()
            return "Automation active • live finding + learning armed"
        }
        ensureTodayFreezeAudit()
        return "Automation active • next market-session scans are armed"
    }
    suspend fun refreshUniverse():Int=instruments.refresh().size
    suspend fun refreshNews():List<NewsItem>{
        val items=news.latest();val observedAt=System.currentTimeMillis()
        items.forEach{n->
            val text=(n.title+" "+n.summary).lowercase()
            val kind=when{
                listOf("quarter","result","earnings","revenue","profit","eps","financial result").any{text.contains(it)}->EvidenceKind.FUNDAMENTAL
                listOf("analyst","rating","upgrade","downgrade","target price","revision").any{text.contains(it)}->EvidenceKind.ANALYST
                else->EvidenceKind.COMPANY_EVENT
            }
            val id=("NEWS|"+n.source+"|"+n.symbol+"|"+n.title).hashCode().toUInt().toString(16)
            prefs.appendPointInTimeEvidence(PointInTimeEvidence(
                id="PIT-"+id,symbol=n.symbol.uppercase(),kind=kind,metric=n.title.take(120),value=n.summary.take(600),source=n.source,
                sourceUrl=n.url,observedAt=observedAt,effectiveAt=observedAt,publishedAt=observedAt,revisionId=id,
                notes="Prospective capture; observed_at is never back-filled."
            ))
            if(n.symbol.isNotBlank()&&listOf("earnings","financial result","results","board meeting").any{text.contains(it)}){
                parseProspectiveEventDate(n.title+" "+n.summary,observedAt)?.let{date->
                    val z=date.atStartOfDay(ist).toInstant().toEpochMilli()
                    prefs.mergeMacroEvents(listOf(MacroEventRecord(
                        id="COMPANY-"+n.symbol.uppercase()+"-"+date,title=n.symbol.uppercase()+" earnings / results event",startAt=z,
                        endAt=date.atTime(23,59,59).atZone(ist).toInstant().toEpochMilli(),risk=EventRiskLevel.MEDIUM,source=n.source,sourceUrl=n.url,
                        symbol=n.symbol.uppercase(),observedAt=observedAt,prospective=true
                    )))
                }
            }
        }
        DiagnosticLog.log(appContext,"EVIDENCE","captured ${items.size} prospective exchange-news observations • PIT total=${prefs.loadPointInTimeEvidence(5000).size}")
        return items
    }

    private fun parseProspectiveEventDate(text:String,observedAt:Long):LocalDate?{
        val base=Instant.ofEpochMilli(observedAt).atZone(ist).toLocalDate()
        val iso=Regex("""\b(20\d{2})[-/](\d{1,2})[-/](\d{1,2})\b""").find(text)
        if(iso!=null){
            val d=runCatching{LocalDate.of(iso.groupValues[1].toInt(),iso.groupValues[2].toInt(),iso.groupValues[3].toInt())}.getOrNull()
            if(d!=null&&d>=base.minusDays(1)&&d<=base.plusDays(180))return d
        }
        val dmy=Regex("""\b(\d{1,2})[-/](\d{1,2})[-/](20\d{2})\b""").find(text)
        if(dmy!=null){
            val d=runCatching{LocalDate.of(dmy.groupValues[3].toInt(),dmy.groupValues[2].toInt(),dmy.groupValues[1].toInt())}.getOrNull()
            if(d!=null&&d>=base.minusDays(1)&&d<=base.plusDays(180))return d
        }
        return null
    }

    suspend fun refreshNewListings():List<ListedSecurity>{
        val attempt=System.currentTimeMillis();val previous=prefs.listingFeedHealth()
        return try{
            val list=nseMaster.recentListings(prefs.loadSettings().newListingDays)
            newListingsCache=list; prefs.saveNewListings(list)
            prefs.saveListingFeedHealth(FeedHealth(if(list.isEmpty())FeedHealthState.EMPTY else FeedHealthState.OK,list.size,attempt,attempt,if(list.isEmpty())"Feed healthy • no listings in selected window" else "Feed healthy"))
            list
        }catch(t:Throwable){
            prefs.saveListingFeedHealth(FeedHealth(FeedHealthState.ERROR,newListingsCache.size,attempt,previous.lastSuccessAt,"NSE listing feed error: ${t.message.orEmpty().take(140)}"))
            throw t
        }
    }


    suspend fun refreshStrategyCatalog(force:Boolean=false):Int{
        val settings=prefs.loadSettings();val now=System.currentTimeMillis();val last=prefs.lastStrategyCatalogRefreshAt()
        val due=last==0L||now-last>=settings.strategyCatalogRefreshDays.coerceIn(1,30).toLong()*24*60*60*1000
        val weekend=!NseTradingCalendar2026.isTradingDate(LocalDate.now(ist))
        if(strategyCatalog==null)strategyCatalog=strategyCatalogClient.embedded()
        if(!force&&(!due||(last>0&&!weekend)))return strategyCatalog!!.strategies.size
        val bundle=runCatching{strategyCatalogClient.remote()}.getOrElse{strategyCatalogClient.embedded()}
        strategyCatalog=bundle;prefs.setLastStrategyCatalogRefreshAt(now)
        return bundle.strategies.size
    }

    private fun activeStrategyDefinitions(settings:AppSettings):Pair<StrategyCatalogClient.Bundle,List<TradingStrategyDefinition>>{
        val bundle=strategyCatalog?:strategyCatalogClient.embedded().also{strategyCatalog=it}
        // v1.7: the research engine evaluates the whole compact catalogue. Catalogue priority and
        // hard-coded OPEN/MID/LATE families no longer decide production eligibility; the frozen
        // contextual roster does that from historical evidence.
        val active=bundle.strategies.take(24)
        DiagnosticLog.log(appContext,"STRATEGY-V2","research rules="+active.size+" • production eligibility comes only from frozen contextual roster")
        return bundle to active
    }

    private suspend fun ensureStrategyLearningV2Migrated(){
        if(prefs.strategyV2Migrated())return
        val closed=prefs.loadStrategyClosed(1500)
        val autopsyBySource=prefs.loadAutopsies(2000).associateBy{it.sourceId}
        val rows=mutableListOf<StrategyLearningEventEntity>()
        for(r in closed){
            if(r.status!=StrategyRecommendationStatus.WIN&&r.status!=StrategyRecommendationStatus.LOSS)continue
            val setup=r.setup
            val components=setup.componentStrategyIds.ifEmpty{listOf(setup.strategyId)}.distinct()
            val weight=1.0/components.size.coerceAtLeast(1)
            val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist)
            val band=strategyGovernance.sessionBand(opened.toLocalTime())
            val regime=autopsyBySource["STRATEGY|"+r.id]?.regime?:MarketRegime.MIXED
            val outcome=if(r.status==StrategyRecommendationStatus.WIN)StrategyLearningOutcome.WIN else StrategyLearningOutcome.LOSS
            val names=(strategyCatalog?:strategyCatalogClient.embedded()).strategies.associateBy{it.id}
            for(cid in components){
                val o=StrategyLearningObservation(
                    eventId="LEGACY|"+r.id+"|"+cid,strategyId=cid,strategyName=names[cid]?.name?:setup.strategyName,
                    symbol=setup.symbol,direction=setup.direction,source=StrategyLearningSource.LEGACY_LIVE,
                    sessionBand=band,regime=regime,rawScore=setup.rawScore.takeIf{it>0.0}?:setup.score,
                    calibratedProbabilityPct=setup.calibratedProbabilityPct,entryPrice=setup.entryPrice,targetPct=setup.targetPct,stopPct=setup.stopPct,
                    openedAt=r.openedAt,closedAt=r.closedAt,sessionDate=opened.toLocalDate().toString(),outcome=outcome,
                    returnPct=r.returnPct,rMultiple=if(setup.stopPct>0.0)r.returnPct/setup.stopPct else 0.0,
                    researchSignature=setup.researchSignature,componentStrategyIds=components,
                    clusterKey=opened.toLocalDate().toString()+"|"+band+"|"+regime.name+"|"+setup.direction.name,
                    sampleWeight=weight,legacy=true
                )
                rows+=StrategyLearningEventEntity.from(o)
            }
        }
        val legacyShadows=prefs.loadChallengerShadows(4000).filter{it.outcome==ChallengerShadowOutcome.WIN||it.outcome==ChallengerShadowOutcome.LOSS}
        for(r in legacyShadows){
            val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist)
            val o=StrategyLearningObservation(
                eventId="LEGACY-SHADOW|"+r.id,strategyId=r.strategyId,strategyName=r.strategyName,symbol=r.symbol,direction=r.direction,
                source=StrategyLearningSource.LEGACY_SHADOW,sessionBand=strategyGovernance.sessionBand(opened.toLocalTime()),regime=MarketRegime.MIXED,
                rawScore=r.score,calibratedProbabilityPct=0.0,entryPrice=r.entryPrice,targetPct=0.0,stopPct=0.0,
                openedAt=r.openedAt,closedAt=r.resolvedAt,sessionDate=opened.toLocalDate().toString(),
                outcome=if(r.outcome==ChallengerShadowOutcome.WIN)StrategyLearningOutcome.WIN else StrategyLearningOutcome.LOSS,
                returnPct=r.returnPct,rMultiple=0.0,researchSignature=r.researchSignature,legacy=true
            )
            rows+=StrategyLearningEventEntity.from(o)
        }
        if(rows.isNotEmpty())strategyLearning.insertEvents(rows)
        val priors=prefs.legacyStrategyPriors().map{StrategyLegacyPriorEntity(it.strategyId,it.strategyName,it.observations,it.wins,it.avgReturnPct,System.currentTimeMillis())}
        if(priors.isNotEmpty())strategyLearning.upsertPriors(priors)
        prefs.markStrategyV2Migrated()
        DiagnosticLog.log(appContext,"STRATEGY-V2","migrated legacy evidence • events="+rows.size+" • priors="+priors.size+" • legacy never creates Champion directly")
    }

    private suspend fun ensureDailyStrategyRoster(date:LocalDate,definitions:List<TradingStrategyDefinition>,settings:AppSettings):List<StrategyRosterDecision>{
        ensureStrategyLearningV2Migrated()
        val existing=strategyLearning.rosterForDate(date.toString())
        if(existing.isNotEmpty())return existing.map{it.toDomain()}
        val events=strategyLearning.recentEvents(20_000).map{it.toDomain()}
        val priors=strategyLearning.allPriors().map{it.toDomain()}
        val frozen=strategyGovernance.buildFrozenRoster(date,definitions,events,priors,settings,ist)
        strategyLearning.upsertRoster(frozen.map{StrategyRosterEntity.from(it)})
        DiagnosticLog.log(appContext,"STRATEGY-V2","frozen production roster "+date+" • contexts="+frozen.size+" • Champion="+frozen.count{it.status==StrategyStatus.CHAMPION}+" • Qualified="+frozen.count{it.status==StrategyStatus.QUALIFIED})
        return frozen
    }

    private suspend fun currentStrategyRegime():MarketRegime=withContext(Dispatchers.IO){
        runCatching{
            regimeClassifier.classify(
                globalMarket.intradaySeries("^NSEI"),
                globalMarket.intradaySeries("^BSESN"),
                globalMarket.intradaySeries("^NSEBANK"),
                globalMarket.snapshot("^INDIAVIX"),
                globalMarket.snapshot("SPY"),
                globalMarket.snapshot("^VIX")
            ).regime
        }.getOrElse{MarketRegime.MIXED}
    }

    private suspend fun recordStrategyLearningV2(r:StrategyRecommendation){
        if(r.status!=StrategyRecommendationStatus.WIN&&r.status!=StrategyRecommendationStatus.LOSS)return
        ensureStrategyLearningV2Migrated()
        val setup=r.setup
        val components=setup.componentStrategyIds.ifEmpty{listOf(setup.strategyId)}.distinct()
        val weight=1.0/components.size.coerceAtLeast(1)
        val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist)
        val band=setup.contextBand.ifBlank{strategyGovernance.sessionBand(opened.toLocalTime())}
        val regime=runCatching{MarketRegime.valueOf(setup.contextRegime)}.getOrDefault(MarketRegime.MIXED)
        val outcome=if(r.status==StrategyRecommendationStatus.WIN)StrategyLearningOutcome.WIN else StrategyLearningOutcome.LOSS
        val defs=(strategyCatalog?:strategyCatalogClient.embedded()).strategies.associateBy{it.id}
        val rows=components.map{cid->
            StrategyLearningEventEntity.from(StrategyLearningObservation(
                eventId="V2|"+r.id+"|"+cid,strategyId=cid,strategyName=defs[cid]?.name?:setup.strategyName,
                symbol=setup.symbol,direction=setup.direction,
                source=if(components.size>1)StrategyLearningSource.COMPONENT_CREDIT else StrategyLearningSource.LIVE_V2,
                sessionBand=band,regime=regime,rawScore=setup.rawScore,calibratedProbabilityPct=setup.calibratedProbabilityPct,
                entryPrice=setup.entryPrice,targetPct=setup.targetPct,stopPct=setup.stopPct,openedAt=r.openedAt,closedAt=r.closedAt,
                sessionDate=opened.toLocalDate().toString(),outcome=outcome,returnPct=r.returnPct,
                rMultiple=if(setup.stopPct>0.0)r.returnPct/setup.stopPct else 0.0,researchSignature=setup.researchSignature,
                componentStrategyIds=components,clusterKey=opened.toLocalDate().toString()+"|"+band+"|"+regime.name+"|"+setup.direction.name,
                sampleWeight=weight,legacy=false
            ))
        }
        strategyLearning.insertEvents(rows)
    }

    suspend fun strategyV2EventCount():Int{ensureStrategyLearningV2Migrated();return strategyLearning.eventCount()}

    private fun nextTradingDate(from:LocalDate):LocalDate=NseTradingCalendar2026.nextTradingDate(from)

    private fun targetSessionFor(bucket:TradeCallBucket,openedAt:Long):LocalDate{
        val z=Instant.ofEpochMilli(openedAt).atZone(ist);val d=z.toLocalDate();val t=z.toLocalTime()
        return when(bucket){
            TradeCallBucket.LIVE->d
            TradeCallBucket.THREE_PM->nextTradingDate(d)
            TradeCallBucket.NEXT_SESSION->if(NseTradingCalendar2026.isTradingDate(d)&&t<NseTradingCalendar2026.open)d else nextTradingDate(d)
        }
    }

    private data class CallPlan(val entry:Double,val stop:Double,val target:Double,val target2:Double?=null)

    private fun candidateCallPlan(c:Candidate,section:ScannerSection,bucket:TradeCallBucket):CallPlan?{
        val entry=c.price.takeIf{it.isFinite()&&it>=ExecutionQuality.MIN_PRICE}?:return null
        return if(section==ScannerSection.UC_CONTINUATION){
            val prevClose=if(c.dayChangePercent>-95.0)entry/(1.0+c.dayChangePercent/100.0) else entry
            val inferredBand=if(prevClose>0.0&&c.upperCircuit>prevClose)((c.upperCircuit/prevClose-1.0)*100.0).coerceIn(2.0,20.0) else 5.0
            val target=when(bucket){
                TradeCallBucket.LIVE->if(c.upperCircuit>entry)c.upperCircuit else entry*(1.0+inferredBand/100.0)
                TradeCallBucket.NEXT_SESSION,TradeCallBucket.THREE_PM->entry*(1.0+inferredBand/100.0)
            }
            CallPlan(entry,entry*0.985,target)
        }else{
            val targetPct=(c.targetMovePct?:2.5).coerceIn(0.5,8.0)
            CallPlan(entry,entry*0.985,entry*(1.0+targetPct/100.0))
        }
    }

    private fun globalCallPlan(c:GlobalLeadCandidate):CallPlan?{
        val base=c.indianPrice.takeIf{it.isFinite()&&it>=ExecutionQuality.MIN_PRICE}?:return null
        val short=c.direction==GlobalLeadDirection.SHORT
        val entry=if(short)base*0.999 else base*1.001
        val openDistancePct=if(c.indianOpen>0.0)abs(c.indianOpen-entry)/entry*100.0 else 0.0
        val riskPct=(openDistancePct*0.25).takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,1.00)?:0.50
        val targetPct=c.expectedTargetPct.takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,3.00)?:0.50
        val target2Pct=(targetPct*1.75).coerceIn(targetPct+0.20,4.00)
        val stop=if(short)entry*(1.0+riskPct/100.0) else entry*(1.0-riskPct/100.0)
        val t1=if(short)entry*(1.0-targetPct/100.0) else entry*(1.0+targetPct/100.0)
        val t2=if(short)entry*(1.0-target2Pct/100.0) else entry*(1.0+target2Pct/100.0)
        return CallPlan(entry,stop,t1,t2)
    }

    private fun recordCandidateCalls(section:ScannerSection,bucket:TradeCallBucket,candidates:List<Candidate>,source:String,nowMs:Long=System.currentTimeMillis()):Int{
        if(candidates.isEmpty())return 0
        val openedAt=Instant.ofEpochMilli(nowMs).atZone(ist)
        if(bucket==TradeCallBucket.LIVE&&!marketSessionInfo(openedAt).isOpen){
            DiagnosticLog.log(appContext,"CALL-LEDGER","suppressed ${section.name} LIVE publication outside NSE 09:15–15:30 IST")
            return 0
        }
        val engine=if(section==ScannerSection.UC_CONTINUATION)TradeCallEngine.UPPER_CIRCUIT else TradeCallEngine.PRESSURE
        val ledger=prefs.loadTradeCalls(1500).toMutableList();var added=0
        for(c in candidates){
            val plan=candidateCallPlan(c,section,bucket)?:continue
            val latest=ledger.filter{it.engine==engine&&it.bucket==bucket&&it.symbol==c.symbol&&it.outcome==TradeCallOutcome.OPEN}.maxByOrNull{it.openedAt}
            val changePct=latest?.let{if(it.entryPrice>0.0)abs(plan.entry/it.entryPrice-1.0)*100.0 else 100.0}?:100.0
            val sameThreePmDay=latest!=null&&bucket==TradeCallBucket.THREE_PM&&Instant.ofEpochMilli(latest.openedAt).atZone(ist).toLocalDate()==Instant.ofEpochMilli(nowMs).atZone(ist).toLocalDate()
            if(sameThreePmDay || (latest!=null&&changePct<0.20))continue
            val id="${engine.name}|${bucket.name}|${c.symbol}|$nowMs"
            val headroom=if(c.upperCircuit>c.price&&c.upperCircuit>0.0)(c.upperCircuit/c.price-1.0)*100.0 else 0.0
            val detail=c.companyName+" • score "+"%.0f".format(c.score)+" • "+"%.2f".format(c.dayChangePercent)+"% • vol "+"%.1f".format(c.volumeRatio)+"x • UC headroom "+"%.2f".format(headroom)+"%"
            ledger+=TradeCallRecord(id,engine,bucket,c.symbol,c.companyName,TradeDirection.LONG,c.score,plan.entry,plan.stop,plan.target,plan.target2,nowMs,targetSessionFor(bucket,nowMs).toString(),source,detail)
            added++
        }
        if(added>0){prefs.saveTradeCalls(ledger);DiagnosticLog.log(appContext,"CALL-LEDGER","opened $added ${engine.name} $bucket calls")}
        return added
    }

    private fun recordGlobalCalls(candidates:List<GlobalLeadCandidate>,indiaMarketOpen:Boolean,nowMs:Long=System.currentTimeMillis()):Int{
        val ledger=prefs.loadTradeCalls(1500).toMutableList();var added=0
        for(c in candidates){
            // Global state machine is strictly: NEXT/RESEARCH -> LIVE -> DONE.
            // NEXT is research only, so it never creates a TradeCallRecord.
            val bucket=when{
                indiaMarketOpen&&(c.action==GlobalLeadAction.ENTER_AFTER_OPEN||c.action==GlobalLeadAction.KEEP_NEXT_SESSION)->TradeCallBucket.LIVE
                else->continue
            }
            val plan=globalCallPlan(c)?:continue
            val direction=if(c.direction==GlobalLeadDirection.SHORT)TradeDirection.SHORT else TradeDirection.LONG
            val sessionDate=Instant.ofEpochMilli(nowMs).atZone(ist).toLocalDate()
            val alreadyPublishedThisSession=ledger.any{
                it.engine==TradeCallEngine.GLOBAL&&it.bucket==bucket&&it.symbol==c.indianSymbol&&it.direction==direction&&
                    it.outcome!=TradeCallOutcome.INVALID&&Instant.ofEpochMilli(it.openedAt).atZone(ist).toLocalDate()==sessionDate
            }
            if(alreadyPublishedThisSession)continue
            val id="GLOBAL|${bucket.name}|${direction.name}|${c.indianSymbol}|$nowMs"
            val detail="Lead ${c.foreignTicker} • ${c.exchange} • foreign ${"%.2f".format(c.foreignDayPct)}%"
            ledger+=TradeCallRecord(id,TradeCallEngine.GLOBAL,bucket,c.indianSymbol,c.indianCompany,direction,c.score,plan.entry,plan.stop,plan.target,plan.target2,nowMs,targetSessionFor(bucket,nowMs).toString(),"Global ${bucket.name}",detail,globalLearningKey(c))
            added++
        }
        if(added>0){prefs.saveTradeCalls(ledger);DiagnosticLog.log(appContext,"CALL-LEDGER","opened $added GLOBAL calls")}
        return added
    }

    private fun recordRejectedCandidateShadow(section:ScannerSection,bucket:TradeCallBucket,c:Candidate,reason:String,nowMs:Long=System.currentTimeMillis()){
        val plan=candidateCallPlan(c,section,bucket)?:return
        val engine=if(section==ScannerSection.UC_CONTINUATION)TradeCallEngine.UPPER_CIRCUIT else TradeCallEngine.PRESSURE
        prefs.appendRejectedShadow(RejectedCandidateRecord(
            id="REJECT|${engine.name}|${bucket.name}|${c.symbol}|$nowMs",engineLabel=engine.name,symbol=c.symbol,direction=TradeDirection.LONG,
            score=c.score,entryPrice=plan.entry,stopPrice=plan.stop,targetPrice=plan.target,capturedAt=nowMs,
            targetSessionDate=targetSessionFor(bucket,nowMs).toString(),reason=reason
        ))
    }

    private fun recordRejectedStrategyShadow(s:StrategySetup,reason:String,nowMs:Long=System.currentTimeMillis()){
        val plan=run{
            val entry=s.entryPrice.takeIf{it.isFinite()&&it>=ExecutionQuality.MIN_PRICE}?:return
            val short=s.direction==TradeDirection.SHORT
            val stop=if(short)entry*(1+s.stopPct.coerceAtLeast(0.1)/100.0) else entry*(1-s.stopPct.coerceAtLeast(0.1)/100.0)
            val target=if(short)entry*(1-s.targetPct.coerceAtLeast(0.1)/100.0) else entry*(1+s.targetPct.coerceAtLeast(0.1)/100.0)
            CallPlan(entry,stop,target)
        }
        prefs.appendRejectedShadow(RejectedCandidateRecord(
            id="REJECT|STRATEGY|${s.direction.name}|${s.symbol}|$nowMs",engineLabel="STRATEGY",symbol=s.symbol,direction=s.direction,
            score=s.score,entryPrice=plan.entry,stopPrice=plan.stop,targetPrice=plan.target,capturedAt=nowMs,
            targetSessionDate=targetSessionFor(TradeCallBucket.LIVE,nowMs).toString(),
            reason=reason+" • STRAT="+s.strategyId+(if(s.researchSignature.isBlank())"" else " • SIG="+s.researchSignature.take(140))
        ))
        prefs.appendDecisionSnapshot(s,"WAIT",reason,NseTradingCalendar2026.VERSION,HandbookSynergyEngine.VERSION,at=nowMs)
        DiagnosticLog.log(appContext,"DECISION","WAIT • ${s.symbol} • ${s.strategyId} • $reason")
    }

    private fun recordRejectedGlobalShadow(c:GlobalLeadCandidate,indiaMarketOpen:Boolean,reason:String,nowMs:Long=System.currentTimeMillis()){
        val plan=globalCallPlan(c)?:return
        val bucket=if(indiaMarketOpen)TradeCallBucket.LIVE else TradeCallBucket.NEXT_SESSION
        val direction=if(c.direction==GlobalLeadDirection.SHORT)TradeDirection.SHORT else TradeDirection.LONG
        prefs.appendRejectedShadow(RejectedCandidateRecord(
            id="REJECT|GLOBAL|${direction.name}|${c.indianSymbol}|$nowMs",engineLabel=TradeCallEngine.GLOBAL.name,symbol=c.indianSymbol,direction=direction,
            score=c.score,entryPrice=plan.entry,stopPrice=plan.stop,targetPrice=plan.target,capturedAt=nowMs,
            targetSessionDate=targetSessionFor(bucket,nowMs).toString(),reason=reason
        ))
    }

    private fun shadowReturnPct(r:RejectedCandidateRecord,exit:Double):Double{
        if(r.entryPrice<=0.0||exit<=0.0)return 0.0
        return if(r.direction==TradeDirection.LONG)(exit/r.entryPrice-1.0)*100.0 else (r.entryPrice/exit-1.0)*100.0
    }

    private fun shadowOutcome(r:RejectedCandidateRecord,candles:List<Candle>):Pair<RejectedShadowOutcome,Double>?{
        for(c in candles.sortedBy{it.epochSeconds}){
            val targetHit=if(r.direction==TradeDirection.LONG)c.high>=r.targetPrice else c.low<=r.targetPrice
            val stopHit=if(r.direction==TradeDirection.LONG)c.low<=r.stopPrice else c.high>=r.stopPrice
            if(targetHit&&stopHit)return RejectedShadowOutcome.WOULD_LOSE to r.stopPrice
            if(stopHit)return RejectedShadowOutcome.WOULD_LOSE to r.stopPrice
            if(targetHit)return RejectedShadowOutcome.WOULD_WIN to r.targetPrice
        }
        return null
    }

    suspend fun reconcileRejectedShadows():Int{
        val all=prefs.loadRejectedShadows(2500).toMutableList();if(all.none{it.outcome==RejectedShadowOutcome.PENDING})return 0
        if(!ensureAutomationAuthentication())return 0
        val token=accessToken();val now=ZonedDateTime.now(ist);val today=now.toLocalDate();var changed=0
        val candleCache=mutableMapOf<String,List<Candle>?>()
        val updated=all.map{r->
            if(r.outcome!=RejectedShadowOutcome.PENDING)return@map r
            val targetDate=runCatching{LocalDate.parse(r.targetSessionDate)}.getOrNull()?:return@map r
            if(today<targetDate||today==targetDate&&now.toLocalTime()<LocalTime.of(9,15))return@map r
            val captured=Instant.ofEpochMilli(r.capturedAt).atZone(ist)
            val startZ=if(captured.toLocalDate()==targetDate&&captured.toLocalTime()>=LocalTime.of(9,15)&&captured.toLocalTime()<LocalTime.of(15,30))captured else targetDate.atTime(9,15).atZone(ist)
            val sessionEnd=targetDate.atTime(15,31).atZone(ist);val endZ=if(today==targetDate&&now.isBefore(sessionEnd))now.plusMinutes(1) else sessionEnd
            val key="${r.symbol}|$targetDate|${startZ.toLocalTime()}|${endZ.toLocalTime()}"
            val candles=if(candleCache.containsKey(key))candleCache[key] else runCatching{groww.getHistoricalCandles(token,r.symbol,startZ.format(dateTimeFmt),endZ.format(dateTimeFmt),"5minute")}.getOrNull().also{candleCache[key]=it}
            var result=candles?.let{shadowOutcome(r,it)}
            val expired=today>targetDate||(today==targetDate&&now.toLocalTime()>=LocalTime.of(15,30))
            if(result==null&&expired&&candles!=null){val exit=candles.lastOrNull()?.close?:r.entryPrice;result=RejectedShadowOutcome.WOULD_LOSE to exit}
            if(result==null)return@map r
            val(out,exit)=result!!;changed++;r.copy(outcome=out,closedAt=System.currentTimeMillis(),exitPrice=exit,returnPct=shadowReturnPct(r,exit))
        }
        if(changed>0){prefs.saveRejectedShadows(updated);DiagnosticLog.log(appContext,"MISSED-OPPORTUNITY","resolved $changed rejected-candidate shadows")}
        return changed
    }

    private fun callReturnPct(call:TradeCallRecord,exit:Double):Double{
        if(call.entryPrice<=0.0||exit<=0.0||!exit.isFinite())return 0.0
        val raw=if(call.direction==TradeDirection.LONG)(exit/call.entryPrice-1.0)*100.0 else (call.entryPrice/exit-1.0)*100.0
        return raw.takeIf{it.isFinite()}?:0.0
    }

    private fun candleOutcome(call:TradeCallRecord,candles:List<Candle>,targetOverride:Double?=null):Pair<TradeCallOutcome,Double>?{
        val target=(targetOverride?:call.targetPrice).takeIf{it.isFinite()&&it>0.0}?:call.targetPrice
        for(c in candles.sortedBy{it.epochSeconds}){
            val targetHit=if(call.direction==TradeDirection.LONG)c.high>=target else c.low<=target
            val stopHit=if(call.direction==TradeDirection.LONG)c.low<=call.stopPrice else c.high>=call.stopPrice
            if(targetHit&&stopHit)return TradeCallOutcome.LOSS to call.stopPrice
            if(stopHit)return TradeCallOutcome.LOSS to call.stopPrice
            if(targetHit)return TradeCallOutcome.WIN to target
        }
        return null
    }

    suspend fun reconcileTradeCallLedger():Int{
        val all=prefs.loadTradeCalls(1500).toMutableList()
        val open=all.filter{it.outcome==TradeCallOutcome.OPEN}
        if(open.isEmpty())return 0
        if(!ensureAutomationAuthentication())return 0
        val token=accessToken()
        val now=ZonedDateTime.now(ist)
        val today=now.toLocalDate()
        var changed=0
        val quoteCache=mutableMapOf<String,Quote?>()
        val candleCache=mutableMapOf<String,List<Candle>?>()
        val globalLearning=mutableListOf<Triple<String,Double,Boolean>>()

        val updated=all.map{call->
            if(call.outcome!=TradeCallOutcome.OPEN)return@map call
            if(call.engine==TradeCallEngine.GLOBAL&&call.bucket!=TradeCallBucket.LIVE)return@map call
            val targetDate=runCatching{LocalDate.parse(call.targetSessionDate)}.getOrNull()?:return@map call
            if(today<targetDate)return@map call
            if(today==targetDate&&now.toLocalTime()<LocalTime.of(9,15))return@map call

            val sessionEnd=targetDate.atTime(15,31).atZone(ist)
            val opened=Instant.ofEpochMilli(call.openedAt).atZone(ist)
            val startZ=if(call.bucket==TradeCallBucket.LIVE&&opened.toLocalDate()==targetDate)opened else targetDate.atTime(9,15).atZone(ist)
            val endZ=if(today==targetDate&&now.isBefore(sessionEnd))now.plusMinutes(1) else sessionEnd
            val cacheKey="${call.symbol}|$targetDate|${startZ.toLocalTime()}|${endZ.toLocalTime()}"
            val candles=if(candleCache.containsKey(cacheKey))candleCache[cacheKey] else runCatching{
                groww.getHistoricalCandles(token,call.symbol,startZ.format(dateTimeFmt),endZ.format(dateTimeFmt),"5minute")
            }.getOrNull().also{candleCache[cacheKey]=it}

            val q=quoteCache.getOrPut(call.symbol){runCatching{groww.getQuote(token,call.symbol)}.getOrNull()}
            val targetForCall=if(call.engine==TradeCallEngine.UPPER_CIRCUIT&&call.bucket==TradeCallBucket.THREE_PM&&today==targetDate&&
                q?.upperCircuit?.let{it.isFinite()&&it>0.0}==true)q!!.upperCircuit else call.targetPrice
            var outcome=candles?.let{candleOutcome(call,it,targetForCall)}
            if(outcome==null&&today==targetDate&&q!=null&&q.lastPrice.isFinite()&&q.lastPrice>0.0){
                val targetHit=if(call.direction==TradeDirection.LONG)q.lastPrice>=targetForCall else q.lastPrice<=targetForCall
                val stopHit=if(call.direction==TradeDirection.LONG)q.lastPrice<=call.stopPrice else q.lastPrice>=call.stopPrice
                outcome=when{
                    stopHit->TradeCallOutcome.LOSS to call.stopPrice
                    targetHit->TradeCallOutcome.WIN to targetForCall
                    else->null
                }
            }

            val expired=today>targetDate||(today==targetDate&&now.toLocalTime()>=LocalTime.of(15,30))
            var forcedExpiry=false
            var invalidExpiry=false
            if(outcome==null&&expired){
                val fallback=candles?.lastOrNull()?.close?.takeIf{it.isFinite()&&it>0.0}
                    ?:q?.lastPrice?.takeIf{it.isFinite()&&it>0.0}
                    ?:call.entryPrice.takeIf{it.isFinite()&&it>0.0}
                    ?:0.0
                invalidExpiry=candles.isNullOrEmpty()
                outcome=(if(invalidExpiry)TradeCallOutcome.INVALID else TradeCallOutcome.LOSS) to fallback
                forcedExpiry=true
            }
            if(outcome==null)return@map call

            val(out,exitRaw)=outcome!!
            val exit=exitRaw.takeIf{it.isFinite()&&it>0.0}?:call.entryPrice
            val ret=callReturnPct(call,exit)
            changed++
            if(call.engine==TradeCallEngine.GLOBAL&&call.bucket==TradeCallBucket.LIVE&&call.learningKey.isNotBlank()&&
                (out==TradeCallOutcome.WIN||out==TradeCallOutcome.LOSS)){
                globalLearning+=Triple(call.learningKey,ret,out==TradeCallOutcome.WIN)
            }
            call.copy(
                outcome=out,
                closedAt=System.currentTimeMillis(),
                exitPrice=exit,
                returnPct=ret,
                closeReason=when(out){
                    TradeCallOutcome.WIN->if(call.engine==TradeCallEngine.UPPER_CIRCUIT&&call.bucket==TradeCallBucket.THREE_PM)"Next-session upper circuit reached" else "Target reached"
                    TradeCallOutcome.LOSS->when{
                        forcedExpiry->"Target not reached by prediction horizon"
                        else->"Stop reached"
                    }
                    TradeCallOutcome.INVALID->when{
                        invalidExpiry->"Invalid outcome • historical replay unavailable at expiry"
                        else->"Invalid outcome • excluded from learning"
                    }
                    else->""
                }
            )
        }

        if(changed>0){
            prefs.saveTradeCalls(updated)
            DiagnosticLog.log(appContext,"CALL-LEDGER","closed $changed calls with WIN/LOSS/INVALID")
        }
        globalLearning.forEach{(key,ret,win)->
            runCatching{prefs.updateGlobalLearning(key,ret,win)}
                .onFailure{DiagnosticLog.log(appContext,"CALL-LEDGER","Global learning update failed after durable close",it)}
        }
        return changed
    }

    suspend fun closeExpiredStrategyCalls():Int{
        val now=ZonedDateTime.now(ist)
        if(now.toLocalTime()<LocalTime.of(15,30)&&marketSessionInfo(now).isOpen)return 0
        val live=prefs.loadStrategyLive()
        if(live.isEmpty())return 0
        if(!ensureAutomationAuthentication())return 0
        val token=accessToken()
        val closed=prefs.loadStrategyClosed(500).toMutableList()
        val surviving=mutableListOf<StrategyRecommendation>()
        val learningUpdates=mutableListOf<Triple<StrategySetup,Double,Boolean>>()
        var changed=0

        for(r in live){
            val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist)
            val sessionDate=opened.toLocalDate()
            if(now.toLocalDate()<sessionDate||(now.toLocalDate()==sessionDate&&now.toLocalTime()<LocalTime.of(15,30))){
                surviving+=r
                continue
            }

            val end=sessionDate.atTime(15,31).atZone(ist)
            val candles=runCatching{
                groww.getHistoricalCandles(token,r.setup.symbol,opened.format(dateTimeFmt),end.format(dateTimeFmt),"1minute")
            }.getOrNull()
            val q=runCatching{groww.getQuote(token,r.setup.symbol)}.getOrNull()
            val target=if(r.setup.direction==TradeDirection.LONG)r.setup.entryPrice*(1+r.setup.targetPct/100.0) else r.setup.entryPrice*(1-r.setup.targetPct/100.0)
            val stop=if(r.setup.direction==TradeDirection.LONG)r.setup.entryPrice*(1-r.setup.stopPct/100.0) else r.setup.entryPrice*(1+r.setup.stopPct/100.0)

            var win=false
            var stopHit=false
            var ambiguous=false
            val replayAvailable=!candles.isNullOrEmpty()
            var exit=candles?.lastOrNull()?.close?.takeIf{it.isFinite()&&it>0.0}
                ?:q?.lastPrice?.takeIf{it.isFinite()&&it>0.0}
                ?:r.lastPrice.takeIf{it.isFinite()&&it>0.0}
                ?:r.setup.entryPrice

            candles?.sortedBy{it.epochSeconds}?.forEach{c->
                if(win||stopHit||ambiguous)return@forEach
                if(!c.high.isFinite()||!c.low.isFinite())return@forEach
                val th=if(r.setup.direction==TradeDirection.LONG)c.high>=target else c.low<=target
                val sh=if(r.setup.direction==TradeDirection.LONG)c.low<=stop else c.high>=stop
                when{
                    th&&sh->{ambiguous=true;exit=c.close.takeIf{it.isFinite()&&it>0.0}?:r.setup.entryPrice}
                    sh->{stopHit=true;exit=stop}
                    th->{win=true;exit=target}
                }
            }

            val rawRet=if(r.setup.entryPrice<=0.0||exit<=0.0)0.0 else if(r.setup.direction==TradeDirection.LONG)(exit/r.setup.entryPrice-1.0)*100.0 else (r.setup.entryPrice/exit-1.0)*100.0
            val ret=rawRet.takeIf{it.isFinite()}?:0.0
            val comparable=replayAvailable&&!ambiguous
            val status=when{
                !replayAvailable->StrategyRecommendationStatus.INVALIDATED
                ambiguous->StrategyRecommendationStatus.INVALIDATED
                win->StrategyRecommendationStatus.WIN
                else->StrategyRecommendationStatus.LOSS
            }
            val done=r.copy(
                lastSeenAt=System.currentTimeMillis(),
                lastPrice=exit,
                closedAt=System.currentTimeMillis(),
                exitPrice=exit,
                status=status,
                returnPct=ret,
                closeReason=when{
                    !replayAvailable->"INVALID • historical 1-minute replay unavailable; excluded from learning"
                    ambiguous->"INVALID • target and stop touched inside same 1-minute bar; excluded from learning"
                    win->"Target reached"
                    stopHit->"Stop reached"
                    else->"Target not reached by session close"
                }
            )
            closed.removeAll{it.id==done.id}
            closed.add(done)
            if(comparable){
                learningUpdates+=Triple(r.setup,ret,win)
                recordStrategyLearningV2(done)
            }
            changed++
        }

        prefs.saveStrategyLedger(surviving,closed)
        learningUpdates.forEach{(setup,ret,win)->
            runCatching{prefs.updateStrategyResult(setup.strategyId,setup.strategyName,ret,win)}
                .onFailure{DiagnosticLog.log(appContext,"STRATEGY","Learning update failed after durable close for ${setup.symbol}",it)}
        }
        if(changed>0)DiagnosticLog.log(appContext,"STRATEGY","closed $changed expired intraday calls with WIN/LOSS")
        return changed
    }

    private suspend fun openChallengerShadow(setup:StrategySetup,nowMs:Long=System.currentTimeMillis()):Boolean{
        val opened=Instant.ofEpochMilli(nowMs).atZone(ist)
        val sessionDate=opened.toLocalDate()
        val resolveAt=sessionDate.atTime(15,31).atZone(ist).toInstant().toEpochMilli()
        val components=setup.componentStrategyIds.ifEmpty{listOf(setup.strategyId)}.distinct()
        val x=ChallengerShadowRecord(
            id="CHAL-V2|${setup.strategyId}|${setup.direction.name}|${setup.symbol}|$nowMs",
            strategyId=setup.strategyId,strategyName=setup.strategyName,symbol=setup.symbol,direction=setup.direction,
            score=setup.calibratedProbabilityPct.takeIf{it>0.0}?:setup.score,entryPrice=setup.entryPrice,openedAt=nowMs,resolveAt=resolveAt,
            scheduledSessionDate=sessionDate.toString(),researchSignature=setup.researchSignature,evidence=setup.evidence,
            targetPct=setup.targetPct,stopPct=setup.stopPct,sessionBand=setup.contextBand.ifBlank{strategyGovernance.sessionBand(opened.toLocalTime())},
            regime=setup.contextRegime.ifBlank{MarketRegime.MIXED.name},componentStrategyIds=components,modelVersion=StrategyGovernanceV2.MODEL_VERSION
        )
        val added=prefs.appendChallengerShadow(x)
        if(added){
            val defs=(strategyCatalog?:strategyCatalogClient.embedded()).strategies.associateBy{it.id}
            val weight=1.0/components.size.coerceAtLeast(1)
            val rows=components.map{cid->
                StrategyLearningEventEntity.from(StrategyLearningObservation(
                    eventId="SHV2|${x.id}|$cid",strategyId=cid,strategyName=defs[cid]?.name?:setup.strategyName,
                    symbol=setup.symbol,direction=setup.direction,source=StrategyLearningSource.SHADOW_V2,
                    sessionBand=x.sessionBand,regime=runCatching{MarketRegime.valueOf(x.regime)}.getOrDefault(MarketRegime.MIXED),
                    rawScore=setup.rawScore,calibratedProbabilityPct=setup.calibratedProbabilityPct,entryPrice=setup.entryPrice,
                    targetPct=setup.targetPct,stopPct=setup.stopPct,openedAt=nowMs,closedAt=0L,sessionDate=sessionDate.toString(),
                    outcome=StrategyLearningOutcome.PENDING,returnPct=0.0,rMultiple=0.0,researchSignature=setup.researchSignature,
                    componentStrategyIds=components,clusterKey=sessionDate.toString()+"|"+x.sessionBand+"|"+x.regime+"|"+setup.direction.name,
                    sampleWeight=weight,legacy=false
                ))
            }
            strategyLearning.insertEvents(rows)
            prefs.appendDecisionSnapshot(setup,"CHALLENGER_SHADOW","Non-executable target/stop/EOD shadow; same objective as LIVE",NseTradingCalendar2026.VERSION,HandbookSynergyEngine.VERSION,at=nowMs)
            DiagnosticLog.log(appContext,"CHALLENGER","opened v2 comparable shadow • ${setup.symbol} • ${setup.strategyId} • resolve EOD=${Instant.ofEpochMilli(resolveAt).atZone(ist)}")
        }
        return added
    }

    suspend fun resolveChallengerShadows():Int{
        val all=prefs.loadChallengerShadows(4000).toMutableList();val nowMs=System.currentTimeMillis()
        val due=all.filter{it.outcome==ChallengerShadowOutcome.PENDING&&nowMs>=it.resolveAt}
        if(due.isEmpty())return 0
        if(!ensureAutomationAuthentication())return 0
        val token=accessToken();if(token.isBlank())return 0
        var changed=0
        val updated=all.map{r->
            if(r !in due)return@map r
            // Old v1.6 shadows are retained as legacy evidence only; resolve them with their old horizon
            // semantics so migration is backwards compatible, but they can never create a v1.7 Champion.
            if(r.modelVersion!=StrategyGovernanceV2.MODEL_VERSION||r.targetPct<=0.0||r.stopPct<=0.0){
                val target=Instant.ofEpochMilli(r.resolveAt).atZone(ist)
                val from=target.minusMinutes(5).format(dateTimeFmt);val to=target.plusMinutes(15).format(dateTimeFmt)
                val candles=runCatching{groww.getHistoricalCandles(token,r.symbol,from,to,"5minute")}.getOrNull()
                val candle=candles?.sortedBy{it.epochSeconds}?.firstOrNull{it.epochSeconds*1000L>=r.resolveAt}
                if(candle==null){
                    if(nowMs-r.resolveAt>24L*60*60_000L){
                        changed++;r.copy(outcome=ChallengerShadowOutcome.UNRESOLVED_DATA,resolvedAt=nowMs,note="Legacy shadow unresolved; excluded from v1.7 Champion evidence.")
                    }else r
                }else{
                    val px=candle.close
                    val ret=if(r.entryPrice<=0.0)0.0 else if(r.direction==TradeDirection.LONG)(px/r.entryPrice-1.0)*100.0 else (r.entryPrice/px-1.0)*100.0
                    val out=if(ret>0.0)ChallengerShadowOutcome.WIN else ChallengerShadowOutcome.LOSS
                    changed++;r.copy(outcome=out,resolvedAt=nowMs,horizonPrice=px,returnPct=ret,note="Legacy 30-minute shadow; weak prior only.")
                }
            }else{
                val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist)
                val end=Instant.ofEpochMilli(r.resolveAt).atZone(ist)
                val candles=runCatching{groww.getHistoricalCandles(token,r.symbol,opened.format(dateTimeFmt),end.format(dateTimeFmt),"1minute")}.getOrNull()
                if(candles.isNullOrEmpty()){
                    if(nowMs-r.resolveAt>24L*60*60_000L){
                        val invalid=r.copy(outcome=ChallengerShadowOutcome.UNRESOLVED_DATA,resolvedAt=nowMs,note="No historical 1-minute replay; excluded from learning.")
                        for(cid in r.componentStrategyIds.ifEmpty{listOf(r.strategyId)}){
                            val old=strategyLearning.recentEvents(20_000).firstOrNull{it.eventId=="SHV2|${r.id}|$cid"}?.toDomain()?:continue
                            strategyLearning.upsertEvent(StrategyLearningEventEntity.from(old.copy(closedAt=nowMs,outcome=StrategyLearningOutcome.INVALID)))
                        }
                        changed++;invalid
                    }else r
                }else{
                    val targetPx=if(r.direction==TradeDirection.LONG)r.entryPrice*(1+r.targetPct/100.0) else r.entryPrice*(1-r.targetPct/100.0)
                    val stopPx=if(r.direction==TradeDirection.LONG)r.entryPrice*(1-r.stopPct/100.0) else r.entryPrice*(1+r.stopPct/100.0)
                    var out:ChallengerShadowOutcome?=null
                    var exit=candles.last().close
                    for(c in candles.sortedBy{it.epochSeconds}){
                        val th=if(r.direction==TradeDirection.LONG)c.high>=targetPx else c.low<=targetPx
                        val sh=if(r.direction==TradeDirection.LONG)c.low<=stopPx else c.high>=stopPx
                        when{
                            th&&sh->{out=ChallengerShadowOutcome.AMBIGUOUS;exit=c.close;break}
                            sh->{out=ChallengerShadowOutcome.LOSS;exit=stopPx;break}
                            th->{out=ChallengerShadowOutcome.WIN;exit=targetPx;break}
                        }
                    }
                    if(out==null)out=ChallengerShadowOutcome.LOSS // same LIVE objective: target not reached by EOD is LOSS
                    val ret=if(r.entryPrice<=0.0||exit<=0.0)0.0 else if(r.direction==TradeDirection.LONG)(exit/r.entryPrice-1.0)*100.0 else (r.entryPrice/exit-1.0)*100.0
                    val learningOutcome=when(out){
                        ChallengerShadowOutcome.WIN->StrategyLearningOutcome.WIN
                        ChallengerShadowOutcome.LOSS->StrategyLearningOutcome.LOSS
                        ChallengerShadowOutcome.AMBIGUOUS->StrategyLearningOutcome.AMBIGUOUS
                        else->StrategyLearningOutcome.INVALID
                    }
                    val comparable=learningOutcome==StrategyLearningOutcome.WIN||learningOutcome==StrategyLearningOutcome.LOSS
                    val recentMap=strategyLearning.recentEvents(20_000).associateBy{it.eventId}
                    for(cid in r.componentStrategyIds.ifEmpty{listOf(r.strategyId)}){
                        val eid="SHV2|${r.id}|$cid";val old=recentMap[eid]?.toDomain()?:continue
                        val rm=if(r.stopPct>0.0)ret/r.stopPct else 0.0
                        strategyLearning.upsertEvent(StrategyLearningEventEntity.from(old.copy(closedAt=nowMs,outcome=learningOutcome,returnPct=ret,rMultiple=rm)))
                    }
                    changed++
                    DiagnosticLog.log(appContext,"CHALLENGER-RESOLVE","$out • ${r.symbol} • target/stop/EOD objective • return=${"%+.2f".format(ret)}% • comparable=$comparable")
                    r.copy(outcome=out,resolvedAt=nowMs,horizonPrice=exit,returnPct=ret,
                        note=if(comparable)"Resolved with same 1-minute target/stop/EOD objective as LIVE." else "Ambiguous 1-minute bar; excluded from learning.")
                }
            }
        }
        if(changed>0)prefs.saveChallengerShadows(updated)
        return changed
    }


    suspend fun scanTradingStrategies(challengerOnly:Boolean=false,progress:suspend(String)->Unit={}):StrategyTournamentSummary=strategyMutex.withLock{
        prefs.markStrategyAttempt()
        val settings=prefs.loadSettings();require(settings.strategyTournamentEnabled){"Strategy tournament is disabled"}
        require(ensureAutomationAuthentication()){"Groww authentication is required. TOTP mode can renew automatically."}
        if(strategyCatalog==null)runCatching{refreshStrategyCatalog(false)}
        val(bundle,active)=activeStrategyDefinitions(settings)
        ensureStrategyLearningV2Migrated()
        val token=accessToken();val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val now=ZonedDateTime.now(ist);val date=now.toLocalDate()
        val sessionBand=strategyGovernance.sessionBand(now.toLocalTime())
        val currentRegime=currentStrategyRegime()
        val roster=ensureDailyStrategyRoster(date,bundle.strategies,settings)
        fun rosterDecision(id:String,direction:TradeDirection):StrategyRosterDecision?=
            roster.firstOrNull{it.strategyId==id&&it.direction==direction&&it.sessionBand==sessionBand&&it.regime==currentRegime}
                ?:roster.firstOrNull{it.strategyId==id&&it.direction==direction&&it.sessionBand==sessionBand&&it.regime==MarketRegime.MIXED}
        fun rankStatus(status:StrategyStatus)=when(status){
            StrategyStatus.CHAMPION->0
            StrategyStatus.QUALIFIED->1
            StrategyStatus.ACTIVE->2
            StrategyStatus.CHALLENGER->3
            StrategyStatus.PROBATION->4
            StrategyStatus.SUSPENDED->5
        }
        fun bestDecision(setup:StrategySetup):StrategyRosterDecision?=
            setup.componentStrategyIds.ifEmpty{listOf(setup.strategyId)}.mapNotNull{rosterDecision(it,setup.direction)}
                .minWithOrNull(compareBy<StrategyRosterDecision>{rankStatus(it.status)}.thenByDescending{it.expectancyR}.thenByDescending{it.hitRatePct})

        val cash=universe.filter{it.exchange=="NSE"&&it.segment=="CASH"&&it.instrumentType=="EQ"&&it.buyAllowed}.distinctBy{it.tradingSymbol}
        val carried=(lastSavedDualSummary()?.uc?.candidates.orEmpty()+lastSavedDualSummary()?.demand?.candidates.orEmpty()).map{it.symbol}.toSet()
        val existingLive=prefs.loadStrategyLive().toMutableList()
        val prioritySymbols=(carried+existingLive.map{it.setup.symbol}).toSet()
        progress("Strategies v2: FULL NSE ${cash.size} • ${active.size} research rules • context $sessionBand/${currentRegime.name}")

        data class Stage1(val instrument:Instrument,val movePct:Double,val rangePct:Double)
        val stage1=mutableListOf<Stage1>();val sessionOhlc=linkedMapOf<String,Ohlc>()
        val chunks=cash.chunked(50)
        chunks.forEachIndexed{idx,batch->
            val map=runCatching{groww.getOhlcBatch(token,batch.map{it.tradingSymbol})}.getOrDefault(emptyMap())
            sessionOhlc.putAll(map)
            batch.forEach{i->
                val o=map[i.tradingSymbol]?:return@forEach
                if(o.close<20.0||o.close>20_000.0||o.open<=0.0||o.high<=0.0||o.low<=0.0)return@forEach
                val move=kotlin.math.abs(o.close/o.open-1.0)*100.0
                val range=((o.high-o.low)/o.open*100.0).coerceAtLeast(0.0)
                if(move>=0.15||range>=0.45||i.tradingSymbol in prioritySymbols)stage1+=Stage1(i,move,range)
            }
            if(idx%10==9||idx==chunks.lastIndex)progress("Strategies v2: OHLC ${minOf((idx+1)*50,cash.size)}/${cash.size} • prefilter ${stage1.size}")
        }

        val deepBudget=220
        val essential=stage1.filter{it.instrument.tradingSymbol in prioritySymbols}
        val rest=stage1.filterNot{it.instrument.tradingSymbol in prioritySymbols}.sortedBy{it.instrument.tradingSymbol}
        val cursor=if(rest.isEmpty())0 else prefs.strategyDeepScanCursor()%rest.size
        val rotated=if(rest.isEmpty())emptyList() else rest.drop(cursor)+rest.take(cursor)
        val chosenRest=rotated.take((deepBudget-essential.size).coerceAtLeast(0))
        val selected=(essential+chosenRest).distinctBy{it.instrument.tradingSymbol}
        if(rest.isNotEmpty())prefs.setStrategyDeepScanCursor((cursor+chosenRest.size)%rest.size)
        val deferred=(stage1.size-selected.size).coerceAtLeast(0)
        progress("Strategies v2: deep ${selected.size}/${stage1.size} • queued $deferred")

        val listingAge=newListingsCache.associate{it.symbol to it.daysListed}
        val industryBundle=withContext(Dispatchers.IO){runCatching{industryClient.load(false)}.getOrNull()}
        if(industryBundle!=null&&industryBundle.fetchedAt>0L)prefs.setSectorMapVersion(industryBundle.source+"@"+industryBundle.fetchedAt)
        val macroEvents=prefs.loadMacroEvents(1000)
        val historyStart=date.minusDays(12).atTime(9,15).format(dateTimeFmt)
        val historyEnd=now.plusMinutes(1).format(dateTimeFmt)
        val quoteCache=mutableMapOf<String,Quote?>()
        suspend fun quote(symbol:String):Quote?=if(quoteCache.containsKey(symbol))quoteCache[symbol] else runCatching{groww.getQuote(token,symbol)}.getOrNull().also{quoteCache[symbol]=it}
        fun spreadPct(q:Quote)=ExecutionQuality.spreadPct(q)
        fun hasBid(q:Quote)=ExecutionQuality.hasBid(q)
        fun hasAsk(q:Quote)=ExecutionQuality.hasAsk(q)
        fun returnPct(setup:StrategySetup,exit:Double):Double{
            if(setup.entryPrice<=0.0||exit<=0.0||!exit.isFinite())return 0.0
            val raw=if(setup.direction==TradeDirection.LONG)(exit/setup.entryPrice-1.0)*100.0 else (setup.entryPrice/exit-1.0)*100.0
            return raw.takeIf{it.isFinite()}?:0.0
        }

        // Reconcile the small diversified LIVE portfolio with 1-minute chronology.
        val closed=prefs.loadStrategyClosed(1500).toMutableList()
        val surviving=mutableListOf<StrategyRecommendation>()
        val durableLearningUpdates=mutableListOf<Triple<StrategySetup,Double,Boolean>>()
        for(r in existingLive){
            val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist);val openedDate=opened.toLocalDate();val oldDay=openedDate<date
            val q=quote(r.setup.symbol);val currentPx=q?.lastPrice?.takeIf{it.isFinite()&&it>0.0}?:r.lastPrice
            val target=if(r.setup.direction==TradeDirection.LONG)r.setup.entryPrice*(1+r.setup.targetPct/100.0) else r.setup.entryPrice*(1-r.setup.targetPct/100.0)
            val stop=if(r.setup.direction==TradeDirection.LONG)r.setup.entryPrice*(1-r.setup.stopPct/100.0) else r.setup.entryPrice*(1+r.setup.stopPct/100.0)
            val pathEnd=if(oldDay)openedDate.atTime(15,31).atZone(ist) else now.plusMinutes(1)
            val pathCandles=runCatching{groww.getHistoricalCandles(token,r.setup.symbol,opened.format(dateTimeFmt),pathEnd.format(dateTimeFmt),"1minute")}.getOrNull()
            var pathStatus:StrategyRecommendationStatus?=null;var ambiguous=false;var exitPx=if(oldDay)r.lastPrice else currentPx
            pathCandles?.sortedBy{it.epochSeconds}?.forEach{c->
                if(pathStatus!=null||ambiguous||!c.high.isFinite()||!c.low.isFinite())return@forEach
                val th=if(r.setup.direction==TradeDirection.LONG)c.high>=target else c.low<=target
                val sh=if(r.setup.direction==TradeDirection.LONG)c.low<=stop else c.high>=stop
                when{th&&sh->{ambiguous=true;exitPx=c.close};sh->{pathStatus=StrategyRecommendationStatus.LOSS;exitPx=stop};th->{pathStatus=StrategyRecommendationStatus.WIN;exitPx=target}}
            }
            val eod=now.toLocalTime()>=LocalTime.of(15,30)
            val status=when{
                ambiguous->StrategyRecommendationStatus.INVALIDATED
                pathStatus!=null->pathStatus!!
                (oldDay||eod)&&pathCandles.isNullOrEmpty()->StrategyRecommendationStatus.INVALIDATED
                oldDay||eod->StrategyRecommendationStatus.LOSS
                else->StrategyRecommendationStatus.LIVE
            }
            if(status==StrategyRecommendationStatus.LIVE){
                surviving+=r.copy(lastSeenAt=System.currentTimeMillis(),lastPrice=currentPx,tradedValue=q?.let{it.volume*it.lastPrice}?:r.tradedValue,volume=q?.volume?:r.volume,spreadPct=q?.let(::spreadPct)?:r.spreadPct)
            }else{
                if(pathStatus==null&&!ambiguous)exitPx=pathCandles?.lastOrNull()?.close?.takeIf{it.isFinite()&&it>0.0}?:r.lastPrice.takeIf{it.isFinite()&&it>0.0}?:currentPx
                val ret=returnPct(r.setup,exitPx)
                val reason=when{
                    ambiguous->"INVALID • target and stop in same 1-minute bar"
                    pathCandles.isNullOrEmpty()->"INVALID • historical 1-minute replay unavailable"
                    status==StrategyRecommendationStatus.WIN->"Target reached"
                    pathStatus==StrategyRecommendationStatus.LOSS->"Stop reached"
                    else->"Target not reached by session close"
                }
                val done=r.copy(lastSeenAt=System.currentTimeMillis(),lastPrice=exitPx,closedAt=System.currentTimeMillis(),exitPrice=exitPx,status=status,returnPct=ret,closeReason=reason,
                    tradedValue=q?.let{it.volume*it.lastPrice}?:r.tradedValue,volume=q?.volume?:r.volume,spreadPct=q?.let(::spreadPct)?:r.spreadPct)
                closed.removeAll{it.id==done.id};closed.add(done)
                if(status==StrategyRecommendationStatus.WIN||status==StrategyRecommendationStatus.LOSS){
                    durableLearningUpdates+=Triple(r.setup,ret,status==StrategyRecommendationStatus.WIN)
                    recordStrategyLearningV2(done)
                }
            }
        }

        val rejectedBefore=prefs.loadRejectedShadows(2500).size
        val rawSetups=mutableListOf<Pair<StrategySetup,Quote>>()
        var enriched=0;var quoteRejected=0;var historyFailed=0;var candleShort=0;var rulesMatched=0;var handbookCautions=0

        for((idx,row) in selected.withIndex()){
            val inst=row.instrument
            val allCandles=runCatching{groww.getHistoricalCandles(token,inst.tradingSymbol,historyStart,historyEnd,"5minute")}.getOrElse{historyFailed++;emptyList()}
            if(allCandles.isEmpty())continue
            val byDate=allCandles.sortedBy{it.epochSeconds}.groupBy{Instant.ofEpochSecond(it.epochSeconds).atZone(ist).toLocalDate()}
            val candles=byDate[date].orEmpty()
            if(candles.size<2){candleShort++;continue}
            val priorDates=byDate.keys.filter{it<date}.sortedDescending()
            val previousSessionClose=priorDates.firstOrNull()?.let{byDate[it]?.lastOrNull()?.close}
            val slotIndex=candles.lastIndex
            val slotVolumes=priorDates.take(8).mapNotNull{d->byDate[d]?.getOrNull(slotIndex)?.volume}.filter{it>0L}

            val evals=buildList{
                for(def in active){
                    val e=strategyEngine.evaluate(def,candles,previousSessionClose,slotVolumes)?:continue
                    val d=rosterDecision(def.id,e.direction)
                    if(challengerOnly&&d?.status !in setOf(StrategyStatus.CHALLENGER,StrategyStatus.PROBATION))continue
                    add(def to e)
                }
            }
            if(evals.isEmpty()){if(idx%25==24)progress("Strategies v2: rules ${idx+1}/${selected.size} • matches $rulesMatched");continue}
            rulesMatched+=evals.size

            val q=quote(inst.tradingSymbol)?:continue;val sp=spreadPct(q);val tradedValue=q.volume*q.lastPrice
            if(!ExecutionQuality.discoveryQuote(q)){quoteRejected++;continue}
            enriched++

            for((def,e) in evals){
                val age=listingAge[inst.tradingSymbol];val listingBoost=if(age!=null&&age<=settings.newListingDays)1.0 else 0.0
                val rawScore=(e.score+listingBoost).coerceAtMost(100.0)
                val d=rosterDecision(def.id,e.direction)
                val maturity=d?.status?:StrategyStatus.CHALLENGER
                val liquidity="₹${"%.1f".format(tradedValue/100000.0)}L traded • vol ${q.volume} • spread ${"%.2f".format(sp)}%"
                val baseSetup=StrategySetup(inst.tradingSymbol,inst.name,def.id,def.name,e.direction,rawScore,q.lastPrice,e.targetPct,e.stopPct,
                    e.evidence+" • "+liquidity+" • frozen maturity "+maturity.name,age,rawScore=rawScore,contextBand=sessionBand,contextRegime=currentRegime.name,componentStrategyIds=listOf(def.id))
                fun reject(setup:StrategySetup,reason:String){recordRejectedStrategyShadow(setup,reason)}
                if(e.targetPct<ExecutionQuality.MIN_PLAN_SEPARATION_PCT||e.stopPct<ExecutionQuality.MIN_PLAN_SEPARATION_PCT){reject(baseSetup,"PLAN_SEPARATION_GATE");continue}
                if(e.direction==TradeDirection.SHORT&&(inst.series!="EQ"||!inst.sellAllowed)){reject(baseSetup,"SHORT_NOT_INTRADAY_ELIGIBLE");continue}
                if(e.direction==TradeDirection.LONG&&!hasAsk(q)){reject(baseSetup,"NO_EXECUTABLE_ASK");continue}
                if(e.direction==TradeDirection.SHORT&&!hasBid(q)){reject(baseSetup,"NO_EXECUTABLE_BID");continue}

                val hb=handbookSynergy.evaluate(baseSetup,candles,q)
                var setup=baseSetup.copy(score=hb.adjustedScore,rawScore=hb.adjustedScore,evidence=baseSetup.evidence+" • "+hb.evidence,researchSignature=hb.signature,
                    handbookQualityPct=hb.qualityPct,handbookPattern=hb.primaryPattern,handbookCombination=hb.combination)
                val sector=industryBundle?.let{evidenceFabric.sector(setup.symbol,setup.direction,it.bySymbol,sessionOhlc)}
                if(sector!=null){
                    val adj=evidenceFabric.sectorAdjustment(sector)
                    setup=setup.copy(score=(setup.score+adj).coerceIn(0.0,100.0),rawScore=(setup.rawScore+adj).coerceIn(0.0,100.0),evidence=setup.evidence+
                        " • sector ${sector.industry} • peers ${sector.peersObserved} • breadth ${"%.0f".format(sector.directionalBreadthPct)}% • RS ${"%+.2f".format(sector.relativeStrengthPct)}%")
                    val eid=("SECTOR|"+setup.symbol+"|"+sector.observedAt).hashCode().toUInt().toString(16)
                    prefs.appendPointInTimeEvidence(PointInTimeEvidence("PIT-"+eid,setup.symbol,EvidenceKind.SECTOR,"NIFTY500 industry intelligence",
                        "industry=${sector.industry}; breadth=${sector.directionalBreadthPct}; relativeStrength=${sector.relativeStrengthPct}; peerConfirmed=${sector.peerConfirmed}",
                        sector.source,observedAt=sector.observedAt,effectiveAt=sector.observedAt,revisionId=eid,notes="Prospective sector snapshot"))
                }
                if(evidenceFabric.shouldHardWaitForMacro(now,macroEvents)){reject(setup,"MACRO_HARD_WAIT "+evidenceFabric.macroRisk(now,macroEvents).second);continue}
                val companyEvent=evidenceFabric.companyEventRisk(setup.symbol,System.currentTimeMillis(),macroEvents)
                if(companyEvent!=null)setup=setup.copy(score=(setup.score-2.0).coerceAtLeast(0.0),rawScore=(setup.rawScore-2.0).coerceAtLeast(0.0),evidence=setup.evidence+" • event-risk "+companyEvent.title)
                if(hb.hardFail){
                    handbookCautions++;val penalty=(hb.failedHardFilters.size*1.5).coerceIn(2.0,6.0)
                    setup=setup.copy(score=(setup.score-penalty).coerceAtLeast(0.0),rawScore=(setup.rawScore-penalty).coerceAtLeast(0.0),
                        evidence=setup.evidence+" • EXECUTION CAUTION: "+hb.failedHardFilters.joinToString(",").take(160))
                }
                if(setup.rawScore<60.0){reject(setup,"RESEARCH_RAW_FLOOR ${"%.1f".format(setup.rawScore)} < 60");continue}
                rawSetups+=setup to q
            }
            if(idx%25==24||idx==selected.lastIndex)progress("Strategies v2: deep rules ${idx+1}/${selected.size} • matches $rulesMatched • quoted $enriched")
        }

        // Same-side ensemble: preserve component identity and give each component fractional credit later.
        val combined=rawSetups.groupBy{it.first.symbol to it.first.direction}.map{(_,pairs)->
            val ranked=pairs.sortedWith(compareBy<Pair<StrategySetup,Quote>>{rankStatus(rosterDecision(it.first.strategyId,it.first.direction)?.status?:StrategyStatus.CHALLENGER)}
                .thenByDescending{it.first.rawScore})
            val agree=ranked.take(3);val primary=agree.first()
            val components=agree.map{it.first.strategyId}.distinct()
            ranked.drop(3).forEach{(setup,_)->recordRejectedStrategyShadow(setup,"ENSEMBLE_COMPONENT_PRUNED")}
            val d=bestDecision(primary.first.copy(componentStrategyIds=components))
            val baseProb=d?.let{strategyGovernance.calibrate(primary.first.rawScore,it)}?:45.0
            val matureConfirmations=components.count{cid->rosterDecision(cid,primary.first.direction)?.let(strategyGovernance::productionEligible)==true}
            val probability=(baseProb+(matureConfirmations-1).coerceAtLeast(0)*0.5).coerceAtMost(85.0)
            val expR=strategyGovernance.expectedR(probability,primary.first.targetPct,primary.first.stopPct)
            val names=agree.map{it.first.strategyName}.distinct()
            primary.first.copy(strategyName=names.joinToString(" + "),score=probability,calibratedProbabilityPct=probability,expectedR=expR,
                componentStrategyIds=components,evidence="${names.size} component ensemble • calibrated target-before-stop ${"%.1f".format(probability)}% • exp ${"%+.2f".format(expR)}R • "+agree.joinToString(" | "){it.first.evidence.take(100)}) to primary.second
        }

        val directionalWinners=combined.groupBy{it.first.symbol}.mapNotNull{(_,v)->
            val winner=v.maxWithOrNull(compareBy<Pair<StrategySetup,Quote>>{it.first.expectedR}.thenBy{it.first.calibratedProbabilityPct})
            if(winner!=null)v.filterNot{it===winner}.forEach{(setup,_)->recordRejectedStrategyShadow(setup,"DIRECTION_CONFLICT_LOST v2 expR")}
            winner
        }.sortedWith(compareByDescending<Pair<StrategySetup,Quote>>{it.first.expectedR}.thenByDescending{it.first.calibratedProbabilityPct})

        val researchPreview=directionalWinners.take(100)
        val sessionClosed=closed.filter{runCatching{Instant.ofEpochMilli(it.openedAt).atZone(ist).toLocalDate()==date}.getOrDefault(false)}
        val scoredSessionClosed=sessionClosed.filter{it.status==StrategyRecommendationStatus.WIN||it.status==StrategyRecommendationStatus.LOSS}
        val sessionColdStrategies=scoredSessionClosed.groupBy{it.setup.strategyId}.mapNotNull{(id,rows)->
            val wins=rows.count{it.status==StrategyRecommendationStatus.WIN};val avg=if(rows.isEmpty())0.0 else rows.map{it.returnPct}.average()
            id.takeIf{AutomationPolicy.strategySessionCold(rows.size,wins,avg)}
        }.toSet()
        val usedSessionSymbols=(surviving.map{it.setup.symbol}+sessionClosed.map{it.setup.symbol}).toMutableSet()
        val existingKeys=surviving.map{"${it.setup.symbol}|${it.setup.direction.name}"}.toMutableSet()
        val newlyOpened=mutableListOf<StrategySetup>()
        var shadowsOpened=0;var maturityBlocked=0;var qualityBlocked=0;var churnBlocked=0;var executionBlocked=0;var correlationBlocked=0

        // Exploration is always non-executable shadow. Production roster never promotes intraday.
        if(now.toLocalTime()<LocalTime.of(15,10)){
            directionalWinners.filter{pair->
                val d=bestDecision(pair.first);d==null||!strategyGovernance.productionEligible(d)
            }.take(20).forEach{(setup,_)->if(openChallengerShadow(setup))shadowsOpened++}
        }

        val productionCandidates=if(challengerOnly)emptyList() else directionalWinners.filter{(setup,_)->
            val d=bestDecision(setup)
            val maturityOk=d?.let(strategyGovernance::productionEligible)==true
            val qualityOk=when(d?.status){
                StrategyStatus.CHAMPION->setup.calibratedProbabilityPct>=52.0&&setup.expectedR>=0.05
                StrategyStatus.QUALIFIED->setup.calibratedProbabilityPct>=55.0&&setup.expectedR>=0.10
                else->false
            }
            if(!maturityOk)maturityBlocked++
            else if(!qualityOk)qualityBlocked++
            maturityOk&&qualityOk
        }

        // Portfolio selector: publish only the best diversified opportunities, not dozens of correlated clones.
        val maxNew=settings.strategyTopCandidates.coerceIn(3,10)
        val directionCap=kotlin.math.ceil(maxNew*0.65).toInt().coerceAtLeast(1)
        val dirCounts=mutableMapOf<TradeDirection,Int>()
        val sectorCounts=mutableMapOf<String,Int>()
        surviving.forEach{r->
            dirCounts[r.setup.direction]=(dirCounts[r.setup.direction]?:0)+1
            val sec=industryBundle?.bySymbol?.get(r.setup.symbol).orEmpty().ifBlank{"UNKNOWN"}
            sectorCounts[sec]=(sectorCounts[sec]?:0)+1
        }

        if(now.toLocalTime()<LocalTime.of(15,10)){
            for((setup,q) in productionCandidates){
                if(newlyOpened.size>=maxNew)break
                val components=setup.componentStrategyIds.ifEmpty{listOf(setup.strategyId)}
                if(components.any{it in sessionColdStrategies}){qualityBlocked++;recordRejectedStrategyShadow(setup,"SESSION_KILL_SWITCH");continue}
                if(setup.symbol in usedSessionSymbols){churnBlocked++;recordRejectedStrategyShadow(setup,"SESSION_SYMBOL_CHURN_BLOCK");continue}
                if(!ExecutionQuality.executableQuote(q)){executionBlocked++;recordRejectedStrategyShadow(setup,"LIVE_EXECUTION_GATE");continue}
                val sector=industryBundle?.bySymbol?.get(setup.symbol).orEmpty().ifBlank{"UNKNOWN"}
                if((sectorCounts[sector]?:0)>=2||(dirCounts[setup.direction]?:0)>=directionCap){
                    correlationBlocked++;recordRejectedStrategyShadow(setup,"PORTFOLIO_CORRELATION_CAP");continue
                }
                val key="${setup.symbol}|${setup.direction.name}"
                if(key in existingKeys)continue
                val d=bestDecision(setup)?:continue
                val liveSetup=setup.copy(evidence=setup.evidence+" • PRODUCTION "+d.status.name+" • frozen before open • LIVE/shadow objectives aligned")
                val id="${date}|$key|${setup.strategyId}|V2"
                surviving+=StrategyRecommendation(id,liveSetup,System.currentTimeMillis(),System.currentTimeMillis(),q.lastPrice,tradedValue=q.volume*q.lastPrice,volume=q.volume,spreadPct=spreadPct(q))
                existingKeys+=key;usedSessionSymbols+=setup.symbol;newlyOpened+=liveSetup
                dirCounts[setup.direction]=(dirCounts[setup.direction]?:0)+1;sectorCounts[sector]=(sectorCounts[sector]?:0)+1
                prefs.appendDecisionSnapshot(liveSetup,"LIVE_PUBLISHED_V2",d.status.name+" • expR="+("%+.2f".format(setup.expectedR)),NseTradingCalendar2026.VERSION,HandbookSynergyEngine.VERSION,
                    sectorIndustry=sector,macroRisk=evidenceFabric.macroRisk(now,macroEvents).first?.name?:"NONE")
                DiagnosticLog.log(appContext,"DECISION","LIVE_PUBLISHED_V2 • ${setup.symbol} • ${setup.strategyId} • ${d.status} • p=${"%.1f".format(setup.calibratedProbabilityPct)}% • expR=${"%+.2f".format(setup.expectedR)}")
            }
        }else productionCandidates.forEach{(setup,_)->recordRejectedStrategyShadow(setup,"LATE_SESSION_GATE >=15:10")}

        prefs.saveStrategyLedger(surviving,closed)
        durableLearningUpdates.forEach{(setup,ret,win)->
            runCatching{prefs.updateStrategyResult(setup.strategyId,setup.strategyName,ret,win)}
                .onFailure{DiagnosticLog.log(appContext,"STRATEGY","Legacy aggregate compatibility update failed for ${setup.symbol}",it)}
        }
        prefs.pruneMemory(settings.memoryRetentionDays.coerceAtLeast(30))

        val perfs=strategyGovernance.performanceRows(bundle.strategies,roster,sessionBand,currentRegime)
        val insights=strategyGovernance.insights(roster,sessionBand,currentRegime,8)
        val rejectedAfter=prefs.loadRejectedShadows(2500).size;val rejectedAdded=(rejectedAfter-rejectedBefore).coerceAtLeast(0)
        val champions=perfs.count{it.status==StrategyStatus.CHAMPION};val qualified=perfs.count{it.status==StrategyStatus.QUALIFIED}
        val v2Count=strategyLearning.eventCount()
        val summary=StrategyTournamentSummary(System.currentTimeMillis(),cash.size,active.size,enriched,researchPreview.map{it.first},active,perfs,bundle.version,
            (if(challengerOnly)"SHADOW RUN • " else "")+"V2 FULL NSE ${cash.size} • context $sessionBand/${currentRegime.name} • deep ${selected.size} queued $deferred • matches $rulesMatched • calibrated production ${productionCandidates.size} • execution blocks $executionBlocked • maturity blocks $maturityBlocked • quality blocks $qualityBlocked • churn $churnBlocked • correlation $correlationBlocked • ${surviving.size} LIVE • ${newlyOpened.size} new • shadows +$shadowsOpened • CHAMP $champions • QUAL $qualified • Room events $v2Count • rejected+journal $rejectedAdded",
            insights,rejectedAfter,HandbookSynergyEngine.VERSION,date.toString(),"$sessionBand/${currentRegime.name}",v2Count)
        DiagnosticLog.log(appContext,"STRATEGY-V2",summary.message)
        prefs.saveStrategySummary(summary);prefs.clearStrategyError();summary
    }

    suspend fun refreshGlobalMappings(force:Boolean=false):Int{
        val settings=prefs.loadSettings();val now=System.currentTimeMillis();val last=prefs.lastGlobalMappingRefreshAt()
        val due=last==0L||now-last>=settings.globalMappingRefreshDays.coerceIn(1,30).toLong()*24*60*60*1000
        val weekend=!NseTradingCalendar2026.isTradingDate(LocalDate.now(ist))
        if(!force&&!due){if(globalMappings==null)globalMappings=globalMarket.loadMappings(preferRemote=false);return globalMappings!!.mappings.size}
        if(!force&&last>0&&!weekend){if(globalMappings==null)globalMappings=globalMarket.loadMappings(preferRemote=false);return globalMappings!!.mappings.size}
        val bundle=globalMarket.loadMappings(preferRemote=true)
        globalMappings=bundle; prefs.setLastGlobalMappingRefreshAt(now);prefs.setGlobalMappingVersion(bundle.version)
        return bundle.mappings.size
    }

    suspend fun scanGlobalLead(progress:suspend(String)->Unit={}):GlobalLeadSummary=globalMutex.withLock{
        val migrated=prefs.enforceGlobalLiveOnlyFlow()
        if(migrated>0)DiagnosticLog.log(appContext,"GLOBAL-FLOW","Archived $migrated legacy NEXT/research records; DONE and learning are LIVE-only")
        val settings=prefs.loadSettings()
        if(globalMappings==null)runCatching{refreshGlobalMappings(false)}
        val bundle=globalMappings?:globalMarket.loadMappings(preferRemote=false).also{globalMappings=it}
        progress("Global Lead: checking ${bundle.mappings.size} global links for LONG + SHORT")
        val benchmarks=linkedMapOf<String,GlobalQuoteSnapshot?>()
        for(t in bundle.mappings.map{it.benchmarkTicker}.filter{it.isNotBlank()}.distinct()){benchmarks[t]=runCatching{globalMarket.snapshot(t)}.getOrNull()}
        val foreign=mutableListOf<Pair<GlobalCounterpart,GlobalQuoteSnapshot>>()
        var loaded=0
        for((idx,m) in bundle.mappings.withIndex()){
            val f=runCatching{globalMarket.snapshot(m.foreignTicker)}.getOrNull()
            if(f!=null){loaded++;foreign+=m to f}
            if(idx%8==7)progress("Global Lead: foreign data ${idx+1}/${bundle.mappings.size}")
        }
        val limit=maxOf(settings.globalTopCandidates*2,16)
        val prelimLong=foreign.filter{globalLeadEngine.inferDirection(it.second,benchmarks[it.first.benchmarkTicker])==GlobalLeadDirection.LONG}
            .sortedByDescending{globalLeadEngine.foreignScore(it.first,it.second,benchmarks[it.first.benchmarkTicker],GlobalLeadDirection.LONG).score}.distinctBy{it.first.indianSymbol}.take(limit)
        val prelimShort=foreign.filter{globalLeadEngine.inferDirection(it.second,benchmarks[it.first.benchmarkTicker])==GlobalLeadDirection.SHORT}
            .sortedByDescending{globalLeadEngine.foreignScore(it.first,it.second,benchmarks[it.first.benchmarkTicker],GlobalLeadDirection.SHORT).score}.distinctBy{it.first.indianSymbol}.take(limit)
        val tokenOk=ensureAutomationAuthentication();val token=if(tokenOk)accessToken() else ""
        val globalUniverse=if(instruments.cached().isEmpty())runCatching{instruments.refresh()}.getOrDefault(emptyList()) else instruments.cached()
        val globalEligible=globalUniverse.filter{ExecutionQuality.eligibleInstrument(it)}.associateBy{it.tradingSymbol}
        val pressure=lastSavedDualSummary()?.demand?.candidates?.associate{it.symbol to it.score}.orEmpty()
        val now=ZonedDateTime.now(ist)
        val indiaMarketOpen=marketSessionInfo(now).isOpen
        val finals=mutableListOf<GlobalLeadCandidate>()
        val selections=(prelimLong.map{Triple(it.first,it.second,GlobalLeadDirection.LONG)}+prelimShort.map{Triple(it.first,it.second,GlobalLeadDirection.SHORT)})
        val quoteCache=mutableMapOf<String,Quote?>();val priorCache=mutableMapOf<String,Double?>()
        for((idx,triple) in selections.withIndex()){
            val(m,f,direction)=triple
            val inst=globalEligible[m.indianSymbol]?:continue
            if(direction==GlobalLeadDirection.SHORT&&!inst.sellAllowed)continue
            val q=quoteCache.getOrPut(m.indianSymbol){if(tokenOk)runCatching{groww.getQuote(token,m.indianSymbol)}.getOrNull() else null}
            // During NSE hours this must be an executable Indian quote. Outside NSE hours, empty depth
            // is normal; Global Lead is research-only and may proceed using the last Indian reference
            // price (or no Indian price at all) until the 09:15 live confirmation pass.
            if(indiaMarketOpen && (q==null||!ExecutionQuality.executableQuote(q)))continue
            if(!indiaMarketOpen && q!=null && (!q.lastPrice.isFinite()||q.lastPrice<ExecutionQuality.MIN_PRICE))continue
            val priorKey="${m.indianSymbol}|${f.marketTimestamp}"
            val prior=if(priorCache.containsKey(priorKey))priorCache[priorKey] else (if(tokenOk)runCatching{priorIndianReturnBefore(token,m.indianSymbol,f.marketTimestamp)}.getOrNull() else null).also{priorCache[priorKey]=it}
            finals+=globalLeadEngine.finalCandidate(m,f,benchmarks[m.benchmarkTicker],q,prior,pressure[m.indianSymbol],settings,now,direction)
            if(idx%5==4)progress("Global Lead: India confirmation ${idx+1}/${selections.size}")
        }
        val learnedFinals=finals.map{c->
            val adj=prefs.globalScoreAdjustment(globalLearningKey(c))
            if(kotlin.math.abs(adj)<0.05)c else c.copy(
                score=(c.score+adj).coerceIn(0.0,100.0),
                reasons=(c.reasons+"Local learning ${if(adj>=0)"+" else ""}${"%.1f".format(adj)} score").take(6)
            )
        }
        val perSide=settings.globalTopCandidates.coerceIn(10,20)
        fun ranked(direction:GlobalLeadDirection)=learnedFinals.filter{it.direction==direction}
            .sortedWith(compareByDescending<GlobalLeadCandidate>{actionPriority(it.action)}.thenByDescending{it.score})
            .take(perSide).mapIndexed{i,c->c.copy(rank=i+1)}
        val longs=ranked(GlobalLeadDirection.LONG);val shorts=ranked(GlobalLeadDirection.SHORT)
        val previousSummary=prefs.loadGlobalLeadSummary()
        val previousCandidates=previousSummary?.candidates.orEmpty()
        val previousLive=if(indiaMarketOpen) previousCandidates.filter{c->
            c.action==GlobalLeadAction.ENTER_AFTER_OPEN &&
                runCatching{Instant.ofEpochMilli(c.generatedAt).atZone(ist).toLocalDate()==now.toLocalDate()}.getOrDefault(false)
        } else emptyList()
        val currentTop=longs+shorts
        val currentKeys=currentTop.map{"${it.direction.name}|${it.indianSymbol}"}.toSet()
        val retained=previousLive.filter{"${it.direction.name}|${it.indianSymbol}" !in currentKeys}
        val top=(currentTop+retained).sortedWith(compareByDescending<GlobalLeadCandidate>{actionPriority(it.action)}.thenByDescending{it.score})
        val actionableLong=top.count{it.direction==GlobalLeadDirection.LONG&&(it.action==GlobalLeadAction.ENTER_AFTER_OPEN||it.action==GlobalLeadAction.KEEP_NEXT_SESSION||it.action==GlobalLeadAction.NEXT_OPEN_WATCH)}
        val actionableShort=top.count{it.direction==GlobalLeadDirection.SHORT&&(it.action==GlobalLeadAction.ENTER_AFTER_OPEN||it.action==GlobalLeadAction.KEEP_NEXT_SESSION||it.action==GlobalLeadAction.NEXT_OPEN_WATCH)}
        val previous=previousCandidates.map{"${it.direction.name} ${it.indianSymbol}"}.toSet()
        val current=top.map{"${it.direction.name} ${it.indianSymbol}"}.toSet();val dropped=(previous-current).sorted()
        fun isLiveAction(c:GlobalLeadCandidate)=c.action==GlobalLeadAction.ENTER_AFTER_OPEN||c.action==GlobalLeadAction.KEEP_NEXT_SESSION
        val currentLiveKeys=top.filter(::isLiveAction).map{"${it.direction.name}|${it.indianSymbol}"}.toSet()
        val closedNow=previousCandidates.filter(::isLiveAction).filter{"${it.direction.name}|${it.indianSymbol}" !in currentLiveKeys}.map{c->
            GlobalLeadClosedRecord(c,System.currentTimeMillis(),if(now.toLocalTime()>LocalTime.of(15,30))"Session closed" else "Signal no longer confirmed")
        }
        prefs.appendGlobalLeadClosed(closedNow)
        if(closedNow.isNotEmpty())DiagnosticLog.log(appContext,"GLOBAL","closed ${closedNow.size}: ${closedNow.joinToString{it.candidate.indianSymbol}}")
        val nextCount=top.count{it.action==GlobalLeadAction.NEXT_OPEN_WATCH}
        val liveCount=top.count{it.action==GlobalLeadAction.ENTER_AFTER_OPEN||it.action==GlobalLeadAction.KEEP_NEXT_SESSION}
        val summary=GlobalLeadSummary(System.currentTimeMillis(),bundle.version,bundle.mappings.size,loaded,top,
            "NEXT $nextCount • LIVE $liveCount • LONG $actionableLong • SHORT $actionableShort • foreign ${loaded}/${bundle.mappings.size}","09:15 IST",dropped)
        top.filter{it.action==GlobalLeadAction.WAIT_FOR_CONFIRMATION||it.action==GlobalLeadAction.OBSERVE}.take(8)
            .forEach{c->recordRejectedGlobalShadow(c,indiaMarketOpen,"GLOBAL_CONFIRMATION_GATE ${c.action.name}",summary.generatedAt)}
        recordGlobalCalls(top,indiaMarketOpen,summary.generatedAt)
        prefs.saveGlobalLeadSummary(summary);summary
    }

    private fun globalLearningKey(c:GlobalLeadCandidate)="${c.direction.name}|${c.mappingType.name}|${c.foreignTicker}"

    private fun actionPriority(a:GlobalLeadAction)=when(a){
        GlobalLeadAction.ENTER_AFTER_OPEN->6;GlobalLeadAction.KEEP_NEXT_SESSION->5;GlobalLeadAction.NEXT_OPEN_WATCH->4;
        GlobalLeadAction.WAIT_FOR_CONFIRMATION->3;GlobalLeadAction.OBSERVE->2;GlobalLeadAction.EXIT_BY_3PM->1
    }

    private suspend fun priorIndianReturnBefore(token:String,symbol:String,signalAtMs:Long):Double?{
        if(signalAtMs<=0)return null
        val signalDate=Instant.ofEpochMilli(signalAtMs).atZone(ist).toLocalDate()
        val start=signalDate.minusDays(12).atStartOfDay().format(dateTimeFmt);val end=signalDate.plusDays(2).atStartOfDay().format(dateTimeFmt)
        val candles=groww.getHistoricalCandles(token,symbol,start,end,"1day").filter{it.epochSeconds*1000L<signalAtMs}.sortedBy{it.epochSeconds}
        if(candles.size<2)return null
        val a=candles[candles.lastIndex-1].close;val b=candles.last().close
        return if(a>0)(b/a-1.0)*100.0 else null
    }

    private fun withUcEvidenceContext(summary:ScanSummary,asOf:Long=System.currentTimeMillis()):ScanSummary{
        if(summary.candidates.isEmpty())return summary
        val enriched=summary.candidates.map{c->
            val evidence=prefs.pointInTimeEvidenceAsOf(c.symbol,asOf)
            val fundamentals=evidence.count{it.kind==EvidenceKind.FUNDAMENTAL}
            val analyst=evidence.count{it.kind==EvidenceKind.ANALYST}
            val events=evidence.count{it.kind==EvidenceKind.COMPANY_EVENT}
            val tags=buildList{
                if(fundamentals>0)add("PIT FUNDAMENTAL $fundamentals")
                if(analyst>0)add("PIT ANALYST $analyst")
                if(events>0)add("PIT EVENTS $events")
                if(evidence.isEmpty())add("PIT EVIDENCE NEUTRAL")
            }
            c.copy(activeStrategies=(c.activeStrategies+tags).distinct())
        }
        return summary.copy(candidates=enriched)
    }

    suspend fun scanAll(progress:suspend(String)->Unit={}):DualScanSummary=scanMutex.withLock{
        val nowMs=System.currentTimeMillis();val cached=lastDualSummary
        if(cached!=null&&nowMs-lastDualScanAt<dualScanReuseMs){progress("Reusing the recent rate-safe scan snapshot");return@withLock cached}
        require(ensureAutomationAuthentication()){ "Groww authentication is required. TOTP mode can renew automatically after the first authentication." }
        val token=secureStore.accessToken()
        val settings=prefs.loadSettings();if(settings.learningEnabled)evaluateDueOutcomes(token);prefs.pruneMemory(settings.memoryRetentionDays)
        val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val listings=runCatching{refreshNewListings()}.getOrDefault(newListingsCache)
        val allowedSeries=if(settings.includeSmeSeries)setOf("EQ","BE","BZ","SM","ST") else setOf("EQ","BE","BZ")
        val symbols=universe.asSequence().filter{it.exchange=="NSE"&&it.segment=="CASH"&&it.series in allowedSeries&&it.buyAllowed}.map{it.tradingSymbol}.distinct().toList()
        progress("Preparing one shared rate-safe market snapshot");groww.prefetchOhlcSnapshot(token,symbols,progress)
        progress("Section 1/2: upper-circuit continuation")
        val uc=withUcEvidenceContext(ucScanner.scan(token,universe,listings,settings,prefs.adaptivePrecisionMap(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION),progress,
            rejectedShadow={c,reason->recordRejectedCandidateShadow(ScannerSection.UC_CONTINUATION,TradeCallBucket.LIVE,c,reason)}))
        prefs.saveLastScan(uc)
        progress("Section 2/2: pre-pressure spike prediction")
        val demand=demandScanner.scan(token,universe,listings,settings,prefs.adaptivePrecisionMap(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION),progress,
            rejectedShadow={c,reason->recordRejectedCandidateShadow(ScannerSection.DEMAND_SQUEEZE,TradeCallBucket.LIVE,c,reason)})
        prefs.saveLastScan(demand)
        val scanNow=ZonedDateTime.now(ist);val scanNowMs=System.currentTimeMillis()
        val liveSessionOpen=marketSessionInfo(scanNow).isOpen
        if(liveSessionOpen){
            val ucLive=uc.candidates.filter{"UC_LIVE" in it.activeStrategies}
            if(ucLive.isNotEmpty())recordCandidateCalls(ScannerSection.UC_CONTINUATION,TradeCallBucket.LIVE,ucLive,"UC live scan",scanNowMs)
        }
        if(liveSessionOpen&&!demand.message.startsWith("WATCHLIST ONLY"))recordCandidateCalls(ScannerSection.DEMAND_SQUEEZE,TradeCallBucket.LIVE,demand.candidates,"Pressure live scan",scanNowMs)
        // v1.6.9: 3 PM next-day picks are produced by scanUpperCircuitThreePm().
        // They intentionally do not depend on the same-day UC_LIVE gate.
        DualScanSummary(uc,demand,listings).also{summary->lastDualSummary=summary;lastDualScanAt=maxOf(uc.completedAt,demand.completedAt)}
    }

    suspend fun scanUpperCircuitThreePm(progress:suspend(String)->Unit={}):List<Candidate> = scanMutex.withLock{
        val now=ZonedDateTime.now(ist)
        val time=now.toLocalTime()
        val session=marketSessionInfo(now)
        if(!session.isOpen||time<LocalTime.of(15,10)||time>LocalTime.of(15,30))return@withLock emptyList()
        val today=now.toLocalDate()
        val already=prefs.loadTradeCalls(1500).any{
            it.engine==TradeCallEngine.UPPER_CIRCUIT&&it.bucket==TradeCallBucket.THREE_PM&&it.outcome==TradeCallOutcome.OPEN&&
                Instant.ofEpochMilli(it.openedAt).atZone(ist).toLocalDate()==today
        }
        if(already)return@withLock emptyList()
        require(ensureAutomationAuthentication()){"Groww authentication is required for 3 PM UC prediction"}
        val token=secureStore.accessToken()
        val settings=prefs.loadSettings()
        val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val listings=if(newListingsCache.isEmpty())runCatching{refreshNewListings()}.getOrDefault(emptyList()) else newListingsCache
        val symbols=universe.asSequence().filter{ExecutionQuality.eligibleInstrument(it)}.map{it.tradingSymbol}.distinct().toList()
        progress("3 PM UC: preparing full-NSE next-session snapshot")
        groww.prefetchOhlcSnapshot(token,symbols,progress)
        val research=withUcEvidenceContext(
            ucScanner.scan(token,universe,listings,settings,prefs.adaptivePrecisionMap(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION),progress,nextSessionMode=true,
                rejectedShadow={c,reason->recordRejectedCandidateShadow(ScannerSection.UC_CONTINUATION,TradeCallBucket.THREE_PM,c,reason)})
        )
        val picks=research.candidates.filter{"UC_LIVE" in it.activeStrategies}
            .sortedWith(compareByDescending<Candidate>{it.score}.thenByDescending{it.buySellRatio}.thenByDescending{it.volumeRatio})
            .take(settings.maxFinalCandidates.coerceIn(1,5))
        prefs.setLastNearCloseAutoScanAt(System.currentTimeMillis())
        if(picks.isNotEmpty()){
            recordCandidateCalls(ScannerSection.UC_CONTINUATION,TradeCallBucket.THREE_PM,picks,"UC 3 PM next-trading-day LONG prediction",System.currentTimeMillis())
            DiagnosticLog.log(appContext,"UC-3PM","published ${picks.size} next-session LONG pick(s): ${picks.joinToString{it.symbol}}")
        }else DiagnosticLog.log(appContext,"UC-3PM","no qualified next-session pick; retry allowed until 15:30")
        picks
    }

    suspend fun scanUpperCircuitNextSession(progress:suspend(String)->Unit={}):ScanSummary=scanMutex.withLock{
        require(ensureAutomationAuthentication()){ "Groww authentication is required for next-session UC research" }
        val token=secureStore.accessToken();val settings=prefs.loadSettings()
        val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val listings=if(newListingsCache.isEmpty())runCatching{refreshNewListings()}.getOrDefault(emptyList()) else newListingsCache
        val symbols=universe.asSequence().filter{ExecutionQuality.eligibleInstrument(it)}.map{it.tradingSymbol}.distinct().toList()
        progress("UC next-session: preparing latest completed-market snapshot")
        groww.prefetchOhlcSnapshot(token,symbols,progress)
        val uc=withUcEvidenceContext(ucScanner.scan(token,universe,listings,settings,prefs.adaptivePrecisionMap(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION),progress,nextSessionMode=true,
            rejectedShadow={c,reason->recordRejectedCandidateShadow(ScannerSection.UC_CONTINUATION,TradeCallBucket.NEXT_SESSION,c,reason)}))
        prefs.saveLastScan(uc)
        val now=ZonedDateTime.now(ist)
        val today=now.toLocalDate()
        val predictionDate=if(NseTradingCalendar2026.isTradingDate(today)&&now.toLocalTime()>=NseTradingCalendar2026.open)today
            else NseTradingCalendar2026.previousTradingDate(today)
        prefs.saveFreezeRecord(predictionDate.toString(),ScannerSection.UC_CONTINUATION,uc.candidates,if(uc.candidates.isEmpty())FreezeOutcome.NO_SIGNAL else FreezeOutcome.PICKS,uc.completedAt,"Autonomous next-session UC research snapshot")
        val nextLive=uc.candidates.filter{"UC_LIVE" in it.activeStrategies}
        if(nextLive.isNotEmpty())recordCandidateCalls(ScannerSection.UC_CONTINUATION,TradeCallBucket.NEXT_SESSION,nextLive,"PRE-UC next-session prediction",System.currentTimeMillis())
        val demand=prefs.loadLastScan(ScannerSection.DEMAND_SQUEEZE)?:ScanSummary(ScannerSection.DEMAND_SQUEEZE,uc.startedAt,uc.completedAt,0,0,0,emptyList(),0,"Market-hours pressure engine")
        lastDualSummary=DualScanSummary(uc,demand,listings);lastDualScanAt=uc.completedAt
        DiagnosticLog.log(appContext,"UC-NEXT","candidates=${uc.candidates.size} msg=${uc.message}")
        uc
    }

    suspend fun scanDemandOnly(progress:suspend(String)->Unit={}):ScanSummary=scanMutex.withLock{
        require(ensureAutomationAuthentication()){ "Groww authentication is required. TOTP mode can renew automatically after the first authentication." }
        val token=secureStore.accessToken()
        val settings=prefs.loadSettings();val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val listings=if(newListingsCache.isEmpty())runCatching{refreshNewListings()}.getOrDefault(emptyList()) else newListingsCache
        // Frequent background passes still pre-screen the full NSE cash universe, but cap expensive
        // quote/history enrichment to 60 leaders so a 15-minute cadence remains rate-safe.
        val backgroundSettings=settings.copy(maxQuotesPerScan=minOf(settings.maxQuotesPerScan,60))
        val summary=demandScanner.scan(token,universe,listings,backgroundSettings,prefs.adaptivePrecisionMap(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION),progress,
            rejectedShadow={c,reason->recordRejectedCandidateShadow(ScannerSection.DEMAND_SQUEEZE,TradeCallBucket.LIVE,c,reason)})
        prefs.saveLastScan(summary);summary
    }

    suspend fun replay(symbol:String,days:Long=30):ReplayResult{
        require(ensureAutomationAuthentication()){ "Groww authentication is required" }
        return replay.replaySymbol(accessToken(),symbol.trim().uppercase(),days)
    }

    fun freezeToday(section:ScannerSection,candidates:List<Candidate>,message:String="Manual freeze"){
        val date=LocalDate.now(ist);val summary=prefs.loadLastScan(section);val sourceAt=summary?.completedAt?:System.currentTimeMillis()
        val outcome=if(candidates.isEmpty())FreezeOutcome.NO_SIGNAL else FreezeOutcome.PICKS
        prefs.saveFreezeRecord(date.toString(),section,candidates,outcome,sourceAt,message)
    }

    fun ensureTodayFreezeAudit(now:ZonedDateTime=ZonedDateTime.now(ist)){
        val session=marketSessionInfo(now);if(!session.isTradingDay)return
        val freeze=LocalTime.of(15,30)
        if(now.toLocalTime()<freeze)return
        val date=now.toLocalDate();val dateKey=date.toString()
        for(section in ScannerSection.entries){
            val existing=prefs.freezeRecord(dateKey,section)
            if(existing.recorded && existing.outcome!=FreezeOutcome.NO_DATA)continue
            val last=prefs.loadLastScan(section)
            val valid=last!=null && Instant.ofEpochMilli(last.completedAt).atZone(ist).toLocalDate()==date && Instant.ofEpochMilli(last.completedAt).atZone(ist).toLocalTime()>=LocalTime.of(14,45)
            if(valid){
                val list=last!!.candidates;val outcome=if(list.isEmpty())FreezeOutcome.NO_SIGNAL else FreezeOutcome.PICKS
                prefs.saveFreezeRecord(dateKey,section,list,outcome,last.completedAt,if(list.isEmpty())"End-of-day audit: scan completed, no qualifying signal" else "End-of-day audit: latest live scan closed")
            }else{
                // WorkManager is inexact. Allow a brief post-close grace window for the final market scan.
                if(now.toLocalTime()<LocalTime.of(15,40))continue
                prefs.saveFreezeRecord(dateKey,section,emptyList(),FreezeOutcome.NO_DATA,last?.completedAt?:0L,"End-of-day audit: no valid late-session scan was available")
            }
        }
        val pressureLast=prefs.loadLastScan(ScannerSection.DEMAND_SQUEEZE)
        if(pressureLast!=null&&pressureLast.candidates.isNotEmpty()&&pressureLast.message.startsWith("WATCHLIST ONLY")){
            recordCandidateCalls(ScannerSection.DEMAND_SQUEEZE,TradeCallBucket.NEXT_SESSION,pressureLast.candidates,"Pressure next-session watch",System.currentTimeMillis())
        }
    }

    fun frozenToday(section:ScannerSection):List<Candidate> = freezeRecordToday(section).candidates

    suspend fun runPostTradeAutopsies(limit:Int=10):Int{
        if(limit<=0||!ensureAutomationAuthentication())return 0
        val token=accessToken();val existing=prefs.loadAutopsies(1000).map{it.sourceId}.toHashSet()
        val closedCalls=prefs.loadTradeCalls(1500).filter{(it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS)&&it.id !in existing}
            .sortedWith(compareByDescending<TradeCallRecord>{it.outcome==TradeCallOutcome.LOSS}.thenByDescending{it.closedAt}).take(limit)
        val left=(limit-closedCalls.size).coerceAtLeast(0)
        val closedStrategies=prefs.loadStrategyClosed(500).filter{"STRATEGY|${it.id}" !in existing}
            .sortedWith(compareByDescending<StrategyRecommendation>{it.status==StrategyRecommendationStatus.LOSS}.thenByDescending{it.closedAt}).take(left)
        if(closedCalls.isEmpty()&&closedStrategies.isEmpty())return 0

        val nifty=runCatching{globalMarket.intradaySeries("^NSEI")}.getOrDefault(emptyList())
        val sensex=runCatching{globalMarket.intradaySeries("^BSESN")}.getOrDefault(emptyList())
        val bank=runCatching{globalMarket.intradaySeries("^NSEBANK")}.getOrDefault(emptyList())
        val indiaVix=runCatching{globalMarket.snapshot("^INDIAVIX")}.getOrNull()
        val spy=runCatching{globalMarket.snapshot("SPY")}.getOrNull()
        val globalVix=runCatching{globalMarket.snapshot("^VIX")}.getOrNull()
        val marketNews=runCatching{news.marketContext(35)}.getOrDefault(emptyList())
        var saved=0

        for(call in closedCalls){
            val targetDate=runCatching{LocalDate.parse(call.targetSessionDate)}.getOrNull()?:continue
            val opened=Instant.ofEpochMilli(call.openedAt).atZone(ist)
            val sessionStart=targetDate.atTime(9,15).atZone(ist)
            val executionStart=if(call.bucket==TradeCallBucket.LIVE&&opened.toLocalDate()==targetDate&&opened.isAfter(sessionStart))opened else sessionStart
            val end=Instant.ofEpochMilli(call.closedAt.takeIf{it>0}?:targetDate.atTime(15,31).atZone(ist).toInstant().toEpochMilli()).atZone(ist)
            val fetchStart=executionStart.minusMinutes(65);val fetchEnd=maxOf(end,executionStart.plusMinutes(30))
            val candles=runCatching{groww.getHistoricalCandles(token,call.symbol,fetchStart.format(dateTimeFmt),fetchEnd.format(dateTimeFmt),"5minute")}.getOrNull()?:continue
            val context=if(call.outcome==TradeCallOutcome.LOSS)runCatching{news.contextual(call.symbol,call.companyName,10)}.getOrDefault(emptyList()) else emptyList()
            val record=autopsyEngine.analyze(TradeAutopsyEngine.Input(
                sourceId=call.id,engineLabel=call.engine.name,symbol=call.symbol,outcome=call.outcome.name,originalReturnPct=call.returnPct,direction=call.direction,
                entryPrice=call.entryPrice,stopPrice=call.stopPrice,targetPrice=call.targetPrice,openedAt=call.openedAt,executionStartAt=executionStart.toInstant().toEpochMilli(),closedAt=call.closedAt,
                sessionDate=targetDate.toString(),stockCandles=candles,niftyBars=nifty,sensexBars=sensex,bankBars=bank,indiaVix=indiaVix,spy=spy,globalVix=globalVix,news=(context+marketNews).distinctBy{it.title}
            ))
            prefs.saveAutopsy(record);saved++
        }

        for(r in closedStrategies){
            val s=r.setup;val sourceId="STRATEGY|${r.id}";val opened=Instant.ofEpochMilli(r.openedAt).atZone(ist);val sessionDate=opened.toLocalDate();val executionStart=opened
            val end=Instant.ofEpochMilli(r.closedAt.takeIf{it>0}?:sessionDate.atTime(15,31).atZone(ist).toInstant().toEpochMilli()).atZone(ist)
            val candles=runCatching{groww.getHistoricalCandles(token,s.symbol,opened.minusMinutes(65).format(dateTimeFmt),maxOf(end,opened.plusMinutes(30)).format(dateTimeFmt),"5minute")}.getOrNull()?:continue
            val target=if(s.direction==TradeDirection.LONG)s.entryPrice*(1+s.targetPct/100.0) else s.entryPrice*(1-s.targetPct/100.0)
            val stop=if(s.direction==TradeDirection.LONG)s.entryPrice*(1-s.stopPct/100.0) else s.entryPrice*(1+s.stopPct/100.0)
            val outcome=if(r.status==StrategyRecommendationStatus.WIN)"WIN" else "LOSS"
            val context=if(outcome=="LOSS")runCatching{news.contextual(s.symbol,s.symbol,8)}.getOrDefault(emptyList()) else emptyList()
            val record=autopsyEngine.analyze(TradeAutopsyEngine.Input(sourceId,"STRATEGY",s.symbol,outcome,r.returnPct,s.direction,s.entryPrice,stop,target,r.openedAt,executionStart.toInstant().toEpochMilli(),r.closedAt,sessionDate.toString(),candles,nifty,sensex,bank,indiaVix,spy,globalVix,(context+marketNews).distinctBy{it.title}))
            prefs.saveAutopsy(record);saved++
        }
        if(saved>0)DiagnosticLog.log(appContext,"AUTOPSY","Generated $saved post-trade autopsies with regime + shadow-strategy replay")
        return saved
    }

    private fun multifyDate(ms:Long):LocalDate = Instant.ofEpochMilli(ms).atZone(ist).toLocalDate()

    private fun buildMultifyDashboard():MultifyDashboard{
        val now=ZonedDateTime.now(ist);val today=now.toLocalDate();val trades=multifyTrading.loadTrades(2500)
        val open=trades.filter{it.status==MultifyShadowStatus.OPEN}
        val closedToday=trades.filter{it.status==MultifyShadowStatus.CLOSED&&it.closedAt>0L&&multifyDate(it.closedAt)==today}
        val realized=closedToday.sumOf{it.netPnl}
        val unrealized=open.sumOf{t->
            val px=t.lastPrice.takeIf{it>0.0}?:t.entryPrice
            val gross=if(t.side==MultifyShadowSide.LONG)(px-t.entryPrice)*t.quantity else (t.entryPrice-px)*t.quantity
            val exitCost=px*t.quantity*MULTIFY_ESTIMATED_COST_RATE_PER_LEG
            gross-(t.entryPrice*t.quantity*MULTIFY_ESTIMATED_COST_RATE_PER_LEG)-exitCost
        }
        val exposure=open.sumOf{it.entryPrice*it.quantity}
        val events=MultifyEventStore.recent(appContext,1000)
        val todayEvents=events.count{multifyDate(it.capturedAt)==today&&it.instrumentClass==MultifyInstrumentClass.EQUITY}
        val sessionDates=buildList{
            var d=today
            while(size<5){if(NseTradingCalendar2026.isTradingDate(d))add(d);d=d.minusDays(1)}
        }
        val daily=sessionDates.map{d->
            val closed=trades.filter{it.status==MultifyShadowStatus.CLOSED&&it.closedAt>0L&&multifyDate(it.closedAt)==d}.sumOf{it.netPnl}
            if(d==today)closed+unrealized else closed
        }
        val fiveAvg=if(daily.isEmpty())0.0 else daily.average()
        val exitRows=events.filter{it.eventType==MultifyEventType.EXIT&&it.evaluatedAt>0L&&it.instrumentClass==MultifyInstrumentClass.EQUITY}
        val exitWins=exitRows.count{it.postExitFall5mPct>=0.20||it.postExitFall15mPct>=0.35}
        val net=realized+unrealized
        val band=when{
            net>=MULTIFY_DAILY_NET_TARGET->"TARGET EXCEEDED"
            net>=2_500.0->"BELOW TARGET"
            else->"FORENSIC ZONE"
        }
        return MultifyDashboard(
            capitalBudget=MULTIFY_CAPITAL_BUDGET,dailyNetTarget=MULTIFY_DAILY_NET_TARGET,todayRealizedNet=realized,todayUnrealizedNet=unrealized,todayNet=net,
            openExposure=exposure,availableCapital=(MULTIFY_CAPITAL_BUDGET-exposure).coerceAtLeast(0.0),openTrades=open.size,closedTradesToday=closedToday.size,alertsToday=todayEvents,
            fiveSessionAverageNet=fiveAvg,daysAtOrAboveTarget=daily.count{it>=MULTIFY_DAILY_NET_TARGET},exitFallSamples=exitRows.size,exitFallWins=exitWins,
            exitFallRatePct=if(exitRows.isEmpty())0.0 else exitWins*100.0/exitRows.size,targetBand=band,lastDecision=multifyTrading.latestDecision(),
            automationMode=if(prefs.loadSettings().multifyLiveTradingEnabled)"REAL ORDERS ARMED • same shadow decisions" else "SHADOW_ONLY"
        )
    }

    private fun buildMultifyStockProfile(symbol:String):MultifyStockProfile{
        if(symbol.isBlank())return MultifyStockProfile("",0,0,0,0.0)
        val rows=multifyTrading.loadTrades(2500).filter{it.symbol==symbol&&it.status==MultifyShadowStatus.CLOSED}
        fun stats(side:MultifyShadowSide):List<MultifyStrategyStat> = rows.filter{it.side==side}.groupBy{it.strategyTag}.map{(tag,list)->
            val wins=list.count{it.netPnl>0.0};val losses=list.count{it.netPnl<=0.0};val rawWin=if(list.isEmpty())0.0 else wins*100.0/list.size
            val avg=list.map{multifyEngine.directionalReturn(it.side,it.entryPrice,it.exitPrice)}.average().takeIf{it.isFinite()}?:0.0
            // Beta(2,2) shrinkage prevents tiny samples from becoming overconfident. A 20-session
            // half-life keeps recent stock behaviour relevant without discarding older evidence.
            val bayes=(wins+2.0)/(list.size+4.0)*100.0
            val nowMs=System.currentTimeMillis();val recencyNet=list.sumOf{t->
                val ageDays=((nowMs-(t.closedAt.takeIf{it>0L}?:t.openedAt)).coerceAtLeast(0L))/86_400_000.0
                val weight=kotlin.math.exp(-0.6931471805599453*ageDays/20.0)
                t.netPnl*weight
            }
            MultifyStrategyStat(tag,side,list.size,wins,losses,rawWin,list.sumOf{it.netPnl},avg,bayes,recencyNet)
        }.sortedWith(compareByDescending<MultifyStrategyStat>{if(it.samples>=5&&it.bayesianWinRatePct>=55.0)1 else 0}.thenByDescending{it.bayesianWinRatePct}.thenByDescending{it.recencyWeightedNet}.thenByDescending{it.netPnl})
        val long=stats(MultifyShadowSide.LONG);val short=stats(MultifyShadowSide.SHORT)
        val events=MultifyEventStore.recent(appContext,1000).filter{it.symbol==symbol&&it.eventType==MultifyEventType.EXIT&&it.evaluatedAt>0L}
        val exitWins=events.count{it.postExitFall5mPct>=0.20||it.postExitFall15mPct>=0.35}
        return MultifyStockProfile(
            symbol=symbol,samples=rows.size,wins=rows.count{it.netPnl>0.0},losses=rows.count{it.netPnl<=0.0},netPnl=rows.sumOf{it.netPnl},
            bestLongStrategy=long.firstOrNull{it.samples>=5&&it.bayesianWinRatePct>=55.0}?.strategyTag.orEmpty(),bestShortStrategy=short.firstOrNull{it.samples>=5&&it.bayesianWinRatePct>=55.0}?.strategyTag.orEmpty(),longStats=long.take(8),shortStats=short.take(8),
            exitFallSamples=events.size,exitFallWins=exitWins,avgExitFall5mPct=events.map{it.postExitFall5mPct}.average().takeIf{it.isFinite()}?:0.0,
            avgExitFall15mPct=events.map{it.postExitFall15mPct}.average().takeIf{it.isFinite()}?:0.0
        )
    }

    private suspend fun closeMultifyShadowTrade(row:MultifyShadowTrade,exitPrice:Double,reason:String,now:Long=System.currentTimeMillis()):MultifyShadowTrade{
        val px=exitPrice.takeIf{it.isFinite()&&it>0.0}?:row.lastPrice.takeIf{it>0.0}?:row.entryPrice
        val gross=if(row.side==MultifyShadowSide.LONG)(px-row.entryPrice)*row.quantity else (row.entryPrice-px)*row.quantity
        val costs=(row.entryPrice*row.quantity+px*row.quantity)*MULTIFY_ESTIMATED_COST_RATE_PER_LEG
        val net=gross-costs
        var closed=row.copy(status=MultifyShadowStatus.CLOSED,closedAt=now,exitPrice=px,grossPnl=gross,estimatedCosts=costs,netPnl=net,closeReason=reason,lastPrice=px,lastUpdatedAt=now)
        multifyTrading.upsertTrade(closed)
        DiagnosticLog.log(appContext,"MULTIFY-SHADOW","CLOSE ${closed.side} ${closed.symbol} • ${closed.strategyTag} • gross ₹${"%.0f".format(gross)} • costs ₹${"%.0f".format(costs)} • net ₹${"%+.0f".format(net)} • $reason")
        if(row.liveEntryReference.isNotBlank()&&row.liveExitReference.isBlank()){
            val side=if(row.side==MultifyShadowSide.LONG)"SELL" else "BUY"
            runCatching{placeMultifyLiveOrder(row.symbol,side,row.quantity,px,"AUTO_EXIT:$reason",isExit=true,protectionId=row.liveProtectionId,sourceEventId=row.sourceEventId)}.onSuccess{sub->
                closed=closed.copy(liveExitReference=sub.referenceId,liveExecutionNote="LIVE exit submitted: $reason • brokerQty=${sub.filledQuantity}")
                multifyTrading.upsertTrade(closed)
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","EXIT ${row.side} ${row.symbol} • broker reconciled exit • ref=${sub.referenceId} • $reason")
            }.onFailure{t->
                closed=closed.copy(liveExecutionNote="LIVE EXIT FAILED: ${t.message.orEmpty().take(180)}")
                multifyTrading.upsertTrade(closed)
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","EXIT FAILED ${row.symbol} • broker attention required",t)
            }
        }
        return closed
    }

    private suspend fun openMultifyShadowTrade(eventId:String,decision:MultifyDecision,now:Long=System.currentTimeMillis()):MultifyShadowTrade?{
        if(decision.tier!=MultifyDecisionTier.LIVE||decision.direction==null||decision.price<=0.0)return null
        val all=multifyTrading.loadTrades(2500)
        if(all.any{it.status==MultifyShadowStatus.OPEN&&it.symbol==decision.symbol})return null
        val exposure=all.filter{it.status==MultifyShadowStatus.OPEN}.sumOf{it.entryPrice*it.quantity}
        val available=(MULTIFY_CAPITAL_BUDGET-exposure).coerceAtLeast(0.0)
        if(available<20_000.0)return null
        val desired=when{
            decision.score>=92.0->200_000.0
            decision.score>=85.0->150_000.0
            else->100_000.0
        }
        val allocation=minOf(desired,available)
        val capitalQty=(allocation/decision.price).toInt().coerceAtLeast(0)
        val riskBudget=when{decision.score>=92.0->1_500.0;decision.score>=85.0->1_250.0;else->1_000.0}
        val riskPerShare=decision.price*0.007
        val riskQty=(riskBudget/riskPerShare).toInt().coerceAtLeast(0)
        val qty=minOf(capitalQty,riskQty)
        if(qty<=0)return null
        val today=multifyDate(now);val wave=all.count{it.symbol==decision.symbol&&multifyDate(it.openedAt)==today}+1
        if(wave>6)return null
        val actual=qty*decision.price
        val row=MultifyShadowTrade(
            id="${today}|${decision.symbol}|${decision.direction.name}|$now",sourceEventId=eventId,symbol=decision.symbol,side=decision.direction,strategyTag=decision.strategyTag,contextKey=decision.contextKey,wave=wave,
            openedAt=now,entryPrice=decision.price,quantity=qty,allocatedCapital=actual,score=decision.score,peakPrice=decision.price,troughPrice=decision.price,lastPrice=decision.price,lastUpdatedAt=now
        )
        multifyTrading.upsertTrade(row)
        DiagnosticLog.log(appContext,"MULTIFY-SHADOW","OPEN ${row.side} ${row.symbol} • wave ${row.wave} • qty ${row.quantity} • ₹${"%.0f".format(row.allocatedCapital)} • ${row.strategyTag} • score ${"%.1f".format(row.score)}")
        if(prefs.loadSettings().multifyLiveTradingEnabled){
            val sourcePackage=MultifyEventStore.find(appContext,eventId)?.packageName.orEmpty()
            if(sourcePackage!=MANUAL_MULTIFY_SOURCE&&!MultifyEventStore.sourceTrustedForLive(appContext,sourcePackage)){
                val shadow=row.copy(liveExecutionNote="LIVE BLOCKED: source package is not explicitly trusted for execution")
                multifyTrading.upsertTrade(shadow)
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","ENTRY BLOCKED ${row.symbol} • untrusted source package=$sourcePackage")
                return shadow
            }
            val side=if(row.side==MultifyShadowSide.LONG)"BUY" else "SELL"
            runCatching{placeMultifyLiveOrder(row.symbol,side,row.quantity,row.entryPrice,"AUTO_ENTRY:${row.strategyTag}",sourceEventId=row.sourceEventId)}.onSuccess{sub->
                val live=row.copy(liveEntryReference=sub.referenceId,liveProtectionReference=sub.protectionReference,liveProtectionId=sub.protectionId,liveFilledQuantity=sub.filledQuantity,liveAverageEntryPrice=sub.averagePrice,liveExecutionNote="LIVE entry submitted • fill=${sub.filledQuantity}/${row.quantity} • protection=${sub.protectionId.ifBlank{"PENDING/ATTENTION"}}")
                multifyTrading.upsertTrade(live)
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","ENTRY ${row.side} ${row.symbol} • wave ${row.wave} • intended ${row.quantity} • filled ${sub.filledQuantity} • ref=${sub.referenceId} • protection=${sub.protectionId}")
                return live
            }.onFailure{t->
                val shadow=row.copy(liveExecutionNote="LIVE ENTRY BLOCKED/FAILED: ${t.message.orEmpty().take(180)}")
                multifyTrading.upsertTrade(shadow)
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","ENTRY BLOCKED/FAILED ${row.symbol} • shadow continues",t)
                return shadow
            }
        }
        return row
    }

    private suspend fun multifyCandles(token:String,symbol:String,now:ZonedDateTime=ZonedDateTime.now(ist)):List<Candle>{
        val open=now.toLocalDate().atTime(LocalTime.of(9,15)).atZone(ist)
        val start=open
        return groww.getHistoricalCandles(token,symbol,start.format(dateTimeFmt),now.plusMinutes(1).format(dateTimeFmt),"1minute")
    }

    suspend fun processMultifyEvent(eventId:String):MultifyDecision?=multifyMutex.withLock{
        val event=MultifyEventStore.find(appContext,eventId)?:return@withLock null
        if(event.instrumentClass!=MultifyInstrumentClass.EQUITY||event.symbol.isBlank()){
            val d=MultifyDecision(event.id,event.symbol,event.eventType,MultifyDecisionTier.NO_TRADE,null,0.0,event.signalPrice,"NON_EQUITY_FILTER","","Only Multify NSE cash-equity events enter this lane")
            multifyTrading.appendDecision(d);MultifyEventStore.update(appContext,event.copy(processedAt=System.currentTimeMillis(),decisionTier=d.tier.name,decisionReason=d.reason));return@withLock d
        }
        val now=ZonedDateTime.now(ist);val session=marketSessionInfo(now)
        if(!session.isOpen){
            val d=MultifyDecision(event.id,event.symbol,event.eventType,MultifyDecisionTier.WATCH,null,0.0,event.signalPrice,"SESSION_WAIT","","Captured for learning; immediate intraday analysis waits for NSE regular session")
            multifyTrading.appendDecision(d);MultifyEventStore.update(appContext,event.copy(processedAt=System.currentTimeMillis(),decisionTier=d.tier.name,decisionReason=d.reason));return@withLock d
        }
        require(ensureAutomationAuthentication()){ "Groww authentication is required for Multify market analysis" }
        val token=accessToken()
        val universe=if(instruments.cached().isEmpty())instruments.refresh() else instruments.cached()
        val inst=universe.firstOrNull{it.tradingSymbol.equals(event.symbol,true)}
        if(inst==null||!ExecutionQuality.eligibleInstrument(inst)){
            val d=MultifyDecision(event.id,event.symbol,event.eventType,MultifyDecisionTier.NO_TRADE,null,0.0,event.signalPrice,"EQUITY_MASTER_REJECT","","Symbol is not an eligible NSE CASH EQ instrument")
            multifyTrading.appendDecision(d);MultifyEventStore.update(appContext,event.copy(processedAt=System.currentTimeMillis(),decisionTier=d.tier.name,decisionReason=d.reason));return@withLock d
        }
        val quote=groww.getQuote(token,event.symbol,fresh=true)
        MultifyEventStore.markLatency(appContext,event.id,quoteReceivedAt=System.currentTimeMillis())
        val candles=runCatching{multifyCandles(token,event.symbol,now)}.getOrDefault(emptyList())
        val openOpposite=multifyTrading.loadTrades(2500).firstOrNull{it.status==MultifyShadowStatus.OPEN&&it.symbol==event.symbol}
        when(event.eventType){
            MultifyEventType.EXIT->{if(openOpposite?.side==MultifyShadowSide.LONG)closeMultifyShadowTrade(openOpposite,quote.lastPrice,"MULTIFY_EXIT",System.currentTimeMillis())}
            MultifyEventType.ENTRY_LONG->{if(openOpposite?.side==MultifyShadowSide.SHORT)closeMultifyShadowTrade(openOpposite,quote.lastPrice,"NEW_MULTIFY_LONG",System.currentTimeMillis())}
            MultifyEventType.ENTRY_SHORT->{if(openOpposite?.side==MultifyShadowSide.LONG)closeMultifyShadowTrade(openOpposite,quote.lastPrice,"NEW_MULTIFY_SHORT",System.currentTimeMillis())}
            else->{}
        }
        val profile=buildMultifyStockProfile(event.symbol)
        var d=multifyEngine.decide(event.id,event.symbol,event.eventType,quote,candles,profile,event.capturedAt/1000L)
        if(d.direction==MultifyShadowSide.SHORT&&!inst.sellAllowed){d=d.copy(tier=MultifyDecisionTier.NO_TRADE,reason=d.reason+" • short not intraday-eligible")}
        multifyTrading.appendDecision(d)
        val decisionDone=System.currentTimeMillis()
        MultifyEventStore.update(appContext,(MultifyEventStore.find(appContext,event.id)?:event).copy(processedAt=decisionDone,decisionCompletedAt=decisionDone,decisionTier=d.tier.name,decisionDirection=d.direction?.name.orEmpty(),decisionScore=d.score,decisionPrice=d.price,decisionStrategy=d.strategyTag,decisionReason=d.reason))
        if(d.tier==MultifyDecisionTier.LIVE)openMultifyShadowTrade(event.id,d)
        AppNotifier.notifyMultifyDecision(appContext,d)
        DiagnosticLog.log(appContext,"MULTIFY-DECISION","${event.symbol} • ${event.eventType} → ${d.tier} ${d.direction?:"NONE"} • ${"%.1f".format(d.score)} • ${d.strategyTag} • notification→decision=${System.currentTimeMillis()-event.capturedAt}ms • ${d.reason}")
        d
    }

    suspend fun monitorMultifyShadowLane():Int=multifyMutex.withLock{
        val nowZ=ZonedDateTime.now(ist);val session=marketSessionInfo(nowZ);if(!session.isOpen)return@withLock 0
        if(!ensureAutomationAuthentication())return@withLock 0
        val token=accessToken();val now=System.currentTimeMillis();var changed=0
        val universe=if(instruments.cached().isEmpty())runCatching{instruments.refresh()}.getOrDefault(emptyList()) else instruments.cached()
        val qCache=mutableMapOf<String,Quote>();val cCache=mutableMapOf<String,List<Candle>>()
        suspend fun quote(symbol:String):Quote{qCache[symbol]?.let{return it};return groww.getQuote(token,symbol,fresh=true).also{qCache[symbol]=it}}
        suspend fun candles(symbol:String):List<Candle>{cCache[symbol]?.let{return it};return runCatching{multifyCandles(token,symbol,nowZ)}.getOrDefault(emptyList()).also{cCache[symbol]=it}}

        val openRows=multifyTrading.loadTrades(2500).filter{it.status==MultifyShadowStatus.OPEN}
        for(row in openRows){
            val px=runCatching{quote(row.symbol)}.getOrNull()?:continue;val bars=candles(row.symbol)
            val currentRet=multifyEngine.directionalReturn(row.side,row.entryPrice,px.lastPrice)
            val peak=maxOf(row.peakPrice,px.lastPrice);val trough=minOf(row.troughPrice,px.lastPrice)
            val mfe=maxOf(row.mfePct,currentRet);val mae=minOf(row.maePct,currentRet)
            val updated=row.copy(lastPrice=px.lastPrice,lastUpdatedAt=now,peakPrice=peak,troughPrice=trough,mfePct=mfe,maePct=mae)
            multifyTrading.upsertTrade(updated)
            if(nowZ.toLocalTime()>=LocalTime.of(15,20)){
                closeMultifyShadowTrade(updated,px.lastPrice,"INTRADAY_CUTOFF",now);changed++;continue
            }
            val close=multifyEngine.shouldClose(updated,px,bars,now)
            if(close!=null){
                closeMultifyShadowTrade(updated,px.lastPrice,close.first,now);changed++
                val reversal=close.second
                if(reversal!=null && now-updated.openedAt>=2L*60_000L){
                    val profile=buildMultifyStockProfile(row.symbol);val wave=multifyEngine.waveDecision("wave:${row.sourceEventId}:$now",row.symbol,px,bars,profile)
                    val adjusted=if(wave.direction==reversal)wave else wave.copy(tier=MultifyDecisionTier.DEVELOPING,reason=wave.reason+" • reversal side not yet confirmed")
                    multifyTrading.appendDecision(adjusted)
                    if(adjusted.tier==MultifyDecisionTier.LIVE&&openMultifyShadowTrade(row.sourceEventId,adjusted,now)!=null)changed++
                }
            }
        }

        // Continue learning between Multify entry and exit: after a completed wave, keep the symbol on
        // a short-lived intraday watch so a fresh LONG/SHORT wave can be shadowed without waiting for a new alert.
        val today=nowZ.toLocalDate();val events=MultifyEventStore.recent(appContext,1000).filter{it.instrumentClass==MultifyInstrumentClass.EQUITY&&it.symbol.isNotBlank()&&multifyDate(it.capturedAt)==today}
        val activeSymbols=events.groupBy{it.symbol}.mapValues{(_,v)->v.maxByOrNull{it.capturedAt}!!}
        val all=multifyTrading.loadTrades(2500)
        for((symbol,lastEvent) in activeSymbols){
            if(all.any{it.status==MultifyShadowStatus.OPEN&&it.symbol==symbol})continue
            val lastTrade=all.filter{it.symbol==symbol&&it.status==MultifyShadowStatus.CLOSED}.maxByOrNull{it.closedAt}?:continue
            if(now-lastEvent.capturedAt>120L*60_000L)continue
            val cooldown=if(lastTrade.closeReason.startsWith("HARD_STOP"))10L*60_000L else 3L*60_000L
            if(now-lastTrade.closedAt<cooldown)continue
            if(all.count{it.symbol==symbol&&multifyDate(it.openedAt)==today}>=6)continue
            val inst=universe.firstOrNull{it.tradingSymbol==symbol&&ExecutionQuality.eligibleInstrument(it)}?:continue
            val px=runCatching{quote(symbol)}.getOrNull()?:continue;val bars=candles(symbol);val profile=buildMultifyStockProfile(symbol)
            var d=multifyEngine.waveDecision("wave:${lastEvent.id}:$now",symbol,px,bars,profile)
            if(d.direction==MultifyShadowSide.SHORT&&!inst.sellAllowed)d=d.copy(tier=MultifyDecisionTier.NO_TRADE,reason=d.reason+" • short not intraday-eligible")
            multifyTrading.appendDecision(d)
            if(d.tier==MultifyDecisionTier.LIVE&&openMultifyShadowTrade(lastEvent.id,d,now)!=null)changed++
        }
        changed
    }

    suspend fun exitAllMultifyPositions():Int=multifyMutex.withLock{
        if(!ensureAutomationAuthentication())return@withLock 0
        val token=accessToken();val all=multifyTrading.loadTrades(2500)
        val fromTrades=all.filter{it.liveEntryReference.isNotBlank()}.map{it.symbol}
        val fromBroker=prefs.loadBrokerOrders(500).filter{it.product=="MIS"&&it.referenceId.firstOrNull() in setOf('M','X','F','P')}.map{it.symbol}
        val managedSymbols=(fromTrades+fromBroker).filter{it.isNotBlank()}.toSet()
        if(managedSymbols.isEmpty())return@withLock 0
        val positions=runCatching{groww.getPositions(token,"CASH")}.getOrDefault(emptyList()).filter{it.product=="MIS"&&it.quantity!=0&&it.tradingSymbol in managedSymbols}
        var closed=0
        for(pos in positions){
            val row=all.firstOrNull{it.symbol==pos.tradingSymbol&&it.status==MultifyShadowStatus.OPEN&&it.liveEntryReference.isNotBlank()}
            val side=if(pos.quantity>0)"SELL" else "BUY"
            val signal=runCatching{groww.getQuote(token,pos.tradingSymbol,fresh=true).lastPrice}.getOrDefault(row?.lastPrice?:pos.netPrice)
            val sub=try{
                placeMultifyLiveOrder(pos.tradingSymbol,side,abs(pos.quantity),signal,"USER_EXIT_ALL",isExit=true,protectionId=row?.liveProtectionId.orEmpty(),sourceEventId=row?.sourceEventId.orEmpty())
            }catch(t:Throwable){
                DiagnosticLog.log(appContext,"MULTIFY-LIVE","EXIT ALL broker close failed • ${pos.tradingSymbol}",t)
                continue
            }
            if(row!=null){
                val px=signal.takeIf{it>0.0}?:row.lastPrice
                val gross=if(row.side==MultifyShadowSide.LONG)(px-row.entryPrice)*row.quantity else (row.entryPrice-px)*row.quantity
                val costs=(row.entryPrice*row.quantity+px*row.quantity)*MULTIFY_ESTIMATED_COST_RATE_PER_LEG
                multifyTrading.upsertTrade(row.copy(status=MultifyShadowStatus.CLOSED,closedAt=System.currentTimeMillis(),exitPrice=px,grossPnl=gross,estimatedCosts=costs,netPnl=gross-costs,closeReason="USER_EXIT_ALL",lastPrice=px,lastUpdatedAt=System.currentTimeMillis(),liveExitReference=sub.referenceId,liveExecutionNote="Broker-based emergency flatten submitted"))
            }
            closed++
        }
        DiagnosticLog.log(appContext,"MULTIFY-LIVE","EXIT ALL reconciled against Groww positions • $closed broker positions processed")
        closed
    }

    private suspend fun counterfactualMultifyReplay(rows:List<MultifyShadowTrade>):String{
        if(rows.isEmpty()||!ensureAutomationAuthentication())return ""
        val token=accessToken()
        data class Rule(val name:String,val stopPct:Double,val targetPct:Double,val trailTrigger:Double,val trailGiveback:Double,val entryDelayMinutes:Int=0,val requireConfirm:Boolean=false)
        val rules=listOf(
            Rule("BASE",0.70,1.60,0.75,0.38),
            Rule("FAST_CAPTURE",0.50,0.90,0.55,0.28),
            Rule("CONFIRM_1M",0.60,1.20,0.70,0.32,entryDelayMinutes=1,requireConfirm=true),
            Rule("BALANCED_DELAY_1M",0.60,1.30,0.75,0.34,entryDelayMinutes=1),
            Rule("RUNNER_DELAY_2M",0.90,2.00,1.00,0.42,entryDelayMinutes=2,requireConfirm=true)
        )
        val totals=linkedMapOf<String,Double>().apply{rules.forEach{put(it.name,0.0)}}
        var tested=0
        for(row in rows.take(24)){
            val opened=Instant.ofEpochMilli(row.openedAt).atZone(ist)
            val end=minOf(opened.plusMinutes(60),opened.toLocalDate().atTime(15,20).atZone(ist))
            val bars=runCatching{groww.getHistoricalCandles(token,row.symbol,opened.minusMinutes(1).format(dateTimeFmt),end.format(dateTimeFmt),"1minute")}.getOrDefault(emptyList())
                .filter{it.epochSeconds*1000L>=row.openedAt-60_000L}.sortedBy{it.epochSeconds}
            if(bars.isEmpty())continue
            tested++
            for(rule in rules){
                val eligibleBars=bars.filter{it.epochSeconds*1000L>=row.openedAt+rule.entryDelayMinutes*60_000L}
                val first=eligibleBars.firstOrNull()?:continue
                val replayEntry=first.open.takeIf{it>0.0}?:first.close
                val confirms=!rule.requireConfirm || if(row.side==MultifyShadowSide.LONG)first.close>=first.open else first.close<=first.open
                if(!confirms){totals[rule.name]=(totals[rule.name]?:0.0);continue}
                var peak=0.0;var exit=replayEntry;var closed=false
                for(b in eligibleBars){
                    val favorable=if(row.side==MultifyShadowSide.LONG)(b.high/replayEntry-1.0)*100.0 else (replayEntry/b.low-1.0)*100.0
                    val adverse=if(row.side==MultifyShadowSide.LONG)(b.low/replayEntry-1.0)*100.0 else (replayEntry/b.high-1.0)*100.0
                    // Conservative intrabar ordering: if both stop and target are touched, assume stop first.
                    if(adverse<=-rule.stopPct){exit=if(row.side==MultifyShadowSide.LONG)replayEntry*(1-rule.stopPct/100.0) else replayEntry*(1+rule.stopPct/100.0);closed=true;break}
                    if(peak>=rule.trailTrigger && peak-favorable>=rule.trailGiveback){
                        val locked=(peak-rule.trailGiveback).coerceAtLeast(0.0)
                        exit=if(row.side==MultifyShadowSide.LONG)replayEntry*(1+locked/100.0) else replayEntry*(1-locked/100.0);closed=true;break
                    }
                    peak=maxOf(peak,favorable)
                    if(favorable>=rule.targetPct){exit=if(row.side==MultifyShadowSide.LONG)replayEntry*(1+rule.targetPct/100.0) else replayEntry*(1-rule.targetPct/100.0);closed=true;break}
                    exit=b.close
                }
                if(!closed&&exit<=0.0)exit=replayEntry
                val gross=if(row.side==MultifyShadowSide.LONG)(exit-replayEntry)*row.quantity else (replayEntry-exit)*row.quantity
                val costs=(replayEntry*row.quantity+exit*row.quantity)*MULTIFY_ESTIMATED_COST_RATE_PER_LEG
                totals[rule.name]=(totals[rule.name]?:0.0)+(gross-costs)
            }
        }
        if(tested<3)return "counterfactual: insufficient comparable trades ($tested)"
        val ranked=totals.entries.sortedByDescending{it.value}
        val best=ranked.first()
        return "counterfactual n=$tested • challenger ${best.key} ₹${"%+.0f".format(best.value)} • "+ranked.joinToString(" | "){"${it.key}=₹${"%+.0f".format(it.value)}"}+" • research-only, no auto-promotion"
    }

    suspend fun runMultifyForensicReplay():String{
        val evaluated=runCatching{replayMultifyEvents(250)}.getOrDefault(0)
        val dash=buildMultifyDashboard();val today=LocalDate.now(ist);val closed=multifyTrading.loadTrades(2500).filter{it.status==MultifyShadowStatus.CLOSED&&it.closedAt>0L&&multifyDate(it.closedAt)==today}
        val strategyLines=closed.groupBy{it.strategyTag}.map{(tag,rows)->
            val net=rows.sumOf{it.netPnl};val wins=rows.count{it.netPnl>0.0};"$tag n=${rows.size} wins=$wins net=₹${"%+.0f".format(net)}"
        }.sortedByDescending{line->Regex("net=₹([+-]?\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?:0}
        val profiles=MultifyEventStore.recent(appContext,1000).filter{it.instrumentClass==MultifyInstrumentClass.EQUITY&&it.symbol.isNotBlank()}.map{it.symbol}.distinct().take(60).map{buildMultifyStockProfile(it)}.filter{it.samples>0}.sortedByDescending{it.netPnl}.take(12)
        val mode=when{
            dash.todayNet>=MULTIFY_DAILY_NET_TARGET->"TARGET EXCEEDED • normal learning"
            dash.todayNet>=2_500.0->"BELOW TARGET • optimization replay"
            else->"FORENSIC ZONE • full replay review"
        }
        val counterfactual=if(dash.todayNet<MULTIFY_DAILY_NET_TARGET)runCatching{counterfactualMultifyReplay(closed)}.getOrDefault("") else ""
        val msg=buildString{
            append("$mode • shadow net ₹${"%+.0f".format(dash.todayNet)} / target ₹${MULTIFY_DAILY_NET_TARGET.toInt()} • events evaluated $evaluated")
            if(strategyLines.isNotEmpty())append(" • strategies "+strategyLines.take(5).joinToString(" | "))
            if(profiles.isNotEmpty())append(" • stock champions "+profiles.joinToString(" | "){p->"${p.symbol}:L=${p.bestLongStrategy.ifBlank{"-"}},S=${p.bestShortStrategy.ifBlank{"-"}},net=₹${"%+.0f".format(p.netPnl)}"})
            if(counterfactual.isNotBlank())append(" • $counterfactual")
        }
        DiagnosticLog.log(appContext,"MULTIFY-NIGHTLY",msg)
        return msg
    }

    fun multifyLearningReport():String=buildString{
        val dash=buildMultifyDashboard();val trades=multifyTrading.loadTrades(2500);val decisions=multifyTrading.loadDecisions(1000);val events=MultifyEventStore.recent(appContext,1000)
        appendLine("--- MULTIFY INTRADAY LAB ---")
        appendLine("capitalBudget=₹${MULTIFY_CAPITAL_BUDGET.toInt()} dailyNetTarget=₹${MULTIFY_DAILY_NET_TARGET.toInt()} mode=${dash.automationMode}")
        appendLine("todayNet=₹${"%+.2f".format(dash.todayNet)} realized=₹${"%+.2f".format(dash.todayRealizedNet)} unrealized=₹${"%+.2f".format(dash.todayUnrealizedNet)} openExposure=₹${"%.2f".format(dash.openExposure)} targetBand=${dash.targetBand}")
        appendLine("fiveSessionAverage=₹${"%+.2f".format(dash.fiveSessionAverageNet)} targetDays=${dash.daysAtOrAboveTarget}/5 exitFall=${dash.exitFallWins}/${dash.exitFallSamples} (${"%.1f".format(dash.exitFallRatePct)}%)")
        appendLine("events=${events.size} decisions=${decisions.size} shadowTrades=${trades.size}")
        appendLine("Recent decisions:")
        decisions.take(80).forEach{appendLine(it.toString())}
        appendLine("Recent shadow trades:")
        trades.take(120).forEach{appendLine(it.toString())}
        appendLine("Stock profiles:")
        events.map{it.symbol}.filter{it.isNotBlank()}.distinct().take(80).map{buildMultifyStockProfile(it)}.filter{it.samples>0||it.exitFallSamples>0}.forEach{appendLine(it.toString())}
    }


    suspend fun replayMultifyEvents(limit:Int=40):Int{
        if(limit<=0||!ensureAutomationAuthentication())return 0
        val nowMs=System.currentTimeMillis();val token=accessToken()
        val pending=MultifyEventStore.recent(appContext,1000).filter{
            it.evaluation=="PENDING"&&it.symbol.isNotBlank()&&it.instrumentClass!=MultifyInstrumentClass.DERIVATIVE_OR_NON_EQUITY&&it.eventType!=MultifyEventType.UNKNOWN&&nowMs-it.capturedAt>=15L*60_000L
        }.sortedBy{it.capturedAt}.take(limit)
        var done=0
        pending.forEach{event->
            val at=Instant.ofEpochMilli(event.capturedAt).atZone(ist)
            val session=NseTradingCalendar2026.phase(at)
            if(!session.tradingDate||at.toLocalTime()<NseTradingCalendar2026.open||at.toLocalTime()>LocalTime.of(15,25)){
                MultifyEventStore.update(appContext,event.copy(evaluatedAt=nowMs,evaluation="OUT_OF_SESSION"));done++
                return@forEach
            }
            val end=minOf(at.plusMinutes(35),at.toLocalDate().atTime(15,30).atZone(ist))
            val bars=runCatching{groww.getHistoricalCandles(token,event.symbol,at.minusMinutes(2).format(dateTimeFmt),end.format(dateTimeFmt),"1minute")}.getOrNull().orEmpty()
                .filter{it.epochSeconds*1000L>=event.capturedAt-60_000L}.sortedBy{it.epochSeconds}
            if(bars.isEmpty())return@forEach
            val entry=event.signalPrice.takeIf{it>0.0}?:bars.first().open.takeIf{it>0.0}?:bars.first().close
            if(entry<=0.0)return@forEach
            fun closeAt(minutes:Int):Double{
                val target=event.capturedAt+minutes*60_000L
                return bars.filter{it.epochSeconds*1000L<=target}.lastOrNull()?.close?:bars.first().close
            }
            val px1=closeAt(1);val px3=closeAt(3);val px5=closeAt(5);val px15=closeAt(15)
            val shortHypothesis=event.eventType in setOf(MultifyEventType.ENTRY_SHORT,MultifyEventType.EXIT)
            fun ret(px:Double)=if(shortHypothesis)(entry-px)/entry*100.0 else (px-entry)/entry*100.0
            val r1=ret(px1);val r3=ret(px3);val r5=ret(px5);val r15=ret(px15)
            val cutoff=event.capturedAt+15L*60_000L
            val first15=bars.filter{it.epochSeconds*1000L<=cutoff}.ifEmpty{bars.take(15)}
            val high=first15.maxOf{it.high};val low=first15.minOf{it.low}
            val mfe=if(shortHypothesis)(entry-low)/entry*100.0 else (high-entry)/entry*100.0
            val mae=if(shortHypothesis)(entry-high)/entry*100.0 else (low-entry)/entry*100.0
            val fall5=if(event.eventType==MultifyEventType.EXIT)((entry-px5)/entry*100.0) else 0.0
            val fall15=if(event.eventType==MultifyEventType.EXIT)((entry-px15)/entry*100.0) else 0.0
            val label=if(event.eventType==MultifyEventType.EXIT){
                when{fall5>=0.20||fall15>=0.35->"EXIT_FALL_EDGE";fall15<=-0.25->"EXIT_REBOUND";else->"EXIT_MIXED"}
            }else when{
                r15>=0.25&&mfe>=0.50->"EDGE_POSITIVE"
                r15<=-0.25->"EDGE_NEGATIVE"
                else->"MIXED"
            }
            MultifyEventStore.update(appContext,event.copy(evaluatedAt=nowMs,return1mPct=r1,return3mPct=r3,return5mPct=r5,return15mPct=r15,mfePct=mfe,maePct=mae,postExitFall5mPct=fall5,postExitFall15mPct=fall15,evaluation=label));done++
        }
        if(done>0)DiagnosticLog.log(appContext,"MULTIFY-REPLAY","evaluated $done captured Multify equity events with 1/3/5/15m path")
        return done
    }

    suspend fun runLearningCycle():Map<ScannerSection,SectionAccuracy>{
        val settings=prefs.loadSettings()
        if(settings.learningEnabled && ensureAutomationAuthentication()){
            evaluateDueOutcomes(accessToken())
            evaluatePendingStrategyOutcomes(accessToken())
            prefs.setLastLearningAt(System.currentTimeMillis())
        }
        prefs.pruneMemory(settings.memoryRetentionDays)
        return accuracies()
    }

    suspend fun runAutonomousLearningPass(force:Boolean=false):String{
        val settings=prefs.loadSettings();if(!settings.learningEnabled)return "Learning disabled"
        val now=System.currentTimeMillis();val last=prefs.lastAutonomousLearningAt()
        if(!force&&last>0&&now-last<14L*60_000L)return "Learning pass not due"
        val sessionOpen=marketSessionInfo(ZonedDateTime.now(ist)).isOpen
        val auth=ensureAutomationAuthentication()
        if(auth){
            runCatching{closeExpiredStrategyCalls()}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Strategy ledger reconciliation failed",it)}
            runCatching{reconcileTradeCallLedger()}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Trade call ledger reconciliation failed",it)}
            runCatching{reconcileRejectedShadows()}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Rejected-candidate shadow reconciliation failed",it)}
            runCatching{resolveChallengerShadows()}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Challenger shadow resolution failed",it)}
            runCatching{runLearningCycle()}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Outcome evaluation failed",it)}
            val autopsyBudget=if(sessionOpen)10 else 30
            runCatching{runPostTradeAutopsies(autopsyBudget)}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Post-trade autopsy failed",it)}
            val multifyBudget=if(sessionOpen)8 else 40
            runCatching{replayMultifyEvents(multifyBudget)}.onFailure{DiagnosticLog.log(appContext,"LEARNING15M","Multify replay failed",it)}
        }
        maybeAdaptDailySettings()
        val uc=prefs.sectionAccuracy(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION)
        val pressure=prefs.sectionAccuracy(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION)
        val strategySummary=prefs.loadStrategySummary();val live=prefs.loadStrategyLive().size;val closed=prefs.loadStrategyClosed(500)
        val globalLive=prefs.loadGlobalLeadSummary()?.candidates.orEmpty().count{it.action==GlobalLeadAction.ENTER_AFTER_OPEN||it.action==GlobalLeadAction.KEEP_NEXT_SESSION||it.action==GlobalLeadAction.NEXT_OPEN_WATCH}
        val ledger=prefs.loadTradeCalls(1500);val ucDone=ledger.filter{it.engine==TradeCallEngine.UPPER_CIRCUIT&&(it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS)};val prDone=ledger.filter{it.engine==TradeCallEngine.PRESSURE&&(it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS)};val glDone=ledger.filter{it.engine==TradeCallEngine.GLOBAL&&it.bucket==TradeCallBucket.LIVE&&(it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS)}
        val champions=strategySummary?.performances?.count{it.status==StrategyStatus.CHAMPION}?:0
        fun wl(x:List<TradeCallRecord>)="${x.count{it.outcome==TradeCallOutcome.WIN}}/${x.size}"
        val autopsies=prefs.loadAutopsies(800);val lossesExplained=autopsies.count{it.originalOutcome=="LOSS"&&it.dominantCause!=AutopsyCause.NO_DOMINANT_CAUSE}
        val rejected=prefs.loadRejectedShadows(2500);val missed=rejected.count{it.outcome==RejectedShadowOutcome.WOULD_WIN}
        val multify=multifyEvents(400);val multifyEvaluated=multify.count{it.evaluation!="PENDING"};val multifyDash=buildMultifyDashboard()
        val mode=if(sessionOpen)"LIVE" else "OFF-HOURS DEEP"
        val msg="$mode • auth=$auth • UC calls ${wl(ucDone)} • Pressure calls ${wl(prDone)} • Strategies live=$live closed=${closed.size} champions=$champions • Global active=$globalLive calls ${wl(glDone)} • Multify ${multify.size}/$multifyEvaluated evaluated shadow=₹${"%+.0f".format(multifyDash.todayNet)}/${MULTIFY_DAILY_NET_TARGET.toInt()} • autopsies=${autopsies.size} loss-diagnostics=$lossesExplained • rejected-shadow=${rejected.size} missed-winners=$missed"
        prefs.setLastAutonomousLearningAt(now)
        DiagnosticLog.log(appContext,"LEARNING15M",msg)
        backupLearningVaultIfDue()
        return msg
    }

    private fun maybeAdaptDailySettings(){
        val today=LocalDate.now(ist).toString();if(prefs.lastAutoTuneDate()==today)return
        val current=prefs.loadSettings();val uc=prefs.sectionAccuracy(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION);val pr=prefs.sectionAccuracy(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION)
        val cutoff=System.currentTimeMillis()-24L*60*60*1000;val ledger=prefs.loadTradeCalls(1500)
        fun callStats(engine:TradeCallEngine):Pair<Int,Double>{val d=ledger.filter{it.engine==engine&&(it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS)&&it.closedAt>=cutoff};return d.size to if(d.isEmpty())0.0 else d.count{it.outcome==TradeCallOutcome.WIN}*100.0/d.size}
        fun tune(base:Double,a:SectionAccuracy,engine:TradeCallEngine,lo:Double,hi:Double):Double{
            val(n,acc)=callStats(engine);val samples=if(n>0)n else a.last24hEvaluated;val accuracy=if(n>0)acc else a.last24hAccuracyPct
            return when{samples>=3&&accuracy<45.0->(base+2.0).coerceAtMost(hi);samples>=5&&accuracy>=75.0->(base-1.0).coerceAtLeast(lo);else->base}
        }
        val ucBase=tune(current.minScore,uc,TradeCallEngine.UPPER_CIRCUIT,60.0,84.0);val prBase=tune(current.demandMinScore,pr,TradeCallEngine.PRESSURE,62.0,86.0)
        val ucAutopsy=prefs.autopsyThresholdAdjustment(TradeCallEngine.UPPER_CIRCUIT.name);val prAutopsy=prefs.autopsyThresholdAdjustment(TradeCallEngine.PRESSURE.name)
        // Calibration can tighten/relax only after an unseen-period walk-forward pass is stable.
        val ucCalibration=prefs.calibrationThresholdAdjustment(TradeCallEngine.UPPER_CIRCUIT.name);val prCalibration=prefs.calibrationThresholdAdjustment(TradeCallEngine.PRESSURE.name)
        val tuned=current.copy(minScore=(ucBase+ucAutopsy+ucCalibration).coerceIn(60.0,84.0),demandMinScore=(prBase+prAutopsy+prCalibration).coerceIn(62.0,86.0))
        if(tuned!=current){prefs.saveSettings(tuned);DiagnosticLog.log(appContext,"AUTOTUNE","UC ${current.minScore}->${tuned.minScore} (autopsy ${"%+.1f".format(ucAutopsy)}, calibration ${"%+.1f".format(ucCalibration)}) • Pressure ${current.demandMinScore}->${tuned.demandMinScore} (autopsy ${"%+.1f".format(prAutopsy)}, calibration ${"%+.1f".format(prCalibration)})")}
        prefs.setLastAutoTuneDate(today)
    }

    fun weeklyLearningReport():String=buildString{
        val uc=prefs.sectionAccuracy(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION);val pr=prefs.sectionAccuracy(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION)
        appendLine("--- LEARNING STATE ---")
        appendLine("UC accuracy: ${uc.hits}/${uc.evaluated} = ${"%.1f".format(uc.accuracyPct)}%")
        appendLine("Pressure accuracy: ${pr.hits}/${pr.evaluated} = ${"%.1f".format(pr.accuracyPct)}%")
        appendLine("Last autonomous learning: ${prefs.lastAutonomousLearningAt()}")
        appendLine("Current settings: ${prefs.loadSettings()}")
        appendLine("Strategy LIVE: ${prefs.loadStrategyLive().size} • CLOSED: ${prefs.loadStrategyClosed(500).size}")
        prefs.loadStrategySummary()?.performances?.sortedByDescending{it.observations}?.take(20)?.forEach{appendLine("Strategy ${it.strategyId} • n=${it.observations} • win=${"%.1f".format(it.accuracyPct)}% • avg=${"%.2f".format(it.avgReturnPct)}% • ${it.status}")}
        appendLine("Global LIVE/NEXT: ${prefs.loadGlobalLeadSummary()?.candidates.orEmpty().size} • CLOSED research signals: ${prefs.loadGlobalLeadClosed(500).size}")
        val multify=multifyEvents(400);val multifyDash=buildMultifyDashboard();appendLine("Multify captured: ${multify.size} • evaluated: ${multify.count{it.evaluation!="PENDING"}} • entry-positive: ${multify.count{it.evaluation=="EDGE_POSITIVE"}} • exit-fall edge: ${multify.count{it.evaluation=="EXIT_FALL_EDGE"}}")
        appendLine("Multify Shadow: today ₹${"%+.2f".format(multifyDash.todayNet)} / target ₹${MULTIFY_DAILY_NET_TARGET.toInt()} • 5-session avg ₹${"%+.2f".format(multifyDash.fiveSessionAverageNet)} • exposure ₹${"%.2f".format(multifyDash.openExposure)} • exit-fall ${multifyDash.exitFallWins}/${multifyDash.exitFallSamples}")
        for(engine in TradeCallEngine.entries){
            val calls=prefs.loadTradeCalls(1500).filter{it.engine==engine};val done=calls.filter{it.outcome==TradeCallOutcome.WIN||it.outcome==TradeCallOutcome.LOSS};val wins=done.count{it.outcome==TradeCallOutcome.WIN};val invalid=calls.count{it.outcome==TradeCallOutcome.INVALID}
            appendLine("${engine.name} calls: open=${calls.count{it.outcome==TradeCallOutcome.OPEN}} scored=${done.size} invalid=$invalid wins=$wins losses=${done.size-wins} accuracy=${if(done.isEmpty())"0.0" else "%.1f".format(wins*100.0/done.size)}%")
        }
        prefs.globalLearningSummary().forEach{appendLine("Global $it")}
        appendLine("--- CALIBRATION + WALK-FORWARD ---")
        for(engine in listOf(TradeCallEngine.UPPER_CIRCUIT.name,TradeCallEngine.PRESSURE.name,TradeCallEngine.GLOBAL.name,"STRATEGY")){
            val wf=prefs.walkForwardValidation(engine);val cal=prefs.confidenceCalibration(engine,80.0)
            appendLine("$engine • 80-score calibrated=${"%.1f".format(cal.calibratedPct)}% bucket=${cal.bucketLabel} n=${cal.observations} reliable=${cal.reliable} • train=${wf.trainObservations}/${"%.1f".format(wf.trainWinRatePct)}% validation=${wf.validationObservations}/${"%.1f".format(wf.validationWinRatePct)}% • ${wf.note}")
        }
        appendLine("--- REJECTED-CANDIDATE SHADOW / MISSED OPPORTUNITIES ---")
        val rejected=prefs.loadRejectedShadows(2500);appendLine("Rejected shadows stored: ${rejected.size} • pending=${rejected.count{it.outcome==RejectedShadowOutcome.PENDING}} • would-win=${rejected.count{it.outcome==RejectedShadowOutcome.WOULD_WIN}} • would-lose=${rejected.count{it.outcome==RejectedShadowOutcome.WOULD_LOSE}}")
        rejected.filter{it.outcome==RejectedShadowOutcome.WOULD_WIN}.take(30).forEach{r->appendLine("MISSED ${r.targetSessionDate} • ${r.engineLabel} • ${r.symbol} • score=${"%.1f".format(r.score)} • ${r.reason} • shadow return ${"%+.2f".format(r.returnPct)}%")}
        appendLine("--- POST-TRADE AUTOPSY ---")
        val autopsies=prefs.loadAutopsies(800);appendLine("Autopsies stored: ${autopsies.size}")
        autopsies.take(30).forEach{a->
            appendLine("${a.sessionDate} • ${a.engineLabel} • ${a.symbol} • ${a.originalOutcome} ${"%+.2f".format(a.originalReturnPct)}% • regime=${a.regime} • cause=${a.dominantCause} ${"%.0f".format(a.causeConfidencePct)}%")
            a.newsEvidence.take(2).forEach{appendLine("  news: $it")};a.shadowResults.take(5).forEach{x->appendLine("  shadow ${x.strategyId}: ${x.outcome} ${"%+.2f".format(x.returnPct)}% • ${x.evidence}")}
            appendLine("  learning: ${a.recommendedRule}")
        }
        appendLine("--- SHADOW STRATEGY LAB ---")
        prefs.shadowLearningSummary(30).forEach{appendLine(it)}
        appendLine("--- V1.5 CHALLENGER LANE ---")
        val ch=prefs.loadChallengerShadows(4000)
        appendLine("Challengers: total=${ch.size} pending=${ch.count{it.outcome==ChallengerShadowOutcome.PENDING}} win=${ch.count{it.outcome==ChallengerShadowOutcome.WIN}} loss=${ch.count{it.outcome==ChallengerShadowOutcome.LOSS}} unresolved=${ch.count{it.outcome==ChallengerShadowOutcome.UNRESOLVED_DATA}}")
        ch.take(40).forEach{appendLine(it.toString())}
        appendLine("--- POINT-IN-TIME EVIDENCE / DECISION AUDIT ---")
        appendLine("PIT evidence="+prefs.loadPointInTimeEvidence(5000).size+" • macro events="+prefs.loadMacroEvents(1000).size+" • decisions="+prefs.loadDecisionSnapshots(3000).size+" • sectorMap="+prefs.sectorMapVersion())
        prefs.loadDecisionSnapshots(50).forEach{appendLine(it.toString())}
        appendLine("--- BROKER RECONCILIATION ---")
        prefs.loadBrokerOrders(100).forEach{appendLine(it.toString())}
    }

    fun endOfDayDiagnosticReport():String=buildString{
        val now=ZonedDateTime.now(ist)
        fun ts(ms:Long):String=if(ms<=0L)"never" else runCatching{Instant.ofEpochMilli(ms).atZone(ist).toString()}.getOrDefault(ms.toString())
        appendLine("=== GLOBAL EDGE END-OF-DAY STATE REPORT ===")
        appendLine("Generated IST: "+now)
        appendLine("Security: credentials/TOTP secret/access token omitted by design")
        appendLine("Authenticated token present: "+accessToken().isNotBlank()+" • expiry="+tokenExpiry())
        appendLine("Market session: "+marketSessionInfo(now))
        appendLine("Settings: "+prefs.loadSettings())
        appendLine()
        appendLine("--- SCHEDULER / DATA HEALTH ---")
        appendLine("lastMarketDataSuccessAt="+ts(prefs.lastMarketDataSuccessAt()))
        appendLine("lastPressureScanAt="+ts(prefs.lastPressureScanAt()))
        appendLine("lastNearCloseAutoScanAt="+ts(prefs.lastNearCloseAutoScanAt()))
        appendLine("lastLearningAt="+ts(prefs.lastLearningAt()))
        appendLine("lastAutonomousLearningAt="+ts(prefs.lastAutonomousLearningAt()))
        appendLine("lastGlobalLeadScanAt="+ts(prefs.lastGlobalLeadScanAt()))
        appendLine("lastGlobalMappingRefreshAt="+ts(prefs.lastGlobalMappingRefreshAt())+" • mappingVersion="+prefs.globalMappingVersion())
        appendLine("lastStrategyAttemptAt="+ts(prefs.lastStrategyAttemptAt()))
        appendLine("lastStrategyScanAt="+ts(prefs.lastStrategyScanAt()))
        appendLine("lastStrategyErrorAt="+ts(prefs.lastStrategyErrorAt())+" • lastStrategyError="+prefs.lastStrategyError())
        appendLine("lastStrategyCatalogRefreshAt="+ts(prefs.lastStrategyCatalogRefreshAt())+" • catalogVersion="+prefs.strategyCatalogVersion())
        appendLine("listingFeedHealth="+prefs.listingFeedHealth())
        appendLine("growwApiHealth="+groww.apiHealthSnapshot())
        appendLine()
        appendLine("--- CURRENT SCAN SUMMARIES ---")
        appendLine("DUAL_SCAN="+(lastSavedDualSummary()?.toString()?:"NONE"))
        appendLine("GLOBAL="+(prefs.loadGlobalLeadSummary()?.toString()?:"NONE"))
        appendLine("STRATEGY="+(prefs.loadStrategySummary()?.toString()?:"NONE"))
        appendLine()
        appendLine("--- MODEL ACCURACY / SIGNAL METRICS ---")
        appendLine("UC="+prefs.sectionAccuracy(ScannerSection.UC_CONTINUATION,SignalEngine.MODEL_VERSION))
        appendLine("PRESSURE="+prefs.sectionAccuracy(ScannerSection.DEMAND_SQUEEZE,DemandSignalEngine.MODEL_VERSION))
        prefs.signalMetrics().forEach{appendLine(it.toString())}
        appendLine()
        appendLine("--- FREEZE HISTORY ---")
        for(section in ScannerSection.entries){
            appendLine("SECTION="+section)
            prefs.freezeHistory(section,100).forEach{appendLine(it.toString())}
        }
        appendLine()
        val calls=prefs.loadTradeCalls(1500)
        appendLine("--- TRADE CALL LEDGER ("+calls.size+") ---")
        calls.sortedBy{it.openedAt}.forEach{appendLine(it.toString())}
        appendLine()
        val rejected=prefs.loadRejectedShadows(2500)
        appendLine("--- REJECTED / SHADOW CANDIDATES ("+rejected.size+") ---")
        rejected.sortedBy{it.capturedAt}.forEach{appendLine(it.toString())}
        appendLine()
        val autopsies=prefs.loadAutopsies(800)
        appendLine("--- POST-TRADE AUTOPSIES ("+autopsies.size+") ---")
        autopsies.forEach{appendLine(it.toString())}
        appendLine()
        val liveStrategy=prefs.loadStrategyLive()
        val closedStrategy=prefs.loadStrategyClosed(2000)
        appendLine("--- STRATEGY LIVE ("+liveStrategy.size+") ---")
        liveStrategy.forEach{appendLine(it.toString())}
        appendLine("--- STRATEGY CLOSED ("+closedStrategy.size+") ---")
        closedStrategy.forEach{appendLine(it.toString())}
        appendLine()
        val globalClosed=prefs.loadGlobalLeadClosed(2000)
        appendLine("--- GLOBAL CLOSED ("+globalClosed.size+") ---")
        globalClosed.forEach{appendLine(it.toString())}
        appendLine()
        appendLine("--- V1.5 EVIDENCE FABRIC ---")
        appendLine("Calendar="+NseTradingCalendar2026.VERSION+" • handbook="+HandbookSynergyEngine.VERSION+" • sectorMap="+prefs.sectorMapVersion())
        appendLine("PIT evidence="+prefs.loadPointInTimeEvidence(5000).size+" • macroEvents="+prefs.loadMacroEvents(1000).size+" • decisions="+prefs.loadDecisionSnapshots(3000).size)
        prefs.loadDecisionSnapshots(200).forEach{appendLine(it.toString())}
        appendLine("--- CHALLENGER SHADOWS ("+prefs.loadChallengerShadows(4000).size+") ---")
        prefs.loadChallengerShadows(4000).forEach{appendLine(it.toString())}
        appendLine("--- BROKER ORDER/FILL RECONCILIATION ("+prefs.loadBrokerOrders(500).size+") ---")
        prefs.loadBrokerOrders(500).forEach{appendLine(it.toString())}
        appendLine("--- NEW LISTINGS CACHE ("+newListingsCache.size+") ---")
        newListingsCache.forEach{appendLine(it.toString())}
    }

    fun trimMemory(){newListingsCache=prefs.loadNewListings();instruments.clearCache();globalMarket.clearCache()}

    private fun loadLastDualFromDisk():DualScanSummary?{val uc=prefs.loadLastScan(ScannerSection.UC_CONTINUATION)?:return null;val d=prefs.loadLastScan(ScannerSection.DEMAND_SQUEEZE)?:return null;return DualScanSummary(uc,d,prefs.loadNewListings())}

    private suspend fun evaluateDueOutcomes(token:String){
        for(section in ScannerSection.entries){for(date in prefs.pendingFrozenDates(section)){
            val key=date.toString();val arr=prefs.getFrozenCandidates(key,section)?:continue;if(arr.length()==0)continue
            val first=arr.optJSONObject(0);val storedVersion=first?.optString("modelVersion").orEmpty().ifBlank{"LEGACY_UNVERSIONED"}
            if(prefs.isOutcomeEvaluated(key,section,storedVersion))continue
            var allProcessed=true
            for(i in 0 until arr.length()){
                val item=arr.optJSONObject(i)?:continue;val symbol=item.optString("symbol");if(symbol.isBlank())continue
                val candles=runCatching{dailyWindow(token,symbol,date)}.getOrNull();if(candles==null){allProcessed=false;continue}
                val exact=selectPredictionAndNext(candles,date);if(exact==null){allProcessed=false;continue};val(predictionDay,nextDay,previousDay)=exact
                val hit=when(section){
                    ScannerSection.DEMAND_SQUEEZE->{val frozenPrice=item.optDouble("price");val target=item.optNullableDouble("targetMovePct")?:prefs.loadSettings().demandSpikeTargetPct;frozenPrice>0&&nextDay.high>=frozenPrice*(1.0+target/100.0)}
                    ScannerSection.UC_CONTINUATION->{val storedUc=item.optDouble("upperCircuit");val bandPct=if(previousDay!=null&&previousDay.close>0&&storedUc>0)(storedUc/previousDay.close-1.0)*100.0 else 0.0;if(bandPct in 1.0..25.0&&predictionDay.close>0){val expectedNextUc=predictionDay.close*(1.0+bandPct/100.0);nextDay.high>=expectedNextUc*0.999}else{val ret=if(predictionDay.close<=0)0.0 else(nextDay.close/predictionDay.close-1.0)*100.0;val closeAtHigh=nextDay.close>0&&abs(nextDay.close-nextDay.high)/nextDay.close<0.0015;ret>=1.8&&closeAtHigh}}
                }
                val idsArray=item.optJSONArray("signalIds")?:JSONArray();val ids=buildList{for(k in 0 until idsArray.length()){val v=idsArray.optString(k);if(v.isNotBlank())add(v)}}
                prefs.updateLearning(section,storedVersion,ids,hit,item.optNullableDouble("targetMovePct"))
            }
            if(allProcessed)prefs.markOutcomeEvaluated(key,section,storedVersion)
        }}
    }


    private suspend fun evaluatePendingStrategyOutcomes(token:String){
        val today=LocalDate.now(ist)
        for(date in prefs.pendingStrategyDates().filter{it<today}){
            val key=date.toString();if(prefs.strategyDateEvaluated(key))continue
            val setups=prefs.pendingStrategySetups(key);if(setups.isEmpty()){prefs.markStrategyDateEvaluated(key);continue}
            var all=true
            for(x in setups){
                val candles=runCatching{dailyWindow(token,x.symbol,date)}.getOrNull();if(candles==null){all=false;continue}
                val dated=candles.map{Instant.ofEpochSecond(it.epochSeconds).atZone(ist).toLocalDate() to it}.sortedBy{it.first}
                val next=dated.firstOrNull{it.first>date}?.second;if(next==null){all=false;continue}
                val win=if(x.direction==TradeDirection.LONG)next.high>=x.entryPrice*(1+x.targetPct/100.0) else next.low<=x.entryPrice*(1-x.targetPct/100.0)
                val ret=if(x.entryPrice<=0)0.0 else if(x.direction==TradeDirection.LONG)(next.close/x.entryPrice-1)*100 else (x.entryPrice/next.close-1)*100
                prefs.updateStrategyResult(x.strategyId,x.strategyName,ret,win)
            }
            if(all)prefs.markStrategyDateEvaluated(key)
        }
    }

    private suspend fun dailyWindow(token:String,symbol:String,date:LocalDate):List<Candle>{val start=date.minusDays(3).atStartOfDay().format(dateTimeFmt);val end=date.plusDays(10).atStartOfDay().format(dateTimeFmt);return groww.getHistoricalCandles(token,symbol,start,end,"1day")}
    private fun selectPredictionAndNext(candles:List<Candle>,date:LocalDate):Triple<Candle,Candle,Candle?>?{val dated=candles.map{Instant.ofEpochSecond(it.epochSeconds).atZone(ist).toLocalDate() to it}.sortedBy{it.first};val prediction=dated.lastOrNull{it.first==date}?:return null;val next=dated.firstOrNull{it.first>date}?:return null;return Triple(prediction.second,next.second,dated.lastOrNull{it.first<date}?.second)}
    private fun JSONObject.optNullableDouble(name:String):Double?{if(!has(name)||isNull(name))return null;val v=optDouble(name,Double.NaN);return v.takeIf{!it.isNaN()}}
}
