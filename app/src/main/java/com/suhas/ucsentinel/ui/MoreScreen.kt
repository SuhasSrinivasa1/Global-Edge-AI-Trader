package com.suhas.globaledgeai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.BuildConfig
import com.suhas.globaledgeai.GlobalEdgeApplication
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.domain.engine.DemandSignalEngine
import com.suhas.globaledgeai.domain.engine.SignalEngine
import com.suhas.globaledgeai.domain.model.*
import kotlin.math.roundToInt

@Composable
fun MoreScreen(
    state:UiState,
    vm:MainViewModel,
    padding:PaddingValues,
    page:String,
    onPage:(String)->Unit
){
    Column(Modifier.fillMaxSize().padding(padding)){
        val pages=listOf("auth","lab","settings")
        TabRow(selectedTabIndex=pages.indexOf(page).coerceAtLeast(0)){
            Tab(selected=page=="auth",onClick={onPage("auth")},text={Text("Groww Auth")})
            Tab(selected=page=="lab",onClick={onPage("lab")},text={Text("Strategy Lab")})
            Tab(selected=page=="settings",onClick={onPage("settings")},text={Text("Settings")})
        }
        when(page){
            "auth"->Auth(state,vm)
            "lab"->StrategyLab(state,vm)
            else->Settings(state,vm)
        }
    }
}

