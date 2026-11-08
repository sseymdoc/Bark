package me.fin.bark

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.fin.bark.data.ServerApi
import me.fin.bark.data.closeOnCancel
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MonitorTransportTest {
    @Test fun monitorRequestsRejectRedirectsAndOversizedResponses() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirected = AtomicInteger()
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "/target")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.createContext("/target") { redirected.incrementAndGet(); it.sendResponseHeaders(200, -1); it.close() }
        server.createContext("/large") {
            it.sendResponseHeaders(200, 128)
            it.responseBody.use { body -> body.write(ByteArray(128)) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            assertEquals(302, ServerApi.request("$base/redirect", headers = mapOf("Authorization" to "Bearer test-only"), followRedirects = false, maxResponseBytes = 64).code)
            assertEquals(0, redirected.get())
            assertEquals("response_too_large", assertThrows(IllegalStateException::class.java) {
                ServerApi.request("$base/large", followRedirects = false, maxResponseBytes = 64)
            }.message)
        } finally { server.stop(0) }
    }

    /** A stream kept alive by heartbeats must end when its coroutine is cancelled, not when the server closes it. */
    @Test fun cancellingClosesAStreamKeptAliveByHeartbeats() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/stream") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            runCatching { while (true) { exchange.responseBody.write(":\n".toByteArray()); exchange.responseBody.flush(); Thread.sleep(50) } }
            exchange.close()
        }
        server.start()
        try {
            val opened = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                val c = URL("http://127.0.0.1:${server.address.port}/stream").openConnection() as HttpURLConnection
                c.readTimeout = 60_000
                c.closeOnCancel { c.inputStream.bufferedReader().lineSequence().forEach { opened.complete(Unit) } }
            }
            withTimeout(5_000) { opened.await() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.stop(0) }
    }
}
