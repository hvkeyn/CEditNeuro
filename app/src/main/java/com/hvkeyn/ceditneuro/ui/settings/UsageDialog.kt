package com.hvkeyn.ceditneuro.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hvkeyn.ceditneuro.agent.UsageLedger
import com.hvkeyn.ceditneuro.agent.UsageRecord
import com.hvkeyn.ceditneuro.agent.UsageTotals
import com.hvkeyn.ceditneuro.data.AgentSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private data class UsageBucket(val label: String, val tokens: Int, val cost: Double, val calls: Int)

@Composable
fun UsageDialog(
    settings: AgentSettings,
    onRates: (Double, Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ledger = remember { UsageLedger(UsageLedger.file(context.filesDir)) }
    var records by remember { mutableStateOf<List<UsageRecord>>(emptyList()) }
    var periodDays by remember { mutableStateOf(7) }
    var inputDraft by remember(settings.inputPricePerM) { mutableStateOf(settings.inputPricePerM.toString()) }
    var outputDraft by remember(settings.outputPricePerM) { mutableStateOf(settings.outputPricePerM.toString()) }
    var cachedDraft by remember(settings.cachedPricePerM) { mutableStateOf(settings.cachedPricePerM.toString()) }
    LaunchedEffect(Unit) { records = ledger.load() }
    val since = if (periodDays <= 0) 0L else System.currentTimeMillis() - periodDays * 86_400_000L
    val selected = remember(records, since) { records.filter { it.timestamp >= since } }
    val totals = remember(selected) {
        ledger.totals(selected, 0, settings.inputPricePerM, settings.outputPricePerM, settings.cachedPricePerM)
    }
    val hasRates = settings.inputPricePerM > 0 || settings.outputPricePerM > 0 || settings.cachedPricePerM > 0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Token spend", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Local ledger. No prompts or code are stored.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close usage") }
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 0 to "All").forEach { (days, label) ->
                        PeriodChip(label, selected = periodDays == days) { periodDays = days }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard("Tokens", formatTokens(totals.tokens), Modifier.weight(1f))
                    SummaryCard("Requests", totals.requests.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(
                        "Cost",
                        if (hasRates) formatCost(totals.cost) else "set rates",
                        Modifier.weight(1f),
                    )
                    SummaryCard("Cache", "${(totals.cacheRate * 100).toInt()}%", Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Input ${formatTokens(totals.promptTokens)} · output ${formatTokens(totals.completionTokens)} · cached ${formatTokens(totals.cachedTokens)} · avg ${totals.averageLatencyMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Section("Daily tokens")
                DailyChart(selected)
                Spacer(Modifier.height(14.dp))
                Section("Rates per 1M tokens")
                Text(
                    "Used only for the local estimate. Zero means no dollar estimate.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RateField("Input", inputDraft, Modifier.weight(1f)) { inputDraft = it }
                    RateField("Output", outputDraft, Modifier.weight(1f)) { outputDraft = it }
                    RateField("Cached", cachedDraft, Modifier.weight(1f)) { cachedDraft = it }
                }
                TextButton(onClick = {
                    onRates(
                        inputDraft.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                        outputDraft.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                        cachedDraft.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                    )
                }) { Text("Save rates") }

                if (records.isEmpty()) {
                    Text(
                        "No token usage has been recorded yet. Run an agent turn, then open this report again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Section("By model")
                    BucketList(buckets(selected, settings) { it.model }, hasRates)
                    Section("By project")
                    BucketList(buckets(selected, settings) { it.project }, hasRates)
                    Section("By task")
                    BucketList(buckets(selected, settings) { focusLabel(it.focus) }, hasRates)
                    Section("By agent")
                    val helperTokens = selected.filter { it.role != "main" }.sumOf { it.totalTokens }
                    Text(
                        "Primary ${formatTokens(totals.tokens - helperTokens)} · helpers ${formatTokens(helperTokens)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    Section("Findings")
                    UsageFindings(selected, totals, hasRates)
                    Section("Recent calls")
                    selected.takeLast(8).reversed().forEach { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(record.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(92.dp),
                            )
                            Text(record.model, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text(
                                "${formatTokens(record.totalTokens)} · ${record.role}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun RateField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { char -> char.isDigit() || char == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun buckets(
    records: List<UsageRecord>,
    settings: AgentSettings,
    label: (UsageRecord) -> String,
): List<UsageBucket> = records
    .groupBy(label)
    .map { (name, rows) ->
        UsageBucket(
            label = name.ifBlank { "unknown" },
            tokens = rows.sumOf { it.totalTokens },
            cost = rows.sumOf { row ->
                val uncached = (row.promptTokens - row.cachedTokens).coerceAtLeast(0)
                (uncached / 1_000_000.0) * settings.inputPricePerM +
                    (row.cachedTokens / 1_000_000.0) * settings.cachedPricePerM +
                    (row.completionTokens / 1_000_000.0) * settings.outputPricePerM
            },
            calls = rows.size,
        )
    }
    .sortedByDescending { it.tokens }
    .take(6)

@Composable
private fun BucketList(items: List<UsageBucket>, hasRates: Boolean) {
    if (items.isEmpty()) return
    val max = items.maxOf { it.tokens }.coerceAtLeast(1)
    items.forEach { item ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.tokens.toFloat() / max)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(5.dp)),
                )
            }
            Text(
                if (hasRates) formatCost(item.cost) else formatTokens(item.tokens),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(76.dp),
            )
        }
    }
}

@Composable
private fun DailyChart(records: List<UsageRecord>) {
    val days = 14
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val values = (days - 1 downTo 0).map { back ->
        val start = now - back * day
        val end = start + day
        records.filter { it.timestamp in start until end }.sumOf { it.totalTokens }
    }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(top = 4.dp),
    ) {
        val gap = size.width / (days * 1.6f)
        val bar = (size.width - gap * (days + 1)) / days
        values.forEachIndexed { index, value ->
            val h = (value.toFloat() / max) * size.height
            drawRoundRect(
                color = if (value == 0) Color(0x33808080) else Color(0xFF7DD3FC),
                topLeft = Offset(gap + index * (bar + gap), size.height - max(h, 2f)),
                size = Size(bar, max(h, 2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }
    }
}

@Composable
private fun UsageFindings(records: List<UsageRecord>, totals: UsageTotals, hasRates: Boolean) {
    val findings = buildList {
        if (!hasRates) add("Set input/output/cached rates to estimate money; token counts are exact.")
        if (records.isNotEmpty() && totals.cacheRate < 0.20 && totals.promptTokens > 20_000) {
            add("Cache reuse is ${(totals.cacheRate * 100).toInt()}%. A long stable prompt prefix can lower input cost.")
        }
        if (totals.promptTokens > 0 && totals.completionTokens * 8 < totals.promptTokens) {
            add("Input is more than 8× output. Check whether old context or re-read files dominate this period.")
        }
        val helper = records.filter { it.role != "main" }.sumOf { it.totalTokens }
        if (totals.tokens > 0 && helper * 4 > totals.tokens) {
            add("Helpers used ${(helper * 100 / totals.tokens)}% of tokens. Use Team only for independent work.")
        }
        if (totals.requests > 0 && totals.averageLatencyMs > 20_000) {
            add("Average call latency is ${totals.averageLatencyMs} ms. A large context or a slow provider may be the cause.")
        }
        if (isEmpty()) add("No repeated problem is visible yet. Run more turns.")
    }
    findings.forEach { finding ->
        Text(
            "• $finding",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

private fun formatTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun formatCost(value: Double): String = when {
    value <= 0.0 -> "$0"
    value < 0.01 -> String.format(Locale.US, "$%.4f", value)
    else -> String.format(Locale.US, "$%.2f", value)
}

private fun focusLabel(focus: String): String = when (focus) {
    AgentSettings.WORK_BUILD -> "Build"
    AgentSettings.WORK_REMOTE -> "Remote"
    AgentSettings.WORK_STUDY -> "Study"
    else -> "Edit"
}
