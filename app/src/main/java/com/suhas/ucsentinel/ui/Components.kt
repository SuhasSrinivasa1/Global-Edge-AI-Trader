package com.suhas.globaledgeai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suhas.globaledgeai.domain.model.Candidate
import com.suhas.globaledgeai.domain.model.ScannerSection

@Composable
fun AppHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusStrip(state: UiState) {
    Surface(
        color = if (state.error == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (state.error != null) {
                Icon(Icons.Default.WarningAmber, contentDescription = null)
            } else {
                Icon(Icons.Default.Bolt, contentDescription = null)
            }
            Spacer(Modifier.width(10.dp))
            Text(state.error ?: state.status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CandidateCard(candidate: Candidate, compact: Boolean = false) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(candidate.companyName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (candidate.section == ScannerSection.UC_CONTINUATION) "UC CONTINUATION"
                        else "PRE-PRESSURE PRICE-SPIKE PREDICTION",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if(candidate.section == ScannerSection.DEMAND_SQUEEZE){
                        val phase=candidate.predictionPhase?.name?.replace("_"," ") ?: "PREDICTION"
                        Text(
                            "$phase • target +${"%.1f".format(candidate.targetMovePct ?: 0.0)}% / ${candidate.predictionHorizonHours}h",
                            color=MaterialTheme.colorScheme.tertiary,
                            style=MaterialTheme.typography.labelSmall
                        )
                    }
                    candidate.listingAgeDays?.let {
                        Text("NEW LISTING • ${it}d", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (candidate.frozen) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(candidate.confidence.name.replace("_", " ")) })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("LTP", "₹%.2f".format(candidate.price), Modifier.weight(1f))
                MetricCard("UC", "₹%.2f".format(candidate.upperCircuit), Modifier.weight(1f))
                MetricCard("Score", "%.1f".format(candidate.score), Modifier.weight(1f))
            }

            if(candidate.section == ScannerSection.DEMAND_SQUEEZE && candidate.setupScore != null){
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Setup", "%.0f".format(candidate.setupScore), Modifier.weight(1f))
                    MetricCard("Accel", "%.0f".format(candidate.accelerationScore ?: 0.0), Modifier.weight(1f))
                    MetricCard("Orderflow", "%.0f".format(candidate.microstructureScore ?: 0.0), Modifier.weight(1f))
                }
            }

            if (candidate.activeStrategies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Active strategy mix: " + candidate.activeStrategies.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!compact) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Signals", "${candidate.passedSignals}/${candidate.totalSignals}", Modifier.weight(1f))
                    MetricCard("Buy/Sell", "%.1fx".format(candidate.buySellRatio), Modifier.weight(1f))
                    MetricCard("Volume", "%.1fx".format(candidate.volumeRatio), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MarketSessionBanner(state: UiState) {
    val last = formatIstTimestamp(state.lastMarketDataSuccessAt)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(state.marketSession.label, style = MaterialTheme.typography.titleMedium)
            if (state.lastMarketDataSuccessAt > 0L) {
                Text("Last successful market scan: $last", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("No successful market scan has been stored yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun formatIstTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val z = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.of("Asia/Kolkata"))
    return java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy • HH:mm").format(z)
}

@Composable
fun FreezeRecordBlock(record: com.suhas.globaledgeai.domain.model.FreezeRecord?, sectionLabel: String, compact: Boolean = true) {
    if (record == null || !record.recorded) {
        EmptyState("No daily record yet", "No audited 3 PM record is available for $sectionLabel.")
        return
    }
    when (record.outcome) {
        com.suhas.globaledgeai.domain.model.FreezeOutcome.PICKS -> {
            Text("${record.dateIso} • frozen ${formatIstTimestamp(record.frozenAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            record.candidates.forEach { CandidateCard(it, compact) }
        }
        com.suhas.globaledgeai.domain.model.FreezeOutcome.NO_SIGNAL -> EmptyState(
            "NO SIGNAL • ${record.dateIso}",
            record.message.ifBlank { "A valid near-close scan completed, but no candidate cleared the model threshold." }
        )
        com.suhas.globaledgeai.domain.model.FreezeOutcome.NO_DATA -> EmptyState(
            "NO DATA • ${record.dateIso}",
            record.message.ifBlank { "No valid near-close scan was available, so this is not counted as a no-signal prediction." }
        )
    }
}
