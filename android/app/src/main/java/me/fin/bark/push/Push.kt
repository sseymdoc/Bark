package me.fin.bark.push

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

/** Notification levels; the name is also the notification channel id. */
enum class Level { active, timeSensitive, passive, critical }

/**
 * One push as bark-server sends it over FCM: a flat map of string parameters.
 * Keys are lowercased and values compared as strings ("1" = on), exactly like the iOS extension.
 */
class Push(val data: Map<String, String>) {
    /** Empty values count as missing. */
    operator fun get(key: String): String? = data[key]?.takeIf { it.isNotEmpty() }

    val id get() = this["id"]
    val title get() = this["title"]
    val subtitle get() = this["subtitle"]
    val body get() = this["body"]
    val markdown get() = this["markdown"]
    val group get() = this["group"]
    val url get() = this["url"]
    val image get() = this["image"]
    val icon get() = this["icon"]
    val copy get() = this["copy"]
    val action get() = this["action"]
    val isDelete get() = data["delete"] == "1"
    val isCall get() = data["call"] == "1"
    val autoCopy get() = data["autocopy"] == "1" || data["automaticallycopy"] == "1"
    val badge get() = this["badge"]?.toIntOrNull()

    /** The server appends ".caf" for iOS; Android sounds are looked up by bare name. */
    val sound get() = this["sound"]?.removeSuffix(".caf")

    val level: Level
        get() = when (this["level"]?.lowercase()) {
            "timesensitive" -> Level.timeSensitive
            "passive" -> Level.passive
            "critical" -> Level.critical
            else -> Level.active
        }

    /** Critical alert volume 0..1 from the 0..10 parameter, default 0.5. */
    val volume: Float get() = this["volume"]?.toFloatOrNull()?.let { (it / 10f).coerceIn(0f, 1f) } ?: 0.5f

    private val ttl get() = this["ttl"]?.toDoubleOrNull()

    /** `ttl <= 0` is never archived; otherwise `isarchive` decides, and only "1" archives. */
    fun shouldArchive(default: Boolean): Boolean {
        ttl?.let { if (it <= 0) return false }
        val value = data["isarchive"] ?: return default
        return value == "1"
    }

    val ttlMillis: Long? get() = ttl?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

    fun expireDate(now: Long): Long? = ttlMillis?.let { now + it }

    companion object {
        fun of(raw: Map<String, String>) = Push(raw.mapKeys { it.key.lowercase() })

        /**
         * Decrypts `ciphertext` if present. The decrypted JSON object replaces all other parameters
         * (only the outer `iv` is read first), as iOS CiphertextProcessor does.
         */
        fun decrypt(push: Push, fields: CryptoFields?): Push {
            val ciphertext = push["ciphertext"] ?: return push
            if (fields?.key.isNullOrEmpty()) throw CryptoException("No encryption key set")
            val text = Crypto.decrypt(fields!!, ciphertext, push["iv"] ?: fields.iv)
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw CryptoException("JSON parsing failed")
            }
            return Push(json.keys().asSequence().filterNot { json.isNull(it) }.associate { it.lowercase() to stringify(json.get(it)) })
        }

        /** NSNumber.stringValue semantics: true -> "1", 10.0 -> "10". */
        fun stringify(value: Any): String = when (value) {
            is Boolean -> if (value) "1" else "0"
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            else -> value.toString()
        }
    }
}
