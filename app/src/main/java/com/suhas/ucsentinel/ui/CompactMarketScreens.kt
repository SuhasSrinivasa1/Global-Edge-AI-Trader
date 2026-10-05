package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suhas.globaledgeai.domain.model.*
import com.suhas.globaledgeai.notifications.AppNotifier
import android.widget.Toast
import kotlin.math.floor
import kotlin.math.max

private enum class CompactView{LIVE,CLOSED}
private enum class UcCompactView{NEXT_SESSION,LIVE,DEVELOPING,WATCH,THREE_PM,CLOSED}
private enum class PressureCompactView{NEXT_SESSION,LIVE,CLOSED}
private enum class GlobalCompactView{NEXT_SESSION,LIVE,CLOSED}

@Composable
private fun CompactHeader(title:String,state:UiState,lastUpdated:Long=0L,onRefresh:(()->Unit)?=null,scheduleText:String?=null){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Column(Modifier.weight(1f)){
                Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text(scheduleText?:"09:15–15:30 IST • ${state.marketSession.label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(onRefresh!=null)TextButton(onClick=onRefresh,enabled=!state.busy&&state.authenticated){Text("Refresh")}
        }
        if(lastUpdated>0)Text("Updated ${formatIstTimestamp(lastUpdated)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!state.authenticated)Text("Groww authentication required",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
        else {
            Text("5 min live • 15 min learning • nightly deep replay • shadow validation",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            val multify=if(state.multifyListenerEnabled)"Multify ON" else "Multify OFF"
            val learning=if(state.settings.learningEnabled)"Learning ON" else "Learning OFF"
            Text("System • Groww ${state.growwApiHealth.status} • $multify • $learning",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val hbAgeMs=if(state.scannerHeartbeatAt>0L)(System.currentTimeMillis()-state.scannerHeartbeatAt).coerceAtLeast(0L) else Long.MAX_VALUE
            val hbFresh=hbAgeMs<=3L*60_000L
            val hbText=when{
                state.scannerHeartbeatAt<=0L->"Scanner STARTING • no heartbeat yet"
                hbFresh->"Scanner ACTIVE • heartbeat ${(hbAgeMs/1000L).coerceAtLeast(0)}s ago"
                else->"Scanner STALE • heartbeat ${hbAgeMs/60_000L}m ago • keep background/auto-start allowed"
            }
            Text(hbText,style=MaterialTheme.typography.labelSmall,color=if(!hbFresh&&state.marketSession.isOpen)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ViewSwitch(view:CompactView,onChange:(CompactView)->Unit,liveCount:Int,closedCount:Int){
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(selected=view==CompactView.LIVE,onClick={onChange(CompactView.LIVE)},label={Text("LIVE $liveCount")})
        FilterChip(selected=view==CompactView.CLOSED,onClick={onChange(CompactView.CLOSED)},label={Text("CLOSED $closedCount")})
    }
}

private data class SimplePlan(val entry:Double,val stop:Double,val target1:Double,val target2:Double?=null)
private fun tradeCallPlan(r:TradeCallRecord)=SimplePlan(r.entryPrice,r.stopPrice,r.targetPrice,r.target2Price)
private fun autopsyStatus(state:UiState,sourceId:String):String?{
    val a=state.tradeAutopsies.firstOrNull{it.sourceId==sourceId}?:return null
    val best=a.shadowResults.firstOrNull{it.outcome==ShadowOutcome.AVOIDED_LOSS||it.outcome==ShadowOutcome.WIN}
    val shadow=best?.let{" • shadow ${it.strategyName}: ${it.outcome.name.replace('_',' ')}"}.orEmpty()
    return "Autopsy • ${a.regime.name.replace('_',' ')} • ${a.dominantCause.name.replace('_',' ')} ${"%.0f".format(a.causeConfidencePct)}%$shadow"
}

private fun ucPlan(c:Candidate):SimplePlan{
    val entry=c.price.coerceAtLeast(0.01)
    val target=if(c.upperCircuit>entry)c.upperCircuit else entry*1.02
    return SimplePlan(entry,entry*0.985,target)
}
private fun pressurePlan(c:Candidate):SimplePlan{
    val entry=c.price.coerceAtLeast(0.01)
    val targetPct=(c.targetMovePct?:2.5).coerceIn(0.5,8.0)
    return SimplePlan(entry,entry*0.985,entry*(1.0+targetPct/100.0))
}
private fun strategyPlan(s:StrategySetup):SimplePlan{
    val short=s.direction==TradeDirection.SHORT
    val entry=s.entryPrice.coerceAtLeast(0.01)
    val stop=if(short)entry*(1.0+s.stopPct.coerceAtLeast(0.1)/100.0) else entry*(1.0-s.stopPct.coerceAtLeast(0.1)/100.0)
    val target=if(short)entry*(1.0-s.targetPct.coerceAtLeast(0.1)/100.0) else entry*(1.0+s.targetPct.coerceAtLeast(0.1)/100.0)
    return SimplePlan(entry,stop,target)
}
private fun globalPlan(c:GlobalLeadCandidate):SimplePlan?{
    if(!c.indianPrice.isFinite()||c.indianPrice<20.0)return null
    val short=c.direction==GlobalLeadDirection.SHORT
    val base=c.indianPrice
    val entry=if(short)base*0.999 else base*1.001
    val openDistancePct=if(c.indianOpen>0.0)kotlin.math.abs(c.indianOpen-entry)/entry*100.0 else 0.0
    val riskPct=(openDistancePct*0.25).takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,1.00)?:0.50
    val targetPct=c.expectedTargetPct.takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,3.00)?:0.50
    val target2Pct=(targetPct*1.75).coerceIn(targetPct+0.20,4.00)
    val stop=if(short)entry*(1.0+riskPct/100.0) else entry*(1.0-riskPct/100.0)
    val t1=if(short)entry*(1.0-targetPct/100.0) else entry*(1.0+targetPct/100.0)
    val t2=if(short)entry*(1.0-target2Pct/100.0) else entry*(1.0+target2Pct/100.0)
    return SimplePlan(entry,stop,t1,t2)
}

@Composable
private fun PlanMetric(label:String,value:String,modifier:Modifier=Modifier){
    Surface(modifier=modifier,shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Column(Modifier.padding(horizontal=9.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Bold,maxLines=1)
        }
    }
}

@Composable
private fun ScoreBadge(score:Double,onClick:(()->Unit)?=null){
    val modifier=if(onClick!=null)Modifier.clickable{onClick()} else Modifier
    Surface(modifier=modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.secondaryContainer){
        Column(Modifier.padding(horizontal=10.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text("${score.toInt()}%",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,maxLines=1)
            Text(if(onClick!=null)"MODEL • TAP" else "MODEL",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer,maxLines=1)
        }
    }
}

@Composable
private fun TradeCard(symbol:String,direction:String,score:Double,plan:SimplePlan?,detail:String,status:String?=null,orderable:Boolean=false,vm:MainViewModel?=null){
    val ctx=LocalContext.current
    var showOrder by remember(symbol,direction,plan?.entry){mutableStateOf(false)}
    var submitting by remember(symbol,direction,plan?.entry){mutableStateOf(false)}
    val short=direction.contains("SHORT",true)||direction.contains("SELL",true)
    val side=if(short)"SELL" else "BUY"
    val product=if(short)"MIS" else "CNC"
    val qty=if(plan!=null&&plan.entry>0.0)floor(20000.0/plan.entry).toInt().coerceAtLeast(0) else 0
    if(showOrder&&plan!=null){
        AlertDialog(
            onDismissRequest={showOrder=false},
            title={Text("Review ₹20,000 order ticket")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(side+" "+symbol+" • "+product)
                Text("Quantity "+qty+" • budget cap ₹20,000")
                Text("Entry ₹"+"%.2f".format(plan.entry)+" • Stop ₹"+"%.2f".format(plan.stop)+" • Target ₹"+"%.2f".format(plan.target1))
                Text("Manual order • NSE CASH MARKET. Nothing is sent until you tap PLACE ORDER. The displayed stop and target are strategy references; this version submits the entry order only.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }},
            dismissButton={TextButton(onClick={if(!submitting){showOrder=false}},enabled=!submitting){Text("Cancel")}},
            confirmButton={
                Button(onClick={
                    if(qty<=0){
                        Toast.makeText(ctx,"Budget insufficient for one share",Toast.LENGTH_LONG).show()
                    }else if(vm==null){
                        Toast.makeText(ctx,"Order service unavailable",Toast.LENGTH_LONG).show()
                    }else{
                        submitting=true
                        vm.placeManualOrder(symbol,side,product,qty,plan.entry){ok,message->
                            submitting=false
                            if(ok){
                                showOrder=false
                                AppNotifier.notifyPreparedOrder(ctx,symbol,side,product,qty,plan.entry,plan.stop,plan.target1)
                                Toast.makeText(ctx,message,Toast.LENGTH_LONG).show()
                            }else{
                                Toast.makeText(ctx,"Order not placed: "+message,Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },enabled=qty>0&&!submitting&&vm!=null){Text(if(submitting)"PLACING…" else "PLACE ORDER")}
            }
        )
    }
    ElevatedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){
        Column(Modifier.padding(horizontal=13.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(symbol,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1)
                    Text(direction,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,maxLines=2,
                        color=if(direction.contains("SHORT")||direction.contains("SELL")||direction.contains("LOSS"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                ScoreBadge(score,if(orderable&&plan!=null){{showOrder=true}}else null)
            }
            if(plan!=null){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    PlanMetric("ENTRY","₹${"%.2f".format(plan.entry)}",Modifier.weight(1f))
                    PlanMetric("STOP","₹${"%.2f".format(plan.stop)}",Modifier.weight(1f))
                    PlanMetric("TARGET","₹${"%.2f".format(plan.target1)}",Modifier.weight(1f))
                }
                if(plan.target2!=null)Text("Target 2  ₹${"%.2f".format(plan.target2)}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
            }else{
                Text("Entry / stop / targets will be set after Indian price confirmation at 09:15 IST",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.tertiary)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3)
            if(!status.isNullOrBlank()){
                val statusColor=if(status.contains("LOSS"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                Text(status,style=MaterialTheme.typography.labelSmall,color=statusColor,maxLines=3)
            }
        }
    }
}

@Composable
fun UpperCircuitCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val shown=state.dualSummary?.uc?.candidates.orEmpty().filter{it.price>=20.0}
    val ucMessage=state.dualSummary?.uc?.message.orEmpty()
    val ucNextSession=ucMessage.startsWith("NEXT SESSION")
    val live=if(ucNextSession) emptyList() else shown.filter{"UC_LIVE" in it.activeStrategies}
    val developing=if(ucNextSession) emptyList() else shown.filter{"UC_DEVELOPING" in it.activeStrategies}
    val watch=if(ucNextSession) emptyList() else shown.filter{"UC_WATCH" in it.activeStrategies}
    val calls=state.tradeCalls.filter{it.engine==TradeCallEngine.UPPER_CIRCUIT}
    val nextCalls=calls.filter{it.outcome==TradeCallOutcome.OPEN&&it.bucket==TradeCallBucket.NEXT_SESSION}.sortedByDescending{it.openedAt}
    val threePmCalls=calls.filter{it.outcome==TradeCallOutcome.OPEN&&it.bucket==TradeCallBucket.THREE_PM}.sortedByDescending{it.openedAt}
    val closed=calls.filter{it.outcome!=TradeCallOutcome.OPEN}.sortedByDescending{it.closedAt}
    var view by remember(state.marketSession.phase){mutableStateOf(
        if(!state.marketSession.isOpen) UcCompactView.NEXT_SESSION
        else if(live.isNotEmpty()) UcCompactView.LIVE
        else if(developing.isNotEmpty()) UcCompactView.DEVELOPING
        else if(watch.isNotEmpty()) UcCompactView.WATCH
        else UcCompactView.LIVE
    )}
    LaunchedEffect(state.marketSession.phase,live.size,developing.size,watch.size){
        if(state.marketSession.isOpen&&view==UcCompactView.LIVE&&live.isEmpty()&&developing.isNotEmpty())view=UcCompactView.DEVELOPING
        if(state.marketSession.isOpen&&view==UcCompactView.LIVE&&live.isEmpty()&&developing.isEmpty()&&watch.isNotEmpty())view=UcCompactView.WATCH
        if(state.marketSession.isOpen&&view!=UcCompactView.LIVE&&live.isNotEmpty())view=UcCompactView.LIVE
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
        item{CompactHeader("Upper Circuit",state,state.dualSummary?.uc?.completedAt?:0L,vm::runScan,"24h NEXT SESSION research • LIVE 09:15–15:30 • 3 PM priority 15:10–15:30")}
        if(ucMessage.isNotBlank())item{Text("Engine • $ucMessage",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.NEXT_SESSION,onClick={view=UcCompactView.NEXT_SESSION},label={Text("NEXT ${nextCalls.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.LIVE,onClick={view=UcCompactView.LIVE},label={Text("LIVE ${live.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.DEVELOPING,onClick={view=UcCompactView.DEVELOPING},label={Text("DEV ${developing.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.WATCH,onClick={view=UcCompactView.WATCH},label={Text("WATCH ${watch.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.THREE_PM,onClick={view=UcCompactView.THREE_PM},label={Text("3PM ${threePmCalls.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==UcCompactView.CLOSED,onClick={view=UcCompactView.CLOSED},label={Text("DONE ${closed.size}",style=MaterialTheme.typography.labelSmall,maxLines=1)})
            }
        }
        when(view){
            UcCompactView.NEXT_SESSION->{
                if(nextCalls.isEmpty())item{EmptyState("No next-session UC call yet","The off-hours engine keeps researching. A candidate is recorded here only when an entry plan is produced.")}
                else items(nextCalls.take(100),key={it.id}){r->
                    TradeCard(r.symbol,"NEXT SESSION • PRE-UC",r.score,tradeCallPlan(r),r.detail,"Target session ${r.targetSessionDate} • call ${formatIstTimestamp(r.openedAt)}")
                }
            }
            UcCompactView.LIVE->{
                if(live.isEmpty())item{EmptyState("No live upper-circuit call",if(ucMessage.isNotBlank())ucMessage else "The scanner will not force a stock into LIVE. Open WATCH to inspect near-miss research candidates.")}
                else items(live,key={it.symbol}){c->
                    val strict="EXECUTION_READY" in c.activeStrategies
                    TradeCard(c.symbol,"LONG • PRE-UC",c.score,ucPlan(c),"${c.companyName} • ${"%.2f".format(c.dayChangePercent)}% today • ${"%.1f".format(c.volumeRatio)}x volume",
                        if(strict)"LIVE • strict execution gate passed • tap MODEL score for ₹20,000 CNC manual order" else "LIVE SIGNAL • entry liquidity seen, but strict two-sided execution gate not passed • observation only",
                        orderable=strict,vm=vm)
                }
            }
            UcCompactView.DEVELOPING->{
                if(developing.isEmpty())item{EmptyState("No developing UC candidate","DEVELOPING shows near-threshold PRE-UC signals while the engine waits for full LIVE confirmation.")}
                else items(developing,key={"developing-${it.symbol}"}){c->TradeCard(c.symbol,"DEVELOPING • PRE-UC",c.score,ucPlan(c),
                    "${c.companyName} • ${"%.2f".format(c.dayChangePercent)}% today • ${"%.1f".format(c.volumeRatio)}x volume • ${c.activeStrategies.joinToString(" • ")}",
                    "Research only • strong signal, still waiting for LIVE score/liquidity confirmation")}
            }
            UcCompactView.WATCH->{
                if(watch.isEmpty())item{EmptyState("No UC watch candidate","WATCH shows the strongest scored PRE-UC names when the LIVE liquidity/score gate is not yet cleared.")}
                else items(watch,key={"watch-${it.symbol}"}){c->TradeCard(c.symbol,"WATCH • PRE-UC",c.score,ucPlan(c),
                    "${c.companyName} • ${"%.2f".format(c.dayChangePercent)}% today • ${"%.1f".format(c.volumeRatio)}x volume • ${c.activeStrategies.joinToString(" • ")}",
                    "Research only • not orderable until the LIVE execution gate is satisfied")}
            }
            UcCompactView.THREE_PM->{
                if(threePmCalls.isEmpty())item{EmptyState("No qualified 3 PM list yet","The first non-empty UC candidate scan from 15:00–15:30 IST freezes the final next-session 3 PM list, independently of the LIVE gate.")}
                else items(threePmCalls.take(50),key={it.id}){r->
                    TradeCard(r.symbol,"3 PM • NEXT-DAY PRE-UC",r.score,tradeCallPlan(r),r.detail,
                        "LONG ONLY • buy window 15:10–15:30 • predicts UC attempt on ${r.targetSessionDate} • tap MODEL score to place manually",orderable=true,vm=vm)
                }
            }
            UcCompactView.CLOSED->{
                if(closed.isEmpty())item{EmptyState("No closed UC calls yet","Every timestamped UC call will finish here as WIN, LOSS, or INVALID when outcome data is insufficient.")}
                else items(closed.take(200),key={it.id}){r->
                    val base="${"%+.2f".format(r.returnPct)}% • ${r.closeReason} • opened ${formatIstTimestamp(r.openedAt)} • closed ${formatIstTimestamp(r.closedAt)}"
                    TradeCard(r.symbol,"${r.outcome.name} • ${r.bucket.name.replace('_',' ')} • UC",r.score,tradeCallPlan(r),r.detail,listOfNotNull(base,autopsyStatus(state,r.id)).joinToString("\n"))
                }
            }
        }
    }
}

@Composable
fun PressureCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val shown=state.dualSummary?.demand?.candidates.orEmpty().filter{it.price>=20.0}
    val pressureWatchOnly=state.dualSummary?.demand?.message?.startsWith("WATCHLIST ONLY")==true
    val live=if(pressureWatchOnly) emptyList() else shown
    val calls=state.tradeCalls.filter{it.engine==TradeCallEngine.PRESSURE}
    val next=calls.filter{it.outcome==TradeCallOutcome.OPEN&&it.bucket==TradeCallBucket.NEXT_SESSION}.sortedByDescending{it.openedAt}
    val closed=calls.filter{it.outcome!=TradeCallOutcome.OPEN}.sortedByDescending{it.closedAt}
    var view by remember(state.marketSession.phase){mutableStateOf(if(state.marketSession.isOpen)PressureCompactView.LIVE else PressureCompactView.NEXT_SESSION)}
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
        item{CompactHeader("Pressure Prediction",state,state.dualSummary?.demand?.completedAt?:0L,vm::runScan)}
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(modifier=Modifier.weight(1f),selected=view==PressureCompactView.NEXT_SESSION,onClick={view=PressureCompactView.NEXT_SESSION},label={Text("NEXT ${next.size}",maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==PressureCompactView.LIVE,onClick={view=PressureCompactView.LIVE},label={Text("LIVE ${live.size}",maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==PressureCompactView.CLOSED,onClick={view=PressureCompactView.CLOSED},label={Text("DONE ${closed.size}",maxLines=1)})
            }
        }
        when(view){
            PressureCompactView.NEXT_SESSION->{
                if(next.isEmpty())item{EmptyState("No next-session pressure call","After the market closes, qualifying monitored pressure setups are carried here for the next session.")}
                else items(next.take(100),key={it.id}){r->TradeCard(r.symbol,"NEXT • BUY PRESSURE",r.score,tradeCallPlan(r),r.detail,"Target session ${r.targetSessionDate} • call ${formatIstTimestamp(r.openedAt)}")}
            }
            PressureCompactView.LIVE->{
                if(live.isEmpty())item{EmptyState("No live pressure setup","No executable NSE EQ pressure setup has cleared the LIVE threshold yet.")}
                else items(live,key={it.symbol}){c->TradeCard(c.symbol,"BUY PRESSURE",c.score,pressurePlan(c),"${c.companyName} • Buy/Sell ${"%.1f".format(c.buySellRatio)}x • Vol ${"%.1f".format(c.volumeRatio)}x","LIVE")}
            }
            PressureCompactView.CLOSED->{
                if(closed.isEmpty())item{EmptyState("No closed pressure calls","Every pressure call will finish here as WIN, LOSS, or INVALID when outcome data is insufficient.")}
                else items(closed.take(200),key={it.id}){r->
                    val base="${"%+.2f".format(r.returnPct)}% • ${r.closeReason} • opened ${formatIstTimestamp(r.openedAt)}"
                    TradeCard(r.symbol,"${r.outcome.name} • PRESSURE",r.score,tradeCallPlan(r),r.detail,listOfNotNull(base,autopsyStatus(state,r.id)).joinToString("\n"))
                }
            }
        }
    }
}

private data class ScoreBandPerformance(
    val low:Int,val high:Int,val samples:Int,val wins:Int,val accuracy:Double,val avgReturn:Double,val established:Boolean
)
private fun bestScoreBand(records:List<StrategyRecommendation>):ScoreBandPerformance?{
    if(records.isEmpty())return null
    data class Bucket(val low:Int,val rows:MutableList<StrategyRecommendation>)
    val buckets=linkedMapOf<Int,MutableList<StrategyRecommendation>>()
    records.take(500).forEach{r->
        val raw=r.setup.score.takeIf{it.isFinite()}?.coerceIn(0.0,100.0)?:return@forEach
        val low=if(raw>=100.0)95 else (kotlin.math.floor(raw/5.0)*5.0).toInt().coerceIn(0,95)
        buckets.getOrPut(low){mutableListOf()}+=r
    }
    val all=buckets.map{(low,rows)->
        val n=rows.size
        val wins=rows.count{it.status==StrategyRecommendationStatus.WIN}
        ScoreBandPerformance(low,(low+5).coerceAtMost(100),n,wins,if(n>0)wins*100.0/n else 0.0,
            if(n>0)rows.map{it.returnPct}.average() else 0.0,n>=3)
    }
    val eligible=all.filter{it.established}.ifEmpty{all}
    return eligible.maxWithOrNull(compareBy<ScoreBandPerformance>{it.accuracy}.thenBy{it.samples}.thenBy{it.avgReturn})
}

@Composable
fun StrategiesCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    var view by remember{mutableStateOf(CompactView.LIVE)}
    val visibleLive=if(state.marketSession.isOpen)state.strategyLive else emptyList()
    val researchOnly=if(state.marketSession.isOpen)state.strategyTournamentSummary?.topSetups.orEmpty().filter{w->visibleLive.none{it.setup.symbol==w.symbol&&it.setup.direction==w.direction}} else emptyList()
    val visibleDeveloping=researchOnly.filter{it.score>=68.0}
    val visibleWatch=researchOnly.filter{it.score<68.0}
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
        item{CompactHeader("Trading Strategies",state,state.lastStrategyScanAt,vm::refreshTradingStrategies)}
        item{
            val band=bestScoreBand(state.strategyClosed)
            if(band==null){
                Text("Best model-score band • collecting closed results",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else{
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(horizontal=12.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                        Text("BEST MODEL-SCORE BAND • ${band.low}–${band.high}%",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        Text("${band.wins}/${band.samples} wins • ${"%.1f".format(band.accuracy)}% observed hit rate • avg ${"%+.2f".format(band.avgReturn)}% return${if(band.established)"" else " • early sample"}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Dynamic 5-point band from closed strategy calls • MODEL score is not a probability.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        state.evidenceFabric?.let{fabric->
            item{
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("TRADE INTELLIGENCE • EVIDENCE FABRIC",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                        Text("Calendar ${fabric.calendarVersion} • week ${fabric.remainingWeekSessions} sessions • month ${fabric.remainingMonthSessions}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("PIT evidence ${fabric.pointInTimeEvidenceCount} • macro next 7d ${fabric.macroEventsNext7Days} • decisions ${fabric.decisionSnapshots}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Challenger pending ${fabric.challengerPending} • resolved ${fabric.challengerResolved} • broker orders ${fabric.brokerOrders}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Sector map • "+fabric.sectorMapVersion.ifBlank{"awaiting first NIFTY 500 snapshot"}+" • missing evidence stays neutral; hard risk/liquidity gates can force WAIT",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        state.strategyTournamentSummary?.let{research->
            item{
                ElevatedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                            Text("CHAMPION RESEARCH • PLAYBOOK SYNERGY",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                            val champ=research.performances.count{it.status==StrategyStatus.CHAMPION}
                            val susp=research.performances.count{it.status==StrategyStatus.SUSPENDED}
                            Text("C $champ • S $susp",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Research library • 100 candle/price-action recognizers • strategy families + parameter variants • shadow challengers",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(research.championInsights.isEmpty()){
                            Text("Collecting chronological closed calls for holdout + walk-forward Champion evidence.",
                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }else research.championInsights.take(6).forEach{line->
                            Text(line,style=MaterialTheme.typography.labelSmall,color=if(line.startsWith("DECAY"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Rejected-candidate journal • ${research.rejectedJournalCount} stored • unavailable handbook data is neutral, never guessed",
                            style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        item{
            val attempt=if(state.lastStrategyAttemptAt>0)formatIstTimestamp(state.lastStrategyAttemptAt) else "not yet"
            val success=if(state.lastStrategyScanAt>0)formatIstTimestamp(state.lastStrategyScanAt) else "not yet"
            val errorSuffix=if(state.lastStrategyError.isNotBlank())" • last error ${if(state.lastStrategyErrorAt>0)formatIstTimestamp(state.lastStrategyErrorAt) else ""}: ${state.lastStrategyError.take(110)}" else ""
            Text("Scheduler • attempt $attempt • success $success$errorSuffix",style=MaterialTheme.typography.labelSmall,
                color=if(state.lastStrategyError.isNotBlank())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.strategyTournamentSummary?.message?.takeIf{it.isNotBlank()}?.let{msg->item{Text("Engine • weekly slate • $msg",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        item{ViewSwitch(view,{view=it},visibleLive.size,state.strategyClosed.size)}
        if(view==CompactView.LIVE){
            if(visibleLive.isEmpty()&&visibleDeveloping.isEmpty()&&visibleWatch.isEmpty())item{EmptyState(if(state.marketSession.isOpen)"No live strategy call" else "Market closed • no LIVE strategy calls",if(state.marketSession.isOpen)(state.strategyTournamentSummary?.message?.takeIf{it.isNotBlank()}?:"Scanner is running; no setup has cleared score, execution and hard-risk gates yet.") else "Intraday strategy calls are closed and scored after the session; they never remain LIVE overnight.")}
            else{
                if(visibleLive.isNotEmpty())items(visibleLive,key={it.id}){r->
                    val s=r.setup
                    TradeCard(s.symbol,"${s.direction.name} • ${s.strategyName}",s.score,strategyPlan(s),s.evidence,"LIVE • opened ${formatIstTimestamp(r.openedAt)} • spread ${"%.2f".format(r.spreadPct)}% • tap MODEL score to place manually",orderable=true,vm=vm)
                }
                if(visibleDeveloping.isNotEmpty()){
                    item{Text("DEVELOPING ${visibleDeveloping.take(12).size}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.tertiary)}
                    items(visibleDeveloping.take(12),key={"dev-${it.symbol}-${it.direction}"}){s->TradeCard(s.symbol,"DEVELOPING • ${s.direction.name} • ${s.strategyName}",s.score,strategyPlan(s),s.evidence,"Research signal • not counted as a LIVE call until the 72+ execution/governance gate clears")}
                }
                if(visibleWatch.isNotEmpty()){
                    item{Text("WATCH ${visibleWatch.take(5).size}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(visibleWatch.take(5),key={"watch-${it.symbol}-${it.direction}"}){s->TradeCard(s.symbol,"WATCH • ${s.direction.name} • ${s.strategyName}",s.score,strategyPlan(s),s.evidence,"Monitoring only • not counted as a call")}
                }
            }
        }else{
            if(state.strategyClosed.isEmpty())item{EmptyState("No closed strategy calls","Every intraday strategy call will finish here as WIN or LOSS.")}
            else items(state.strategyClosed.take(200),key={it.id}){r->
                val s=r.setup;val outcome=if(r.status==StrategyRecommendationStatus.WIN)"WIN" else "LOSS"
                val base="${"%+.2f".format(r.returnPct)}% • ${r.closeReason} • opened ${formatIstTimestamp(r.openedAt)} • closed ${formatIstTimestamp(r.closedAt)}"
                TradeCard(s.symbol,"$outcome • ${s.direction.name} • ${s.strategyName}",s.score,strategyPlan(s),s.evidence,listOfNotNull(base,autopsyStatus(state,"STRATEGY|${r.id}")).joinToString("\n"))
            }
        }
    }
}

@Composable
fun PortfolioCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val orders=state.brokerOrders.sortedByDescending{it.submittedAt}
    val filled=orders.sumOf{it.filledQuantity}
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
        item{CompactHeader("Portfolio",state,orders.maxOfOrNull{it.lastReconciledAt}?:0L,vm::reconcileBrokerNow,"Groww order/fill reconciliation • permanent execution ledger")}
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                PlanMetric("ORDERS",orders.size.toString(),Modifier.weight(1f))
                PlanMetric("FILLED QTY",filled.toString(),Modifier.weight(1f))
                PlanMetric("PENDING",orders.count{it.remainingQuantity>0&&it.status !in setOf("REJECTED","CANCELLED")}.toString(),Modifier.weight(1f))
            }
        }
        item{
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text("BROKER RECONCILIATION",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                    Text("Requested order data is never treated as a fill. Filled quantity, remaining quantity, average fill and individual Groww trades are reconciled into this ledger.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.staticIpMatch==false)Text("Static-IP route mismatch • live submission remains blocked",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                }
            }
        }
        if(orders.isEmpty())item{EmptyState("No broker orders yet","After a manual PLACE ORDER, the Groww order and its actual fills will appear here.")}
        else items(orders,key={it.growwOrderId+"|"+it.referenceId}){o->
            ElevatedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text(o.symbol,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        Text(o.status,style=MaterialTheme.typography.labelMedium,color=if(o.status.contains("REJECT",true)||o.status.contains("UNCERTAIN",true))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text("${o.side} • ${o.product} • requested ${o.requestedQuantity} • filled ${o.filledQuantity} • remaining ${o.remainingQuantity}",
                        style=MaterialTheme.typography.bodySmall)
                    Text("Signal ₹${"%.2f".format(o.signalEntryPrice)} • broker avg "+if(o.averageFillPrice>0)"₹${"%.2f".format(o.averageFillPrice)}" else "pending",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Groww ID ${o.growwOrderId} • ref ${o.referenceId}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(o.lastReconciledAt>0)Text("Reconciled ${formatIstTimestamp(o.lastReconciledAt)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(o.reconciliationError.isNotBlank())Text(o.reconciliationError,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                    o.fills.take(8).forEach{f->
                        Text("Fill • qty ${f.quantity} @ ₹${"%.2f".format(f.price)} • ${f.tradeStatus} • ${f.tradeDateTime}",
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalCompactScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    val summary=state.globalLeadSummary
    val all=summary?.candidates.orEmpty().sortedByDescending{it.score}
    val next=all.filter{it.action==GlobalLeadAction.NEXT_OPEN_WATCH}
    val live=if(state.marketSession.isOpen) all.filter{(it.action==GlobalLeadAction.ENTER_AFTER_OPEN||it.action==GlobalLeadAction.KEEP_NEXT_SESSION)&&it.indianPrice>=20.0} else emptyList()
    val closed=state.tradeCalls.filter{it.engine==TradeCallEngine.GLOBAL&&it.bucket==TradeCallBucket.LIVE&&it.outcome!=TradeCallOutcome.OPEN}.sortedByDescending{it.closedAt}
    val preOpen=state.marketSession.phase!=MarketPhase.OPEN
    var view by remember(state.marketSession.phase){mutableStateOf(if(preOpen)GlobalCompactView.NEXT_SESSION else GlobalCompactView.LIVE)}
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp)){
        item{CompactHeader("Global Lead",state,state.lastGlobalLeadScanAt,vm::refreshGlobalLead,"24h global-market research • India confirmation from 09:15 IST")}
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(modifier=Modifier.weight(1f),selected=view==GlobalCompactView.NEXT_SESSION,onClick={view=GlobalCompactView.NEXT_SESSION},label={Text("NEXT ${next.size}",maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==GlobalCompactView.LIVE,onClick={view=GlobalCompactView.LIVE},label={Text("LIVE ${live.size}",maxLines=1)})
                FilterChip(modifier=Modifier.weight(1f),selected=view==GlobalCompactView.CLOSED,onClick={view=GlobalCompactView.CLOSED},label={Text("DONE ${closed.size}",maxLines=1)})
            }
        }
        when(view){
            GlobalCompactView.NEXT_SESSION->{
                if(next.isEmpty())item{EmptyState("No next-session global signal yet",summary?.message?:"NEXT is a pre-market research shortlist. At/after 09:15 IST each candidate is revalidated; only confirmed candidates are promoted to LIVE.")}
                else items(next,key={"next-${it.direction}-${it.indianSymbol}"}){c->
                    val indiaRef=if(c.indianPrice>=20.0)" • India ref ₹${"%.2f".format(c.indianPrice)}" else " • India reference pending"
                    TradeCard(c.indianSymbol,"NEXT SESSION • ${c.direction.name}",c.score,globalPlan(c),"Lead ${c.foreignTicker} • ${c.exchange} • foreign ${"%.2f".format(c.foreignDayPct)}%$indiaRef","Research only • not a call • must be revalidated into LIVE after 09:15 IST")
                }
            }
            GlobalCompactView.LIVE->{
                if(live.isEmpty())item{EmptyState("No confirmed live global entry","NEXT candidates move here after Indian price/liquidity confirmation. Each materially changed entry becomes a separate timestamped call.")}
                else items(live,key={"live-${it.direction}-${it.indianSymbol}"}){c->TradeCard(c.indianSymbol,c.direction.name,c.score,globalPlan(c),"Lead ${c.foreignTicker} • ${c.exchange} • foreign ${"%.2f".format(c.foreignDayPct)}% • India ${"%.2f".format(c.indianFromOpenPct)}%","LIVE • ${c.action.name.replace('_',' ')} • tap MODEL score to place manually",orderable=true,vm=vm)}
            }
            GlobalCompactView.CLOSED->{
                if(closed.isEmpty())item{EmptyState("No completed LIVE global calls","DONE contains only signals that were first confirmed in LIVE. NEXT and research-watch candidates never enter DONE directly.")}
                else items(closed.take(250),key={it.id}){r->
                    val base="${"%+.2f".format(r.returnPct)}% • ${r.closeReason} • opened ${formatIstTimestamp(r.openedAt)} • closed ${formatIstTimestamp(r.closedAt)}"
                    TradeCard(r.symbol,"${r.outcome.name} • ${r.direction.name} • GLOBAL",r.score,tradeCallPlan(r),r.detail,listOfNotNull(base,autopsyStatus(state,r.id)).joinToString("\n"))
                }
            }
        }
    }
}
