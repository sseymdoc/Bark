package me.fin.bark.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import me.fin.bark.R
import me.fin.bark.copyText
import me.fin.bark.data.ExampleType
import me.fin.bark.data.Prefs
import me.fin.bark.data.Server
import me.fin.bark.data.ServerApi
import me.fin.bark.hyperos.HyperOs
import me.fin.bark.toast

@Composable
fun HomeScreen(nav: Nav) {
    val context = LocalContext.current
    val servers by Prefs.servers.collectAsState()
    val currentId by Prefs.currentServerId.collectAsState()
    val server = servers.firstOrNull { it.id == currentId } ?: servers.first()
    var adding by remember { mutableStateOf(false) }
    // Bumped on every resume so permission state is re-read after returning from system settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }

    Page(server.displayName, actions = {
        BarButton(rememberVectorPainter(Icons.Default.Add), stringResource(R.string.AddServer), { adding = true })
    }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val notificationsOn = remember(resumes) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            PermissionCard(notificationsOn)
            TokenErrorCard()
            if (HyperOs.isXiaomi) HyperOsCard(resumes)
            ExampleCard(server, notificationsOn, onQr = { nav.push("qr") })
            SettingsCard(nav)
            DocsCard()
        }
    }
    if (adding) AddServerDialog { adding = false }
}

/** iOS HomePermissionCard: tinted 44dp bell circle, title and detail, "Enable" button while off. */
@Composable
private fun PermissionCard(enabled: Boolean) {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) HyperOs.openNotificationSettings(context)
    }
    BarkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (enabled) bk.systemGreen.copy(alpha = 0.12f) else bk.accentOrange)
                    .border(Dp.Hairline, bk.legacyBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sym_notifications_active_fill), null, tint = if (enabled) bk.systemGreen else bk.systemOrange, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(if (enabled) R.string.notificationPermissionOn else R.string.notificationPermissionOff), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = bk.label)
                Text(stringResource(if (enabled) R.string.notificationPermissionOnDetail else R.string.notificationPermissionOffDetail), fontSize = 13.sp, color = bk.secondaryLabel)
            }
            if (!enabled) {
                PillButton(stringResource(R.string.goToSettings)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else HyperOs.openNotificationSettings(context)
                }
            }
        }
    }
}

/** iOS HomeGlassButton, flat variant: 36dp pill, 15sp title. */
@Composable
private fun PillButton(title: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Text(
        title, fontSize = 15.sp, color = bk.label, maxLines = 1,
        modifier = Modifier.height(36.dp).graphicsLayer { alpha = if (pressed) 0.78f else 1f }
            .clip(shape).background(bk.legacyButton).border(Dp.Hairline, bk.legacyBorder, shape)
            .clickable(source, indication = null, onClick = onClick).padding(horizontal = 14.dp).wrapContentHeight(),
    )
}

@Composable
private fun TokenErrorCard() {
    val error by ServerApi.tokenError.collectAsState()
    val reason = error ?: return
    val res = LocalResources.current
    BarkCard {
        // With the SSE fallback on, a missing FCM is expected rather than an error.
        if (Prefs.sse) Text(res.getString(R.string.sse_fcm_fallback), fontSize = 15.sp, color = bk.secondaryLabel)
        else Text(
            if (reason == "firebase") res.getString(R.string.fcm_not_configured) else res.getString(R.string.fcm_unavailable, reason),
            fontSize = 15.sp, color = bk.systemRed,
        )
    }
}

