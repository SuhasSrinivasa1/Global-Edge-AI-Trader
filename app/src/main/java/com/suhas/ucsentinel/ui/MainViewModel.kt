package com.suhas.globaledgeai.ui

import androidx.lifecycle.*
import com.suhas.globaledgeai.data.repository.GlobalEdgeAITraderRepository
import com.suhas.globaledgeai.domain.model.*
import com.suhas.globaledgeai.notifications.MultifyEvent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job


data class UiState(
    val busy:Boolean=false,val status:String="Ready",val error:String?=null,val authenticated:Boolean=false,val tokenExpiry:String="",
    val credentials:Credentials=Credentials(),val staticIp:String="",val currentPublicIp:String="",val staticIpMatch:Boolean?=null,val staticIpCheckedAt:Long=0L,val settings:AppSettings=AppSettings(),val dualSummary:DualScanSummary?=null,
    val frozenUc:List<Candidate> = emptyList(),val frozenDemand:List<Candidate> = emptyList(),val newListings:List<ListedSecurity> = emptyList(),
    val replayResult:ReplayResult?=null,val universeCount:Int=0,val newsItems:List<NewsItem> = emptyList(),
    val strategyMetrics:List<StrategyMetric> = emptyList(),val accuracies:Map<ScannerSection,SectionAccuracy> = emptyMap(),
    val marketSession:MarketSessionInfo=MarketSessionInfo(MarketPhase.POST_CLOSE,false,false,"Checking market clock…",""),
    val listingFeedHealth:FeedHealth=FeedHealth(),val lastMarketDataSuccessAt:Long=0L,
    val ucFreezeRecord:FreezeRecord=FreezeRecord(ScannerSection.UC_CONTINUATION,"",false),
    val demandFreezeRecord:FreezeRecord=FreezeRecord(ScannerSection.DEMAND_SQUEEZE,"",false),
    val ucFreezeHistory:List<FreezeRecord> = emptyList(),val demandFreezeHistory:List<FreezeRecord> = emptyList(),
    val globalLeadSummary:GlobalLeadSummary?=null,val lastGlobalLeadScanAt:Long=0L,val lastGlobalMappingRefreshAt:Long=0L,val globalMappingVersion:String="",
    val strategyTournamentSummary:StrategyTournamentSummary?=null,val lastStrategyScanAt:Long=0L,val lastStrategyAttemptAt:Long=0L,val lastStrategyErrorAt:Long=0L,val lastStrategyError:String="",val lastStrategyCatalogRefreshAt:Long=0L,val strategyCatalogVersion:String="",
    val strategyLive:List<StrategyRecommendation> = emptyList(),val strategyClosed:List<StrategyRecommendation> = emptyList(),
    val globalClosed:List<GlobalLeadClosedRecord> = emptyList(),val tradeCalls:List<TradeCallRecord> = emptyList(),val tradeAutopsies:List<TradeAutopsyRecord> = emptyList(),
    val challengerShadows:List<ChallengerShadowRecord> = emptyList(),val brokerOrders:List<BrokerOrderRecord> = emptyList(),
    val decisionSnapshots:List<DecisionSnapshot> = emptyList(),val pointInTimeEvidence:List<PointInTimeEvidence> = emptyList(),
    val evidenceFabric:EvidenceFabricSummary?=null,
    val growwApiHealth:GrowwApiHealthSnapshot=GrowwApiHealthSnapshot(),
    val multifyEvents:List<MultifyEvent> = emptyList(),val multifyListenerEnabled:Boolean=false,
    val multifyDashboard:MultifyDashboard=MultifyDashboard(),val multifyShadowTrades:List<MultifyShadowTrade> = emptyList(),
    val multifyDecisions:List<MultifyDecision> = emptyList(),val multifyProfiles:List<MultifyStockProfile> = emptyList()
)

