package com.suhas.globaledgeai.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.model.MultifyEventType
import com.suhas.globaledgeai.domain.model.MultifyShadowStatus
import java.util.Locale

private fun money(v:Double)=String.format(Locale.US,"₹%,.0f",v)

@Composable
fun MultifyCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val ctx=LocalContext.current
    val events=state.multifyEvents.sortedByDescending{it.capturedAt}
    val dash=state.multifyDashboard
    val open=state.multifyShadowTrades.filter{it.status==MultifyShadowStatus.OPEN}.sortedByDescending{it.openedAt}
    val evaluated=events.count{it.evaluation!="PENDING"}
    val positive=events.count{it.evaluation in setOf("EDGE_POSITIVE","EXIT_FALL_EDGE")}
    val netColor=if(dash.todayNet>=0.0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),
        contentPadding=PaddingValues(top=12.dp,bottom=24.dp)
    ){
        item{
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){
                        Text("Multify Intraday Lab",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                        Text("Immediate equity reaction • stock-specific wave memory • shadow-first learning",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if(state.multifyListenerEnabled)"LISTENING" else "OFF",color=if(state.multifyListenerEnabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("SHADOW P&L",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        Text(money(dash.todayNet),style=MaterialTheme.typography.titleLarge,color=netColor,fontWeight=FontWeight.Bold)
                    }
                    Text("Target ${money(dash.dailyNetTarget)} net/day • Multify capital ${money(dash.capitalBudget)} • ${dash.targetBand}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress={((dash.todayNet.coerceAtLeast(0.0)/dash.dailyNetTarget).coerceIn(0.0,1.0)).toFloat()},modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Realized ${money(dash.todayRealizedNet)}",style=MaterialTheme.typography.labelSmall)
                        Text("Open ${money(dash.todayUnrealizedNet)}",style=MaterialTheme.typography.labelSmall)
                        Text("Exposure ${money(dash.openExposure)}",style=MaterialTheme.typography.labelSmall)
                    }
                    Text("5-session avg ${money(dash.fiveSessionAverageNet)} • target days ${dash.daysAtOrAboveTarget}/5 • alerts today ${dash.alertsToday} • closed waves ${dash.closedTradesToday}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("EXIT→fall edge ${dash.exitFallWins}/${dash.exitFallSamples} (${String.format(Locale.US,"%.1f",dash.exitFallRatePct)}%) • Mode: ${dash.automationMode}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Column(Modifier.weight(1f)){
                            Text("Real Groww orders",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text(if(state.settings.multifyLiveTradingEnabled)"ON • Multify LIVE decisions and learned waves can place NSE CASH MIS orders automatically." else "OFF • identical decisions run in shadow only.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked=state.settings.multifyLiveTradingEnabled,onCheckedChange=vm::setMultifyLiveTrading)
                    }
                    Text("LIVE resets OFF on the next IST date. The ₹2,00,000 Multify capital ceiling and the same Shadow decision/quantity are used for real orders. Existing live positions remain managed even if you switch OFF new entries.",style=MaterialTheme.typography.labelSmall,color=if(state.settings.multifyLiveTradingEnabled)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick=vm::exitAllMultify,enabled=!state.busy&&state.authenticated&&open.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
                        Text("STOP / EXIT ALL MULTIFY POSITIONS")
                    }
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    Text("Multify listener + immediate analysis",style=MaterialTheme.typography.titleMedium)
                    Text(
                        if(state.multifyListenerEnabled)
                            "Enabled. NSE cash-equity BUY/SELL/EXIT notifications are captured. Options, futures, indices and commodity-style alerts are filtered before the Multify learning lane."
                        else
                            "Notification access is required before the app can capture Multify calls. Enable Global Edge AI Trader in Android Notification access.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))},modifier=Modifier.weight(1f)){
                            Text(if(state.multifyListenerEnabled)"Notification access" else "Enable listener")
                        }
                        OutlinedButton(onClick=vm::replayMultifyNow,enabled=!state.busy&&state.authenticated&&events.any{it.evaluation=="PENDING"},modifier=Modifier.weight(1f)){
                            Text("Replay pending")
                        }
                    }
                }
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text("Decision loop",style=MaterialTheme.typography.titleMedium)
                    Text("ENTRY → immediately front-load this symbol → score price, volume, VWAP, momentum, candles and stock memory → LIVE / DEVELOPING / WATCH / NO TRADE.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("EXIT → close the current shadow-long if present, measure 1/3/5/15-minute decay, immediately test a SHORT hypothesis, then keep watching the symbol for later up/down waves.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Shadow and LIVE use the same decision engine. With REAL ORDERS ON, LIVE-tier Multify decisions submit Groww MIS orders; DEVELOPING/WATCH remain research-only.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        if(open.isNotEmpty()){
            item{Text("OPEN SHADOW WAVES",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(open,key={it.id}){t->
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text("${t.symbol} • ${t.side}",fontWeight=FontWeight.Bold)
                            val pnl=if(t.side.name=="LONG")(t.lastPrice-t.entryPrice)*t.quantity else (t.entryPrice-t.lastPrice)*t.quantity
                            Text(money(pnl),color=if(pnl>=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)
                        }
                        Text("wave ${t.wave} • ${t.strategyTag} • qty ${t.quantity} • entry ₹${String.format(Locale.US,"%.2f",t.entryPrice)} • last ₹${String.format(Locale.US,"%.2f",t.lastPrice)}",style=MaterialTheme.typography.bodySmall)
                        Text("MFE ${String.format(Locale.US,"%+.2f",t.mfePct)}% • MAE ${String.format(Locale.US,"%+.2f",t.maePct)}% • ${t.contextKey}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(t.liveEntryReference.isNotBlank())Text("LIVE entry ref ${t.liveEntryReference}${if(t.liveExitReference.isNotBlank())" • exit ref ${t.liveExitReference}" else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                        if(t.liveExecutionNote.isNotBlank())Text(t.liveExecutionNote,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(state.multifyProfiles.isNotEmpty()){
            item{Text("STOCK-SPECIFIC MEMORY",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(state.multifyProfiles.take(12),key={"profile-${it.symbol}"}){p->
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(p.symbol,fontWeight=FontWeight.Bold)
                            Text("net ${money(p.netPnl)}",color=if(p.netPnl>=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        Text("Champion LONG: ${p.bestLongStrategy.ifBlank{"learning"}} • SHORT: ${p.bestShortStrategy.ifBlank{"learning"}}",style=MaterialTheme.typography.bodySmall)
                        if(p.exitFallSamples>0)Text("Multify EXIT fall: ${p.exitFallWins}/${p.exitFallSamples} • avg 5m ${String.format(Locale.US,"%.2f",p.avgExitFall5mPct)}% • 15m ${String.format(Locale.US,"%.2f",p.avgExitFall15mPct)}%",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(state.multifyDecisions.isNotEmpty()){
            item{Text("RECENT GLOBAL EDGE DECISIONS",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(state.multifyDecisions.take(20),key={"decision-${it.eventId}-${it.generatedAt}"}){d->
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(d.symbol,fontWeight=FontWeight.Bold)
                            Text("${d.tier} ${d.direction?.name.orEmpty()}",color=if(d.tier.name=="LIVE")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.SemiBold)
                        }
                        Text("score ${String.format(Locale.US,"%.1f",d.score)} • ${d.strategyTag} • ₹${String.format(Locale.US,"%.2f",d.price)}",style=MaterialTheme.typography.bodySmall)
                        Text(d.reason,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item{
            Text("Captured ${events.size} • evaluated $evaluated • positive/exit edge $positive",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(events.isEmpty()){
            item{
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("No Multify equity events captured yet",fontWeight=FontWeight.Bold)
                        Text("After notification access is enabled, matching cash-equity notifications will appear here automatically and immediate analysis will be queued.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }else{
            item{Text("RECENT MULTIFY EVENTS",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(events.take(100),key={it.id}){e->
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(e.symbol.ifBlank{"Unparsed symbol"},fontWeight=FontWeight.Bold)
                            Text(e.eventType.name.replace('_',' '),color=if(e.eventType in setOf(MultifyEventType.ENTRY_SHORT,MultifyEventType.EXIT))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                        }
                        Text(formatIstTimestamp(e.capturedAt)+(if(e.signalPrice>0)" • signal ₹${"%.2f".format(e.signalPrice)}" else "")+" • ${e.instrumentClass}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(e.decisionTier.isNotBlank())Text("Global Edge: ${e.decisionTier} ${e.decisionDirection} • score ${String.format(Locale.US,"%.1f",e.decisionScore)} • ${e.decisionStrategy}",style=MaterialTheme.typography.bodySmall)
                        Text((e.title+" "+e.text).trim().take(220),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(e.evaluation=="PENDING"){
                            Text("PENDING PATH REPLAY",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                        }else{
                            Text("${e.evaluation.replace('_',' ')} • 1m ${"%+.2f".format(e.return1mPct)}% • 3m ${"%+.2f".format(e.return3mPct)}% • 5m ${"%+.2f".format(e.return5mPct)}% • 15m ${"%+.2f".format(e.return15mPct)}%",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(e.eventType==MultifyEventType.EXIT)Text("Post-exit fall • 5m ${"%.2f".format(e.postExitFall5mPct)}% • 15m ${"%.2f".format(e.postExitFall15mPct)}%",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
