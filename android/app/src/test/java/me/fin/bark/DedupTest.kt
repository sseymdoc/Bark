package me.fin.bark

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.fin.bark.push.Dedup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FCM and SSE copies of one push share a `_msgid`; it must be handled once and never lost. */
class DedupTest {
    private val delivered = mutableSetOf<String>()
    private val dedup = Dedup({ it in delivered }, { delivered += it })

    @Test fun aCopyArrivingMeanwhileWaitsAndThenSkips() = runBlocking {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val first = launch { dedup.run("m") { runs++; gate.await() } }
        yield() // first holds the claim
        val second = launch { dedup.run("m") { runs++ } }
        yield()
        gate.complete(Unit)
        joinAll(first, second)
        assertEquals(1, runs)
        assertTrue("m" in delivered)
    }

    @Test fun aCancelledCopyLeavesThePushToTheWaitingOne() = runBlocking {
        var runs = 0
        val first = launch { dedup.run("m") { awaitCancellation() } }
        yield()
        val second = launch { dedup.run("m") { runs++ } }
        yield()
        first.cancelAndJoin()
        second.join()
        assertEquals("the waiting copy handled it", 1, runs)
        assertTrue("m" in delivered)
    }

    @Test fun aDeliveredPushIsSkipped() = runBlocking {
        delivered += "m"
        var runs = 0
        dedup.run("m") { runs++ }
        assertEquals(0, runs)
    }
}