class MainViewModel(private val repo:GlobalEdgeAITraderRepository):ViewModel(){
    private val _state=MutableStateFlow(buildInitialState())
    val state:StateFlow<UiState> = _state.asStateFlow()
    private var lastForegroundMarketScanAt=0L
    private var lastForegroundStrategyScanAt=0L
    private var lastForegroundGlobalScanAt=0L
    private var lastForegroundGovernanceAt=0L
    private var lastBrokerReconcileAt=0L
    private var foregroundMarketJob:Job?=null
    private var foregroundStrategyJob:Job?=null
    private var foregroundGlobalJob:Job?=null
    init{
        repo.ensureTodayFreezeAudit()
        // Keep the visible market clock and persisted background results fresh while the app is open.
        viewModelScope.launch{
            // Android background periodic work has a 15-minute minimum. While the app is open,
            // use a common fast market loop so all four engines wake together from 09:15 IST.
            // Persisted worker results are re-read every few seconds so a notification and its
            // corresponding LIVE card can never drift apart for minutes at a time.
            delay(2_000L)
            while(isActive){
                runCatching{reliabilityRefresh()}
                    .onFailure{t->_state.value=_state.value.copy(error=t.message?.takeIf{it.isNotBlank()})}
                delay(5_000L)
            }
        }
        // v1.1.9: the foreground scheduler used to exist but was never invoked. Keep it in its own
        // coroutine so slow network work never blocks the five-second persisted-state refresh above.
        viewModelScope.launch{
            delay(3_000L)
            while(isActive){
                runCatching{runForegroundAutomation()}
                    .onFailure{t->_state.value=_state.value.copy(error=t.message?.takeIf{it.isNotBlank()})}
                delay(15_000L)
            }
        }
        viewModelScope.launch{
            delay(1_500L)
            while(isActive){
                checkTradingRoute()
                delay(60_000L)
            }
        }
    }

    private suspend fun runForegroundAutomation(){
        if(_state.value.busy)return
        val settings=repo.settings()
        val session=repo.marketSessionInfo()
        val now=System.currentTimeMillis()

        if(now-lastForegroundGovernanceAt>=5L*60_000L){
            lastForegroundGovernanceAt=now
            runCatching{repo.closeExpiredStrategyCalls()}
            runCatching{repo.reconcileTradeCallLedger()}
            runCatching{repo.resolveChallengerShadows()}
        }
        if(now-lastBrokerReconcileAt>=60_000L){
            lastBrokerReconcileAt=now
            runCatching{repo.reconcileBrokerOrders()}
        }

        if(!session.isOpen){
            if(settings.globalLeadEnabled && now-lastForegroundGlobalScanAt>=15L*60_000L){
                lastForegroundGlobalScanAt=now
                runCatching{repo.scanGlobalLead()}.onSuccess{summary->
                    _state.value=_state.value.copy(globalLeadSummary=summary,lastGlobalLeadScanAt=summary.generatedAt,error=null)
                }
            }
            return
        }

        val liveCadenceMs=5L*60_000L
        // v1.6.2 keeps independent jobs but coordinates start times so the three engines do not
        // compete for the same Groww live-data window. UC/Pressure has first priority, then Strategy, then Global.
        if(settings.strategyTournamentEnabled && now-lastForegroundStrategyScanAt>=liveCadenceMs && foregroundStrategyJob?.isActive!=true && foregroundMarketJob?.isActive!=true && foregroundGlobalJob?.isActive!=true && !(settings.autoScanEnabled && now-lastForegroundMarketScanAt>=liveCadenceMs)){
            lastForegroundStrategyScanAt=now
            repo.markStrategyScanAttempt(now)
            foregroundStrategyJob=viewModelScope.launch{
                runCatching{repo.scanTradingStrategies()}.onSuccess{summary->
                    _state.value=_state.value.copy(strategyTournamentSummary=summary,lastStrategyScanAt=summary.generatedAt,
                        lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=0L,lastStrategyError="",
                        strategyLive=repo.strategyLiveRecommendations(),strategyClosed=repo.strategyClosedRecommendations(),error=null)
                }.onFailure{t->
                    repo.markStrategyScanError(t)
                    _state.value=_state.value.copy(lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=repo.lastStrategyErrorAt(),lastStrategyError=repo.lastStrategyError())
                }
            }
        }
        if(settings.autoScanEnabled && now-lastForegroundMarketScanAt>=liveCadenceMs && foregroundMarketJob?.isActive!=true && foregroundStrategyJob?.isActive!=true && foregroundGlobalJob?.isActive!=true){
            lastForegroundMarketScanAt=now
            foregroundMarketJob=viewModelScope.launch{
                runCatching{repo.scanAll()}.onSuccess{dual->
                    repo.markPressureScanAt()
                    _state.value=_state.value.copy(dualSummary=dual,newListings=dual.newListings,status="Live market engines updated",error=null)
                }.onFailure{t->
                    if(repo.lastSavedDualSummary()==null)_state.value=_state.value.copy(status="Automatic scan will retry",error=t.message?.takeIf{it.isNotBlank()})
                }
            }
        }
        if(settings.globalLeadEnabled && now-lastForegroundGlobalScanAt>=liveCadenceMs && foregroundGlobalJob?.isActive!=true && foregroundMarketJob?.isActive!=true && foregroundStrategyJob?.isActive!=true && !(settings.autoScanEnabled && now-lastForegroundMarketScanAt>=liveCadenceMs) && !(settings.strategyTournamentEnabled && now-lastForegroundStrategyScanAt>=liveCadenceMs)){
            lastForegroundGlobalScanAt=now
            foregroundGlobalJob=viewModelScope.launch{
                runCatching{repo.scanGlobalLead()}.onSuccess{summary->
                    _state.value=_state.value.copy(globalLeadSummary=summary,lastGlobalLeadScanAt=summary.generatedAt,error=null)
                }
            }
        }
    }

