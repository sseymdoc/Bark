package me.fin.bark.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.fin.bark.push.SseParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Tokens and cost of one model or tool in a period. */
data class Usage(val id: String, val tokens: Long, val costUsd: Double)

data class Period(
    val tokens: Long,
    val costUsd: Double,
    /** Most tokens first. */
    val models: List<Usage>,
    /** Model id to the tool that used it most, for the row icon. */
    val modelClient: Map<String, String>,
    /** Most recently used first, at most 5. */
    val sessions: List<MonitorSession>,
    /** Tools, most tokens first. */
    val clients: List<Usage> = emptyList(),
)

/**
 * A quota (`remainingPercent`) or, when [credits], a balance (`remaining` in [currency]). [period] is set when
 * [label] names an Antigravity model group, so the period ("5-hour") goes before the reset time instead.
 */
data class LimitWindow(
    val kind: String,
    val label: String?,
    val remainingPercent: Double?,
    val resetsAt: Long?,
    val remaining: Double?,
    val currency: String?,
    val credits: Boolean,
    val period: String? = null,
)

/** [windows] is the desktop home's compact pick; [allWindows] every window, for the detailed limits card. */
data class LimitProvider(
    val provider: String,
    val plan: String?,
    val status: String,
    val stale: Boolean,
    val windows: List<LimitWindow>,
    val allWindows: List<LimitWindow> = windows,
)

/** Raw session fields; [state], [contextLeft] and [cacheMinutes] follow token-monitor's src/shared/sessionLive.js. */
data class MonitorSession(
    val client: String,
    val name: String,
    val tokens: Long,
    val model: String?,
    val reasoningEffort: String?,
    val lastUsedAt: Long?,
    val contextTokens: Long,
    val contextWindow: Long,
    /** Three-state on the wire: true ended, false under way, null not reported. */
    val turnEnded: Boolean?,
    val waitingForInput: Boolean,
    val archived: Boolean,
    val liveState: String?,
    val liveObservedAt: Long?,
    val cacheObservedAt: Long?,
    val cacheTtlSeconds: Int,
) {
    /** `running`, `waiting`, `ended` or `idle` (sessionActivityState). */
    fun state(now: Long): String {
        if (archived) return "idle"
        val live = liveState
        if (live in LIVE_STATES && liveObservedAt != null && liveObservedAt <= now && now < liveObservedAt + LIVE_ACTIVITY_TTL_MS) return live!!
        val last = lastUsedAt ?: return "idle"
        val recent = now - last <= RUNNING_WINDOW_MS
        if (turnEnded == true) return if (recent) "ended" else "idle"
        // ponytail: skips sessionLive's Cursor grok-bot rule; port it if those sessions show as running.
        if (recent && client == "codex" && waitingForInput) return "waiting"
        return if (recent) "running" else "idle"
    }

    /** Context headroom in percent, only while the session is not idle (sessionContextForRow). */
    fun contextLeft(now: Long): Int? {
        if (contextTokens <= 0 || contextWindow <= 0 || state(now) == "idle") return null
        return ((contextWindow - contextTokens).toDouble() / contextWindow * 100).roundToInt().coerceAtLeast(0)
    }

    /** Minutes left on Claude's prompt-cache estimate (sessionPromptCacheForRow). */
    fun cacheMinutes(now: Long): Int? {
        if (archived || client != "claude" || cacheTtlSeconds !in CACHE_TTLS) return null
        val observed = cacheObservedAt?.takeIf { it <= now } ?: return null
        val expires = observed + cacheTtlSeconds * 1000L
        return if (expires <= now) null else ceil((expires - now) / 60_000.0).toInt()
    }

    private companion object {
        const val RUNNING_WINDOW_MS = 10 * 60_000L
        const val LIVE_ACTIVITY_TTL_MS = 30_000L
        val LIVE_STATES = setOf("running", "waiting", "idle")
        val CACHE_TTLS = setOf(300, 1800, 3600)
    }
}

