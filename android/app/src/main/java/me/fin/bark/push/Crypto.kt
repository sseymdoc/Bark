package me.fin.bark.push

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same fields and JSON keys as iOS `CryptoSettingFields`. Key and IV are raw UTF-8 strings, not hex. */
data class CryptoFields(
    val algorithm: String = "AES128",
    val mode: String = "CBC",
    val padding: String = "pkcs7",
    val key: String? = null,
    val iv: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("algorithm", algorithm).put("mode", mode).put("padding", padding)
        .put("key", key ?: JSONObject.NULL).put("iv", iv ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = CryptoFields(
            algorithm = o.optString("algorithm", "AES128"),
            mode = o.optString("mode", "CBC"),
            padding = o.optString("padding", "pkcs7"),
            key = if (o.isNull("key")) null else o.optString("key"),
            iv = if (o.isNull("iv")) null else o.optString("iv"),
        )
    }
}

class CryptoException(message: String) : Exception(message)

/** AES as in iOS `AESCryptoModel` (CryptoSwift): CBC/ECB with PKCS7 or none, GCM with the 16-byte tag appended. */
object Crypto {
    val algorithms = listOf("AES128", "AES192", "AES256")
    val modes = listOf("CBC", "ECB", "GCM")
    val keyLength = mapOf("AES128" to 16, "AES192" to 24, "AES256" to 32)
    val ivLength = mapOf("CBC" to 16, "GCM" to 12)

    fun paddingFor(mode: String) = if (mode == "GCM") "noPadding" else "pkcs7"

    private fun cipher(fields: CryptoFields, iv: String?, opmode: Int): Cipher {
        val keyLen = keyLength[fields.algorithm] ?: throw CryptoException("Invalid algorithm")
        val key = fields.key?.toByteArray(Charsets.UTF_8)
        if (key == null || key.size != keyLen) throw CryptoException("Key must be $keyLen bytes")
        if (fields.mode !in modes) throw CryptoException("Invalid Mode")
        val padding = when (fields.padding) {
            "pkcs7" -> "PKCS5Padding" // identical to PKCS7 for 16-byte blocks
            "noPadding" -> "NoPadding"
            else -> throw CryptoException("Invalid Padding")
        }
        val ivBytes = iv?.toByteArray(Charsets.UTF_8)
        ivLength[fields.mode]?.let { len ->
            if (ivBytes == null || ivBytes.size != len) throw CryptoException("IV must be $len bytes")
        }
        val c = Cipher.getInstance(if (fields.mode == "GCM") "AES/GCM/NoPadding" else "AES/${fields.mode}/$padding")
        val spec = when (fields.mode) {
            "GCM" -> GCMParameterSpec(128, ivBytes)
            "CBC" -> IvParameterSpec(ivBytes)
            else -> null
        }
        c.init(opmode, SecretKeySpec(key, "AES"), spec)
        return c
    }

    /** [ciphertext] is Base64; characters outside the alphabet are ignored, like iOS `.ignoreUnknownCharacters`. */
    fun decrypt(fields: CryptoFields, ciphertext: String, iv: String?): String {
        val data = try {
            Base64.getMimeDecoder().decode(ciphertext)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("Invalid Base64")
        }
        val plain = try {
            cipher(fields, iv, Cipher.DECRYPT_MODE).doFinal(data)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException(e.message ?: e.javaClass.simpleName)
        }
        return String(plain, Charsets.UTF_8)
    }

    fun encrypt(fields: CryptoFields, plaintext: String, iv: String?): String =
        Base64.getEncoder().encodeToString(cipher(fields, iv, Cipher.ENCRYPT_MODE).doFinal(plaintext.toByteArray(Charsets.UTF_8)))
}