    private fun buildInitialState():UiState{
        val uc=repo.freezeRecordToday(ScannerSection.UC_CONTINUATION);val d=repo.freezeRecordToday(ScannerSection.DEMAND_SQUEEZE)
        return UiState(authenticated=repo.accessToken().isNotBlank(),tokenExpiry=repo.tokenExpiry(),credentials=repo.credentials(),staticIp=repo.tradingStaticIp(),settings=repo.settings(),
            dualSummary=repo.lastSavedDualSummary(),frozenUc=uc.candidates,frozenDemand=d.candidates,newListings=repo.newListings(),strategyMetrics=repo.strategyMetrics(),
            accuracies=repo.accuracies(),marketSession=repo.marketSessionInfo(),listingFeedHealth=repo.listingFeedHealth(),lastMarketDataSuccessAt=repo.lastMarketDataSuccessAt(),
            ucFreezeRecord=uc,demandFreezeRecord=d,ucFreezeHistory=repo.freezeHistory(ScannerSection.UC_CONTINUATION),demandFreezeHistory=repo.freezeHistory(ScannerSection.DEMAND_SQUEEZE),
            globalLeadSummary=repo.globalLeadSummary(),lastGlobalLeadScanAt=repo.lastGlobalLeadScanAt(),lastGlobalMappingRefreshAt=repo.lastGlobalMappingRefreshAt(),globalMappingVersion=repo.globalMappingVersion(),
            strategyTournamentSummary=repo.strategyTournamentSummary(),lastStrategyScanAt=repo.lastStrategyScanAt(),lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=repo.lastStrategyErrorAt(),lastStrategyError=repo.lastStrategyError(),lastStrategyCatalogRefreshAt=repo.lastStrategyCatalogRefreshAt(),strategyCatalogVersion=repo.strategyCatalogVersion(),
            strategyLive=repo.strategyLiveRecommendations(),strategyClosed=repo.strategyClosedRecommendations(),globalClosed=repo.globalLeadClosedRecommendations(),tradeCalls=repo.tradeCalls(),tradeAutopsies=repo.tradeAutopsies(),
            challengerShadows=repo.challengerShadows(),brokerOrders=repo.brokerOrders(),decisionSnapshots=repo.decisionSnapshots(),pointInTimeEvidence=repo.pointInTimeEvidence(),evidenceFabric=repo.evidenceFabricSummary(),growwApiHealth=repo.growwApiHealth(),
            multifyEvents=repo.multifyEvents(),multifyListenerEnabled=repo.multifyListenerEnabled(),multifyDashboard=repo.multifyDashboard(),multifyShadowTrades=repo.multifyShadowTrades(),
            multifyDecisions=repo.multifyDecisions(),multifyProfiles=repo.multifyProfiles())
    }

