package me.fin.bark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import me.fin.bark.data.MonitorLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.fin.bark.R
import me.fin.bark.data.BeszelApi
import me.fin.bark.data.BeszelData
import me.fin.bark.data.Day
import me.fin.bark.data.History
import me.fin.bark.data.LimitProvider
import me.fin.bark.data.LimitWindow
import me.fin.bark.data.MonitorApi
import me.fin.bark.data.MonitorSession
import me.fin.bark.data.MonitorStats
import me.fin.bark.data.Period
import me.fin.bark.data.Prefs
import me.fin.bark.data.RateTracker
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal val Mono = FontFamily.Monospace

/**
 * token-monitor's home view (usage, limits, models, sessions, activity, trend), kept live over the Hub's stats stream,
 * plus Beszel's servers and network probes, all read through the default server (it serves them on Bark's port).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val res = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val servers by Prefs.servers.collectAsState()
    val currentId by Prefs.currentServerId.collectAsState()
    val server = servers.firstOrNull { it.id == currentId } ?: servers.first()
    val url = server.cleanAddress
    val key = server.key
    var data by remember { mutableStateOf(MonitorApi.last?.second) }
    var history by remember { mutableStateOf(MonitorApi.history) }
    var error by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var reconnect by remember { mutableIntStateOf(0) }
    var beszel by remember { mutableStateOf(BeszelApi.last) }
    var beszelError by remember { mutableStateOf<String?>(null) }
    // Not registered yet (no key), or the server answers that it serves no monitoring.
    val ready = key.isNotEmpty() && !unavailable
    var layout by remember { mutableStateOf(MonitorLayout.parse(Prefs.monitorLayout)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val setLayout = { cards: List<String> ->
        layout = cards
        Prefs.monitorLayout = MonitorLayout.format(cards)
    }
    BackHandler(editing) { editing = false }
    val rate = remember { RateTracker() }
    // Ages, reset countdowns and running → ended move with the clock even when no update arrives.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    // Stays connected while this tab is shown and the app is in the foreground; reconnects with backoff.
    LaunchedEffect(url, key, reconnect) {
        unavailable = false
        error = null
        if (key.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var backoff = 2_000L
            while (true) {
                try {
                    // Read on every attempt: the registered token changes when FCM hands out a new one.
                    MonitorApi.stream(url, MonitorApi.auth(key, Prefs.deviceToken)) { stats ->
                        data = stats
                        error = null
                        live = true
                        pulling = false
                        backoff = 2_000L
                        rate.observe(stats.devices, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive() // leaving the tab closes the socket; that is no error
                    pulling = false
                    if (e is MonitorApi.Unauthorized || e is MonitorApi.Unavailable) {
                        if (e is MonitorApi.Unavailable) unavailable = true else error = res.getString(R.string.monitor_unauthorized)
                        live = false
                        return@repeatOnLifecycle // wait for a pull to refresh or another default server
                    }
                    error = res.getString(R.string.monitor_error, e.message ?: e.javaClass.simpleName)
                }
                live = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    // The full History only feeds the heatmap and trend; refetch it at most every 5 minutes when it changed.
    val historyRevision = data?.historyRevision
    LaunchedEffect(url, key, historyRevision, reconnect) {
        if (key.isEmpty()) return@LaunchedEffect
        // Too soon after the last fetch: wait out the 5 minutes rather than drop the change; a newer revision restarts the wait.
        if (MonitorApi.history != null) delay(5 * 60_000 - (System.currentTimeMillis() - MonitorApi.historyAt))
        runCatching { MonitorApi.history(url, MonitorApi.auth(key, Prefs.deviceToken)) }.onSuccess { history = it }
    }

    // Beszel is polled while its cards are in the layout (or have never been added), this tab is shown and the app
    // is in the foreground; its Hub refreshes systems every minute.
    val showsBeszel = layout.any { it in MonitorLayout.BESZEL }
    val pollBeszel = showsBeszel || !Prefs.monitorBeszelCardsAdded
    LaunchedEffect(url, key, reconnect, pollBeszel, unavailable) {
        if (!ready || !pollBeszel) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    beszel = BeszelApi.fetch(url, MonitorApi.auth(key, Prefs.deviceToken))
                    if (!Prefs.monitorBeszelCardsAdded) {
                        setLayout(layout + MonitorLayout.BESZEL.filter { it !in layout })
                        Prefs.monitorBeszelCardsAdded = true
                    }
                    beszelError = null
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    beszelError = when (e) {
                        is MonitorApi.Unauthorized -> res.getString(R.string.monitor_unauthorized)
                        is BeszelApi.Unavailable -> res.getString(R.string.beszel_unavailable)
                        else -> res.getString(R.string.beszel_error, e.message ?: e.javaClass.simpleName)
                    }
                    if (e is MonitorApi.Unauthorized) {
                        pulling = false
                        return@repeatOnLifecycle // wait for a pull to refresh or another default server
                    }
                }
                pulling = false
                delay(30_000)
            }
        }
    }

    Page(stringResource(if (editing) R.string.monitor_edit_layout else R.string.monitor), actions = {
        if (editing) {
            TextButton(onClick = {
                Prefs.monitorLayout = null
                layout = MonitorLayout.DEFAULT
            }) { Text(stringResource(R.string.monitor_reset_layout)) }
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.done)) }
        } else if (ready) {
            Box(Modifier.padding(end = 16.dp).size(8.dp).background(if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }) { padding ->
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = {
                pulling = key.isNotEmpty()
                MonitorApi.historyAt = 0
                reconnect++
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val stats = data.takeIf { ready }
                val systems = beszel.takeIf { ready }
                when {
                    editing -> LayoutEditor(layout, setLayout)
                    !ready -> SetupCard(server.displayName)
                    else -> {
                        listOfNotNull(error, beszelError.takeIf { showsBeszel }).forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        // Cards of a source that is not set up are left out; the rest show once their data has arrived.
                        val configured = layout
                        val shown = configured.filter { if (it in MonitorLayout.BESZEL) systems != null else stats != null }
                        val loading = (stats == null && error == null) || (showsBeszel && systems == null && beszelError == null)
                        if (configured.isEmpty()) {
                            OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.monitor_edit_layout)) }
                        } else if (shown.isNotEmpty()) {
                            Dashboard(stats, systems, history, rate.speed(now), now, shown) { editing = true }
                        }
                        if (configured.isNotEmpty() && loading) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

private val PERIODS = listOf(R.string.today, R.string.monitor_month, R.string.monitor_all_time)

/**
 * The cards in [cards]' order on one panel; a long press anywhere on it opens the layout editor.
 * [cards] holds only cards whose source has data: Hub cards need [stats], Beszel cards [beszel].
 */
