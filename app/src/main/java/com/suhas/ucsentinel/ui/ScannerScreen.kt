package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.engine.DemandSignalEngine
import com.suhas.globaledgeai.domain.model.*

@Composable
fun ScannerScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    var section by remember{mutableIntStateOf(0)}
    var strategyView by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().padding(padding)){
        AppHeader("Live Scanner","Upper Circuit + dynamic Pressure Prediction + adaptive Trading Strategies")
        TabRow(selectedTabIndex=section){
            Tab(selected=section==0,onClick={section=0},text={Text("Upper Circuit")})
            Tab(selected=section==1,onClick={section=1},text={Text("Pressure Prediction")})
            Tab(selected=section==2,onClick={section=2},text={Text("Trading Strategies")})
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
            item{StatusStrip(state)}
            item{MarketSessionBanner(state)}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(vm::refreshUniverse,enabled=!state.busy,modifier=Modifier.weight(1f)){
                    Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(5.dp));Text("Refresh master")
                }
                Button(vm::runScan,enabled=!state.busy&&state.authenticated&&state.marketSession.isOpen,modifier=Modifier.weight(1f)){
                    Icon(Icons.Default.Radar,null);Spacer(Modifier.width(5.dp));Text(if(state.marketSession.isOpen)"Scan all" else "Market closed")
                }
            }}
            when(section){
                0->{
                    val s=state.dualSummary?.uc
                    if(s==null)item{EmptyState("No stored UC scan","Run during market hours. After close, the last successful session scan will stay here.")}
                    else{
                        item{AccuracyCard(state,ScannerSection.UC_CONTINUATION)}
                        item{Text("Scan completed ${formatIstTimestamp(s.completedAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        if(state.settings.adaptiveRangesEnabled)item{Text(s.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}
                        if(s.candidates.isEmpty())item{EmptyState("NO QUALIFIED UC CANDIDATE",if(state.marketSession.isOpen)"No forced recommendation." else "This is the result from the last successful market-session scan.")}
                        else items(s.candidates){CandidateCard(it)}
                    }
                }
                1->{
                    val s=state.dualSummary?.demand
                    if(s==null)item{EmptyState("No stored pressure scan","Run during market hours. Stored session results remain visible after close.")}
                    else{
                        item{AccuracyCard(state,ScannerSection.DEMAND_SQUEEZE)}
                        item{ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
                            Text("Prediction target",style=MaterialTheme.typography.titleMedium)
                            Text(
                                if(state.settings.adaptiveRangesEnabled) "+1.5–4.5% adaptive price-spike target within ${state.settings.demandPredictionHorizonHours}h • ${DemandSignalEngine.MODEL_VERSION}"
                                else "+${"%.1f".format(state.settings.demandSpikeTargetPct)}% price spike within ${state.settings.demandPredictionHorizonHours}h • ${DemandSignalEngine.MODEL_VERSION}",
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Stocks already at UC or already showing extreme buy pressure are rejected by the early-entry gates.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Scan completed ${formatIstTimestamp(s.completedAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(state.settings.adaptiveRangesEnabled)Text(s.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        }}}
                        if(s.candidates.isEmpty())item{EmptyState("NO PRE-PRESSURE CANDIDATE",if(state.marketSession.isOpen)"No stock cleared the early price-spike threshold." else "This is the last stored session result, not a fresh after-hours scan.")}
                        else items(s.candidates){CandidateCard(it)}
                    }
                }
                else->{
                    val t=state.strategyTournamentSummary
                    item{
                        ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Text("Trading Strategies",style=MaterialTheme.typography.titleMedium)
                            Text("All-session intraday engine • LONG + SHORT • recommendations persist until target, stop or end-of-day.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Execution gate: NSE EQ • ₹20+ • volume ≥50k • traded value ≥₹25L • spread ≤1.5% • usable bid/ask.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        }}
                    }
                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(vm::refreshTradingStrategies,enabled=!state.busy&&state.authenticated&&state.marketSession.isOpen,modifier=Modifier.weight(1f)){Text(if(state.marketSession.isOpen)"Scan now" else "Market closed")}
                        OutlinedButton(vm::refreshStrategyCatalog,enabled=!state.busy,modifier=Modifier.weight(1f)){Text("Strategies")}
                    }}
                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        MetricCard("LIVE",state.strategyLive.size.toString(),Modifier.weight(1f))
                        MetricCard("CLOSED",state.strategyClosed.size.toString(),Modifier.weight(1f))
                        MetricCard("Rules",t?.strategiesRun?.toString()?:"20",Modifier.weight(1f))
                    }}
                    item{TabRow(selectedTabIndex=strategyView){
                        Tab(selected=strategyView==0,onClick={strategyView=0},text={Text("Live")})
                        Tab(selected=strategyView==1,onClick={strategyView=1},text={Text("Closed")})
                    }}
                    if(strategyView==0){
                        if(state.strategyLive.isEmpty())item{EmptyState("NO LIVE STRATEGY CALL","No executable setup has cleared the strategy + liquidity gates. Existing calls are never removed just because a later scan ranks something else.")}
                        else items(state.strategyLive,key={it.id}){r->StrategyLiveCard(r)}
                    }else{
                        if(state.strategyClosed.isEmpty())item{EmptyState("NO CLOSED CALLS YET","Completed strategy calls will stay here with their outcome and realized model return.")}
                        else items(state.strategyClosed.take(100),key={it.id}){r->StrategyClosedCard(r)}
                    }
                    if(t!=null){
                        item{Text("Strategy learning",style=MaterialTheme.typography.titleMedium)}
                        items(t.performances.take(10)){m->
                            ElevatedCard(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Column(Modifier.weight(1f)){Text(m.name);Text("${m.status.name} • ${m.wins}/${m.observations} • expectancy ${"%.2f".format(m.expectancyPct)}%",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                Text(if(m.observations==0)"Learning" else "${"%.1f".format(m.accuracyPct)}%")
                            }}
                        }
                        item{Text("Last scan ${formatIstTimestamp(t.generatedAt)} • ${t.message}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
    }
}


@Composable
private fun StrategyLiveCard(r:StrategyRecommendation){
    val s=r.setup;val short=s.direction==TradeDirection.SHORT
    val stop=if(short)s.entryPrice*(1+s.stopPct/100.0) else s.entryPrice*(1-s.stopPct/100.0)
    val target=if(short)s.entryPrice*(1-s.targetPct/100.0) else s.entryPrice*(1+s.targetPct/100.0)
    ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(s.symbol,style=MaterialTheme.typography.titleMedium)
            Text("${s.direction.name} • ${"%.0f".format(s.score)}",color=if(short)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Text(s.strategyName,style=MaterialTheme.typography.bodyMedium)
        Text("Entry ₹${"%.2f".format(s.entryPrice)} • LTP ₹${"%.2f".format(r.lastPrice)}",style=MaterialTheme.typography.bodySmall)
        Text("Stop ₹${"%.2f".format(stop)} • Target ₹${"%.2f".format(target)}",style=MaterialTheme.typography.bodySmall)
        Text("Vol ${r.volume} • traded ₹${"%.1f".format(r.tradedValue/100000.0)}L • spread ${"%.2f".format(r.spreadPct)}%",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Opened ${formatIstTimestamp(r.openedAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}

@Composable
private fun StrategyClosedCard(r:StrategyRecommendation){
    val s=r.setup
    val label=when(r.status){
        StrategyRecommendationStatus.WIN->"WIN"
        StrategyRecommendationStatus.LOSS->"LOSS"
        StrategyRecommendationStatus.INVALIDATED->"INVALIDATED"
        StrategyRecommendationStatus.EXPIRED->"EXPIRED"
        StrategyRecommendationStatus.LIVE->"LIVE"
    }
    ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(s.symbol,style=MaterialTheme.typography.titleMedium)
            Text(label,color=if(r.status==StrategyRecommendationStatus.WIN)MaterialTheme.colorScheme.primary else if(r.status==StrategyRecommendationStatus.LOSS)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${s.direction.name} • ${s.strategyName}",style=MaterialTheme.typography.bodyMedium)
        Text("Entry ₹${"%.2f".format(s.entryPrice)} • Exit ₹${"%.2f".format(r.exitPrice)} • ${if(r.returnPct>=0)"+" else ""}${"%.2f".format(r.returnPct)}%",style=MaterialTheme.typography.bodySmall)
        Text(r.closeReason,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(r.closedAt>0)Text("Closed ${formatIstTimestamp(r.closedAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
}

@Composable
private fun AccuracyCard(state:UiState,section:ScannerSection){
    val a=state.accuracies[section];val demand=section==ScannerSection.DEMAND_SQUEEZE
    ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
        Text(if(demand)"Spike-prediction accuracy" else "Next-session UC accuracy",style=MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(18.dp)){
            Column(Modifier.weight(1f)){Text("All learned",style=MaterialTheme.typography.labelMedium);Text(if(a==null||a.evaluated==0)"Learning" else "%.1f%%".format(a.accuracyPct),style=MaterialTheme.typography.titleLarge)}
            Column(Modifier.weight(1f)){Text("Last 24h",style=MaterialTheme.typography.labelMedium);Text(if(a==null||a.last24hEvaluated==0)"—" else "%.1f%%".format(a.last24hAccuracyPct),style=MaterialTheme.typography.titleLarge)}
            Column(Modifier.weight(1f)){Text("Sample",style=MaterialTheme.typography.labelMedium);Text("${a?.hits?:0}/${a?.evaluated?:0}",style=MaterialTheme.typography.titleLarge)}
        }
        if(demand){Spacer(Modifier.height(6.dp));Text(
            if(state.settings.adaptiveRangesEnabled) "Success target is stored per candidate and adapts between +1.5% and +4.5% from the frozen prediction price."
            else "Success = next trading session reaches at least +${"%.1f".format(state.settings.demandSpikeTargetPct)}% from the frozen prediction price.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
        )}
    }}
}