@Composable
private fun StrategyLab(state:UiState,vm:MainViewModel){
    var sec by remember{mutableIntStateOf(0)}
    val section=if(sec==0)ScannerSection.UC_CONTINUATION else ScannerSection.DEMAND_SQUEEZE
    val modelVersion=if(sec==0)SignalEngine.MODEL_VERSION else DemandSignalEngine.MODEL_VERSION
    val metrics=state.strategyMetrics.filter{it.section==section && it.modelVersion==modelVersion}
    val families=if(sec==0){
        listOf("Circuit","Depth","Momentum","Breakout","Trend","Volume","Intraday","Candles","IPO")
    } else {
        listOf(
            "Compression","Pressure acceleration","Volume ignition","Intraday trigger",
            "Supply thinning","Microstructure trigger","Breakout setup","Trend context",
            "New listing","Risk control"
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),
        contentPadding=PaddingValues(bottom=20.dp)
    ){
        item{AppHeader("Strategy Lab","Shows the exact model generation, active strategy families and learned precision")}
        state.evidenceFabric?.let{fabric->
            item{
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("EVIDENCE FABRIC",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                        Text("Calendar ${fabric.calendarVersion} • week ${fabric.remainingWeekSessions} sessions • month ${fabric.remainingMonthSessions}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("PIT evidence ${fabric.pointInTimeEvidenceCount} • macro next 7d ${fabric.macroEventsNext7Days} • decisions ${fabric.decisionSnapshots}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Challenger pending ${fabric.challengerPending} • resolved ${fabric.challengerResolved} • broker orders ${fabric.brokerOrders}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Sector map • "+fabric.sectorMapVersion.ifBlank{"awaiting first NIFTY 500 snapshot"},
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                        Text("Point-in-time evidence is prospective: historical validation can only use observations captured by that historical timestamp.",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick=vm::runChallengerShadowNow,enabled=!state.busy&&state.authenticated&&state.marketSession.isOpen,modifier=Modifier.weight(1f)){Text("Shadow Run")}
                OutlinedButton(onClick=vm::resolveChallengerShadowsNow,enabled=!state.busy&&state.authenticated,modifier=Modifier.weight(1f)){Text("Resolve")}
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text("CHALLENGER SHADOW LANE • NON-EXECUTABLE",fontWeight=FontWeight.Bold)
                    val pending=state.challengerShadows.count{it.outcome==ChallengerShadowOutcome.PENDING}
                    val wins=state.challengerShadows.count{it.outcome==ChallengerShadowOutcome.WIN}
                    val losses=state.challengerShadows.count{it.outcome==ChallengerShadowOutcome.LOSS}
                    Text("Pending $pending • Wins $wins • Losses $losses • prospective shadow evidence is required before promotion.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    state.challengerShadows.take(5).forEach{r->
                        Text("${r.outcome} • ${r.symbol} • ${r.direction} • ${r.strategyName.take(38)} • ${"%+.2f".format(r.returnPct)}%",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item{
            TabRow(selectedTabIndex=sec){
                Tab(selected=sec==0,onClick={sec=0},text={Text("UC")})
                Tab(selected=sec==1,onClick={sec=1},text={Text("Pre-Pressure")})
            }
        }
        item{
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                MetricCard("Signals",if(sec==0)"60" else "60",Modifier.weight(1f))
                val a=state.accuracies[section]
                MetricCard("Accuracy",if(a==null||a.evaluated==0)"Learning" else "%.1f%%".format(a.accuracyPct),Modifier.weight(1f))
                MetricCard("Learned",metrics.count{it.observations>=5}.toString(),Modifier.weight(1f))
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp)){
                    Text("Model generation",style=MaterialTheme.typography.titleMedium)
                    Text(modelVersion,color=MaterialTheme.colorScheme.primary)
                    if(sec==1){
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Formula: 30% setup + 34% acceleration + 26% microstructure + 10% context − risk penalty. " +
                                    "The model rejects stocks already at UC or already showing extreme buy imbalance.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if(state.settings.adaptiveRangesEnabled)
                                "Accuracy target: adaptive +1.5–4.5% per candidate from the frozen prediction price on the next trading session."
                            else "Accuracy target: +${"%.1f".format(state.settings.demandSpikeTargetPct)}% from frozen prediction price on the next trading session.",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item{
            OutlinedButton(
                onClick=vm::runLearningNow,
                enabled=!state.busy&&state.authenticated,
                modifier=Modifier.fillMaxWidth()
            ){Text("Run 24-hour learning cycle now")}
        }
        item{Text("Active strategy families",style=MaterialTheme.typography.titleMedium)}
        items(families){family->
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp)){
                    Text(family,style=MaterialTheme.typography.titleMedium)
                    Text(
                        if(sec==1)"Used only when the pre-pressure conditions are present; no single family can dominate the prediction."
                        else "Used as part of the combined model when its conditions are active.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item{Text("Learned signal precision",style=MaterialTheme.typography.titleMedium)}
        if(metrics.isEmpty()) item{
            EmptyState("No learned sample yet","This model generation learns only from predictions made by the same generation, preventing old strategies from contaminating new accuracy.")
        } else items(metrics.take(30)){m->
            ElevatedCard(Modifier.fillMaxWidth()){
                Row(Modifier.padding(12.dp)){
                    Column(Modifier.weight(1f)){
                        Text(m.signalId)
                        Text(
                            "${m.wins}/${m.observations} ${if(sec==0)"UC hits" else "price-spike hits"}",
                            color=MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("%.1f%%".format(m.precision),style=MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun News(state:UiState,vm:MainViewModel){
    val ctx=LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{AppHeader("Exchange News","Official NSE corporate announcements + BSE notices")}
        item{StatusStrip(state)}
        item{Button(onClick=vm::refreshNews,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Refresh exchange feeds")}}
        if(state.newsItems.isEmpty()) item{EmptyState("No news loaded","Refresh to load exchange updates.")}
        else items(state.newsItems){n->
            ElevatedCard(
                onClick={if(n.url.isNotBlank())runCatching{ctx.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(n.url))) }},
                modifier=Modifier.fillMaxWidth()
            ){
                Column(Modifier.padding(14.dp)){
                    Text("${n.source}${if(n.symbol.isNotBlank())" • ${n.symbol}" else ""}",color=MaterialTheme.colorScheme.primary)
                    Text(n.title,style=MaterialTheme.typography.titleMedium)
                    if(n.summary.isNotBlank())Text(n.summary.take(220),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(n.publishedAt,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Auth(state:UiState,vm:MainViewModel){
    var mode by remember(state.credentials.mode){mutableStateOf(state.credentials.mode)}
    var key by remember(state.credentials.apiKeyOrTotpToken){mutableStateOf(state.credentials.apiKeyOrTotpToken)}
    var secret by remember(state.credentials.secret){mutableStateOf(state.credentials.secret)}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        AppHeader("Groww Authentication","Credentials are encrypted on-device")
        StatusStrip(state)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=mode==AuthMode.TOTP,onClick={mode=AuthMode.TOTP},label={Text("TOTP")})
            FilterChip(selected=mode==AuthMode.APPROVAL,onClick={mode=AuthMode.APPROVAL},label={Text("API key")})
        }
        OutlinedTextField(
            value=key,onValueChange={key=it.trim()},
            label={Text(if(mode==AuthMode.TOTP)"TOTP token / API key" else "API key")},
            singleLine=true,modifier=Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value=secret,onValueChange={secret=it.trim()},
            label={Text(if(mode==AuthMode.TOTP)"TOTP secret" else "API secret")},
            singleLine=true,modifier=Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(
                onClick={vm.updateCredentials(Credentials(mode,key,secret));vm.saveCredentials()},
                modifier=Modifier.weight(1f)
            ){Text("Save")}
            Button(
                onClick={vm.updateCredentials(Credentials(mode,key,secret));vm.saveCredentials();vm.authenticate()},
                enabled=!state.busy&&key.isNotBlank()&&secret.isNotBlank(),
                modifier=Modifier.weight(1f)
            ){Text("Refresh / Authenticate")}
        }
        Text(if(state.authenticated)"Authenticated • ${state.tokenExpiry}" else "Not authenticated",color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Trading route",style=MaterialTheme.typography.titleMedium)
        val routeColor=when(state.staticIpMatch){true->Color(0xFF2E7D32);false->Color(0xFFC62828);null->MaterialTheme.colorScheme.onSurfaceVariant}
        val routeTitle=when(state.staticIpMatch){true->"● GREEN • STATIC IP MATCH";false->"● RED • STATIC IP MISMATCH";null->"● CHECKING STATIC IP ROUTE"}
        ElevatedCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(routeTitle,fontWeight=FontWeight.Bold,color=routeColor)
                Text("Expected static IPv4 • ${state.staticIp}",style=MaterialTheme.typography.bodyMedium)
                Text(
                    if(state.currentPublicIp.isNotBlank())"Current public IPv4 • ${state.currentPublicIp}"
                    else "Current public IPv4 • unavailable / checking",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if(state.staticIpMatch==true)"Surfshark static route is active."
                    else if(state.staticIpMatch==false)"Turn on the whitelisted Surfshark static-IP route before trading."
                    else "Unable to verify the public route yet. Tap Check route now.",
                    style=MaterialTheme.typography.bodySmall,color=routeColor
                )
            }
        }
        OutlinedButton(onClick=vm::refreshTradingRoute,modifier=Modifier.fillMaxWidth()){Text("Check route now")}
        if(state.authenticated){
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp)){
                    Text("Automation armed",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                    Text(
                        if(mode==AuthMode.TOTP)
                            "Automation runs from the NSE open through 15:30 IST, keeps recommendations visible, runs learning automatically, and renews the daily Groww token when possible."
                        else
                            "Finding and learning are automatic after authentication. Groww API-key approval mode still requires the daily approval step when Groww expires the access token.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Settings(state:UiState,vm:MainViewModel){
    val ctx=LocalContext.current
    var logExportStatus by remember{mutableStateOf<String?>(null)}
    val vaultCreator=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)vm.configureLearningVault(uri.toString())
    }
    val vaultRestorer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)vm.restoreLearningVault(uri.toString())
    }
    val centralizedExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        if(uri!=null){
            runCatching{
                val repo=(ctx.applicationContext as GlobalEdgeApplication).repository
                val out=ctx.contentResolver.openOutputStream(uri)?:error("Unable to open selected file")
                DiagnosticLog.writeCentralizedBundle(
                    context=ctx,
                    output=out,
                    appVersion=BuildConfig.VERSION_NAME,
                    stateReport=repo.endOfDayDiagnosticReport(),
                    learningReport=repo.weeklyLearningReport()
                )
            }.onSuccess{
                DiagnosticLog.log(ctx,"EXPORT","Centralized diagnostic + learning bundle exported")
                logExportStatus="Centralized log ZIP saved • use the same export for daily, weekly or monthly review"
            }.onFailure{logExportStatus="Centralized export failed: "+it.message}
        }
    }
    var uc by remember(state.settings.minScore){mutableFloatStateOf(state.settings.minScore.toFloat())}
    var prediction by remember(state.settings.demandMinScore){mutableFloatStateOf(state.settings.demandMinScore.toFloat())}
    var spikeTarget by remember(state.settings.demandSpikeTargetPct){mutableFloatStateOf(state.settings.demandSpikeTargetPct.toFloat())}
    var maxRatio by remember(state.settings.demandPressureMaxBuySellRatio){mutableFloatStateOf(state.settings.demandPressureMaxBuySellRatio.toFloat())}
    var ucMax by remember(state.settings.maxFinalCandidates){mutableFloatStateOf(state.settings.maxFinalCandidates.toFloat())}
    var dsMax by remember(state.settings.maxDemandCandidates){mutableFloatStateOf(state.settings.maxDemandCandidates.toFloat())}
    var days by remember(state.settings.newListingDays){mutableFloatStateOf(state.settings.newListingDays.toFloat())}
    var retention by remember(state.settings.memoryRetentionDays){mutableFloatStateOf(state.settings.memoryRetentionDays.toFloat())}
    var cadence by remember(state.settings.pressureScanIntervalMinutes){mutableFloatStateOf(state.settings.pressureScanIntervalMinutes.toFloat())}
    var globalCadence by remember(state.settings.globalLeadScanIntervalMinutes){mutableFloatStateOf(state.settings.globalLeadScanIntervalMinutes.toFloat())}
    var globalEnabled by remember{mutableStateOf(state.settings.globalLeadEnabled)}
    var adaptive by remember(state.settings.adaptiveRangesEnabled){mutableStateOf(state.settings.adaptiveRangesEnabled)}
    var sme by remember{mutableStateOf(state.settings.includeSmeSeries)}
    var auto by remember{mutableStateOf(state.settings.autoScanEnabled)}
    var pressureAuto by remember{mutableStateOf(state.settings.pressureAutoScanEnabled)}
    var learn by remember{mutableStateOf(state.settings.learningEnabled)}
    val api=state.growwApiHealth

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp),
        contentPadding=PaddingValues(bottom=28.dp)
    ){
        item{AppHeader("Settings","Global Edge AI Trader ${BuildConfig.VERSION_NAME} • coordinated engines • adaptive learning")}
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Persistent learning vault",style=MaterialTheme.typography.titleMedium)
                    val backupLabel=if(state.learningVaultLastBackupAt>0L){
                        java.time.Instant.ofEpochMilli(state.learningVaultLastBackupAt).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDateTime().toString().replace('T',' ')
                    }else "never"
                    Text(
                        if(state.learningVaultConfigured) "ACTIVE • automatic snapshot about every 2 minutes while the scanner service is alive • last backup $backupLabel"
                        else "NOT CONFIGURED • a normal Android uninstall will delete app-private learning unless you create this vault first.",
                        style=MaterialTheme.typography.bodySmall,
                        color=if(state.learningVaultConfigured)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("The vault keeps scanner/strategy/global learning and ledgers in a document outside the app sandbox. Groww credentials and access tokens are excluded.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={vaultCreator.launch("Global-Edge-Learning-Vault.json")},modifier=Modifier.weight(1f)){Text(if(state.learningVaultConfigured)"Replace vault" else "Create vault")}
                        OutlinedButton(onClick=vm::backupLearningVaultNow,enabled=state.learningVaultConfigured&&!state.busy,modifier=Modifier.weight(1f)){Text("Backup now")}
                    }
                    OutlinedButton(onClick={vaultRestorer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("Restore learning vault")}
                    Text("After a full uninstall/reinstall, open Settings → Restore learning vault and select the same file once. ADB uninstall -k can preserve local data without needing restore.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("System coordination",style=MaterialTheme.typography.titleMedium)
                    Text("UC + Pressure • 5-minute market fast lane",style=MaterialTheme.typography.bodySmall)
                    Text("Strategies • ranked deep-history rotation with execution cautions",style=MaterialTheme.typography.bodySmall)
                    Text("Global • independent research, lower priority than NSE live engines during market hours",style=MaterialTheme.typography.bodySmall)
                    Text("Learning • ${if(state.settings.learningEnabled) "15-minute audit + 17:45 IST deep replay" else "disabled"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Groww API hygiene",style=MaterialTheme.typography.titleMedium)
                        Text(api.status,color=if(api.status=="RATE LIMITED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                    }
                    Text(
                        if(state.authenticated) "Access token: AUTHENTICATED • expiry ${state.tokenExpiry.ifBlank { "not reported" }}" else "Access token: NOT AUTHENTICATED",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Live Data rolling 60s: ${api.liveLastMinute}/${api.livePerMinuteLimit} • published headroom ${api.liveHeadroom}")
                    Text("1-second burst: ${api.liveLastSecond}/${api.livePerSecondLimit} • adaptive app budget ${api.internalLiveMinuteBudget}/min",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Orders: ${api.ordersLastMinute}/250 • Non-trading: ${api.nonTradingLastMinute}/500 • Auth: ${api.authLastMinute}/30 • Historical: ${api.historicalLastMinute}/min",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Groww 429 rate-limit responses (last hour): ${api.rateLimitLastHour} • requests tracked since app start: ${api.totalRequestsSinceStart}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Automatic hygiene is always ON. Healthy mode can use the current app budget shown above; a real Groww 429 temporarily tightens the budget and then recovers automatically.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Column(Modifier.weight(1f)){
                            Text("Dynamic model ranges",style=MaterialTheme.typography.titleMedium)
                            Text(
                                if(adaptive) "ON • thresholds are chosen from the current market's score distribution with hard safety floors."
                                else "OFF • the manual slider values below are used as fixed thresholds.",
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked=adaptive,onCheckedChange={adaptive=it})
                    }
                    if(adaptive){
                        Text("UC score band 60–84 • Pressure score band 62–86 • spike target +1.5–4.5% per candidate • pressure cutoff 6–10x by setup stage",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item{Text(if(adaptive)"UC continuation score AUTO • baseline ${uc.roundToInt()}" else "UC continuation score ${uc.roundToInt()}")}
        item{Slider(value=uc,onValueChange={uc=it},valueRange=60f..95f,enabled=!adaptive)}
        item{Text(if(adaptive)"Pre-pressure prediction score AUTO • baseline ${prediction.roundToInt()}" else "Pre-pressure prediction score ${prediction.roundToInt()}")}
        item{Slider(value=prediction,onValueChange={prediction=it},valueRange=55f..90f,enabled=!adaptive)}
        item{Text(if(adaptive)"Price-spike success target AUTO • +1.5–4.5% / 24h" else "Price-spike success target +${"%.1f".format(spikeTarget)}% / 24h")}
        item{Slider(value=spikeTarget,onValueChange={spikeTarget=it},valueRange=1.5f..8f,enabled=!adaptive)}
        item{Text(if(adaptive)"Already-pressure cutoff AUTO • 6–10x Buy/Sell" else "Already-pressure cutoff ${"%.1f".format(maxRatio)}x Buy/Sell")}
        item{Slider(value=maxRatio,onValueChange={maxRatio=it},valueRange=4f..12f,enabled=!adaptive)}
        item{Text("UC picks maximum ${ucMax.roundToInt()} • actual count is dynamic")}
        item{Slider(value=ucMax,onValueChange={ucMax=it},valueRange=1f..5f,steps=3)}
        item{Text("Prediction picks maximum ${dsMax.roundToInt()} • actual count is dynamic")}
        item{Slider(value=dsMax,onValueChange={dsMax=it},valueRange=1f..10f,steps=8)}
        item{Text("New listing window ${days.roundToInt()} days")}
        item{Slider(value=days,onValueChange={days=it},valueRange=15f..90f)}
        item{Text("Learning-memory retention ${retention.roundToInt()} days")}
        item{Slider(value=retention,onValueChange={retention=it},valueRange=30f..365f)}
        item{Text("Background pressure scan every ${cadence.roundToInt()} minutes")}
        item{Slider(value=cadence,onValueChange={cadence=it},valueRange=15f..120f)}
        item{Text("Global Lead background scan every ${globalCadence.roundToInt()} minutes")}
        item{Slider(value=globalCadence,onValueChange={globalCadence=it},valueRange=15f..120f)}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Include SME");Switch(checked=sme,onCheckedChange={sme=it})}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Upper Circuit 24h next-session research");Switch(checked=auto,onCheckedChange={auto=it})}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Intraday pre-pressure scan");Switch(checked=pressureAuto,onCheckedChange={pressureAuto=it})}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Global Lead cross-market scan");Switch(checked=globalEnabled,onCheckedChange={globalEnabled=it})}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("15-minute autonomous learning");Switch(checked=learn,onCheckedChange={learn=it})}}
        item{
            Text(
                "Automation: live-market engines use 5-minute scans. Upper Circuit and Global Lead continue 24h research outside NSE hours. Every 15 minutes the learning worker reconciles calls, runs post-trade autopsies, classifies the market regime, checks relevant exchange/global news, replays shadow strategies without look-ahead, and only adapts production thresholds after minimum-sample safeguards. The weekly log includes the full autopsy + shadow-strategy audit trail. Force-stopping the app pauses Android background work until it is opened again.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall
            )
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Centralized log export",style=MaterialTheme.typography.titleMedium)
                    Text(
                        "One ZIP for daily, weekly or monthly review. It contains the runtime timeline, persisted scanner/strategy/global state, learning/autopsy/shadow reports and broker diagnostics. Groww credentials, TOTP secrets and access tokens are excluded.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick={
                            val day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                            centralizedExporter.launch("Global-Edge-All-Logs-"+day+"-v"+BuildConfig.VERSION_NAME+".zip")
                        },
                        modifier=Modifier.fillMaxWidth()
                    ){Text("Export all logs")}
                }
            }
        }
        if(!logExportStatus.isNullOrBlank())item{Text(logExportStatus!!,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{
            Button(
                onClick={
                    vm.updateSettings(
                        state.settings.copy(
                            minScore=uc.toDouble(),
                            demandMinScore=prediction.toDouble(),
                            demandSpikeTargetPct=spikeTarget.toDouble(),
                            adaptiveRangesEnabled=adaptive,
                            demandPressureMaxBuySellRatio=maxRatio.toDouble(),
                            maxFinalCandidates=ucMax.roundToInt(),
                            maxDemandCandidates=dsMax.roundToInt(),
                            newListingDays=days.roundToInt(),
                            memoryRetentionDays=retention.roundToInt(),
                            pressureScanIntervalMinutes=cadence.roundToInt(),
                            globalLeadEnabled=globalEnabled,
                            globalLeadScanIntervalMinutes=globalCadence.roundToInt(),
                            globalTopCandidates=10,
                            includeSmeSeries=sme,
                            autoScanEnabled=auto,
                            pressureAutoScanEnabled=pressureAuto,
                            learningEnabled=learn
                        )
                    )
                },
                modifier=Modifier.fillMaxWidth()
            ){Text("Save settings")}
        }
    }
}
