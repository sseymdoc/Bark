package me.fin.bark

import me.fin.bark.data.Day
import me.fin.bark.data.Device
import me.fin.bark.data.History
import me.fin.bark.data.LimitWindow
import me.fin.bark.data.MonitorLayout
import me.fin.bark.data.MonitorStats
import me.fin.bark.data.RateTracker
import me.fin.bark.data.StatsReceiver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MonitorStatsTest {
    private companion object {
        /** 2026-05-30T05:00:10Z */
        const val NOW = 1_780_117_210_000L
        const val RECENT = "2026-05-30T04:55:10.000Z" // five minutes before NOW
    }

    private val stats = MonitorStats.parse(
        JSONObject(
            """
            {
              "historyRevision": "abc",
              "periods": {
                "today": {
                  "totalTokens": 1500, "costUsd": 1.25,
                  "models": {"gpt-5": 500, "claude-opus": 1000, "unused": 0}, "modelCosts": {"claude-opus": 1.0, "gpt-5": 0.25},
                  "clientModels": {"claude": {"claude-opus": 1000}, "codex": {"gpt-5": 400}, "cursor": {"gpt-5": 100}},
                  "sessions": {
                    "claude:a": {"client": "claude", "sessionId": "a", "totalTokens": 100, "lastUsedAt": "2026-05-30T04:00:00.000Z",
                      "projectLabel": "bark", "models": {"claude-opus": 90, "claude-haiku": 10}, "reasoningEffort": "xhigh",
                      "contextTokens": 71, "contextWindow": 100},
                    "codex:b": {"client": "codex", "sessionId": "b", "totalTokens": 200, "lastUsedAt": "2026-05-30T05:00:00.000Z", "title": "Fix bug"}
                  }
                },
                "month": {"totalTokens": 4567, "costUsd": 0.04},
                "allTime": {"totalTokens": 8901, "costUsd": 0.08}
              },
              "limits": {"providers": [
                {"provider": "claude", "planLabel": "Max", "status": "ok", "windows": [
                  {"kind": "session", "usedPercent": 9, "resetsAt": "2026-05-18T05:00:00.000Z"},
                  {"kind": "weekly", "usedPercent": 6, "remainingPercent": 94},
                  {"kind": "weekly", "usedPercent": 20, "additional": true},
                  {"kind": "billing", "metric": "spend", "used": 3, "showMeter": false}
                ]},
                {"provider": "cursor", "status": "notConfigured", "windows": []},
                {"provider": "deepseek", "status": "ok", "windows": [
                  {"kind": "billing", "metric": "credits", "remaining": 12.5, "currency": "CNY", "showMeter": false}
                ]},
                {"provider": "codex", "status": "unauthorized", "stale": true, "windows": []},
                {"provider": "kimi", "status": "ok", "windows": []}
              ]},
              "devices": [
                {"deviceId": "d1", "stale": false, "periods": {"today": {"timedOutputTokens": 50, "timedDurationMs": 1000}}},
                {"deviceId": "d2", "stale": true}
              ],
              "historyPreview": {"daily": [{"date": "2026-05-29", "tokens": 10, "cost": 0.01}]}
            }
            """,
        ),
    )

    private fun session(fields: JSONObject) = MonitorStats.parse(
        JSONObject().put("periods", JSONObject().put("today", JSONObject().put("sessions", JSONObject().put("x:1", fields)))),
    ).today.sessions.single()

    @Test
    fun periodsCarryModelsByTokensWithTheirMainTool() {
        assertEquals(1500L, stats.today.tokens)
        assertEquals(1.25, stats.today.costUsd, 1e-9)
        assertEquals(listOf("claude-opus" to 1.0, "gpt-5" to 0.25), stats.today.models.map { it.id to it.costUsd })
        assertEquals(mapOf("claude-opus" to "claude", "gpt-5" to "codex"), stats.today.modelClient)
        assertEquals(4567L, stats.month.tokens)
        assertEquals(emptyList<Any>(), stats.month.models + stats.month.sessions)
        assertEquals("abc", stats.historyRevision)
        assertEquals(listOf(Day("2026-05-29", 10, 0.01)), stats.daily)
    }

    @Test
    fun limitsShowRemainingAndDropNonMeters() {
        // Hub order is claude, deepseek, codex; Codex (even in error) and Claude are pinned to the top.
        assertEquals(listOf("codex", "claude", "deepseek"), stats.limits.map { it.provider })
        val claude = stats.limits[1]
        assertEquals("Max", claude.plan)
        assertEquals(listOf(91.0, 94.0), claude.windows.map { it.remainingPercent })
        assertEquals(1_779_080_400_000L, claude.windows[0].resetsAt)
        val credits = stats.limits[2].windows.single()
        assertTrue(credits.credits)
        assertEquals(12.5, credits.remaining!!, 1e-9)
        assertEquals("CNY", credits.currency)
        assertEquals("unauthorized", stats.limits[0].status)
        assertTrue(stats.limits[0].stale)
    }

    @Test
    fun sessionsAreRecentFirstWithTheirMainModel() {
        val (b, a) = stats.today.sessions
        assertEquals("Fix bug", b.name)
        assertEquals("bark", a.name)
        assertEquals("claude-opus", a.model)
        assertEquals("xhigh", a.reasoningEffort)
        assertEquals(29, a.copy(lastUsedAt = NOW).contextLeft(NOW))
        assertNull("idle sessions drop the context gauge", a.contextLeft(NOW))
    }

    @Test
    fun autoReviewSessionsAreLeftOut() {
        val sessions = JSONObject()
            .put("codex:review", JSONObject().put("lastUsedAt", "2026-05-30T05:00:00.000Z").put("models", JSONObject().put("codex-auto-review", 9)))
            .put("codex:work", JSONObject().put("lastUsedAt", "2026-05-30T04:00:00.000Z").put("models", JSONObject().put("gpt-5", 9).put("codex-auto-review", 1)))
        val parsed = MonitorStats.parse(JSONObject().put("periods", JSONObject().put("today", JSONObject().put("sessions", sessions))))
        assertEquals("a session whose main model is something else stays", listOf("work"), parsed.today.sessions.map { it.name })
    }

    @Test
    fun sessionsAreCappedAtFive() {
        val sessions = JSONObject()
        repeat(7) { sessions.put("claude:$it", JSONObject().put("totalTokens", it).put("lastUsedAt", "2026-05-30T04:0$it:00.000Z")) }
        val parsed = MonitorStats.parse(JSONObject().put("periods", JSONObject().put("today", JSONObject().put("sessions", sessions))))
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), parsed.today.sessions.map { it.tokens })
    }

    private fun window(kind: String, label: String?, left: Double?) = LimitWindow(kind, label, left, null, null, null, false)

    @Test
    fun compactWindowsFollowTheDesktopHome() {
        // Other providers: two windows, shortest cycle first (Claude's extra "Fable" weekly drops out).
        val claude = listOf(window("weekly", null, 94.0), window("weekly", "Fable", 100.0), window("session", null, 91.0))
        assertEquals(listOf(91.0, 94.0), MonitorStats.compactWindows("claude", claude).map { it.remainingPercent })

        // Antigravity: one window per model group, labelled by the group with the period moved aside.
        val ag = listOf(
            window("session", "Gemini 5-hour", 100.0), window("weekly", "Gemini weekly", 100.0),
            window("session", "Claude/GPT 5-hour", 100.0), window("weekly", "Claude/GPT weekly", 100.0),
        )
        assertEquals(
            listOf(Triple("Gemini", "5-hour", 100.0), Triple("Claude/GPT", "5-hour", 100.0)),
            MonitorStats.compactWindows("antigravity", ag).map { Triple(it.label, it.period, it.remainingPercent) },
        )
        // A weekly quota replaces the session one only when it is tighter and below 20 %.
        val tight = ag.toMutableList().also { it[1] = window("weekly", "Gemini weekly", 15.0); it[3] = window("weekly", "Claude/GPT weekly", 50.0) }
        assertEquals(listOf("Weekly", "5-hour"), MonitorStats.compactWindows("antigravity", tight).map { it.period })
        // Three groups: the two tightest stay, in their original order.
        val three = ag + window("session", "Flash 5-hour", 30.0)
        assertEquals(listOf("Gemini", "Flash"), MonitorStats.compactWindows("antigravity", three.toMutableList().also { it[0] = window("session", "Gemini 5-hour", 40.0) }).map { it.label })
        // One group keeps both periods with full labels.
        assertEquals(listOf("Gemini 5-hour", "Gemini Weekly"), MonitorStats.compactWindows("antigravity", ag.take(2)).map { it.label })
        // Ungrouped labels fall back to the first two windows.
        assertEquals(listOf("Pro", "Flash"), MonitorStats.compactWindows("antigravity", listOf(window("weekly", "Pro", 1.0), window("weekly", "Flash", 2.0), window("weekly", "X", 3.0))).map { it.label })
    }

    @Test
    fun activityFollowsTokenMonitorRules() {
        fun state(fields: JSONObject, now: Long = NOW) = session(fields.put("client", fields.optString("client", "claude"))).state(now)
        assertEquals("running", state(JSONObject().put("lastUsedAt", RECENT)))
        assertEquals("running", state(JSONObject().put("lastUsedAt", RECENT).put("turnEnded", false)))
        assertEquals("ended", state(JSONObject().put("lastUsedAt", RECENT).put("turnEnded", true)))
        assertEquals("idle", state(JSONObject().put("lastUsedAt", "2026-05-30T04:40:00.000Z")))
        assertEquals("idle", state(JSONObject().put("lastUsedAt", RECENT).put("archived", true)))
        assertEquals("idle", state(JSONObject()))
        // waitingForInput only counts for Codex.
        assertEquals("waiting", state(JSONObject().put("client", "codex").put("lastUsedAt", RECENT).put("waitingForInput", true)))
        assertEquals("running", state(JSONObject().put("lastUsedAt", RECENT).put("waitingForInput", true)))
        // A live reading wins for 30 s, then the transcript decides again.
        val live = JSONObject().put("state", "waiting").put("observedAt", "2026-05-30T05:00:00.000Z")
        assertEquals("waiting", state(JSONObject().put("lastUsedAt", RECENT).put("liveActivity", live)))
        assertEquals("running", state(JSONObject().put("lastUsedAt", RECENT).put("liveActivity", live), NOW + 30_000))
    }

    @Test
    fun promptCacheCountsDownForClaudeOnly() {
        val cache = JSONObject().put("observedAt", "2026-05-30T04:20:00.000Z").put("ttlSeconds", 3600)
        assertEquals(20, session(JSONObject().put("client", "claude").put("promptCache", cache)).cacheMinutes(NOW))
        assertNull(session(JSONObject().put("client", "codex").put("promptCache", cache)).cacheMinutes(NOW))
        assertNull(session(JSONObject().put("client", "claude").put("promptCache", cache)).cacheMinutes(NOW + 3_600_000))
    }

    @Test
    fun receiverAppliesSnapshotDeltaAndFreshness() {
        val r = StatsReceiver()
        val snapshot = JSONObject(
            """{"type":"stats","revision":4,"stats":{"updatedAt":"t0","list":[1,3],"a/b":{"x":1},
               "periods":{"today":{"sessions":{"claude:s":{"liveActivity":{"state":"running","observedAt":"o0"}}}}},
               "devices":[{"deviceId":"d1","updatedAt":"u0","stale":false,"hostname":"pc"}]}}""",
        )
        r.receive(snapshot)
        val patch = JSONArray(
            """[{"op":"replace","path":"/a~1b/x","value":2},{"op":"add","path":"/list/1","value":2},
               {"op":"add","path":"/list/-","value":4},{"op":"remove","path":"/updatedAt"},{"op":"add","path":"/n","value":null}]""",
        )
        val s = r.receive(JSONObject().put("type", "delta").put("baseRevision", 4).put("revision", 5).put("patch", patch))!!
        assertEquals(2, s.getJSONObject("a/b").getInt("x"))
        assertEquals("[1,2,3,4]", s.getJSONArray("list").toString())
        assertTrue(!s.has("updatedAt") && s.isNull("n"))

        val fresh = JSONObject(
            """{"type":"freshness","baseRevision":5,"revision":6,"stats":{"updatedAt":"t1","limits":{"updatedAt":"l1"},
               "sessionActivityObservedAt":{"today":{"claude:s":"o1"}},
               "devices":[{"deviceId":"d1","updatedAt":"u1","stale":true,"clientHealthObservedAt":"h1"}]}}""",
        )
        r.receive(fresh)
        assertEquals("t1", s.getString("updatedAt"))
        assertEquals("l1", s.getJSONObject("limits").getString("updatedAt"))
        assertEquals("o1", s.getJSONObject("periods").getJSONObject("today").getJSONObject("sessions").getJSONObject("claude:s").getJSONObject("liveActivity").getString("observedAt"))
        val device = s.getJSONArray("devices").getJSONObject(0)
        assertEquals(listOf("u1", true, "pc", false), listOf(device.get("updatedAt"), device.get("stale"), device.get("hostname"), device.has("clientHealthObservedAt")))

        // A skipped revision must not be applied.
        try {
            r.receive(JSONObject().put("type", "delta").put("baseRevision", 7).put("revision", 8).put("patch", JSONArray()))
            fail("expected a revision mismatch")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun malformedPatchesThrow() {
        fun apply(patch: String) = StatsReceiver.applyPatch(JSONObject("""{"a":{"b":1},"l":[1]}"""), JSONArray(patch))
        for (bad in listOf(
            """[{"op":"replace","path":"/missing","value":1}]""",
            """[{"op":"add","path":"/x/y","value":1}]""",
            """[{"op":"move","path":"/a","from":"/l"}]""",
            """[{"op":"replace","path":"/l/5","value":1}]""",
            """[{"op":"add","path":"/a/~2","value":1}]""",
            """[{"op":"replace","path":"/a/b"}]""",
        )) {
            try {
                apply(bad)
                fail("expected $bad to throw")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun rateNeedsTwoSamplesAndLapses() {
        val rate = RateTracker()
        rate.observe(listOf(Device("d1", false, 100, 1_000)), NOW)
        assertNull("the first state is only a baseline", rate.speed(NOW))
        rate.observe(listOf(Device("d1", false, 160, 2_000), Device("d2", false, 5, 5)), NOW + 1_000)
        assertEquals(60.0, rate.speed(NOW + 1_000)!!, 1e-9)
        rate.observe(listOf(Device("d1", false, 10, 100)), NOW + 2_000) // new day: counters reset
        assertEquals(60.0, rate.speed(NOW + 2_000)!!, 1e-9)
        assertNull(rate.speed(NOW + 62_000))
    }

    @Test
    fun historyParsesAndMergesToday() {
        val h = History.parse(
            JSONObject(
                """{"daily":[{"date":"2026-05-28","tokens":40,"cost":1},{"date":"2026-05-29","tokens":10}],
                   "summary":{"activeDays":83,"peakDayTokens":52,"totalTokens":420,"totalCost":24.5,"currentStreak":3,"longestStreak":9,
                     "favoriteModel":"claude-opus","activeTimeMs":7200000}}""",
            ),
        )
        assertEquals(83, h.activeDays)
        assertEquals(52L, h.peakDayTokens)
        assertEquals(listOf<Any?>(420L, 24.5, 3, 9, "claude-opus", 7_200_000L), listOf(h.totalTokens, h.totalCost, h.currentStreak, h.longestStreak, h.favoriteModel, h.activeTimeMs))
        assertEquals(listOf(40L, 15L), History.withToday(h.daily, "2026-05-29", 15, 0.0).map { it.tokens })
        assertEquals(listOf(40L, 10L, 7L), History.withToday(h.daily, "2026-05-30", 7, 0.0).map { it.tokens })
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0L, 1L, 25L, 50L, 75L).map { History.intensity(it, 100) })
    }

    @Test
    fun toolsAndDevices() {
        val s = MonitorStats.parse(
            JSONObject(
                """
                {"periods": {"today": {"totalTokens": 100,
                  "clients": {"codex": 30, "claude": 70, "idle": 0}, "clientCosts": {"claude": 1.5}}},
                 "limits": {"providers": [{"provider": "antigravity", "status": "ok", "windows": [
                   {"kind": "session", "label": "Gemini 5-hour", "usedPercent": 0}, {"kind": "weekly", "label": "Gemini weekly", "usedPercent": 0},
                   {"kind": "session", "label": "Claude/GPT 5-hour", "usedPercent": 0}, {"kind": "weekly", "label": "Claude/GPT weekly", "usedPercent": 0}]}]},
                 "devices": [
                   {"deviceId": "desktop-1", "hostname": "Desktop-1", "osName": "Windows", "osVersion": "11", "stale": true,
                    "updatedAt": "2026-05-30T05:00:00.000Z", "periods": {"today": {"totalTokens": 5}, "allTime": {"totalTokens": 9}}},
                   {"deviceId": "laptop", "displayName": "Laptop"},
                   {"hostname": "linux"}
                 ]}
                """,
            ),
        )
        assertEquals(listOf("claude" to 1.5, "codex" to 0.0), s.today.clients.map { it.id to it.costUsd })
        val ag = s.limits.single()
        assertEquals(2 to 4, ag.windows.size to ag.allWindows.size)
        val d = s.devices[0]
        assertEquals(listOf("desktop-1", "Windows 11", true, listOf(5L, 0L, 9L), NOW - 10_000), listOf(d.name, d.os, d.stale, d.tokens, d.updatedAt))
        assertEquals(listOf("Laptop", "linux"), s.devices.drop(1).map { it.name })
    }

    @Test
    fun limitsPinCodexThenClaudeAndKeepTheHubOrder() {
        fun order(vararg ids: Pair<String, Int>): List<String> {
            val providers = JSONArray()
            ids.forEach { (id, used) ->
                providers.put(JSONObject().put("provider", id).put("status", "ok").put("windows", JSONArray().put(JSONObject().put("kind", "weekly").put("usedPercent", used))))
            }
            return MonitorStats.parse(JSONObject().put("limits", JSONObject().put("providers", providers))).limits.map { it.provider }
        }
        assertEquals(listOf("codex", "claude", "antigravity", "deepseek"), order("antigravity" to 0, "claude" to 20, "deepseek" to 5, "codex" to 22))
        assertEquals("not re-sorted by what is left", listOf("claude", "zai", "kimi"), order("zai" to 90, "claude" to 0, "kimi" to 10))
        assertEquals("both Codex accounts lead, in Hub order", listOf("codex", "codex", "kimi"), order("codex" to 1, "kimi" to 2, "codex" to 3))
    }

    @Test
    fun layoutKeepsKnownCardsInStoredOrder() {
        assertEquals(MonitorLayout.DEFAULT, MonitorLayout.parse(null))
        assertEquals("every card removed", emptyList<String>(), MonitorLayout.parse(""))
        assertEquals(listOf("tools", "overview"), MonitorLayout.parse("tools, bogus,overview,tools"))
        assertEquals("removed cards drop out of saved layouts", listOf("overview", "stats"), MonitorLayout.parse("overview,projects,status,stats"))
        assertEquals(listOf("tools", "devices", "limitsDetail", "stats"), MonitorLayout.hidden(MonitorLayout.DEFAULT))
        assertEquals(listOf("b", "c", "a"), MonitorLayout.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), MonitorLayout.move(listOf("a", "b", "c"), 2, 0))
        assertEquals("tools,overview", MonitorLayout.format(listOf("tools", "overview")))
    }

    @Test
    fun emptyResponseParsesToZeros() {
        val empty = MonitorStats.parse(JSONObject("{}"))
        assertEquals(0L, empty.today.tokens)
        assertEquals(emptyList<Any>(), empty.limits + empty.devices + empty.daily + empty.today.models)
    }
}
