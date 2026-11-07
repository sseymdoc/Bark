package me.fin.bark.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fin.bark.R
import me.fin.bark.data.Prefs
import me.fin.bark.push.Crypto
import me.fin.bark.push.CryptoFields
import me.fin.bark.toast

/** Encryption settings (iOS CryptoSettingController). The IV is not configured here: it travels with each push in `iv`. */
@Composable
fun CryptoScreen(nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    var fields by remember { mutableStateOf(Prefs.crypto ?: CryptoFields()) }
    val keyLength = Crypto.keyLength[fields.algorithm] ?: 16

    /** Validates and saves; keeps an IV saved by older versions only if it still fits the mode. Lengths are UTF-8 bytes, as Crypto checks them. */
    fun save(): Boolean {
        if (fields.key?.toByteArray()?.size != keyLength) {
            context.toast(res.getString(R.string.enterKey, keyLength))
            return false
        }
        val oldIv = Prefs.crypto?.iv
        Prefs.crypto = fields.copy(iv = oldIv?.takeIf { it.toByteArray().size == Crypto.ivLength[fields.mode] })
        return true
    }

    Page(
        stringResource(R.string.encryptionSettings), onBack = nav.pop,
        actions = {
            TextButton(onClick = {
                if (save()) {
                    context.toast(res.getString(R.string.setSuccessfully))
                    nav.pop()
                }
            }) { Text(stringResource(R.string.done)) }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DropBox(stringResource(R.string.algorithm), fields.algorithm, Crypto.algorithms) { fields = fields.copy(algorithm = it) }
            DropBox(stringResource(R.string.mode), fields.mode, Crypto.modes) { fields = fields.copy(mode = it, padding = Crypto.paddingFor(it)) }
            DropBox(stringResource(R.string.padding), fields.padding, listOf(fields.padding)) {}
            OutlinedTextField(
                fields.key.orEmpty(), { fields = fields.copy(key = it) },
                label = { Text("Key") },
                placeholder = { Text(stringResource(R.string.enterKey, keyLength)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (save()) {
                        val server = Prefs.currentServer
                        context.copyAndToast(exampleScript(context, fields, server.key, server.cleanAddress))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.copyExample)) }
        }
    }
}

/** Read-only outlined field that opens a menu of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropBox(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value, {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem({ Text(option) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}

private fun randomIv(mode: String): String {
    val length = Crypto.ivLength[mode] ?: return ""
    return (1..length).map { "0123456789abcdef".random() }.joinToString("")
}

/** Same scripts as iOS CryptoSettingViewModel: Node.js for GCM, bash + openssl otherwise. */
fun exampleScript(context: Context, fields: CryptoFields, deviceKey: String, serverAddress: String): String {
    val key = fields.key.orEmpty()
    val iv = randomIv(fields.mode)
    val bits = fields.algorithm.takeLast(3)
    val s = { id: Int -> context.getString(id) }
    val keyComment = context.getString(R.string.keyComment, bits.toInt() / 8)
    return if (fields.mode == "GCM") {
        val preview = runCatching { Crypto.encrypt(fields, "{\"body\":\"test\",\"sound\":\"birdsong\"}", iv) }.getOrDefault("")
        """
        |// Documentation: ${s(R.string.encryptionUrl)}
        |
        |const crypto = require('crypto');
        |
        |
        |// bark key
        |const deviceKey = '$deviceKey';
        |// push payload
        |const json = JSON.stringify({ body: "test", sound: "birdsong" });
        |
        |// $keyComment
        |const key = '$key';
        |// ${s(R.string.ivComment)}
        |const iv = '$iv';
        |
        |// AES-$bits-GCM
        |const cipher = crypto.createCipheriv('aes-$bits-gcm', Buffer.from(key, 'utf8'), Buffer.from(iv, 'utf8'));
        |const encrypted = Buffer.concat([
        |  cipher.update(json, 'utf8'),
        |  cipher.final()
        |]);
        |const tag = cipher.getAuthTag()
        |
        |const combined = Buffer.concat([encrypted, tag])
        |let ciphertext = combined.toString('base64')
        |
        |// ${s(R.string.consoleComment)} "$preview"
        |console.log(ciphertext);
        |
        |// ${s(R.string.ciphertextComment)}
        |const pushUrl = `$serverAddress/${'$'}{deviceKey}?ciphertext=${'$'}{encodeURIComponent(ciphertext)}&iv=${'$'}{encodeURIComponent(iv)}`;
        |
        |(async () => {
        |  try {
        |    const response = await fetch(pushUrl);
        |    console.log(await response.text());
        |  } catch (err) {
        |    console.error(err);
        |  }
        |})();
        """.trimMargin()
    } else {
        val preview = runCatching { Crypto.encrypt(fields, "{\"body\": \"test\", \"sound\": \"birdsong\"}", iv.ifEmpty { null }) }.getOrDefault("")
        val ivArg = if (iv.isNotEmpty()) "-iv \$iv " else ""
        val ivParam = if (iv.isEmpty()) "" else " --data-urlencode \"iv=$iv\""
        """
        |#!/usr/bin/env bash
        |
        |# Documentation: ${s(R.string.encryptionUrl)}
        |
        |set -e
        |
        |# bark key
        |deviceKey='$deviceKey'
        |# push payload
        |json='{"body": "test", "sound": "birdsong"}'
        |
        |# $keyComment
        |key='$key'
        |# ${s(R.string.ivComment)}
        |iv='$iv'
        |
        |# ${s(R.string.opensslEncodingComment)}
        |key=${'$'}(printf ${'$'}key | xxd -ps -c 200)
        |iv=${'$'}(printf ${'$'}iv | xxd -ps -c 200)
        |
        |# ${s(R.string.base64Notice)}
        |ciphertext=${'$'}(echo -n ${'$'}json | openssl enc -aes-$bits-${fields.mode.lowercase()} -K ${'$'}key $ivArg| base64)
        |
        |# ${s(R.string.consoleComment)} "$preview"
        |echo ${'$'}ciphertext
        |
        |# ${s(R.string.ciphertextComment)}
        |curl --data-urlencode "ciphertext=${'$'}ciphertext"$ivParam $serverAddress/${'$'}deviceKey
        """.trimMargin()
    }
}