/** Android-only: HyperOS settings that keep FCM delivery alive, styled like the other Home cards. */
@Composable
private fun HyperOsCard(resumes: Int) {
    val context = LocalContext.current
    val gms = remember(resumes) { HyperOs.googleServicesEnabled(context) }
    val dnd = remember(resumes) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
    BarkCard(spacing = 4.dp) {
        Text(stringResource(R.string.hyperos_title) + HyperOs.version.let { if (it.isEmpty()) "" else " · $it" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = bk.label)
        Text(stringResource(R.string.hyperos_desc), fontSize = 13.sp, color = bk.secondaryLabel)
        Text(stringResource(if (gms) R.string.hyperos_gms_on else R.string.hyperos_gms_off), fontSize = 13.sp, color = if (gms) bk.systemGreen else bk.systemRed)
        val rows = buildList {
            add(R.string.hyperos_autostart to { HyperOs.openAutostart(context) })
            add(R.string.hyperos_battery to { HyperOs.openBattery(context) })
            add(R.string.hyperos_notification to { HyperOs.openNotificationSettings(context) })
            if (!dnd) add(R.string.hyperos_dnd to { HyperOs.openDndAccess(context) })
        }
        rows.forEachIndexed { i, (label, open) ->
            if (i > 0) HorizontalDivider(thickness = Dp.Hairline, color = bk.divider)
            PressRow(open) {
                Text(stringResource(label), fontSize = 17.sp, color = bk.label, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_sym_chevron_right), null, tint = bk.tertiaryLabel, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 44dp row that dims to 0.64 while pressed (iOS HomeSettingRow). */
@Composable
private fun PressRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).graphicsLayer { alpha = if (pressed) 0.64f else 1f }.clickable(source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** iOS HomeExampleCard: code panel with GET/POST/JSON switch and highlighted curl, then Copy and Send Test. */
@Composable
private fun ExampleCard(server: Server, notificationsOn: Boolean, onQr: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(ExampleType.GET) }
    var menu by remember { mutableStateOf(false) }
    val text = ServerApi.exampleText(server, type)
    val colors = bk
    BarkCard {
        Text(stringResource(R.string.usageExamples), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = bk.label)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bk.codePanel).padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("bash", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = bk.secondaryLabel, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ExampleType.entries.forEach { t ->
                        val color by animateColorAsState(if (type == t) bk.label else bk.tertiaryLabel, tween(180))
                        Text(
                            t.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
                            modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { type = t }.padding(horizontal = 2.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Text(
                remember(text, colors) { highlightCurl(text, colors) },
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bk.label,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SplitButton(stringResource(R.string.Copy2), onClick = { context.copyAndToast(text) }, onMenu = { menu = true }, modifier = Modifier.weight(1f)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val hasKey = server.key.isNotEmpty()
                    DropdownMenuItem({ Text(stringResource(R.string.copyAddressAndKey)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_link), null, Modifier.size(20.dp)) }, onClick = { menu = false; context.copyAndToast(server.addressAndKey) })
                    DropdownMenuItem({ Text(stringResource(R.string.copyKey)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_key), null, Modifier.size(20.dp)) }, onClick = { menu = false; context.copyAndToast(server.key) })
                    DropdownMenuItem({ Text(stringResource(R.string.showQRCode)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_qr_code), null, Modifier.size(20.dp)) }, onClick = { menu = false; onQr() })
                }
            }
            FlatButton(stringResource(R.string.sendTest), painterResource(R.drawable.ic_sym_send_fill), modifier = Modifier.weight(1f), onClick = {
                when {
                    !notificationsOn -> context.toast(res.getString(R.string.notificationPermissionAlertMessage))
                    server.key.isEmpty() -> context.toast(res.getString(R.string.deviceNotRegistered))
                    else -> scope.launch {
                        ServerApi.sendTest(server, type)
                            .onSuccess { context.toast(res.getString(R.string.testPushSent)) }
                            .onFailure { context.toast(res.getString(R.string.testPushFailed, it.message.orEmpty())) }
                    }
                }
            })
        }
    }
}

/** iOS CodeHighlighter: quoted strings, a leading `curl`, and flags outside quotes. */
private fun highlightCurl(text: String, c: BkColors) = buildAnnotatedString {
    append(text)
    val quoted = Regex("\"[^\"]*\"|'[^']*'").findAll(text).map { it.range }.toList()
    quoted.forEach { addStyle(SpanStyle(color = c.codeString), it.first, it.last + 1) }
    Regex("^curl").find(text)?.let { addStyle(SpanStyle(color = c.codeCommand), it.range.first, it.range.last + 1) }
    Regex("(?<=\\s)-{1,2}[A-Za-z][A-Za-z-]*").findAll(text).map { it.range }
        .filter { flag -> quoted.none { it.first <= flag.last && flag.first <= it.last } }
        .forEach { addStyle(SpanStyle(color = c.codeFlag), it.first, it.last + 1) }
}

/** iOS HomeSettingsCard: three 44dp rows with tinted 32dp icon tiles, hairline dividers from 44dp. */
@Composable
private fun SettingsCard(nav: Nav) {
    BarkCard(spacing = 4.dp) {
        listOf(
            Triple(R.string.serverList, R.drawable.ic_sym_dns_fill, "servers"),
            Triple(R.string.encryptionSettings, R.drawable.ic_sym_key_fill, "crypto"),
            Triple(R.string.notificationSound, R.drawable.ic_sym_volume_up_fill, "sounds"),
        ).forEachIndexed { i, (label, icon, route) ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 44.dp), thickness = Dp.Hairline, color = bk.divider)
            val (tint, tile) = when (i) {
                0 -> bk.systemBlue to bk.accentBlue
                1 -> bk.systemTeal to bk.accentTeal
                else -> bk.systemOrange to bk.accentOrange
            }
            PressRow({ nav.push(route) }) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(tile).border(Dp.Hairline, bk.legacyBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(18.dp)) }
                Text(stringResource(label), fontSize = 17.sp, color = bk.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Icon(painterResource(R.drawable.ic_sym_chevron_right), null, tint = bk.tertiaryLabel, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** iOS HomeDocumentsCard. */
@Composable
private fun DocsCard() {
    val context = LocalContext.current
    val res = LocalResources.current
    BarkCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.documentsAndExamples), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = bk.label)
            Text(stringResource(R.string.documentsAndExamplesDetail), fontSize = 15.sp, color = bk.secondaryLabel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlatButton(stringResource(R.string.pushParameters), painterResource(R.drawable.ic_sym_menu_book), { context.openUrl(res.getString(R.string.paramsUrl)) }, Modifier.weight(1f))
            FlatButton(stringResource(R.string.faq), painterResource(R.drawable.ic_sym_help), { context.openUrl(res.getString(R.string.faqUrl)) }, Modifier.weight(1f))
        }
    }
}

fun Context.copyAndToast(text: String) {
    copyText(text)
    toast(getString(R.string.Copy))
}
