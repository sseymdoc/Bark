package me.fin.bark

import me.fin.bark.push.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun events(stream: String) = SseParser().let { p -> stream.split("\n").mapNotNull { p.line(it) } }

    @Test
    fun eventsAreSplitByBlankLinesAndKeepTheirId() {
        val got = events("id: 1\ndata: {\"body\":\"a\"}\n\nid: 2\ndata: {\"body\":\"b\"}\n\n")
        assertEquals(listOf("1" to "{\"body\":\"a\"}", "2" to "{\"body\":\"b\"}"), got.map { it.id to it.data })
    }

    @Test
    fun commentsAndEmptyEventsAreIgnored() {
        assertEquals(emptyList<Any>(), events(": ping\n\n: ping\n\n\n"))
    }

    @Test
    fun multiLineDataIsJoinedAndUnknownFieldsSkipped() {
        val got = events("event: x\ndata:first\ndata: second\nretry: 5\n\n").single()
        assertNull(got.id)
        assertEquals("first\nsecond", got.data)
    }
}
