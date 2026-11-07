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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
    val res = LocalResources.current
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
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val notificationsOn = remember(resumes) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            PermissionCard(notificationsOn)
            TokenErrorCard()
            if (HyperOs.isXiaomi) HyperOsCard(resumes)
            ExampleCard(server, notificationsOn, onQr = { nav.push("qr") })
            Card {
                LinkRow(R.string.serverList) { nav.push("servers") }
                LinkRow(R.string.encryptionSettings) { nav.push("crypto") }
                LinkRow(R.string.notificationSound) { nav.push("sounds") }
            }
            Card {
                LinkRow(R.string.pushParameters) { context.openUrl(res.getString(R.string.paramsUrl)) }
                LinkRow(R.string.faq) { context.openUrl(res.getString(R.string.faqUrl)) }
            }
        }
    }
    if (adding) AddServerDialog { adding = false }
}

@Composable
private fun LinkRow(label: Int, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PermissionCard(enabled: Boolean) {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) HyperOs.openNotificationSettings(context)
    }
    Card {
        ListItem(
            headlineContent = { Text(stringResource(if (enabled) R.string.notificationPermissionOn else R.string.notificationPermissionOff)) },
            supportingContent = { Text(stringResource(if (enabled) R.string.notificationPermissionOnDetail else R.string.notificationPermissionOffDetail)) },
            trailingContent = if (enabled) null else {
                {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else HyperOs.openNotificationSettings(context)
                    }) { Text(stringResource(R.string.goToSettings)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun TokenErrorCard() {
    val error by ServerApi.tokenError.collectAsState()
    val reason = error ?: return
    val context = LocalContext.current
    val res = LocalResources.current
    Card {
        Text(
            if (reason == "firebase") res.getString(R.string.fcm_not_configured) else res.getString(R.string.fcm_unavailable, reason),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun HyperOsCard(resumes: Int) {
    val context = LocalContext.current
    val gms = remember(resumes) { HyperOs.googleServicesEnabled(context) }
    val dnd = remember(resumes) { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }
    Card {
        Column(Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.hyperos_title) + HyperOs.version.let { if (it.isEmpty()) "" else " · $it" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Text(stringResource(R.string.hyperos_desc), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Text(
                stringResource(if (gms) R.string.hyperos_gms_on else R.string.hyperos_gms_off),
                color = if (gms) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LinkRow(R.string.hyperos_autostart) { HyperOs.openAutostart(context) }
            LinkRow(R.string.hyperos_battery) { HyperOs.openBattery(context) }
            LinkRow(R.string.hyperos_notification) { HyperOs.openNotificationSettings(context) }
            if (!dnd) LinkRow(R.string.hyperos_dnd) { HyperOs.openDndAccess(context) }
        }
    }
}

@Composable
private fun ExampleCard(server: Server, notificationsOn: Boolean, onQr: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(ExampleType.GET) }
    var menu by remember { mutableStateOf(false) }
    val text = ServerApi.exampleText(server, type)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.usageExamples), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExampleType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, ExampleType.entries.size),
                    ) { Text(t.name) }
                }
            }
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            Row {
                TextButton(onClick = { context.copyAndToast(text) }) { Text(stringResource(R.string.Copy2)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val hasKey = server.key.isNotEmpty()
                        DropdownMenuItem({ Text(stringResource(R.string.copyAddressAndKey)) }, enabled = hasKey, onClick = { menu = false; context.copyAndToast(server.addressAndKey) })
                        DropdownMenuItem({ Text(stringResource(R.string.copyKey)) }, enabled = hasKey, onClick = { menu = false; context.copyAndToast(server.key) })
                        DropdownMenuItem({ Text(stringResource(R.string.showQRCode)) }, enabled = hasKey, onClick = { menu = false; onQr() })
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    when {
                        !notificationsOn -> context.toast(res.getString(R.string.notificationPermissionAlertMessage))
                        server.key.isEmpty() -> context.toast(res.getString(R.string.deviceNotRegistered))
                        else -> scope.launch {
                            ServerApi.sendTest(server, type)
                                .onSuccess { context.toast(res.getString(R.string.testPushSent)) }
                                .onFailure { context.toast(res.getString(R.string.testPushFailed, it.message.orEmpty())) }
                        }
                    }
                }) { Text(stringResource(R.string.sendTest)) }
            }
        }
    }
}

fun Context.copyAndToast(text: String) {
    copyText(text)
    toast(getString(R.string.Copy))
}