    private fun reliabilityRefresh(status:String?=null,error:String?=_state.value.error){
        repo.ensureTodayFreezeAudit();val uc=repo.freezeRecordToday(ScannerSection.UC_CONTINUATION);val d=repo.freezeRecordToday(ScannerSection.DEMAND_SQUEEZE)
        _state.value=_state.value.copy(status=status?:_state.value.status,error=error,settings=repo.settings(),dualSummary=repo.lastSavedDualSummary(),newListings=repo.newListings(),
            frozenUc=uc.candidates,frozenDemand=d.candidates,marketSession=repo.marketSessionInfo(),listingFeedHealth=repo.listingFeedHealth(),
            lastMarketDataSuccessAt=repo.lastMarketDataSuccessAt(),ucFreezeRecord=uc,demandFreezeRecord=d,
            ucFreezeHistory=repo.freezeHistory(ScannerSection.UC_CONTINUATION),demandFreezeHistory=repo.freezeHistory(ScannerSection.DEMAND_SQUEEZE),
            globalLeadSummary=repo.globalLeadSummary(),lastGlobalLeadScanAt=repo.lastGlobalLeadScanAt(),lastGlobalMappingRefreshAt=repo.lastGlobalMappingRefreshAt(),globalMappingVersion=repo.globalMappingVersion(),
            strategyTournamentSummary=repo.strategyTournamentSummary(),lastStrategyScanAt=repo.lastStrategyScanAt(),lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=repo.lastStrategyErrorAt(),lastStrategyError=repo.lastStrategyError(),lastStrategyCatalogRefreshAt=repo.lastStrategyCatalogRefreshAt(),strategyCatalogVersion=repo.strategyCatalogVersion(),
            strategyLive=repo.strategyLiveRecommendations(),strategyClosed=repo.strategyClosedRecommendations(),globalClosed=repo.globalLeadClosedRecommendations(),tradeCalls=repo.tradeCalls(),tradeAutopsies=repo.tradeAutopsies(),
            challengerShadows=repo.challengerShadows(),brokerOrders=repo.brokerOrders(),decisionSnapshots=repo.decisionSnapshots(),pointInTimeEvidence=repo.pointInTimeEvidence(),evidenceFabric=repo.evidenceFabricSummary(),growwApiHealth=repo.growwApiHealth(),
            multifyEvents=repo.multifyEvents(),multifyListenerEnabled=repo.multifyListenerEnabled(),multifyDashboard=repo.multifyDashboard(),multifyShadowTrades=repo.multifyShadowTrades(),
            multifyDecisions=repo.multifyDecisions(),multifyProfiles=repo.multifyProfiles())
    }

    fun updateCredentials(c:Credentials){_state.value=_state.value.copy(credentials=c)}
    fun saveCredentials(){repo.saveCredentials(_state.value.credentials);_state.value=_state.value.copy(status="Credentials saved securely",error=null)}
    private suspend fun checkTradingRoute(){
        runCatching{repo.currentPublicIpv4()}.onSuccess{actual->
            val expected=repo.tradingStaticIp()
            _state.value=_state.value.copy(staticIp=expected,currentPublicIp=actual,staticIpMatch=actual==expected,staticIpCheckedAt=System.currentTimeMillis())
        }.onFailure{
            _state.value=_state.value.copy(staticIp=repo.tradingStaticIp(),currentPublicIp="",staticIpMatch=null,staticIpCheckedAt=System.currentTimeMillis())
        }
    }
    fun refreshTradingRoute()=viewModelScope.launch{checkTradingRoute()}
    fun saveTradingStaticIp(value:String){refreshTradingRoute()}
    fun placeManualOrder(symbol:String,side:String,product:String,quantity:Int,entryPrice:Double,onResult:(Boolean,String)->Unit)=viewModelScope.launch{
        _state.value=_state.value.copy(status="Submitting manual $side $quantity $symbol ($product)…",error=null)
        runCatching{repo.placeManualMarketOrder(symbol,side,product,quantity,entryPrice)}
            .onSuccess{message->
                runCatching{repo.reconcileBrokerOrders()}
                _state.value=_state.value.copy(status=message,error=null,brokerOrders=repo.brokerOrders(),evidenceFabric=repo.evidenceFabricSummary())
                onResult(true,message)
            }
            .onFailure{t->
                val message=t.message.orEmpty().ifBlank{"Order submission failed"}
                _state.value=_state.value.copy(status="Order not placed / reconcile required",error=message,brokerOrders=repo.brokerOrders(),evidenceFabric=repo.evidenceFabricSummary())
                onResult(false,message)
            }
    }

