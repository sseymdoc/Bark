package me.fin.bark

import me.fin.bark.data.BeszelData
import me.fin.bark.data.BeszelMonitor
import me.fin.bark.data.History
import me.fin.bark.data.MonitorApi
import me.fin.bark.data.ServerApi
import me.fin.bark.data.time
import me.fin.bark.ui.bytesPerSec
import me.fin.bark.ui.ms
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * Snapshot tests read the server's live `GET /api/beszel/snapshot`, so no server or probe name is kept in the repo.
 * Set BARK_MONITOR_URL, BARK_MONITOR_KEY and BARK_MONITOR_DEVICE_TOKEN (an Android device registered there) to run
 * them; without all three they are skipped.
 */
class BeszelTest {
    private companion object {
        val env = listOf("BARK_MONITOR_URL", "BARK_MONITOR_KEY", "BARK_MONITOR_DEVICE_TOKEN").map { System.getenv(it) }

        /** The live snapshot's body, fetched once, with the response's Date as `serverTime`; null when not configured. */
        val live: String? by lazy {
            val (url, key, token) = env
            if (env.any { it.isNullOrBlank() }) return@lazy null
            val (code, text, date) = ServerApi.request(
                "${MonitorApi.base(url!!)}/api/beszel/snapshot", headers = MonitorApi.auth(key!!, token!!),
                followRedirects = false, maxResponseBytes = 65_536,
            )
            check(code == 200) { "snapshot HTTP $code" }
            JSONObject(text).apply { if (!has("serverTime")) put("serverTime", Instant.ofEpochMilli(date).toString()) }.toString()
        }
    }

    /** A fresh copy of the live snapshot to change. */
    private fun snapshot(): JSONObject {
        val text = live
        assumeTrue("BARK_MONITOR_URL, BARK_MONITOR_KEY and BARK_MONITOR_DEVICE_TOKEN are not set", text != null)
        return JSONObject(text!!)
    }

    /** A snapshot served as a file has no `serverTime`; the response's Date stands in, and without either it is refused. */
    @Test fun dateHeaderStandsInForServerTime() {
        val json = JSONObject().put("version", 1).put("observedAt", "2026-11-07T14:47:03Z").put("receivedAt", "2026-11-07T14:47:04Z")
            .put("hubStatus", "up").put("systems", JSONArray()).put("monitors", JSONArray())
        assertEquals(1_794_062_830_000L, BeszelData.parse(json, 0, 1_794_062_830_000L).serverTime)
        assertThrows(Exception::class.java) { BeszelData.parse(json, 0) }
        json.put("serverTime", "2026-11-07T14:47:05Z")
        assertEquals("the snapshot's own clock wins", 1_794_062_825_000L, BeszelData.parse(json, 0, 1L).serverTime)
    }

    private fun JSONObject.systems(): JSONArray = getJSONArray("systems")
    private fun JSONObject.monitors(): JSONArray = getJSONArray("monitors")
    private fun JSONArray.strings(key: String) = (0 until length()).map { getJSONObject(it).getString(key) }

    @Test fun parsesLiveSnapshot() {
        val json = snapshot()
        val data = BeszelData.parse(json, 1000)
        assertEquals("every system the Hub sends", json.systems().strings("id"), data.systems.map { it.id })
        assertEquals(json.systems().strings("name"), data.systems.map { it.name })
        assertEquals("every probe the Hub sends", json.monitors().strings("id"), data.monitors.map { it.id })
        assertEquals(json.monitors().strings("name"), data.monitors.map { it.name })
        assertEquals(data.serverTime + 1000, data.now(2000))
        assertTrue(data.isStale(data.receivedAt + 181_000))
        assertTrue("a snapshot from the future is not trusted", data.isStale(data.receivedAt - 61_000))
    }

    @Test fun unknownMetricsStayNullAndExpiredDataStopsBeingOnline() {
        val json = snapshot()
        assumeTrue(json.systems().length() > 0 && json.monitors().length() > 0)
        json.systems().getJSONObject(0).put("cpu", JSONObject.NULL)
        val data = BeszelData.parse(json, 0)
        assertNull(data.systems[0].cpu)
        val old = data.receivedAt + 181_000
        assertTrue(data.isStale(old))
        assertEquals("stale", data.systems[0].state(old, data.isStale(old)))
        assertEquals("stale", data.monitors[0].state(old, true))
    }

    @Test fun latestWindowIsIndependentOfHourlyLoss() {
        val at = 1_794_062_823_955L
        val m = BeszelMonitor("probe", "Probe", "server", "icmp", 60, 1000, 1000, 80.0, at, 1, 1)
        assertEquals("ok", m.state(at))
        assertEquals("down", m.copy(successCount = 0).state(at))
        assertEquals("loss", m.copy(totalCount = 2).state(at))
        assertEquals("unknown", m.copy(successCount = null, totalCount = null).state(at))
        assertEquals("stale", m.state(at + 180_001))
    }

