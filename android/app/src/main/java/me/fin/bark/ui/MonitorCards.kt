package me.fin.bark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fin.bark.R
import me.fin.bark.data.Device
import me.fin.bark.data.History
import me.fin.bark.data.LimitProvider
import me.fin.bark.data.Period
import java.util.Locale

// The other token-monitor views (tool, device, limits, trends) as Monitor cards, hidden by default.

/** token-monitor's Tools view: tokens, cost and share per tool for the selected period. */
@Composable
internal fun ToolsCard(p: Period) {
    SectionTitle(stringResource(R.string.monitor_tools))
    if (p.clients.isEmpty()) NoData()
    p.clients.take(8).forEach { ShareRow(it.id, name(it.id), it.tokens, p.tokens, it.costUsd) }
}

/** Devices view: every reporting machine, whether it is still reporting, and its share of the selected period. */
@Composable
internal fun DevicesCard(devices: List<Device>, period: Int, now: Long) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    SectionTitle(stringResource(R.string.monitor_devices))
    if (devices.isEmpty()) NoData()
    val total = devices.sumOf { it.tokens.getOrElse(period) { 0 } }
    devices.forEach { d ->
        val tokens = d.tokens.getOrElse(period) { 0 }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (d.stale) colors.outline else colors.primary, CircleShape))
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = stringResource(if (d.stale) R.string.monitor_offline else R.string.monitor_online)
                    Text(
                        listOfNotNull(state, d.os, d.updatedAt?.let { Dates.ago(context, it, now) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(compact(tokens), style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
            ShareBar(tokens, total)
        }
    }
}

/** Limits view: every quota window (not the home's compact two), each with a remaining-quota bar. */
@Composable
internal fun LimitsDetailCard(limits: List<LimitProvider>, now: Long) {
    val colors = MaterialTheme.colorScheme
    SectionTitle(stringResource(R.string.monitor_limits_detail))
    if (limits.isEmpty()) NoData()
    limits.forEach { p ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderHeader(p)
            p.allWindows.forEach { w ->
                Column(LimitIndent, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LimitCell(w, now, Modifier.fillMaxWidth())
                    if (!w.credits) {
                        val left = (w.remainingPercent ?: 0.0).coerceIn(0.0, 100.0)
                        LinearProgressIndicator(
                            progress = { (left / 100).toFloat() }, modifier = Modifier.fillMaxWidth(),
                            color = if (left <= 10) colors.error else colors.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Trends view's summary: lifetime totals and streaks from the Hub's History. */
@Composable
internal fun StatsCard(history: History?) {
    SectionTitle(stringResource(R.string.monitor_stats))
    val h = history ?: return NoData()
    val tiles = listOf(
        R.string.monitor_stat_total_tokens to compact(h.totalTokens),
        R.string.monitor_stat_total_cost to usd(h.totalCost),
        R.string.monitor_stat_active_days to h.activeDays.toString(),
        R.string.monitor_stat_active_time to String.format(Locale.US, "%.1f", h.activeTimeMs / 3_600_000.0),
        R.string.monitor_stat_streak to h.currentStreak.toString(),
        R.string.monitor_stat_longest to h.longestStreak.toString(),
    )
    tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, value) -> StatTile(stringResource(label), value, Modifier.weight(1f)) }
        }
    }
    h.favoriteModel?.let { StatTile(stringResource(R.string.monitor_stat_favorite), it, Modifier.fillMaxWidth()) }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = Mono, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