    fun reconcileBrokerNow()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Reconciling Groww orders and fills…",error=null)
        runCatching{repo.reconcileBrokerOrders()}.onSuccess{n->
            _state.value=_state.value.copy(busy=false,status="Broker reconciliation complete • $n updated",brokerOrders=repo.brokerOrders(),evidenceFabric=repo.evidenceFabricSummary(),error=null)
        }.onFailure{t->_state.value=_state.value.copy(busy=false,status="Broker reconciliation failed",error=t.message,brokerOrders=repo.brokerOrders())}
    }

    fun runChallengerShadowNow()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Running non-executable Challenger shadow scan…",error=null)
        repo.markStrategyScanAttempt()
        runCatching{repo.scanTradingStrategies(progress={m->_state.value=_state.value.copy(status=m)},challengerOnly=true)}.onSuccess{summary->
            _state.value=_state.value.copy(busy=false,strategyTournamentSummary=summary,status=summary.message,error=null,
                challengerShadows=repo.challengerShadows(),decisionSnapshots=repo.decisionSnapshots(),pointInTimeEvidence=repo.pointInTimeEvidence(),evidenceFabric=repo.evidenceFabricSummary())
        }.onFailure{t->repo.markStrategyScanError(t);_state.value=_state.value.copy(busy=false,status="Shadow Run failed",error=t.message)}
    }

    fun resolveChallengerShadowsNow()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Resolving scheduled Challenger horizons…",error=null)
        runCatching{repo.resolveChallengerShadows()}.onSuccess{n->
            _state.value=_state.value.copy(busy=false,status="Challenger resolution complete • $n resolved",challengerShadows=repo.challengerShadows(),evidenceFabric=repo.evidenceFabricSummary(),error=null)
        }.onFailure{t->_state.value=_state.value.copy(busy=false,status="Challenger resolution failed",error=t.message)}
    }

    fun authenticate()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Authenticating with Groww…",error=null)
        runCatching{repo.authenticate()}.onSuccess{e->
            _state.value=_state.value.copy(authenticated=true,tokenExpiry=e,status="Groww authenticated • starting automation")
            runCatching{
                repo.bootstrapAfterAuthentication{message->_state.value=_state.value.copy(status=message)}
            }.onSuccess{message->
                _state.value=_state.value.copy(busy=false,authenticated=true,status=message,error=null,accuracies=repo.accuracies(),strategyMetrics=repo.strategyMetrics())
                reliabilityRefresh(status=message,error=null)
            }.onFailure{bootstrapError->
                _state.value=_state.value.copy(
                    busy=false,
                    authenticated=true,
                    status="Groww authenticated • background automation armed",
                    error="Initial automation pass could not finish: ${bootstrapError.message.orEmpty()}"
                )
                reliabilityRefresh()
            }
        }.onFailure{
            _state.value=_state.value.copy(busy=false,authenticated=false,error=it.message,status="Authentication failed")
        }
    }
    fun refreshUniverse()=viewModelScope.launch{_state.value=_state.value.copy(busy=true,status="Refreshing instrument master…",error=null);runCatching{repo.refreshUniverse()}.onSuccess{c->_state.value=_state.value.copy(busy=false,universeCount=c,status="Instrument master refreshed: $c")}.onFailure{_state.value=_state.value.copy(busy=false,error=it.message)}}
    fun refreshNewListings()=viewModelScope.launch{_state.value=_state.value.copy(busy=true,status="Loading newly listed NSE stocks…",error=null);runCatching{repo.refreshNewListings()}.onSuccess{_state.value=_state.value.copy(busy=false,newListings=it,status=if(it.isEmpty())"Listing feed healthy • zero listings in selected window" else "Loaded ${it.size} recent listings");reliabilityRefresh(error=null)}.onFailure{_state.value=_state.value.copy(busy=false,error=it.message,status="Listing feed refresh failed");reliabilityRefresh(status="Listing feed refresh failed",error=it.message)}}

    fun runScan()=viewModelScope.launch{
        val session=repo.marketSessionInfo()
        if(!session.isOpen){
            _state.value=_state.value.copy(busy=true,status="Building next-session Upper Circuit shortlist…",error=null)
            runCatching{repo.scanUpperCircuitNextSession{m->_state.value=_state.value.copy(status=m)}}.onSuccess{uc->
                _state.value=_state.value.copy(busy=false,status=uc.message,error=null);reliabilityRefresh(status=uc.message,error=null)
            }.onFailure{_state.value=_state.value.copy(busy=false,status="Next-session UC scan failed",error=it.message)}
            return@launch
        }
        _state.value=_state.value.copy(busy=true,status="Scanning UC + pre-pressure models…",error=null)
        runCatching{repo.scanAll{m->_state.value=_state.value.copy(status=m)}}.onSuccess{dual->
            _state.value=_state.value.copy(busy=false,dualSummary=dual,newListings=dual.newListings,status="UC: ${dual.uc.candidates.size} • Pre-pressure: ${dual.demand.candidates.size}",strategyMetrics=repo.strategyMetrics(),accuracies=repo.accuracies())
            repo.ensureTodayFreezeAudit();reliabilityRefresh(error=null)
        }.onFailure{val raw=it.message.orEmpty();val friendly=if(raw.contains("429")||raw.contains("rate limit",true))"Groww is temporarily rate-limiting live data. Global Edge AI Trader is cooling down automatically; the previous successful scan remains available." else raw.ifBlank{"Scan failed"};_state.value=_state.value.copy(busy=false,error=friendly,status="Scan paused");reliabilityRefresh(status="Scan paused",error=friendly)}
    }


    fun refreshGlobalLead()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Scanning global leads → Indian delivery candidates…",error=null)
        runCatching{repo.scanGlobalLead{m->_state.value=_state.value.copy(status=m)}}.onSuccess{summary->
            _state.value=_state.value.copy(busy=false,globalLeadSummary=summary,lastGlobalLeadScanAt=summary.generatedAt,
                lastGlobalMappingRefreshAt=repo.lastGlobalMappingRefreshAt(),globalMappingVersion=repo.globalMappingVersion(),
                status=summary.message,error=null)
        }.onFailure{_state.value=_state.value.copy(busy=false,status="Global Lead refresh failed",error=it.message)}
    }

    fun refreshGlobalMappings()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Refreshing weekly global counterpart map…",error=null)
        runCatching{repo.refreshGlobalMappings(true)}.onSuccess{count->
            _state.value=_state.value.copy(busy=false,lastGlobalMappingRefreshAt=repo.lastGlobalMappingRefreshAt(),globalMappingVersion=repo.globalMappingVersion(),
                status="Global mapping refreshed: $count counterparts",error=null)
            refreshGlobalLead()
        }.onFailure{_state.value=_state.value.copy(busy=false,status="Mapping refresh failed",error=it.message)}
    }


    fun refreshTradingStrategies()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Scanning intraday strategies with liquidity and execution gates…",error=null)
        repo.markStrategyScanAttempt()
        runCatching{repo.scanTradingStrategies{m->_state.value=_state.value.copy(status=m)}}.onSuccess{summary->
            _state.value=_state.value.copy(busy=false,strategyTournamentSummary=summary,lastStrategyScanAt=summary.generatedAt,lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=0L,lastStrategyError="",
                strategyCatalogVersion=summary.catalogVersion,lastStrategyCatalogRefreshAt=repo.lastStrategyCatalogRefreshAt(),
                strategyLive=repo.strategyLiveRecommendations(),strategyClosed=repo.strategyClosedRecommendations(),status=summary.message,error=null)
        }.onFailure{t->repo.markStrategyScanError(t);_state.value=_state.value.copy(busy=false,status="Strategy scan failed",error=t.message,lastStrategyAttemptAt=repo.lastStrategyAttemptAt(),lastStrategyErrorAt=repo.lastStrategyErrorAt(),lastStrategyError=repo.lastStrategyError())}
    }
    fun refreshStrategyCatalog()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Refreshing weekly strategy catalogue…",error=null)
        runCatching{repo.refreshStrategyCatalog(true)}.onSuccess{count->
            _state.value=_state.value.copy(busy=false,lastStrategyCatalogRefreshAt=repo.lastStrategyCatalogRefreshAt(),strategyCatalogVersion=repo.strategyCatalogVersion(),status="Strategy catalogue refreshed: $count rules",error=null)
            if(repo.marketSessionInfo().isOpen) refreshTradingStrategies()
        }.onFailure{_state.value=_state.value.copy(busy=false,status="Strategy catalogue refresh failed",error=it.message)}
    }

    fun freezeNow(section:ScannerSection){val list=when(section){ScannerSection.UC_CONTINUATION->_state.value.dualSummary?.uc?.candidates;ScannerSection.DEMAND_SQUEEZE->_state.value.dualSummary?.demand?.candidates}.orEmpty();repo.freezeToday(section,list,"Manual freeze from visible shortlist");reliabilityRefresh(status="${section.name} daily record frozen",error=null)}
    fun runReplay(symbol:String,days:Long=30)=viewModelScope.launch{_state.value=_state.value.copy(busy=true,status="Replaying $symbol…",error=null);runCatching{repo.replay(symbol,days)}.onSuccess{_state.value=_state.value.copy(busy=false,replayResult=it,status="Replay complete")}.onFailure{_state.value=_state.value.copy(busy=false,error=it.message,status="Replay failed")}}
    fun refreshNews()=viewModelScope.launch{_state.value=_state.value.copy(busy=true,status="Loading NSE/BSE news…",error=null);runCatching{repo.refreshNews()}.onSuccess{_state.value=_state.value.copy(busy=false,newsItems=it,status="Loaded ${it.size} exchange updates")}.onFailure{_state.value=_state.value.copy(busy=false,error=it.message,status="News refresh failed")}}
    fun runLearningNow()=viewModelScope.launch{_state.value=_state.value.copy(busy=true,status="Running autonomous learning pass…",error=null);runCatching{repo.runAutonomousLearningPass(force=true)}.onSuccess{msg->_state.value=_state.value.copy(busy=false,accuracies=repo.accuracies(),strategyMetrics=repo.strategyMetrics(),status=msg);reliabilityRefresh(error=null)}.onFailure{_state.value=_state.value.copy(busy=false,error=it.message)}}
    fun replayMultifyNow()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Replaying captured Multify events…",error=null)
        runCatching{repo.replayMultifyEvents(80)}.onSuccess{n->
            _state.value=_state.value.copy(busy=false,status="Multify replay complete • $n evaluated",multifyEvents=repo.multifyEvents(),multifyDashboard=repo.multifyDashboard(),multifyShadowTrades=repo.multifyShadowTrades(),multifyDecisions=repo.multifyDecisions(),multifyProfiles=repo.multifyProfiles(),error=null)
        }.onFailure{t->_state.value=_state.value.copy(busy=false,status="Multify replay failed",error=t.message)}
    }
    fun setMultifyLiveTrading(enabled:Boolean){
        val next=repo.settings().copy(multifyLiveTradingEnabled=enabled)
        repo.saveSettings(next)
        _state.value=_state.value.copy(settings=repo.settings(),status=if(enabled)"Multify REAL ORDERS armed for today" else "Multify new real orders disabled; shadow continues",error=null)
        reliabilityRefresh(error=null)
    }
    fun exitAllMultify()=viewModelScope.launch{
        _state.value=_state.value.copy(busy=true,status="Closing all Multify positions…",error=null)
        runCatching{repo.exitAllMultifyPositions()}.onSuccess{n->
            _state.value=_state.value.copy(busy=false,status="Multify exit-all processed • $n positions",error=null);reliabilityRefresh(error=null)
        }.onFailure{t->_state.value=_state.value.copy(busy=false,status="Multify exit-all failed",error=t.message)}
    }
    fun updateSettings(s:AppSettings){repo.saveSettings(s);_state.value=_state.value.copy(settings=repo.settings(),status="Settings saved");reliabilityRefresh(error=null)}
    class Factory(private val repo:GlobalEdgeAITraderRepository):ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=MainViewModel(repo) as T}
}
