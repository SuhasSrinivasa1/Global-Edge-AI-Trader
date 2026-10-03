package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.model.*

@Composable
fun GlobalLeadScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val summary=state.globalLeadSummary
    var selectedDirection by remember{mutableStateOf(GlobalLeadDirection.LONG)}
    val visible=when(selectedDirection){
        GlobalLeadDirection.LONG->summary?.longCandidates.orEmpty()
        GlobalLeadDirection.SHORT->summary?.shortCandidates.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(padding)){
        AppHeader("Global Lead","Foreign-market move → Indian opportunity • dynamic LONG + SHORT watchlists")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)){
            item{StatusStrip(state)}
            item{
                ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
                    Text("How this window works",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("Foreign markets are leading signals only; the actionable side is India. LONG looks for foreign strength followed by Indian continuation. SHORT looks for foreign weakness followed by Indian downside continuation. A gap alone is not enough: catch-up moves, benchmark excess return, abnormal volume and post-open follow-through are checked. The weekly catalogue combines direct ADR/parent relationships with broad Nifty 500 sector/global proxies. Proxy links are lower-weight research signals, not claims that two companies are the same.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("9:15 AM entry rule: Global Lead is used to select Indian LONG/SHORT entries after the NSE open. Enter only after the mapped foreign lead and Indian post-open continuation both confirm. The 3 PM check is for reassessment / exit management, not the primary entry decision. No order is placed automatically.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("SHORT watchlist is research-only. Normal cash-market naked short selling is not a delivery product; overnight delivery shorts require an eligible securities-borrowing route such as SLBM through your broker. Verify product/eligibility before acting.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }}
            }
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(selectedDirection==GlobalLeadDirection.LONG){
                        Button(onClick={selectedDirection=GlobalLeadDirection.LONG},modifier=Modifier.weight(1f)){Text("LONG  ${summary?.longCandidates?.size?:0}")}
                        OutlinedButton(onClick={selectedDirection=GlobalLeadDirection.SHORT},modifier=Modifier.weight(1f)){Text("SHORT  ${summary?.shortCandidates?.size?:0}")}
                    }else{
                        OutlinedButton(onClick={selectedDirection=GlobalLeadDirection.LONG},modifier=Modifier.weight(1f)){Text("LONG  ${summary?.longCandidates?.size?:0}")}
                        Button(onClick={selectedDirection=GlobalLeadDirection.SHORT},modifier=Modifier.weight(1f)){Text("SHORT  ${summary?.shortCandidates?.size?:0}")}
                    }
                }
            }
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick=vm::refreshGlobalLead,enabled=!state.busy,modifier=Modifier.weight(1f)){
                    Icon(Icons.Default.Public,null);Spacer(Modifier.width(5.dp));Text("Refresh both")
                }
                OutlinedButton(onClick=vm::refreshGlobalMappings,enabled=!state.busy,modifier=Modifier.weight(1f)){
                    Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(5.dp));Text("Refresh map")
                }
            }}
            item{
                ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
                    Text("Global mapping",style=MaterialTheme.typography.titleMedium)
                    Text("Version ${state.globalMappingVersion.ifBlank{"embedded"}} • weekly rebuilt coverage",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last map refresh: ${formatIstTimestamp(state.lastGlobalMappingRefreshAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last global scan: ${formatIstTimestamp(state.lastGlobalLeadScanAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
            }
            if(summary==null){
                item{EmptyState("No Global Lead scan yet","After Groww authentication the app scans global counterparts automatically. You can also refresh both watchlists now.")}
            }else{
                item{
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        MetricCard("Global links",summary.mappingsScanned.toString(),Modifier.weight(1f))
                        MetricCard("LONG",summary.longCandidates.size.toString(),Modifier.weight(1f))
                        MetricCard("SHORT",summary.shortCandidates.size.toString(),Modifier.weight(1f))
                    }
                }
                item{Text(summary.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(summary.droppedSincePrevious.isNotEmpty()) item{
                    ElevatedCard(Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){
                        Column(Modifier.padding(12.dp)){
                            Text("Dropped since previous scan",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onErrorContainer)
                            Text(summary.droppedSincePrevious.joinToString(" • "),color=MaterialTheme.colorScheme.onErrorContainer)
                            Text("If you hold a matching position, reassess it immediately; near 3 PM this is the removal / exit queue.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                if(visible.isEmpty()){
                    item{EmptyState("No ${selectedDirection.name.lowercase()} setup right now","The app will not manufacture a directional signal just to fill ten slots. This side repopulates as mapped foreign markets produce qualifying moves.")}
                }else{
                    items(visible,key={"${it.direction.name}-${it.indianSymbol}"}){GlobalLeadCard(it)}
                }
            }
        }
    }
}

@Composable
private fun GlobalLeadCard(c:GlobalLeadCandidate){
    val short=c.direction==GlobalLeadDirection.SHORT
    val actionText=when(c.action){
        GlobalLeadAction.ENTER_AFTER_OPEN->if(short)"SHORT ENTRY CANDIDATE" else "LONG ENTRY CANDIDATE"
        GlobalLeadAction.WAIT_FOR_CONFIRMATION->"WAIT / CONFIRM"
        GlobalLeadAction.KEEP_NEXT_SESSION->if(short)"NEXT OPEN SHORT / ENTER AFTER 09:15" else "NEXT OPEN / ENTER AFTER 09:15"
        GlobalLeadAction.EXIT_BY_3PM->if(short)"COVER / DROP BY 3 PM" else "EXIT / DROP BY 3 PM"
        GlobalLeadAction.NEXT_OPEN_WATCH->if(short)"NEXT-OPEN SHORT WATCH" else "NEXT-OPEN LONG WATCH"
        GlobalLeadAction.OBSERVE->"OBSERVE ONLY"
    }
    val actionColor=when(c.action){
        GlobalLeadAction.ENTER_AFTER_OPEN,GlobalLeadAction.KEEP_NEXT_SESSION,GlobalLeadAction.NEXT_OPEN_WATCH->if(short)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        GlobalLeadAction.EXIT_BY_3PM->MaterialTheme.colorScheme.tertiary
        else->MaterialTheme.colorScheme.tertiary
    }
    ElevatedCard(Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
        Column(Modifier.padding(15.dp)){
            Row{
                Column(Modifier.weight(1f)){
                    Text("#${c.rank}  ${c.indianSymbol}",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(c.indianCompany,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(actionText,color=actionColor,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)
                }
                AssistChip(onClick={},label={Text("${"%.0f".format(c.score)} • ${c.confidence.name.replace("_"," ")}")})
            }
            Spacer(Modifier.height(10.dp))
            Text("Lead: ${c.foreignTicker} • ${c.foreignCompany} • ${c.exchange}",fontWeight=FontWeight.SemiBold)
            Text("${c.mappingType.name.replace("_"," ")} • relationship ${"%.0f".format(c.relationshipWeight*100)}%",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                MetricCard("Foreign gap","${signed(c.foreignGapPct)}%",Modifier.weight(1f))
                MetricCard("Foreign move","${signed(c.foreignDayPct)}%",Modifier.weight(1f))
                MetricCard("Vol","${"%.1f".format(c.foreignVolumeRatio)}x",Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                MetricCard("India open",if(c.indianOpen>0)"₹${"%.2f".format(c.indianOpen)}" else "—",Modifier.weight(1f))
                MetricCard("India LTP",if(c.indianPrice>0)"₹${"%.2f".format(c.indianPrice)}" else "—",Modifier.weight(1f))
                MetricCard("From open","${signed(c.indianFromOpenPct)}%",Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if(c.indianPrice>0){
                val plan=globalExecutionPlan(c)
                Text("Execution plan",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleSmall)
                Text(
                    if(short) "SHORT only if price trades at / below ₹${"%.2f".format(plan.trigger)}"
                    else "LONG only if price trades at / above ₹${"%.2f".format(plan.trigger)}",
                    fontWeight=FontWeight.Bold,
                    color=actionColor
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    MetricCard("Trigger","₹${"%.2f".format(plan.trigger)}",Modifier.weight(1f))
                    MetricCard("Model stop","₹${"%.2f".format(plan.stop)}",Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    MetricCard("Target 1","₹${"%.2f".format(plan.target1)}",Modifier.weight(1f))
                    MetricCard("Target 2","₹${"%.2f".format(plan.target2)}",Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if(short) "Invalidation: cover / cancel the setup if price trades at or above ₹${"%.2f".format(plan.stop)}; a sustained reclaim of the India open also cancels the downside thesis."
                    else "Invalidation: exit / cancel the setup if price trades at or below ₹${"%.2f".format(plan.stop)}; a sustained loss of the India open also cancels the upside thesis.",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Levels are generated from the latest validated quote and session structure. Revalidate live price, spread and liquidity before acting.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            Text("Freshness ${"%.2f".format(c.freshnessPct)}% directional • benchmark excess ${signed(c.foreignExcessPct)}% • pressure confirmation ${"%.0f".format(c.pressureConfirmationScore)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(c.reasons.isNotEmpty()){
                Spacer(Modifier.height(6.dp));Text(c.reasons.joinToString(" • "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(if(short)"Research target: -${"%.1f".format(c.expectedTargetPct)}% continuation after entry; 3 PM is reassessment / exit management, not the entry deadline." else "Research target: +${"%.1f".format(c.expectedTargetPct)}% continuation after entry; 3 PM is reassessment / exit management, not the entry deadline.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
        }
    }
}


private data class GlobalExecutionPlan(val trigger:Double,val stop:Double,val target1:Double,val target2:Double)

private fun globalExecutionPlan(c:GlobalLeadCandidate):GlobalExecutionPlan{
    val short=c.direction==GlobalLeadDirection.SHORT
    val base=c.indianPrice.coerceAtLeast(0.01)
    val trigger=if(short)base*0.999 else base*1.001
    val openDistancePct=if(c.indianOpen>0.0)kotlin.math.abs(c.indianOpen-trigger)/trigger*100.0 else 0.0
    val riskPct=(openDistancePct*0.25).takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,1.00)?:0.50
    val targetPct=c.expectedTargetPct.takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,3.00)?:0.50
    val target2Pct=(targetPct*1.75).coerceIn(targetPct+0.20,4.00)
    val stop=if(short)trigger*(1.0+riskPct/100.0) else trigger*(1.0-riskPct/100.0)
    val target1=if(short)trigger*(1.0-targetPct/100.0) else trigger*(1.0+targetPct/100.0)
    val target2=if(short)trigger*(1.0-target2Pct/100.0) else trigger*(1.0+target2Pct/100.0)
    return GlobalExecutionPlan(trigger,stop,target1,target2)
}

private fun signed(v:Double)=if(v>=0)"+${"%.2f".format(v)}" else "%.2f".format(v)
