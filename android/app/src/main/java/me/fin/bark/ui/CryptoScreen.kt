package me.fin.bark.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.fin.bark.R
import me.fin.bark.data.Prefs
import me.fin.bark.push.Crypto
import me.fin.bark.push.CryptoFields
import me.fin.bark.toast

/** iOS CryptoSettingController. The IV is not configured here: it travels with each push in `iv`. */
@Composable
fun CryptoScreen(nav: Nav) {
    val context = LocalContext.current
    val res = LocalResources.current
    var fields by remember { mutableStateOf(Prefs.crypto ?: CryptoFields()) }
    val keyLength = Crypto.keyLength[fields.algorithm] ?: 16

    /** Validates and saves; keeps an IV saved by older versions only if it still fits the mode. */
    fun save(): Boolean {
        if (fields.key?.length != keyLength) {
            context.toast(res.getString(R.string.enterKey, keyLength))
            return false
        }
        val oldIv = Prefs.crypto?.iv
        Prefs.crypto = fields.copy(iv = oldIv?.takeIf { it.length == Crypto.ivLength[fields.mode] })
        return true
    }

    Page(
        stringResource(R.string.encryptionSettings), onBack = nav.pop, background = bk.white,
        actions = {
            Text(
                stringResource(R.string.done), fontSize = 14.sp, color = bk.lightBlueDarken3,
                modifier = Modifier.clickable {
                    if (save()) {
                        context.toast(res.getString(R.string.setSuccessfully))
                        nav.pop()
                    }
                }.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        },
    ) { padding ->
        // iOS CryptoSettingController: 14sp labels at 24dp, 45dp fields inset 20dp, 5dp below each label, 20dp between fields.
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp)) {
            FieldLabel(stringResource(R.string.algorithm))
            DropBox(fields.algorithm, Crypto.algorithms) { fields = fields.copy(algorithm = it) }
            FieldLabel(stringResource(R.string.mode), top = 20.dp)
            DropBox(fields.mode, Crypto.modes) { fields = fields.copy(mode = it, padding = Crypto.paddingFor(it)) }
            FieldLabel(stringResource(R.string.padding), top = 20.dp)
            DropBox(fields.padding, listOf(fields.padding)) {}
            FieldLabel("Key", top = 20.dp)
            BorderTextField(fields.key.orEmpty(), stringResource(R.string.enterKey, keyLength)) { fields = fields.copy(key = it) }
            Text(
                stringResource(R.string.copyExample), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 25.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brush.horizontalGradient(listOf(Color(36, 51, 236), Color(70, 44, 233))))
                    .clickable {
                        if (save()) {
                            val server = Prefs.currentServer
                            context.copyAndToast(exampleScript(context, fields, server.key, server.cleanAddress))
                        }
                    }
                    .wrapContentHeight(),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, top: Dp = 0.dp) {
    Text(text, fontSize = 14.sp, color = bk.greyDarken4, modifier = Modifier.padding(start = 4.dp, top = top, bottom = 5.dp))
}

/** iOS DropBoxView/BorderTextField frame: 45dp, radius 8, 1dp border that turns blue.darken5 with a soft glow (0.3s) while active. */
@Composable
private fun FieldFrame(active: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val border by animateColorAsState(if (active) bk.blueDarken5 else bk.greyLighten2, tween(300))
    val glow by animateDpAsState(if (active) 3.dp else 0.dp, tween(300))
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.fillMaxWidth().height(45.dp)
            .shadow(glow, shape, ambientColor = bk.blueDarken5, spotColor = bk.blueDarken5)
            .background(bk.white, shape).border(1.dp, border, shape),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun DropBox(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FieldFrame(open, Modifier.clickable { open = true }) {
        Text(value, fontSize = 14.sp, color = bk.greyDarken3, modifier = Modifier.padding(start = 16.dp))
        Icon(painterResource(R.drawable.ic_arrow_down), null, tint = bk.greyLighten2, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(24.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem({ Text(option, fontSize = 15.sp) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}

@Composable
private fun BorderTextField(value: String, placeholder: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FieldFrame(focused) {
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = bk.greyDarken3),
            cursorBrush = SolidColor(bk.blueDarken5),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { focused = it.isFocused },
            decorationBox = { field ->
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = bk.greyDarken1)
                field()
            },
        )
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
