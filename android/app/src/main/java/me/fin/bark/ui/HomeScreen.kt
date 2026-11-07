package me.fin.bark.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.AddServer)) }
    }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

/** Notification permission state: a tonal bell, title and detail, and an "Enable" button while off. */
@Composable
private fun PermissionCard(enabled: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) HyperOs.openNotificationSettings(context)
    }
    BarkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (enabled) colors.primaryContainer else colors.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sym_notifications_active_fill), null, tint = if (enabled) colors.onPrimaryContainer else colors.onErrorContainer, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(stringResource(if (enabled) R.string.notificationPermissionOn else R.string.notificationPermissionOff), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (enabled) R.string.notificationPermissionOnDetail else R.string.notificationPermissionOffDetail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (!enabled) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else HyperOs.openNotificationSettings(context)
                }) { Text(stringResource(R.string.goToSettings)) }
            }
        }
    }
}

@Composable
private fun TokenErrorCard() {
    val error by ServerApi.tokenError.collectAsState()
    val reason = error ?: return
    val res = LocalResources.current
    BarkCard {
        // With the SSE fallback on, a missing FCM is expected rather than an error.
        if (Prefs.sse) Text(res.getString(R.string.sse_fcm_fallback), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(
            if (reason == "firebase") res.getString(R.string.fcm_not_configured) else res.getString(R.string.fcm_unavailable, reason),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Android-only: HyperOS settings that keep FCM delivery alive. */
@Composable
private fun HyperOsCard(resumes: Int) {
    val context = LocalContext.current
    val gms = remember(resumes) { HyperOs.googleServicesEnabled(context) }
    val dnd = remember(resumes) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
    BarkCard(spacing = 0.dp, padding = PaddingValues(vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.hyperos_title) + HyperOs.version.let { if (it.isEmpty()) "" else " · $it" }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hyperos_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(if (gms) R.string.hyperos_gms_on else R.string.hyperos_gms_off), style = MaterialTheme.typography.bodyMedium,
                color = if (gms) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        CardRow(stringResource(R.string.hyperos_autostart), null) { HyperOs.openAutostart(context) }
        CardRow(stringResource(R.string.hyperos_battery), null) { HyperOs.openBattery(context) }
        CardRow(stringResource(R.string.hyperos_notification), null) { HyperOs.openNotificationSettings(context) }
        if (!dnd) CardRow(stringResource(R.string.hyperos_dnd), null) { HyperOs.openDndAccess(context) }
    }
}

/** A tappable list row inside a card, transparent so the card color shows through. */
@Composable
private fun CardRow(title: String, icon: Painter?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, null) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** GET/POST/JSON switch, the highlighted curl command, then Copy (with more copy options) and Send Test. */
@Composable
private fun ExampleCard(server: Server, notificationsOn: Boolean, onQr: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(ExampleType.GET) }
    var menu by remember { mutableStateOf(false) }
    val text = ServerApi.exampleText(server, type)
    val code = codeColors
    BarkCard {
        Text(stringResource(R.string.usageExamples), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ExampleType.entries.forEachIndexed { i, t ->
                SegmentedButton(selected = type == t, onClick = { type = t }, shape = SegmentedButtonDefaults.itemShape(i, ExampleType.entries.size)) { Text(t.name) }
            }
        }
        Text(
            remember(text, code) { highlightCurl(text, code) },
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { context.copyAndToast(text) }, modifier = Modifier.weight(1f)) {
                ButtonIcon(painterResource(R.drawable.ic_file_copy))
                Text(stringResource(R.string.Copy2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                FilledTonalIconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_sym_expand_more), stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val hasKey = server.key.isNotEmpty()
                    DropdownMenuItem({ Text(stringResource(R.string.copyAddressAndKey)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_link), null) }, onClick = { menu = false; context.copyAndToast(server.addressAndKey) })
                    DropdownMenuItem({ Text(stringResource(R.string.copyKey)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_key), null) }, onClick = { menu = false; context.copyAndToast(server.key) })
                    DropdownMenuItem({ Text(stringResource(R.string.showQRCode)) }, enabled = hasKey, leadingIcon = { Icon(painterResource(R.drawable.ic_sym_qr_code), null) }, onClick = { menu = false; onQr() })
                }
            }
            Button(modifier = Modifier.weight(1f), onClick = {
                when {
                    !notificationsOn -> context.toast(res.getString(R.string.notificationPermissionAlertMessage))
                    server.key.isEmpty() -> context.toast(res.getString(R.string.deviceNotRegistered))
                    else -> scope.launch {
                        ServerApi.sendTest(server, type)
                            .onSuccess { context.toast(res.getString(R.string.testPushSent)) }
                            .onFailure { context.toast(res.getString(R.string.testPushFailed, it.message.orEmpty())) }
                    }
                }
            }) {
                ButtonIcon(painterResource(R.drawable.ic_sym_send_fill))
                Text(stringResource(R.string.sendTest), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Same rules as iOS CodeHighlighter: quoted strings, a leading `curl`, and flags outside quotes. */
private fun highlightCurl(text: String, c: CodeColors) = buildAnnotatedString {
    append(text)
    val quoted = Regex("\"[^\"]*\"|'[^']*'").findAll(text).map { it.range }.toList()
    quoted.forEach { addStyle(SpanStyle(color = c.string), it.first, it.last + 1) }
    Regex("^curl").find(text)?.let { addStyle(SpanStyle(color = c.command), it.range.first, it.range.last + 1) }
    Regex("(?<=\\s)-{1,2}[A-Za-z][A-Za-z-]*").findAll(text).map { it.range }
        .filter { flag -> quoted.none { it.first <= flag.last && flag.first <= it.last } }
        .forEach { addStyle(SpanStyle(color = c.flag), it.first, it.last + 1) }
}

/** Entries to the server list, encryption and sound pages. */
@Composable
private fun SettingsCard(nav: Nav) {
    BarkCard(spacing = 0.dp, padding = PaddingValues(vertical = 8.dp)) {
        CardRow(stringResource(R.string.serverList), painterResource(R.drawable.ic_sym_dns_fill)) { nav.push("servers") }
        CardRow(stringResource(R.string.encryptionSettings), painterResource(R.drawable.ic_sym_key_fill)) { nav.push("crypto") }
        CardRow(stringResource(R.string.notificationSound), painterResource(R.drawable.ic_sym_volume_up_fill)) { nav.push("sounds") }
    }
}

@Composable
private fun DocsCard() {
    val context = LocalContext.current
    val res = LocalResources.current
    BarkCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.documentsAndExamples), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.documentsAndExamplesDetail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ context.openUrl(res.getString(R.string.paramsUrl)) }, Modifier.weight(1f)) {
                ButtonIcon(painterResource(R.drawable.ic_sym_menu_book))
                Text(stringResource(R.string.pushParameters), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton({ context.openUrl(res.getString(R.string.faqUrl)) }, Modifier.weight(1f)) {
                ButtonIcon(painterResource(R.drawable.ic_sym_help))
                Text(stringResource(R.string.faq), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun Context.copyAndToast(text: String) {
    copyText(text)
    toast(getString(R.string.Copy))
}
