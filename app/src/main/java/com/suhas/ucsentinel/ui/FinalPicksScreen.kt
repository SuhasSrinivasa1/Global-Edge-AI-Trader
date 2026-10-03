package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.model.*

@Composable
fun FinalPicksScreen(state:UiState,vm:MainViewModel,padding:PaddingValues){
    var tab by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().padding(padding)){
        AppHeader("3:00 PM Daily Audit","Every scheduled trading day is recorded as PICKS, NO SIGNAL, or NO DATA")
        TabRow(tab){
            Tab(selected=tab==0,onClick={tab=0},text={Text("Upper Circuit")})
            Tab(selected=tab==1,onClick={tab=1},text={Text("Pressure Prediction")})
        }
        val section=if(tab==0)ScannerSection.UC_CONTINUATION else ScannerSection.DEMAND_SQUEEZE
        val today=if(tab==0)state.ucFreezeRecord else state.demandFreezeRecord
        val history=if(tab==0)state.ucFreezeHistory else state.demandFreezeHistory
        val display=if(today.recorded)today else history.firstOrNull()
        val current=if(tab==0)state.dualSummary?.uc?.candidates else state.dualSummary?.demand?.candidates
        LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
            item{StatusStrip(state)}
            item{MarketSessionBanner(state)}
            item{FreezeRecordBlock(display,if(tab==0)"upper-circuit continuation" else "pre-pressure prediction",false)}
            if(!today.recorded&&current!=null&&state.marketSession.isOpen){
                item{OutlinedButton({vm.freezeNow(section)},modifier=Modifier.fillMaxWidth()){
                    Icon(Icons.Default.Lock,null);Spacer(Modifier.width(6.dp));Text("Freeze current ${if(tab==0)"UC" else "prediction"} shortlist")
                }}
            }
            item{Text("Audit history",style=MaterialTheme.typography.titleMedium)}
            if(history.isEmpty())item{EmptyState("No history yet","Daily 3 PM audit records will appear here and survive app restarts.")}
            else items(history.take(12)){r->
                ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                    Text("${r.dateIso} • ${r.outcome.name.replace("_"," ")}",style=MaterialTheme.typography.titleSmall)
                    Text("${r.candidates.size} candidate(s) • frozen ${formatIstTimestamp(r.frozenAt)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(r.sourceScanAt>0)Text("Source scan ${formatIstTimestamp(r.sourceScanAt)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
            }
            item{Text("Research-only: execution remains manual. NO DATA is kept separate from NO SIGNAL so accuracy statistics are not polluted by feed or scheduling failures.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