/** One reporting machine. The timed counters are today's, for [RateTracker]. */
data class Device(
    val id: String,
    val stale: Boolean,
    val timedOutputTokens: Long,
    val timedDurationMs: Long,
    val name: String = id,
    val os: String? = null,
    val updatedAt: Long? = null,
    /** Tokens today, this month and all time. */
    val tokens: List<Long> = emptyList(),
)

data class Day(val date: String, val tokens: Long, val cost: Double)

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

/**
 * ISO-8601 with `Z` or an offset such as `+00:00`, or PocketBase's "2026-11-07 12:00:00.000Z".
 * Not `Instant.parse`: before Java 12, which older Android versions follow, it only takes `Z`.
 */
internal fun JSONObject.time(key: String): Long? =
    str(key)?.let { runCatching { OffsetDateTime.parse(it.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull() }

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

private fun JSONObject?.entries(): List<Pair<String, JSONObject>> =
    this?.keys()?.asSequence()?.mapNotNull { k -> optJSONObject(k)?.let { k to it } }?.toList() ?: emptyList()

private fun JSONObject?.longs(): List<Pair<String, Long>> = this?.keys()?.asSequence()?.map { it to optLong(it) }?.toList() ?: emptyList()

private fun day(o: JSONObject) = Day(o.optString("date"), o.optLong("tokens"), o.optDouble("cost", 0.0))

/** The slice of token-monitor's `GET /api/stats` (or one stream state) the Monitor tab shows. */
data class MonitorStats(
    val today: Period,
    val month: Period,
    val allTime: Period,
    val limits: List<LimitProvider>,
    val devices: List<Device>,
    /** `historyPreview.daily`: the last 30 days, until the full History arrives. */
    val daily: List<Day>,
    val historyRevision: String?,
) {
    companion object {
        private val HIDDEN_STATUS = setOf("notConfigured", "disabled")
        private val PINNED_PROVIDERS = listOf("codex", "claude")
        private const val HIDDEN_SESSION_MODEL = "codex-auto-review"

        private fun session(key: String, o: JSONObject): MonitorSession {
            val live = o.optJSONObject("liveActivity")
            val cache = o.optJSONObject("promptCache")
            return MonitorSession(
                client = o.str("client") ?: key.substringBefore(':'),
                name = o.str("title") ?: o.str("projectLabel") ?: (o.str("sessionId") ?: key.substringAfter(':')).take(12),
                tokens = o.optLong("totalTokens"),
                model = o.optJSONObject("models").longs().maxByOrNull { it.second }?.first,
                reasoningEffort = o.str("reasoningEffort"),
                lastUsedAt = o.time("lastUsedAt"),
                contextTokens = o.optLong("contextTokens"),
                contextWindow = o.optLong("contextWindow"),
                turnEnded = o.opt("turnEnded") as? Boolean,
                waitingForInput = o.optBoolean("waitingForInput"),
                archived = o.optBoolean("archived") || o.optBoolean("deleted") || o.optBoolean("sourceDeleted"),
                liveState = live?.str("state"),
                liveObservedAt = live?.time("observedAt"),
                cacheObservedAt = cache?.time("observedAt"),
                cacheTtlSeconds = cache?.optInt("ttlSeconds") ?: 0,
            )
        }

        private fun period(o: JSONObject?): Period {
            val costs = o?.optJSONObject("modelCosts")
            val models = o?.optJSONObject("models").longs().filter { it.second > 0 }
                .map { (id, tokens) -> Usage(id, tokens, costs?.optDouble(id, 0.0) ?: 0.0) }.sortedByDescending { it.tokens }
            val byModel = HashMap<String, Pair<String, Long>>()
            o?.optJSONObject("clientModels").entries().forEach { (client, m) ->
                m.longs().forEach { (model, tokens) -> if (tokens > (byModel[model]?.second ?: -1)) byModel[model] = client to tokens }
            }
            val clientCosts = o?.optJSONObject("clientCosts")
            return Period(
                tokens = o?.optLong("totalTokens") ?: 0,
                costUsd = o?.optDouble("costUsd", 0.0) ?: 0.0,
                models = models,
                modelClient = byModel.mapValues { it.value.first },
                // Codex's automatic review runs are not sessions anyone started; they would crowd out the real ones.
                sessions = o?.optJSONObject("sessions").entries().map { (k, v) -> session(k, v) }
                    .filter { it.model != HIDDEN_SESSION_MODEL }
                    .sortedByDescending { it.lastUsedAt ?: 0 }.take(5),
                clients = o?.optJSONObject("clients").longs().filter { it.second > 0 }
                    .map { (id, tokens) -> Usage(id, tokens, clientCosts?.optDouble(id, 0.0) ?: 0.0) }.sortedByDescending { it.tokens },
            )
        }

        private fun window(o: JSONObject): LimitWindow? {
            if (o.optBoolean("additional")) return null // separately metered extras (Codex)
            val w = LimitWindow(
                kind = o.optString("kind"),
                label = o.str("label"),
                remainingPercent = o.num("remainingPercent") ?: o.num("usedPercent")?.let { 100 - it },
                resetsAt = o.time("resetsAt"),
                remaining = o.num("remaining"),
                currency = o.str("currency"),
                credits = o.str("metric") == "credits",
            )
            val meter = w.remainingPercent != null && o.optBoolean("showMeter", true)
            return w.takeIf { meter || (w.credits && w.remaining != null) }
        }

        private val KIND_RANK = mapOf("session" to 0, "daily" to 1, "weekly" to 2, "billing" to 3, "monthly" to 4)
        private val SESSION_SUFFIX = Regex("\\s+5-hour$", RegexOption.IGNORE_CASE)
        private val WEEKLY_SUFFIX = Regex("\\s+weekly$", RegexOption.IGNORE_CASE)

        private fun LimitWindow.left() = remainingPercent ?: Double.POSITIVE_INFINITY

        /** "Gemini 5-hour" → ("Gemini", "5-hour"); null unless the label carries its group and period. */
        private fun antigravityGroup(w: LimitWindow): Pair<String, String>? {
            val (suffix, period) = when (w.kind) {
                "session" -> SESSION_SUFFIX to "5-hour"
                "weekly" -> WEEKLY_SUFFIX to "Weekly"
                else -> return null
            }
            val label = w.label?.trim() ?: return null
            if (!suffix.containsMatchIn(label)) return null
            return label.replace(suffix, "").trim().ifEmpty { null }?.let { it to period }
        }

        /**
         * Antigravity's compact pick (renderer/limits/providerPresentation.js limitProviderCompactWindows): one window
         * per model group, the session one unless the weekly one is tighter and critical, at most two groups.
         * Null when some window is not grouped.
         */
        private fun antigravityCompact(windows: List<LimitWindow>): List<LimitWindow>? {
            val groups = windows.map { antigravityGroup(it) ?: return null }
            val byGroup = windows.indices.groupBy { groups[it].first } // keeps first-seen group order
            fun tightest(indices: List<Int>) = indices.minWithOrNull(compareBy({ windows[it].left() }, { it }))
            fun ofKind(indices: List<Int>, kind: String) = tightest(indices.filter { windows[it].kind == kind })
            if (byGroup.size == 1) {
                val indices = byGroup.values.single()
                return listOfNotNull(ofKind(indices, "session"), ofKind(indices, "weekly"))
                    .map { windows[it].copy(label = "${groups[it].first} ${groups[it].second}") }
            }
            return byGroup.values.mapIndexed { groupIndex, indices ->
                val session = ofKind(indices, "session")
                val weekly = ofKind(indices, "weekly")
                val sessionLeft = session?.let { windows[it].left() } ?: Double.POSITIVE_INFINITY
                val weeklyLeft = weekly?.let { windows[it].left() } ?: Double.POSITIVE_INFINITY
                val pick = when {
                    session == null -> weekly!!
                    weekly == null -> session
                    weeklyLeft < sessionLeft && (weeklyLeft < 20 || sessionLeft.isInfinite()) -> weekly
                    else -> session
                }
                groupIndex to pick
            }.sortedWith(compareBy({ windows[it.second].left() }, { it.first })).take(2).sortedBy { it.first }
                .map { (_, i) -> windows[i].copy(label = groups[i].first, period = groups[i].second) }
        }

        /** The two windows token-monitor's home shows per account: shortest cycle first (Antigravity: per model group). */
        internal fun compactWindows(provider: String, windows: List<LimitWindow>): List<LimitWindow> {
            if (provider == "antigravity") return antigravityCompact(windows) ?: windows.take(2)
            return windows.withIndex().sortedWith(compareBy({ KIND_RANK[it.value.kind] ?: 10 }, { it.index })).map { it.value }.take(2)
        }

        private fun provider(o: JSONObject): LimitProvider? {
            val status = o.optString("status")
            if (status in HIDDEN_STATUS) return null
            val id = o.optString("provider")
            val all = o.optJSONArray("windows").objects().mapNotNull(::window)
            val p = LimitProvider(
                provider = id,
                plan = o.str("planLabel") ?: o.str("accountLabel"),
                status = status,
                stale = o.optBoolean("stale"),
                windows = compactWindows(id, all),
                allWindows = all,
            )
            return p.takeUnless { status == "ok" && p.windows.isEmpty() }
        }

        private fun device(o: JSONObject): Device {
            val periods = o.optJSONObject("periods")
            val today = periods?.optJSONObject("today")
            val id = o.optString("deviceId")
            return Device(
                id = id,
                stale = o.optBoolean("stale"),
                timedOutputTokens = today?.optLong("timedOutputTokens") ?: 0,
                timedDurationMs = today?.optLong("timedDurationMs") ?: 0,
                // The desktop Devices view's resolver: displayName, deviceId, then hostname.
                name = o.str("displayName") ?: id.ifEmpty { null } ?: o.str("hostname") ?: "",
                os = listOfNotNull(o.str("osName"), o.str("osVersion")).joinToString(" ").ifEmpty { null },
                updatedAt = o.time("updatedAt"),
                tokens = listOf("today", "month", "allTime").map { periods?.optJSONObject(it)?.optLong("totalTokens") ?: 0 },
            )
        }

        fun parse(o: JSONObject): MonitorStats {
            val periods = o.optJSONObject("periods")
            return MonitorStats(
                today = period(periods?.optJSONObject("today")),
                month = period(periods?.optJSONObject("month")),
                allTime = period(periods?.optJSONObject("allTime")),
                // Codex first and Claude second when present; everyone else keeps the Hub's order (stable sort).
                limits = o.optJSONObject("limits")?.optJSONArray("providers").objects().mapNotNull(::provider)
                    .sortedBy { PINNED_PROVIDERS.indexOf(it.provider).takeIf { i -> i >= 0 } ?: PINNED_PROVIDERS.size },
                devices = o.optJSONArray("devices").objects().map(::device),
                daily = o.optJSONObject("historyPreview")?.optJSONArray("daily").objects().map(::day),
                historyRevision = o.str("historyRevision"),
            )
        }
    }
}

/** `GET /api/history`: daily rows (up to 370 days) and the lifetime summary. */
data class History(
    val daily: List<Day>,
    val activeDays: Int,
    val peakDayTokens: Long,
    val totalTokens: Long = 0,
    val totalCost: Double = 0.0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val favoriteModel: String? = null,
    val activeTimeMs: Long = 0,
) {
    companion object {
        fun parse(o: JSONObject): History {
            val daily = o.optJSONArray("daily").objects().map(::day)
            val summary = o.optJSONObject("summary")
            return History(
                daily = daily,
                activeDays = summary?.optInt("activeDays") ?: daily.count { it.tokens > 0 },
                peakDayTokens = summary?.optLong("peakDayTokens") ?: (daily.maxOfOrNull { it.tokens } ?: 0),
                totalTokens = summary?.optLong("totalTokens") ?: 0,
                totalCost = summary?.optDouble("totalCost", 0.0) ?: 0.0,
                currentStreak = summary?.optInt("currentStreak") ?: 0,
                longestStreak = summary?.optInt("longestStreak") ?: 0,
                favoriteModel = summary?.str("favoriteModel"),
                activeTimeMs = summary?.optLong("activeTimeMs") ?: 0,
            )
        }

        /** Replaces (or appends) [today]'s row with the live total, as the desktop heatmap and trend do. */
        fun withToday(days: List<Day>, today: String, tokens: Long, cost: Double): List<Day> =
            days.filter { it.date < today } + Day(today, tokens, cost)

        /** history.js intensityBucket: 0 for nothing, then quartiles of the busiest day. */
        fun intensity(tokens: Long, max: Long): Int {
            if (max <= 0) return 0
            val ratio = tokens.toDouble() / max
            return if (ratio >= 0.75) 4 else if (ratio >= 0.5) 3 else if (ratio >= 0.25) 2 else if (ratio > 0) 1 else 0
        }
    }
}

/** Which Monitor cards show, in order (like token-monitor's homeModulePreferences); stored as comma-separated ids. */
object MonitorLayout {
    /** Every card: the desktop home's sections, then the other desktop views. */
    val ALL = listOf("overview", "limits", "models", "sessions", "activity", "trend", "servers", "probes", "tools", "devices", "limitsDetail", "stats")
    val DEFAULT = ALL.take(8)

    /** Cards fed by Beszel rather than the token-monitor Hub. */
    val BESZEL = setOf("servers", "probes")

    /** Known ids once each, in stored order; null (never edited) is the default layout. */
    fun parse(stored: String?): List<String> = stored?.split(',')?.map { it.trim() }?.filter { it in ALL }?.distinct() ?: DEFAULT

    fun format(visible: List<String>): String = visible.joinToString(",")

    /** The cards not shown, in catalogue order. */
    fun hidden(visible: List<String>): List<String> = ALL.filter { it !in visible }

    fun move(list: List<String>, from: Int, to: Int): List<String> = list.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * One stream connection's baseline (token-monitor src/shared/hubDelta.js createStatsStreamReceiver).
 * Any gap or malformed frame throws: the caller reconnects for a complete snapshot rather than show mixed data.
 */
class StatsReceiver {
    var stats: JSONObject? = null
        private set
    private var revision: Long? = null

    /** Revisions are JSON integers; anything else (missing, fractional) counts as absent. */
    private fun JSONObject.rev(key: String): Long? = opt(key).let { if (it is Int || it is Long) (it as Number).toLong() else null }

    /** Feeds one event's data; returns the new state, or null for an event type it ignores. */
    fun receive(data: JSONObject): JSONObject? {
        when (data.optString("type")) {
            "stats" -> { // also the type of the initial `snapshot` event
                stats = data.optJSONObject("stats") ?: error("invalid_stats_snapshot")
                revision = data.rev("revision")
            }
            "delta" -> {
                val base = stats
                val rev = revision
                check(base != null && rev != null && data.rev("baseRevision") == rev && data.rev("revision") == rev + 1) { "stats_revision_mismatch" }
                stats = applyPatch(base, data.optJSONArray("patch") ?: error("invalid_stats_patch"))
                revision = rev + 1
            }
            "freshness" -> {
                val base = stats ?: error("invalid_stats_freshness")
                val fresh = data.optJSONObject("stats") ?: error("invalid_stats_freshness")
                val next = data.rev("revision")
                val rev = revision
                check(if (next == null) rev == null else rev != null && data.rev("baseRevision") == rev && next == rev + 1) { "stats_revision_mismatch" }
                applyFreshness(base, fresh)
                revision = next
            }
            else -> return null
        }
        return stats
    }

    companion object {
        private val BAD_ESCAPE = Regex("~(?![01])")

        private fun child(parent: Any, key: String): Any? = when (parent) {
            is JSONObject -> parent.opt(key)
            is JSONArray -> key.toIntOrNull()?.let { parent.opt(it) }
            else -> null
        }

        /** RFC 6902 add/remove/replace with RFC 6901 paths, applied in place. Returns the (possibly replaced) root. */
        fun applyPatch(root: JSONObject, patch: JSONArray): JSONObject {
            var result = root
            for (i in 0 until patch.length()) {
                val entry = patch.getJSONObject(i)
                val op = entry.optString("op")
                require(op == "add" || op == "remove" || op == "replace") { "invalid_stats_patch" }
                require(op == "remove" || entry.has("value")) { "invalid_stats_patch" }
                val value = entry.opt("value")
                val path = entry.getString("path")
                if (path.isEmpty()) {
                    require(op != "remove") { "invalid_stats_patch_root" }
                    result = value as? JSONObject ?: error("invalid_stats_snapshot")
                    continue
                }
                require(path.startsWith("/") && !BAD_ESCAPE.containsMatchIn(path)) { "invalid_stats_pointer" }
                val keys = path.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
                var parent: Any = result
                for (key in keys.dropLast(1)) parent = child(parent, key) ?: error("missing_stats_patch_parent")
                val key = keys.last()
                when (parent) {
                    is JSONObject -> {
                        require(op == "add" || parent.has(key)) { "missing_stats_patch_target" }
                        if (op == "remove") parent.remove(key) else parent.put(key, value)
                    }
                    is JSONArray -> {
                        val n = parent.length()
                        val index = if (key == "-") n else key.toIntOrNull()?.takeIf { it.toString() == key } ?: -1
                        require(index in 0..n && (op == "add" || index < n)) { "invalid_stats_patch_index" }
                        when (op) {
                            "add" -> {
                                for (j in n downTo index + 1) parent.put(j, parent.get(j - 1))
                                parent.put(index, value)
                            }
                            "remove" -> parent.remove(index)
                            else -> parent.put(index, value)
                        }
                    }
                    else -> error("invalid_stats_patch_parent")
                }
            }
            return result
        }

        /** hubProtocol.js applyActivityClocks: newer `liveActivity.observedAt` per period and session key. */
        private fun applyActivityClocks(periods: JSONObject?, clocks: JSONObject?) {
            if (periods == null || clocks == null) return
            for (name in clocks.keys()) {
                val sessions = periods.optJSONObject(name)?.optJSONObject("sessions") ?: continue
                val times = clocks.optJSONObject(name) ?: continue
                for (key in times.keys()) sessions.optJSONObject(key)?.optJSONObject("liveActivity")?.put("observedAt", times.getString(key))
            }
        }

        /** hubProtocol.js applyFreshnessEvent: timestamps only; usage never changes in a freshness event. */
        fun applyFreshness(stats: JSONObject, fresh: JSONObject) {
            fresh.str("updatedAt")?.let { stats.put("updatedAt", it) }
            if (fresh.num("staleAfterMs") != null) stats.put("staleAfterMs", fresh.get("staleAfterMs"))
            fresh.optJSONObject("limits")?.let { l ->
                val limits = stats.optJSONObject("limits") ?: JSONObject().also { stats.put("limits", it) }
                for (k in l.keys()) limits.put(k, l.get(k))
            }
            applyActivityClocks(stats.optJSONObject("periods"), fresh.optJSONObject("sessionActivityObservedAt"))
            val byId = fresh.optJSONArray("devices").objects().associateBy { it.optString("deviceId") }
            for (device in stats.optJSONArray("devices").objects()) {
                val f = byId[device.optString("deviceId")] ?: continue
                for (k in f.keys()) if (k != "clientHealthObservedAt" && k != "sessionActivityObservedAt") device.put(k, f.get(k))
                applyActivityClocks(device.optJSONObject("periods"), f.optJSONObject("sessionActivityObservedAt"))
            }
        }
    }
}

/**
 * Live output speed from consecutive states: Σ new timed output / Σ new timed duration across fresh devices.
 * ponytail: a simplified token-monitor tokenRatePresentation (no per-device leases or idle aggregate);
 * a reading lapses a minute after the last device moved.
 */
class RateTracker {
    private val last = HashMap<String, Pair<Long, Long>>()
    private var speed: Double? = null
    private var sampledAt = 0L

    fun observe(devices: List<Device>, now: Long) {
        var output = 0L
        var duration = 0L
        for (d in devices) {
            if (d.stale) {
                last.remove(d.id)
                continue
            }
            val previous = last.put(d.id, d.timedOutputTokens to d.timedDurationMs) ?: continue
            val dOut = d.timedOutputTokens - previous.first
            val dDur = d.timedDurationMs - previous.second
            if (dOut > 0 && dDur > 0) { // a new day resets the counters; that delta is negative and skipped
                output += dOut
                duration += dDur
            }
        }
        if (duration > 0) {
            speed = output * 1000.0 / duration
            sampledAt = now
        }
    }

    /** Tokens per second, or null when nothing has moved in the last minute. */
    fun speed(now: Long): Double? = speed?.takeIf { now - sampledAt <= 60_000 }
}

/** Reads a token-monitor Hub (https://github.com/Javis603/token-monitor, docs/API.md). */
object MonitorApi {
    class Unauthorized : Exception()

    /** Last state seen (epoch ms to stats), so the tab shows data at once when reopened. */
    @Volatile
    var last: Pair<Long, MonitorStats>? = null

    @Volatile
    var history: History? = null

    /** When [history] was fetched; 0 forces the next refetch. */
    @Volatile
    var historyAt = 0L

    internal fun base(url: String): String {
        val clean = url.trim().trimEnd('/')
        val uri = java.net.URI(clean)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "HTTPS Hub URL required" }
        return clean
    }

    internal fun auth(secret: String?) = secret?.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    /** Follows `GET /api/stats/stream` (protocol v4: snapshots, then patches) until it drops; [onStats] runs on the main thread. */
    suspend fun stream(url: String, secret: String?, onStats: (MonitorStats) -> Unit) = withContext(Dispatchers.IO) {
        val c = URL("${base(url)}/api/stats/stream").openConnection() as HttpURLConnection
        // Blocking reads ignore coroutine cancellation; closing the socket ends them.
        val closer = currentCoroutineContext().job.invokeOnCompletion { c.disconnect() }
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 75_000 // the Hub sends a heartbeat every 30 s
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("x-token-monitor-stream", "4")
            auth(secret).forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw Unauthorized()
            check(code == 200) { "HTTP $code" }
            val parser = SseParser()
            val receiver = StatsReceiver()
            val reader = c.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                val event = parser.line(line) ?: continue
                val stats = MonitorStats.parse(receiver.receive(JSONObject(event.data)) ?: continue)
                last = System.currentTimeMillis() to stats
                withContext(Dispatchers.Main) { onStats(stats) }
            }
        } finally {
            closer.dispose()
            c.disconnect()
        }
    }

    suspend fun history(url: String, secret: String?): History = withContext(Dispatchers.IO) {
        val (code, text) = ServerApi.request("${base(url)}/api/history", headers = auth(secret), followRedirects = false)
        if (code == 401 || code == 403) throw Unauthorized()
        check(code in 200..299) { "HTTP $code" }
        History.parse(JSONObject(text)).also {
            history = it
            historyAt = System.currentTimeMillis()
        }
    }
}