    @Test fun rejectsInvalidSnapshots() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("version", 99) },
            { it.put("observedAt", "bad") },
            { it.remove("systems") },
        )) {
            val json = snapshot(); change(json)
            assertThrows(Exception::class.java) { BeszelData.parse(json, 0) }
        }
    }

    @Test fun dropsOnlyMalformedRecords() {
        val systems = snapshot().systems().length()
        assumeTrue(systems >= 2)
        for (change in listOf<(JSONArray) -> Unit>(
            { it.getJSONObject(0).put("cpu", 101) },
            { it.getJSONObject(0).put("mem", true) },
            { it.getJSONObject(0).put("sampledAt", "bad") },
            { it.getJSONObject(1).put("id", it.getJSONObject(0).getString("id")) },
        )) {
            val json = snapshot(); change(json.systems())
            assertEquals(systems - 1, BeszelData.parse(json, 0).systems.size)
        }
        val json = snapshot()
        assumeTrue(json.monitors().length() > 0)
        val broken = json.monitors().getJSONObject(0).put("successCount", 5).put("totalCount", 1).getString("id")
        val monitors = BeszelData.parse(json, 0).monitors
        assertEquals(json.monitors().length() - 1, monitors.size)
        assertFalse(monitors.any { it.id == broken })
    }

    @Test fun followsWhateverTheHubSends() {
        val json = snapshot()
        val ids = json.systems().strings("id")
        assumeTrue(ids.isNotEmpty() && json.monitors().length() >= 2)
        json.systems().put(JSONObject(json.systems().getJSONObject(0).toString()).put("id", "extra-system").put("status", "rebooting"))
        val removed = json.monitors().getJSONObject(0).getString("id")
        json.monitors().remove(0)
        json.monitors().getJSONObject(0).put("system", "extra-system").put("protocol", "udp")
        val data = BeszelData.parse(json, 0)
        assertEquals(ids + "extra-system", data.systems.map { it.id })
        assertEquals("unknown", data.systems.last().status)
        assertFalse(data.monitors.any { it.id == removed })
        assertEquals("extra-system" to "udp", data.monitors[0].system to data.monitors[0].protocol)
    }

    @Test fun parsesOffsetsAndZ() {
        val at = 1_794_062_823_955L // 2026-11-07T14:47:03.955Z
        for (s in listOf("2026-11-07T14:47:03.955293+00:00", "2026-11-07T22:47:03.955+08:00", "2026-11-07T14:47:03.955Z", "2026-11-07 14:47:03.955Z")) {
            assertEquals(s, at, JSONObject().put("t", s).time("t"))
        }
    }

    @Test fun httpsServerAndBarkDeviceCredentials() {
        assertEquals("https://example.com:8443", MonitorApi.base(" https://example.com:8443/ "))
        assertEquals(mapOf("X-Bark-Key" to "k", "X-Bark-Device-Token" to "t"), MonitorApi.auth("k", "t"))
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?secret=x", "https://example.com#x", "not a url")) {
            assertThrows(url, MonitorApi.Unavailable::class.java) { MonitorApi.base(url) }
        }
        // A plain bark-server reads /api/... as a push to the key "api" and answers 400.
        for (code in listOf(400, 404, 405)) assertThrows(MonitorApi.Unavailable::class.java) { MonitorApi.checkCode(code) }
        for (code in listOf(401, 403)) assertThrows(MonitorApi.Unauthorized::class.java) { MonitorApi.checkCode(code) }
        assertThrows(IllegalStateException::class.java) { MonitorApi.checkCode(502) }
        MonitorApi.checkCode(200)
    }

    /** Switching the default server must not show the old server's data or keep its history throttle. */
    @Test fun cachedDataBelongsToOneServer() {
        MonitorApi.use("https://a key-a")
        MonitorApi.history = History(emptyList(), 0, 0)
        MonitorApi.historyAt = 5
        MonitorApi.use("https://a key-a")
        assertEquals(5L, MonitorApi.historyAt)
        MonitorApi.use("https://b key-b")
        assertNull(MonitorApi.history)
        assertEquals(0L, MonitorApi.historyAt)
        assertNull(MonitorApi.last)
    }

    @Test fun formats() {
        assertEquals("1.2 ms", ms(1234))
        assertEquals("37 ms", ms(37_400))
        assertEquals("512 B/s", bytesPerSec(512))
        assertEquals("1.5 MB/s", bytesPerSec(1_572_864))
    }
}
