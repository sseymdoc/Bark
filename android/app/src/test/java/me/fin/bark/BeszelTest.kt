package me.fin.bark

import me.fin.bark.data.BeszelData
import me.fin.bark.data.BeszelMonitor
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

/**
 * Snapshot tests read the Hub's live `GET /api/beszel/snapshot`, so no server or probe name is kept in the repo.
 * Set BARK_MONITOR_URL and BARK_MONITOR_SECRET to run them; without both they are skipped.
 */
class BeszelTest {
    private companion object {
        /** The live snapshot's body, fetched once; null when no Hub is configured. */
        val live: String? by lazy {
            val url = System.getenv("BARK_MONITOR_URL")
            val secret = System.getenv("BARK_MONITOR_SECRET")
            if (url.isNullOrBlank() || secret.isNullOrBlank()) return@lazy null
            val (code, text) = ServerApi.request(
                "${MonitorApi.base(url)}/api/beszel/snapshot", headers = MonitorApi.auth(secret),
                followRedirects = false, maxResponseBytes = 65_536,
            )
            check(code == 200) { "snapshot HTTP $code" }
            text
        }
    }

    /** A fresh copy of the live snapshot to change. */
    private fun snapshot(): JSONObject {
        val text = live
        assumeTrue("BARK_MONITOR_URL and BARK_MONITOR_SECRET are not set", text != null)
        return JSONObject(text!!)
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

    @Test fun oneHttpsOriginAndBearerSecret() {
        assertEquals("https://example.com:8443", MonitorApi.base(" https://example.com:8443/ "))
        assertEquals(mapOf("Authorization" to "Bearer shared"), MonitorApi.auth("shared"))
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?secret=x", "https://example.com#x")) {
            assertThrows(Exception::class.java) { MonitorApi.base(url) }
        }
    }

    @Test fun formats() {
        assertEquals("1.2 ms", ms(1234))
        assertEquals("37 ms", ms(37_400))
        assertEquals("512 B/s", bytesPerSec(512))
        assertEquals("1.5 MB/s", bytesPerSec(1_572_864))
    }
}