@Composable
private fun Dashboard(stats: MonitorStats?, beszel: BeszelData?, history: History?, speed: Double?, now: Long, cards: List<String>, onEdit: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    val p = stats?.let { listOf(it.today, it.month, it.allTime)[period] }
    // The live total replaces today's History row so the charts agree with the number above them.
    val days = stats?.let { History.withToday(history?.daily ?: it.daily, LocalDate.now().toString(), it.today.tokens, it.today.costUsd) }.orEmpty()
    val haptic = LocalHapticFeedback.current
    val edit by rememberUpdatedState(onEdit)
    BarkCard(
        // Scrolling and button taps consume their pointer events, so only a still press opens the editor.
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                edit()
            })
        },
        spacing = 16.dp,
        padding = PaddingValues(20.dp),
    ) {
        cards.forEachIndexed { i, id ->
            key(id) {
                if (i > 0) HorizontalDivider()
                when (id) {
                    "servers" -> beszel?.let { ServersCard(it, it.now(android.os.SystemClock.elapsedRealtime())) }
                    "probes" -> beszel?.let { ProbesCard(it, it.now(android.os.SystemClock.elapsedRealtime())) }
                    else -> if (stats != null && p != null) when (id) {
                        "overview" -> Overview(p, period, { period = it }, speed)
                        "limits" -> Limits(stats.limits, now)
                        "models" -> Models(p)
                        "sessions" -> Sessions(p.sessions, now)
                        "activity" -> Activity(days, history?.activeDays ?: days.count { it.tokens > 0 })
                        "trend" -> Trend(days.takeLast(45), maxOf(history?.peakDayTokens ?: 0, days.maxOfOrNull { it.tokens } ?: 0))
                        "tools" -> ToolsCard(p)
                        "devices" -> DevicesCard(stats.devices, period, now)
                        "limitsDetail" -> LimitsDetailCard(stats.limits, now)
                        "stats" -> StatsCard(history)
                    }
                }
            }
        }
    }
}

