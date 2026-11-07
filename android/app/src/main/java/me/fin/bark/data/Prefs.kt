package me.fin.bark.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.fin.bark.BuildConfig
import me.fin.bark.push.CryptoFields
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val address: String,
    val key: String = "",
    val name: String? = null,
) {
    val host: String get() = runCatching { URI(address).host }.getOrNull() ?: ""
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: host
    val cleanAddress: String get() = address.trim('/')
    val addressAndKey: String get() = if (key.isEmpty()) cleanAddress else "$cleanAddress/$key"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("address", address).put("key", key).put("name", name ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            address = o.getString("address"),
            key = o.optString("key"),
            name = if (o.isNull("name")) null else o.optString("name"),
        )
    }
}

/** App settings (iOS BarkSettings / ServerManager / CryptoSettingManager / GroupMuteSettingManager). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("bark", Context.MODE_PRIVATE)
        _servers.value = loadServers()
        _currentServerId.value = sp.getString("currentServerId", null)
    }

    // region servers
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers = _servers.asStateFlow()
    private val _currentServerId = MutableStateFlow<String?>(null)
    val currentServerId = _currentServerId.asStateFlow()

    val currentServer: Server
        get() = servers.value.firstOrNull { it.id == currentServerId.value } ?: servers.value.first()

    private fun loadServers(): List<Server> {
        val list = sp.getString("servers", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { Server.fromJson(a.getJSONObject(it)) } } }.getOrNull()
        }
        return list?.takeIf { it.isNotEmpty() } ?: listOf(Server(address = BuildConfig.DEFAULT_SERVER))
    }

    fun saveServers(list: List<Server>) {
        // Like iOS, there is always at least one server.
        val value = list.ifEmpty { listOf(Server(address = BuildConfig.DEFAULT_SERVER)) }
        _servers.value = value
        sp.edit { putString("servers", JSONArray(value.map { it.toJson() }).toString()) }
    }

    fun updateServer(server: Server) = saveServers(servers.value.map { if (it.id == server.id) server else it })

    fun setCurrentServer(id: String) {
        _currentServerId.value = id
        sp.edit { putString("currentServerId", id) }
    }
    // endregion

    var fcmToken: String?
        get() = sp.getString("fcmToken", null)
        set(value) = sp.edit { putString("fcmToken", value) }

    /** Registered when FCM is unavailable; bark-server sends pushes for an `sse:` token over SSE only. */
    private val sseToken: String
        @Synchronized get() = sp.getString("sseToken", null)
            ?: ("sse:" + UUID.randomUUID()).also { token -> sp.edit { putString("sseToken", token) } }

    /** The token the app registers with and authenticates its SSE stream with. */
    val deviceToken: String get() = fcmToken ?: sseToken

    /** Keep an SSE connection as a fallback for FCM (on by default). */
    var sse: Boolean
        get() = sp.getBoolean("sse", true)
        set(value) = sp.edit { putBoolean("sse", value) }

    fun lastEventId(serverId: String): String? = sp.getString("sseLastId:$serverId", null)

    fun setLastEventId(serverId: String, id: String) = sp.edit { putString("sseLastId:$serverId", id) }

    /**
     * Records a push's `_msgid` among the last 200 delivered; false when it was already delivered (FCM and SSE
     * both carry every Android push). Persisted, so a restarted process still drops the late copy.
     */
    @Synchronized
    fun markDelivered(msgId: String): Boolean {
        val ids = sp.getString("delivered", "")!!.split(',').filter { it.isNotEmpty() }
        if (msgId in ids) return false
        sp.edit { putString("delivered", (ids.takeLast(199) + msgId).joinToString(",")) }
        return true
    }

    /** Archive pushes that don't carry `isArchive`. */
    var isArchive: Boolean
        get() = sp.getBoolean("isArchive", true)
        set(value) = sp.edit { putBoolean("isArchive", value) }

    var crypto: CryptoFields?
        get() = sp.getString("crypto", null)?.let { runCatching { CryptoFields.fromJson(JSONObject(it)) }.getOrNull() }
        set(value) = sp.edit { putString("crypto", value?.toJson()?.toString()) }

    // region group mute: group -> mute-until epoch ms
    private fun mutes(): JSONObject = runCatching { JSONObject(sp.getString("groupMutes", "{}")!!) }.getOrDefault(JSONObject())

    fun muteGroup(group: String, until: Long) {
        val now = System.currentTimeMillis()
        val m = mutes()
        m.keys().asSequence().toList().forEach { if (m.optLong(it) <= now) m.remove(it) }
        m.put(group, until)
        sp.edit { putString("groupMutes", m.toString()) }
    }

    fun isMuted(group: String): Boolean = mutes().optLong(group) > System.currentTimeMillis()
    // endregion

    var groupMode: Boolean
        get() = sp.getBoolean("groupMode", false)
        set(value) = sp.edit { putBoolean("groupMode", value) }

    var exactDate: Boolean
        get() = sp.getBoolean("exactDate", false)
        set(value) = sp.edit { putBoolean("exactDate", value) }

    var lastTab: Int
        get() = sp.getInt("lastTab", 0)
        set(value) = sp.edit { putInt("lastTab", value) }
}
