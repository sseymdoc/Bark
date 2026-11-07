package me.fin.bark.push

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.fin.bark.MainActivity
import me.fin.bark.R
import me.fin.bark.data.Prefs
import me.fin.bark.data.Server
import me.fin.bark.data.ServerApi
import me.fin.bark.data.closeOnCancel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fallback for FCM: keeps a Server-Sent Events connection (bark-server `GET /stream`) to every server and
 * feeds the events into the same pipeline as FCM. Duplicates are dropped there by `_msgid`.
 */
class SseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sse_running))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
        )
        if (job == null) {
            job = scope.launch {
                // A changed server list (added, removed, new key) restarts the connections.
                Prefs.servers.collectLatest { servers -> coroutineScope { servers.forEach { launch { keepConnected(it) } } } }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
    }

    /**
     * Reconnects with backoff (2s doubling to 60s). Servers without SSE answer 404, or 400 when an older
     * bark-server reads "/stream" as a device key; those are retried every 30 minutes.
     */
    private suspend fun keepConnected(server: Server) {
        if (server.key.isEmpty()) return // not registered yet; the list re-emits once it is
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val code = runCatching { stream(server) { backoff = 2_000L } }.getOrDefault(-1)
            when (code) {
                // The record no longer matches (token rotated or cleared): register again, then retry.
                403 -> ServerApi.register(server.address, Prefs.deviceToken, server.key)
                    .onSuccess { if (it != server.key) Prefs.updateServer(server.copy(key = it)) }
                in 400..499 -> { delay(30 * 60_000L); continue }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Reads one stream until it drops. Returns the HTTP status (200 when an open stream ended). */
    private suspend fun stream(server: Server, onOpen: () -> Unit): Int {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val c = URL("${server.cleanAddress}/stream?key=${enc(server.key)}&devicetoken=${enc(Prefs.deviceToken)}").openConnection() as HttpURLConnection
        // Blocking reads ignore coroutine cancellation; closeOnCancel disconnects as soon as a changed server list cancels us.
        return c.closeOnCancel {
            c.connectTimeout = 15_000
            c.readTimeout = 60_000 // the server pings every 25s
            c.setRequestProperty("Accept", "text/event-stream")
            Prefs.lastEventId(server.id)?.let { c.setRequestProperty("Last-Event-ID", it) }
            if (c.responseCode != 200) return@closeOnCancel c.responseCode
            onOpen()
            val parser = SseParser()
            c.inputStream.bufferedReader().lineSequence().forEach { line ->
                currentCoroutineContext().ensureActive()
                val event = parser.line(line) ?: return@forEach
                // A malformed event, or one that fails to process (say a database error), is skipped and its id still
                // advances, so a replay from Last-Event-ID cannot loop on it. Cancellation leaves it for the replay.
                val data = runCatching { JSONObject(event.data).let { json -> json.keys().asSequence().associateWith { json.getString(it) } } }.getOrNull()
                if (data != null) {
                    try {
                        PushProcessor.process(applicationContext, data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SseService", "push processing failed for server ${server.id}, event ${event.id}", e)
                    }
                }
                event.id?.let { Prefs.setLastEventId(server.id, it) }
            }
            200
        }
    }

    companion object {
        const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 31

        /** Starts the connection if the user has it on; a refused background start is retried by the next app launch. */
        fun start(context: Context) {
            if (Prefs.sse) runCatching { ContextCompat.startForegroundService(context, Intent(context, SseService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SseService::class.java))
        }
    }
}

/** Minimal text/event-stream parser: `id:` and `data:` fields, comments ignored, a blank line ends an event. */
class SseParser {
    class Event(val id: String?, val data: String)

    private var id: String? = null
    private val data = StringBuilder()

    /** Feeds one line; returns the event a blank line completes. */
    fun line(line: String): Event? {
        if (line.isEmpty()) {
            val event = if (data.isEmpty()) null else Event(id, data.toString())
            data.setLength(0)
            return event
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "id" -> id = value
            "data" -> data.append(if (data.isEmpty()) value else "\n$value")
        }
        return null
    }
}

/** Restarts the SSE connection after a reboot (HyperOS only delivers this when autostart is allowed). */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SseService.start(context)
    }
}