@Composable
private fun cardTitle(id: String): String = stringResource(
    when (id) {
        "overview" -> R.string.monitor_overview
        "limits" -> R.string.monitor_limits
        "models" -> R.string.monitor_models
        "sessions" -> R.string.monitor_sessions
        "activity" -> R.string.monitor_activity
        "trend" -> R.string.monitor_trend
        "tools" -> R.string.monitor_tools
        "devices" -> R.string.monitor_devices
        "limitsDetail" -> R.string.monitor_limits_detail
        "servers" -> R.string.monitor_servers
        "probes" -> R.string.monitor_probes
        else -> R.string.monitor_stats
    },
)

/** The shown cards as equal rows: drag ≡ to reorder, ✕ to hide; hidden cards below with ＋ to add them back. */
@Composable
private fun LayoutEditor(cards: List<String>, onChange: (List<String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    // The drag gesture outlives recompositions; it must read the order as it is now, not when the drag began.
    val latest by rememberUpdatedState(cards)
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val up = stringResource(R.string.monitor_move_up)
    val down = stringResource(R.string.monitor_move_down)
    val header = @Composable { text: String ->
        Text(text, style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    }

    header(stringResource(R.string.monitor_shown))
    BarkCard(spacing = 0.dp, padding = PaddingValues(vertical = 4.dp)) {
        cards.forEachIndexed { index, id ->
            key(id) {
                val title = cardTitle(id)
                val dragging = dragged == id
                Row(
                    Modifier.fillMaxWidth().height(rowHeight).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) offset else 0f
                            shadowElevation = if (dragging) 8.dp.toPx() else 0f
                        }
                        .background(if (dragging) colors.surfaceContainerHighest else Color.Transparent)
                        .semantics {
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction(up) { onChange(MonitorLayout.move(latest, index, index - 1)); true } else null,
                                if (index < cards.lastIndex) CustomAccessibilityAction(down) { onChange(MonitorLayout.move(latest, index, index + 1)); true } else null,
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Menu, null, tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { dragged = id; offset = 0f },
                                onDragEnd = { dragged = null; offset = 0f },
                                onDragCancel = { dragged = null; offset = 0f },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                                val list = latest
                                val from = list.indexOf(id)
                                val to = (from + (offset / rowPx).roundToInt()).coerceIn(0, list.lastIndex)
                                if (to != from) { // swap past half a row; keep the row under the finger
                                    onChange(MonitorLayout.move(list, from, to))
                                    offset -= (to - from) * rowPx
                                }
                            }
                        },
                    )
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(latest - id) }) { Icon(Icons.Default.Close, stringResource(R.string.monitor_remove_card, title)) }
                }
            }
        }
    }

    val hidden = MonitorLayout.hidden(cards)
    if (hidden.isNotEmpty()) {
        header(stringResource(R.string.monitor_add_cards))
        BarkCard(spacing = 0.dp, padding = PaddingValues(vertical = 4.dp)) {
            hidden.forEach { id ->
                val title = cardTitle(id)
                Row(Modifier.fillMaxWidth().height(rowHeight).padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(latest + id) }) { Icon(Icons.Default.Add, stringResource(R.string.monitor_add_card, title), tint = colors.primary) }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(server: String) {
    BarkCard {
        Text(stringResource(R.string.monitor_setup_title, server), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.monitor_setup_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Overview(p: Period, period: Int, onPeriod: (Int) -> Unit, speed: Double?) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PERIODS.forEachIndexed { i, label ->
            SegmentedButton(selected = period == i, onClick = { onPeriod(i) }, shape = SegmentedButtonDefaults.itemShape(i, PERIODS.size)) { Text(stringResource(label)) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.monitor_total_tokens), style = MaterialTheme.typography.labelMedium, fontFamily = Mono,
                letterSpacing = 1.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (speed != null) {
                Icon(painterResource(R.drawable.ic_sym_bolt), null, Modifier.size(16.dp), tint = colors.primary)
                Text(stringResource(R.string.monitor_rate, speed.roundToInt().toString()), style = MaterialTheme.typography.labelMedium, fontFamily = Mono, fontWeight = FontWeight.SemiBold)
            }
        }
        val text = NumberFormat.getIntegerInstance().format(p.tokens)
        // Monospace digits: shrink long totals so they stay on one line.
        val size = if (text.length <= 11) 44f else 44f * 11 / text.length
        RollingNumber(text, p.tokens, MaterialTheme.typography.displayMedium.copy(fontFamily = Mono, fontSize = size.sp, lineHeight = (size * 1.15f).sp))
        RollingNumber(usd(p.costUsd), (p.costUsd * 100).toLong(), MaterialTheme.typography.titleMedium.copy(fontFamily = Mono, color = colors.onSurfaceVariant))
    }
}

/** Each changed digit rolls in place (upward when the number grows), like token-monitor's readouts. */
@Composable
private fun RollingNumber(text: String, value: Long, style: TextStyle) {
    val last = remember { longArrayOf(value) }
    val up = value >= last[0]
    SideEffect { last[0] = value }
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            // Keyed from the right, so every digit keeps its column when the number gains a digit.
            key(text.length - i) {
                if (ch.isDigit()) {
                    AnimatedContent(
                        targetState = ch,
                        transitionSpec = {
                            val dir = if (up) 1 else -1
                            (slideInVertically { it * dir } + fadeIn()) togetherWith (slideOutVertically { -it * dir } + fadeOut()) using SizeTransform(clip = true)
                        },
                        label = "digit",
                    ) { Text(it.toString(), style = style) }
                } else {
                    Text(ch.toString(), style = style)
                }
            }
        }
    }
}

/** Shown when a card has nothing to list (e.g. no sessions this period, History still loading). */
@Composable
internal fun NoData() {
    Text(stringResource(R.string.monitor_no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Indent that lines quota rows up under the provider name. */
internal val LimitIndent = Modifier.padding(start = 26.dp)

/** Provider icon, name and plan, then its error or staleness. */
@Composable
internal fun ProviderHeader(p: LimitProvider) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClientIcon(p.provider)
        Text(name(p.provider), style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
        p.plan?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = Mono, color = colors.onSurfaceVariant) }
    }
    if (p.status != "ok") Text(stringResource(R.string.monitor_status, p.status), style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = LimitIndent)
    if (p.stale) Text(stringResource(R.string.monitor_stale), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = LimitIndent)
}

@Composable
private fun Limits(limits: List<LimitProvider>, now: Long) {
    SectionTitle(stringResource(R.string.monitor_limits))
    if (limits.isEmpty()) NoData()
    limits.take(3).forEach { p -> // the first three accounts in the Hub's order
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderHeader(p)
            // Two windows per row like the desktop, unless a label (e.g. "Claude/GPT weekly") needs the width.
            val perRow = if (p.windows.any { (it.label?.length ?: 0) > 12 }) 1 else 2
            p.windows.chunked(perRow).forEach { row ->
                Row(LimitIndent, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { LimitCell(it, now, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun LimitCell(w: LimitWindow, now: Long, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = w.label ?: when (w.kind) {
        "session" -> stringResource(R.string.monitor_kind_session)
        "daily" -> stringResource(R.string.monitor_kind_daily)
        "weekly" -> stringResource(R.string.monitor_kind_weekly)
        "billing" -> stringResource(R.string.monitor_kind_billing)
        else -> w.kind
    }
    val left = w.remainingPercent ?: 0.0
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (w.credits) String.format(Locale.US, "%.2f %s", w.remaining ?: 0.0, w.currency.orEmpty()).trim()
                else stringResource(R.string.monitor_left, left.roundToInt()),
                style = MaterialTheme.typography.bodySmall, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                color = if (!w.credits && left <= 10) colors.error else colors.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Antigravity groups put their period here, as the desktop does: "5-hour · 4小时57分后重置".
        val reset = w.resetsAt?.let { stringResource(R.string.monitor_resets, span(it - now)) }
        listOfNotNull(w.period, reset).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon, name, tokens (with cost when given) and share of [total], over a share bar: the Models and Tools rows. */
@Composable
internal fun ShareRow(icon: String, title: String, tokens: Long, total: Long, cost: Double? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClientIcon(icon)
            Text(title, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Text(
                compact(tokens) + (cost?.let { " · " + usd(it) } ?: ""), style = MaterialTheme.typography.bodyMedium, fontFamily = Mono,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                "${if (total > 0) (tokens * 100.0 / total).roundToInt() else 0}%", style = MaterialTheme.typography.bodySmall, fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
            )
        }
        ShareBar(tokens, total)
    }
}

/** A row's share of the total, under its text and styled like the limit-details meters. */
@Composable
internal fun ShareBar(tokens: Long, total: Long) {
    LinearProgressIndicator(progress = { if (total > 0) (tokens.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = LimitIndent.fillMaxWidth())
}

@Composable
private fun Models(p: Period) {
    SectionTitle(stringResource(R.string.monitor_models))
    if (p.models.isEmpty()) NoData()
    p.models.take(6).forEach { u -> ShareRow(p.modelClient[u.id] ?: u.id.substringBefore('-'), u.id, u.tokens, p.tokens) }
}

@Composable
private fun Sessions(sessions: List<MonitorSession>, now: Long) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val running = sessions.count { it.state(now) == "running" }
    SectionTitle(stringResource(R.string.monitor_sessions), if (running > 0) stringResource(R.string.monitor_running_count, running) else null)
    if (sessions.isEmpty()) NoData()
    sessions.forEach { s ->
        Row {
            ClientIcon(s.client, Modifier.padding(top = 2.dp))
            Box(Modifier.padding(start = 6.dp, top = 3.dp).size(14.dp), contentAlignment = Alignment.Center) { StateGlyph(s.state(now)) }
            Column(Modifier.padding(start = 6.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(compact(s.tokens), style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ago = s.lastUsedAt?.let { Dates.ago(context, it, now) }
                    Text(
                        listOfNotNull(s.model, s.reasoningEffort, ago).joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono, letterSpacing = 0.sp),
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val contextLeft = s.contextLeft(now)
                    val small = MaterialTheme.typography.labelSmall
                    if (contextLeft != null) {
                        // Context headroom, coloured only when it is running out (token-monitor contextTone).
                        val tone = if (contextLeft <= 10) colors.error else colors.onSurfaceVariant
                        Box(Modifier.padding(start = 8.dp).width(28.dp).height(3.dp).background(colors.surfaceVariant, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(contextLeft / 100f).fillMaxHeight().background(tone, RoundedCornerShape(2.dp)))
                        }
                        Text("$contextLeft%", style = small, fontFamily = Mono, color = tone, modifier = Modifier.padding(start = 6.dp))
                    } else {
                        s.cacheMinutes(now)?.let { Text(stringResource(R.string.monitor_cache, it), style = small, fontFamily = Mono, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}

/** Spinner while running, a check once the turn ended, a dot while waiting or idle. */
@Composable
private fun StateGlyph(state: String) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(
        when (state) {
            "running" -> R.string.monitor_running
            "waiting" -> R.string.monitor_waiting
            "ended" -> R.string.monitor_ended
            else -> R.string.monitor_idle
        },
    )
    val modifier = Modifier.semantics { contentDescription = label }
    when (state) {
        "running" -> CircularProgressIndicator(modifier.size(11.dp), color = colors.primary, strokeWidth = 1.5.dp)
        "ended" -> Icon(Icons.Outlined.CheckCircle, label, Modifier.size(13.dp), tint = colors.onSurfaceVariant)
        "waiting" -> Box(modifier.size(7.dp).background(colors.tertiary, CircleShape))
        else -> Box(modifier.size(6.dp).background(colors.outline, CircleShape))
    }
}

/** A GitHub-style grid of daily usage, one column per week, the newest week on the right. */
@Composable
private fun Activity(days: List<Day>, activeDays: Int) {
    val colors = MaterialTheme.colorScheme
    SectionTitle(stringResource(R.string.monitor_activity), stringResource(R.string.monitor_active_days, activeDays))
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono, color = colors.onSurfaceVariant)
    val months = DateTimeFormatter.ofPattern("MMM", LocalConfiguration.current.locales[0])
    val byDate = remember(days) { days.associate { it.date to it.tokens } }
    val max = days.maxOfOrNull { it.tokens } ?: 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = 10.dp
        val gap = 3.dp
        val weeks = ((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(cell * 7 + gap * 6 + 20.dp)) {
            val today = LocalDate.now()
            val start = today.with(DayOfWeek.MONDAY).minusWeeks(weeks - 1L)
            val step = (cell + gap).toPx()
            val side = cell.toPx()
            for (w in 0 until weeks) {
                val monday = start.plusWeeks(w.toLong())
                if (w > 0 && monday.month != monday.minusWeeks(1).month) {
                    drawText(measurer, months.format(monday), Offset(w * step, 7 * step + 2.dp.toPx()), labelStyle)
                }
                for (d in 0 until 7) {
                    val date = monday.plusDays(d.toLong())
                    if (date.isAfter(today)) break
                    val level = History.intensity(byDate[date.toString()] ?: 0, max)
                    val color = if (level == 0) colors.surfaceVariant else colors.primary.copy(alpha = 0.2f + 0.2f * level)
                    drawRoundRect(color, Offset(w * step, d * step), Size(side, side), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
}

/** A smoothed line of daily tokens with a fading fill, labelled with its first, middle and last day. */
@Composable
private fun Trend(days: List<Day>, peak: Long) {
    val colors = MaterialTheme.colorScheme
    SectionTitle(stringResource(R.string.monitor_trend), stringResource(R.string.monitor_peak, compact(peak)))
    if (days.size < 2) return NoData()
    val max = days.maxOf { it.tokens }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val dx = size.width / (days.size - 1)
        val points = days.mapIndexed { i, d -> Offset(i * dx, size.height - size.height * 0.92f * d.tokens / max) }
        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val (a, b) = points[i - 1] to points[i]
                val mid = (a.x + b.x) / 2 // horizontal tangents: smooth, and never overshoots the data
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(line, colors.primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
    Row {
        val labels = listOf(days.first(), days[(days.size - 1) / 2], days.last()).map { shortDate(it.date) }
        labels.forEachIndexed { i, text ->
            Text(
                text, style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = colors.onSurfaceVariant,
                textAlign = listOf(TextAlign.Start, TextAlign.Center, TextAlign.End)[i], modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Tool or provider glyph from token-monitor's icon set; other ids get their initial. */
@Composable
internal fun ClientIcon(id: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val icon = when (id.lowercase()) {
        "claude" -> R.drawable.ic_client_claude
        "codex", "openai", "gpt" -> R.drawable.ic_client_codex
        "antigravity" -> R.drawable.ic_client_antigravity
        "gemini" -> R.drawable.ic_client_gemini
        "deepseek" -> R.drawable.ic_client_deepseek
        else -> null
    }
    if (icon != null) {
        Icon(painterResource(icon), name(id), modifier.size(16.dp), tint = colors.onSurface)
    } else {
        Box(modifier.size(16.dp).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(id.take(1).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
        }
    }
}

/** Remaining time in token-monitor's short form: "5d18h" / "3h10m" / "45m". */
@Composable
internal fun span(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    val d = minutes / 1440
    val h = minutes / 60 % 24
    val m = minutes % 60
    return when {
        d > 0 -> stringResource(R.string.monitor_span_dh, d, h)
        h > 0 -> stringResource(R.string.monitor_span_hm, h, m)
        else -> stringResource(R.string.monitor_span_m, m)
    }
}

/** token-monitor's short counts: 15M, 160.6M, 5.2B. */
internal fun compact(n: Long): String {
    val (v, unit) = when {
        n >= 1_000_000_000 -> n / 1e9 to "B"
        n >= 1_000_000 -> n / 1e6 to "M"
        n >= 1_000 -> n / 1e3 to "K"
        else -> return n.toString()
    }
    return String.format(Locale.US, "%.1f", v).removeSuffix(".0") + unit
}

internal fun usd(v: Double): String = String.format(Locale.US, "$%.2f", v)

/** "2026-09-22" → "9/22". */
internal fun shortDate(date: String): String = runCatching { LocalDate.parse(date).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(date)

/** token-monitor client / provider ids are lowercase (`claude`, `codex`). */
internal fun name(id: String): String = id.replaceFirstChar { it.titlecase(Locale.getDefault()) }
