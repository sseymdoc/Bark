package me.fin.bark.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metrics projected by the shared Hub; null means not measured. */
data class BeszelSystem(
    val id: String, val name: String, val status: String, val sampledAt: Long?,
    val cpu: Double?, val mem: Double?, val disk: Double?, val uptimeSec: Long?,
    val load: List<Double?>, val bandwidth: Long?, val temp: Double?, val version: String?,
) {
    fun state(now: Long, snapshotStale: Boolean): String = when {
        snapshotStale -> "stale"
        status == "up" && stale(sampledAt, now, 180_000) -> "stale"
        else -> status
    }
}

data class BeszelMonitor(
    val id: String, val name: String, val system: String, val protocol: String, val interval: Int,
    val latencyUs: Long?, val avg1hUs: Long?, val loss1h: Double?, val updatedAt: Long?,
    val successCount: Int?, val totalCount: Int?,
) {
    fun state(now: Long, snapshotStale: Boolean = false): String = when {
        snapshotStale || stale(updatedAt, now, maxOf(180_000L, interval * 3_000L)) -> "stale"
        totalCount == null || totalCount == 0 || successCount == null -> "unknown"
        successCount == 0 -> "down"
        successCount < totalCount -> "loss"
        else -> "ok"
    }
}

private fun stale(sampled: Long?, now: Long, limit: Long) = sampled == null || now - sampled > limit || sampled - now > 60_000

data class BeszelData(
    val systems: List<BeszelSystem>, val monitors: List<BeszelMonitor>,
    val observedAt: Long, val receivedAt: Long, val serverTime: Long, val fetchedElapsed: Long,
) {
    fun now(elapsed: Long) = serverTime + (elapsed - fetchedElapsed).coerceAtLeast(0)
    fun isStale(now: Long) = stale(receivedAt, now, 180_000) || stale(observedAt, now, 180_000)

    companion object {
        private val statuses = setOf("up", "down", "paused", "pending", "unknown")
        private fun JSONObject.metric(key: String, max: Double, min: Double = 0.0): Double? {
            require(has(key)) { "invalid_snapshot" }
            if (isNull(key)) return null
            val n = get(key) as? Number ?: error("invalid_snapshot")
            return n.toDouble().also { require(it.isFinite() && it in min..max) { "invalid_snapshot" } }
        }
        private fun JSONObject.sample(key: String): Long? {
            require(has(key)) { "invalid_snapshot" }
            if (isNull(key)) return null
            require(get(key) is String) { "invalid_snapshot" }
            return time(key) ?: error("invalid_snapshot")
        }
        private fun JSONObject.label(key: String, max: Int = 80) = getString(key).also {
            require(it.isNotEmpty() && it.length <= max && it.none { c -> c.code < 32 }) { "invalid_snapshot" }
        }
        /** Whatever records the Hub sends; a malformed one is left out rather than failing the whole snapshot. */
        private fun <T> JSONObject.records(key: String, parse: (JSONObject) -> T): List<T> {
            val array = getJSONArray(key)
            return (0 until array.length()).mapNotNull { i -> runCatching { parse(array.getJSONObject(i)) }.getOrNull() }
        }
        /** [date] (the response's `Date`, epoch ms) stands in for `serverTime`, which a snapshot served as a file lacks. */
        fun parse(json: JSONObject, fetchedElapsed: Long, date: Long = 0): BeszelData {
            require(json.get("version") == 1) { "unsupported_snapshot" }
            val systems = json.records("systems") { s ->
                val load = s.getJSONArray("load")
                require(load.length() <= 3) { "invalid_snapshot" }
                BeszelSystem(s.label("id", 64), s.label("name"), s.getString("status").takeIf { it in statuses } ?: "unknown", s.sample("sampledAt"),
                    s.metric("cpu", 100.0), s.metric("mem", 100.0), s.metric("disk", 100.0), s.metric("uptimeSec", 1e10)?.toLong(),
                    (0 until load.length()).map { i -> JSONObject().put("v", load.get(i)).metric("v", 1e6) },
                    s.metric("bandwidth", 1e15)?.toLong(), s.metric("temp", 200.0, -100.0),
                    if (s.isNull("version")) null else s.label("version", 32))
            }.distinctBy { it.id }
            val monitors = json.records("monitors") { m ->
                val interval = m.metric("interval", 86400.0, 10.0) ?: error("invalid_snapshot")
                val success = m.metric("successCount", 1e6)
                val total = m.metric("totalCount", 1e6)
                require((success == null && total == null) || (success != null && total != null && success <= total && success % 1 == 0.0 && total % 1 == 0.0)) { "invalid_snapshot" }
                BeszelMonitor(m.label("id", 64), m.label("name"), m.label("system", 64), m.label("protocol", 16), interval.toInt(),
                    m.metric("latencyUs", 1e12)?.toLong(), m.metric("avg1hUs", 1e12)?.toLong(), m.metric("loss1h", 100.0),
                    m.sample("sampledAt"), success?.toInt(), total?.toInt())
            }.distinctBy { it.id }
            return BeszelData(systems, monitors, json.sample("observedAt") ?: error("invalid_snapshot"),
                json.sample("receivedAt") ?: error("invalid_snapshot"),
                (if (json.has("serverTime")) json.sample("serverTime") else date.takeIf { it > 0 }) ?: error("invalid_snapshot"), fetchedElapsed)
        }
    }
}

/** Same server and credentials as [MonitorApi]. */
object BeszelApi {
    class Unavailable : Exception()
    @Volatile var last: BeszelData? = null

    suspend fun fetch(url: String, auth: Map<String, String>): BeszelData = withContext(Dispatchers.IO) {
        val (code, text, date) = ServerApi.request("${MonitorApi.base(url)}/api/beszel/snapshot",
            headers = auth, followRedirects = false, maxResponseBytes = 65_536)
        if (code == 401 || code == 403) throw MonitorApi.Unauthorized()
        if (code == 400 || code == 404 || code == 503) throw Unavailable()
        check(code == 200) { "HTTP $code" }
        BeszelData.parse(JSONObject(text), SystemClock.elapsedRealtime(), date).also { last = it }
    }
}
