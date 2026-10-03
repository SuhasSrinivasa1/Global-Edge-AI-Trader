package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReplayScreen(state: UiState, vm: MainViewModel, padding: PaddingValues) {
    var symbol by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppHeader("Replay / Backtest", "Measure precision for UC → next-session UC continuation")
        StatusStrip(state)

        OutlinedTextField(
            value = symbol,
            onValueChange = { symbol = it.uppercase() },
            label = { Text("NSE symbol") },
            placeholder = { Text("Example: RELIANCE") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { vm.runReplay(symbol) },
            enabled = symbol.isNotBlank() && state.authenticated && !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Replay last 30 days")
        }

        val r = state.replayResult
        if (r != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Predictions", r.predictedUcContinuations.toString(), Modifier.weight(1f))
                MetricCard("True hits", r.truePositives.toString(), Modifier.weight(1f))
                MetricCard("Precision", "%.1f%%".format(r.precision), Modifier.weight(1f))
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(r.symbol, style = MaterialTheme.typography.titleLarge)
                    Text("Sessions analyzed: ${r.sessionsAnalyzed}")
                    Text("Actual next-day UC-like sessions: ${r.actualUcContinuations}")
                    Spacer(Modifier.height(8.dp))
                    r.notes.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
