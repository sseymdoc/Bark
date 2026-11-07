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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.fin.bark.R
import me.fin.bark.data.BeszelData
import me.fin.bark.data.BeszelSystem
import java.util.Locale
import kotlin.math.roundToInt

// Monitor cards fed by a Beszel Hub (servers and network probes).

/** Beszel's systems: status and uptime, CPU / memory / disk meters, then load, bandwidth and temperature. */
@Composable
internal fun ServersCard(data: BeszelData, now: Long) {
    val systems = data.systems
    val stale = data.isStale(now)
    val colors = MaterialTheme.colorScheme
    val up = systems.count { it.state(now, stale) == "up" }
    SectionTitle(stringResource(R.string.monitor_servers), if (stale) stringResource(R.string.monitor_stale) else stringResource(R.string.beszel_up_count, up, systems.size))
    if (systems.isEmpty()) NoData()
    systems.forEach { s ->
        val current = s.state(now, stale)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (current) {
                    "up" -> colors.primary
                    "down" -> colors.error
                    else -> colors.outline
                }
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = stringResource(
                        when (current) {
                            "up" -> R.string.monitor_online
                            "down" -> R.string.monitor_offline
                            "stale" -> R.string.monitor_stale
                            "unknown" -> R.string.beszel_unknown
                            "paused" -> R.string.beszel_paused
                            else -> R.string.beszel_pending
                        },
                    )
                    Text(
                        listOfNotNull(state, sampleLabel(s.sampledAt), s.version?.let { "v$it" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (current == "up" && (s.uptimeSec ?: 0) > 0) {
                    Text(
                        stringResource(R.string.beszel_uptime, span((s.uptimeSec ?: 0) * 1000)), style = MaterialTheme.typography.labelMedium, fontFamily = Mono,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (current == "up") {
                Row(LimitIndent, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meter(stringResource(R.string.beszel_cpu), s.cpu, Modifier.weight(1f))
                    Meter(stringResource(R.string.beszel_mem), s.mem, Modifier.weight(1f))
                    Meter(stringResource(R.string.beszel_disk), s.disk, Modifier.weight(1f))
                }
                val load = s.load.takeIf { it.isNotEmpty() }?.joinToString(" ") { it?.let { value -> String.format(Locale.US, "%.2f", value) } ?: "—" }
                Text(
                    listOfNotNull(
                        load?.let { stringResource(R.string.beszel_load, it) },
                        "↕ " + (s.bandwidth?.let(::bytesPerSec) ?: "—"),
                        s.temp?.let { String.format(Locale.US, "%.0f°C", it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = LimitIndent,
                )
            }
        }
    }
}

/** A labelled percentage over a bar, in the error colour from 90 % (Beszel's disk alert threshold). */
@Composable
private fun Meter(label: String, percent: Double?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val high = percent != null && percent >= 90
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(percent?.let { "${it.roundToInt()}%" } ?: "—", style = MaterialTheme.typography.bodySmall, fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = if (high) colors.error else colors.onSurface)
        }
        if (percent != null) LinearProgressIndicator(
            progress = { (percent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
            color = if (high) colors.error else colors.primary,
        )
    }
}

/** Beszel's network monitors: state, target, latest latency, and the last hour's average and loss. */
@Composable
internal fun ProbesCard(data: BeszelData, now: Long) {
    val colors = MaterialTheme.colorScheme
    val names = data.systems.associate { it.id to it.name }
    val probeSystems = data.monitors.map { it.system }.distinct()
    val manySystems = probeSystems.size > 1
    // One probing system: its name goes in the title; several: on each probe.
    SectionTitle(stringResource(R.string.monitor_probes), probeSystems.singleOrNull()?.let { names[it] })
    if (data.monitors.isEmpty()) NoData()
    data.monitors.forEach { m ->
        val state = m.state(now, data.isStale(now))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when (state) {
                "ok" -> colors.primary to R.string.monitor_online
                "loss" -> colors.tertiary to R.string.beszel_partial_failure
                "unknown" -> colors.outline to R.string.beszel_unknown
                "down" -> colors.error to R.string.monitor_offline
                else -> colors.outline to R.string.monitor_stale
            }
            val description = stringResource(label)
            Box(Modifier.size(8.dp).background(dot, CircleShape).semantics { contentDescription = description })
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val protocol = m.protocol.uppercase()
                Text(
                    listOfNotNull(
                        names[m.system]?.takeIf { manySystems },
                        protocol,
                        description,
                        sampleLabel(m.updatedAt),
                        m.avg1hUs?.takeIf { it > 0 }?.let { stringResource(R.string.beszel_avg, ms(it)) },
                        m.loss1h?.takeIf { it > 0 }?.let { stringResource(R.string.beszel_loss, String.format(Locale.US, "%.1f", it).removeSuffix(".0")) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = if (state == "ok") colors.onSurfaceVariant else dot,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (state in setOf("down", "stale", "unknown") || m.latencyUs == null) "—" else ms(m.latencyUs), style = MaterialTheme.typography.bodyMedium, fontFamily = Mono,
                fontWeight = FontWeight.SemiBold, color = if (state == "down") colors.error else colors.onSurface, modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Microseconds as "4.2 ms" below 10 ms, "37 ms" above. */
internal fun ms(us: Long): String = if (us < 10_000) String.format(Locale.US, "%.1f ms", us / 1000.0) else "${(us / 1000.0).roundToInt()} ms"

/** Bytes per second in binary units: "512 B/s", "1.2 MB/s". */
internal fun bytesPerSec(n: Long): String {
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$n B/s" else String.format(Locale.US, "%.1f %s", v, units[i])
}

@Composable
private fun sampleLabel(at: Long?): String {
    if (at == null) return stringResource(R.string.beszel_no_sample)
    val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    return stringResource(R.string.beszel_sample_time, time)
}
