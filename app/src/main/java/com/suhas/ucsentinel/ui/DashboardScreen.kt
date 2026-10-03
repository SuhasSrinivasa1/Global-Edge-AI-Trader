package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.model.*

@Composable
fun DashboardScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val ucLatest=if(state.ucFreezeRecord.recorded)state.ucFreezeRecord else state.ucFreezeHistory.firstOrNull()
    val demandLatest=if(state.demandFreezeRecord.recorded)state.demandFreezeRecord else state.demandFreezeHistory.firstOrNull()
    val feedLabel=when(state.listingFeedHealth.state){
        FeedHealthState.OK->"${state.listingFeedHealth.itemCount} • OK"
        FeedHealthState.EMPTY->"0 • OK"
        FeedHealthState.ERROR->"Feed error"
        FeedHealthState.NEVER_LOADED->"Not loaded"
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{AppHeader("Global Edge AI Trader","Upper Circuit • Pressure • Trading Strategies")}
        item{StatusStrip(state)}
        item{MarketSessionBanner(state)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            MetricCard("Groww",if(state.authenticated)"Connected" else "Connect",Modifier.weight(1f))
            MetricCard("3 PM",if(state.ucFreezeRecord.recorded||state.demandFreezeRecord.recorded)"Recorded" else if(state.marketSession.isOpen)"Pending" else "History",Modifier.weight(1f))
            MetricCard("Strategy live",state.strategyLive.size.toString(),Modifier.weight(1f))
        }}
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp)){
                    Text("Hands-free automation",style=MaterialTheme.typography.titleMedium)
                    val armed=state.authenticated&&(state.settings.autoScanEnabled||state.settings.pressureAutoScanEnabled||state.settings.learningEnabled)
                    Text(
                        if(armed)"ARMED • discovery, 3 PM audit and adaptive learning run in the background"
                        else if(state.authenticated)"Authenticated • background automation is disabled in Settings"
                        else "Authenticate Groww once to arm automatic finding and learning",
                        color=if(armed)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if(armed){
                        Text(
                            if(state.credentials.mode==AuthMode.TOTP)
                                "TOTP mode can renew the daily Groww access token automatically from encrypted on-device credentials."
                            else
                                "API-key approval mode still needs Groww's daily approval; scans resume automatically after approval.",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item{
            val t=state.strategyTournamentSummary
            ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
                Text("Trading Strategies",style=MaterialTheme.typography.titleMedium)
                Text("${state.strategyLive.size} live • ${state.strategyClosed.size} closed • 15-minute intraday scanning",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(t!=null)Text("${t.strategiesRun} rules • catalogue ${t.catalogVersion}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            }}
        }
        item{Text("Model accuracy",style=MaterialTheme.typography.titleMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val u=state.accuracies[ScannerSection.UC_CONTINUATION];val q=state.accuracies[ScannerSection.DEMAND_SQUEEZE]
            MetricCard("UC next-day",if(u==null||u.evaluated==0)"Learning" else "%.1f%%".format(u.accuracyPct),Modifier.weight(1f))
            MetricCard("Spike predictor",if(q==null||q.evaluated==0)"Learning" else "%.1f%%".format(q.accuracyPct),Modifier.weight(1f))
        }}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(vm::runScan,enabled=!state.busy&&state.authenticated&&state.marketSession.isOpen,modifier=Modifier.weight(1f)){
                Icon(Icons.Default.LockClock,null);Spacer(Modifier.width(5.dp));Text(if(state.marketSession.isOpen)"Scan both" else "Market closed")
            }
            OutlinedButton(vm::runLearningNow,enabled=!state.busy&&state.authenticated,modifier=Modifier.weight(1f)){
                Icon(Icons.Default.AutoGraph,null);Spacer(Modifier.width(5.dp));Text("Learn now")
            }
        }}
        item{Text("Latest upper-circuit 3 PM record",style=MaterialTheme.typography.titleMedium)}
        item{FreezeRecordBlock(ucLatest,"upper-circuit continuation",true)}
        item{Text("Latest pre-pressure 3 PM record",style=MaterialTheme.typography.titleMedium)}
        item{FreezeRecordBlock(demandLatest,"pre-pressure prediction",true)}
        item{
            val h=state.listingFeedHealth
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp)){
                    Text("New-listing feed",style=MaterialTheme.typography.titleMedium)
                    Text(h.message,color=if(h.state==FeedHealthState.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(h.lastSuccessAt>0)Text("Last successful refresh: ${formatIstTimestamp(h.lastSuccessAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
