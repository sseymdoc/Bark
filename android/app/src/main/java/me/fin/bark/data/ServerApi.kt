package me.fin.bark.data

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class ExampleType { GET, POST, JSON }

/** bark-server client (iOS BarkApi + ServerManager.register). */
object ServerApi {
    /** Token the app has right now, or the reason it has none. */
    private val _tokenError = MutableStateFlow<String?>(null)
    val tokenError = _tokenError.asStateFlow()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal fun request(
        url: String, method: String = "GET", contentType: String? = null, body: String? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true, maxResponseBytes: Int? = null,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = followRedirects
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { input ->
                if (maxResponseBytes == null) input.bufferedReader().readText() else {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(output.size() + n <= maxResponseBytes) { "response_too_large" }
                        output.write(buffer, 0, n)
                    }
                    output.toString("UTF-8")
                }
            } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** bark-server answers `{"code":200,...}`; `code` may be a string or a number. */
    private fun parse(text: String): JSONObject {
        val json = JSONObject(text)
        if (json.optString("code") != "200") error(json.optString("message").ifEmpty { text })
        return json
    }

    suspend fun ping(address: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = request("${address.trim('/')}/ping")
            parse(text) // throws unless the body says code 200
            code in 200..209
        }.getOrDefault(false)
    }

    /** Returns the key the server assigned. An empty [key] asks the server for a new one. */
    suspend fun register(address: String, token: String, key: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var url = "${address.trim('/')}/register?devicetoken=${enc(token)}&device_type=android"
            if (key.isNotEmpty()) url += "&key=${enc(key)}"
            parse(request(url).second).getJSONObject("data").getString("key")
        }
    }

    /** Fetches the FCM token; null when Firebase is missing or Google services are unavailable. */
    suspend fun fcmToken(): String? = runCatching {
        suspendCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _tokenError.value = null
                    cont.resume(task.result)
                } else {
                    _tokenError.value = task.exception?.message ?: "unknown"
                    cont.resume(null)
                }
            }
        }
    }.getOrElse {
        // FirebaseApp is not initialized when google-services.json was not present at build time.
        _tokenError.value = "firebase"
        null
    }?.also { Prefs.fcmToken = it }

    /** Re-registers every server with [token], keeping the keys they return (iOS syncAllServers). */
    suspend fun syncAllServers(token: String) = coroutineScope {
        val updated = Prefs.servers.value.map { s ->
            async { register(s.address, token, s.key).getOrNull()?.let { s.copy(key = it) } ?: s }
        }.awaitAll()
        // Servers may have changed while we were waiting; only update keys of the ones still there.
        val byId = updated.associateBy { it.id }
        Prefs.saveServers(Prefs.servers.value.map { byId[it.id]?.let { u -> it.copy(key = u.key) } ?: it })
    }

    private object Example {
        const val TITLE = "title"
        const val BODY = "body"
        const val GROUP = "example"
        const val TTL = 600
    }

    suspend fun sendTest(server: Server, type: ExampleType): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = when (type) {
                ExampleType.GET -> request("${server.cleanAddress}/${server.key}/${enc(Example.TITLE)}/${enc(Example.BODY)}?group=${Example.GROUP}&ttl=${Example.TTL}")
                ExampleType.POST -> request(
                    "${server.cleanAddress}/${server.key}", "POST", "application/x-www-form-urlencoded",
                    "title=${enc(Example.TITLE)}&body=${enc(Example.BODY)}&group=${enc(Example.GROUP)}&ttl=${Example.TTL}",
                )
                ExampleType.JSON -> request(
                    "${server.cleanAddress}/push", "POST", "application/json",
                    JSONObject().put("device_key", server.key).put("title", Example.TITLE).put("body", Example.BODY)
                        .put("group", Example.GROUP).put("ttl", Example.TTL).toString(),
                )
            }
            if (code !in 200..299) error("$code $text")
            parse(text)
            Unit
        }
    }

    fun exampleText(server: Server, type: ExampleType): String {
        val address = server.cleanAddress
        val key = server.key.ifEmpty { "YOUR_KEY" }
        return when (type) {
            ExampleType.GET -> "curl -X GET '$address/$key/${Example.TITLE}/${Example.BODY}?group=${Example.GROUP}&ttl=${Example.TTL}'"
            ExampleType.POST -> """
                |curl -X POST $address/$key \
                |  -d "title=${Example.TITLE}" \
                |  -d "body=${Example.BODY}" \
                |  -d "group=${Example.GROUP}" \
                |  -d "ttl=${Example.TTL}"
            """.trimMargin()
            ExampleType.JSON -> """
                |curl -X POST $address/push \
                |  -H "Content-Type: application/json" \
                |  -d '{
                |    "device_key": "$key",
                |    "title": "${Example.TITLE}",
                |    "body": "${Example.BODY}",
                |    "group": "${Example.GROUP}",
                |    "ttl": ${Example.TTL}
                |  }'
            """.trimMargin()
        }
    }
}
