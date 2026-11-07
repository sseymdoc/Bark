package me.fin.bark.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the shared monitor credential; ciphertext is excluded from Android backups. */
internal class MonitorSecretStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "monitor-secret.json"))
    val exists get() = file.baseFile.exists()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bark-monitor", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bark-monitor", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? {
        if (!exists) return null
        require(file.baseFile.length() <= 16384)
        val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val iv = Base64.decode(data.getString("iv"), Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(Base64.decode(data.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun write(secret: String?) {
        if (secret.isNullOrEmpty()) { file.delete(); return }
        require(secret.toByteArray().size <= 4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(secret.toByteArray()), Base64.NO_WRAP)).toString()
        val output = file.startWrite()
        try { output.write(data.toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        check(read() == secret) { "credential_storage_failed" }
    }
}
