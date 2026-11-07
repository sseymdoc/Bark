package me.fin.bark

import com.sun.net.httpserver.HttpServer
import me.fin.bark.data.ServerApi
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
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
            assertEquals(302, ServerApi.request("$base/redirect", headers = mapOf("Authorization" to "Bearer test-only"), followRedirects = false, maxResponseBytes = 64).first)
            assertEquals(0, redirected.get())
            assertEquals("response_too_large", assertThrows(IllegalStateException::class.java) {
                ServerApi.request("$base/large", followRedirects = false, maxResponseBytes = 64)
            }.message)
        } finally { server.stop(0) }
    }
}
