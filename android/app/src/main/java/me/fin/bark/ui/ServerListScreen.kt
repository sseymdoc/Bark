package me.fin.bark.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch
import me.fin.bark.R
import me.fin.bark.data.Prefs
import me.fin.bark.data.Server
import me.fin.bark.data.ServerApi
import me.fin.bark.toast

/** Token bark-server treats as "this key is gone" (iOS uses it on delete and before a key reset). */
private const val DELETED_TOKEN = "deleted"

/** Pings, adds, selects and registers a server (iOS NewServerViewModel and the bark://addServer link). */
suspend fun addServer(context: Context, address: String): Boolean {
    val clean = address.trim()
    if (!ServerApi.ping(clean)) {
        context.toast(context.getString(R.string.InvalidServer))
        return false
    }
    val server = Server(address = clean)
    Prefs.saveServers(Prefs.servers.value + server)
    Prefs.setCurrentServer(server.id)
    Prefs.fcmToken?.let { token ->
        ServerApi.register(clean, token, "").onSuccess { Prefs.updateServer(server.copy(key = it)) }
    }
    context.toast(context.getString(R.string.AddedSuccessfully))
    return true
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("https://") }
    var busy by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        if (text.startsWith("http://") || text.startsWith("https://")) address = text else context.toast(res.getString(R.string.InvalidURL))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.AddServer)) },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.ServerAddress)) },
                    supportingText = { Text(stringResource(R.string.ServerExample)) },
                    singleLine = true,
                )
                TextButton(onClick = { scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false)) }) {
                    Text(stringResource(R.string.scan_qr))
                }
                TextButton(onClick = { context.openUrl(res.getString(R.string.deployUrl)) }) {
                    Text(stringResource(R.string.DeploymentDocuments))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    if (addServer(context, address)) onDismiss()
                    busy = false
                }
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.Cancel)) } },
    )
}

@Composable
fun ServerListScreen(nav: Nav) {
    val servers by Prefs.servers.collectAsState()
    val currentId by Prefs.currentServerId.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Page(stringResource(R.string.serverList), onBack = nav.pop, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.AddServer)) }
    }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(servers, key = { it.id }) { server ->
                ServerRow(server, current = server.id == (currentId ?: servers.first().id), isLast = servers.size <= 1)
            }
        }
    }
    if (adding) AddServerDialog { adding = false }
}

@Composable
private fun ServerRow(server: Server, current: Boolean, isLast: Boolean) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(server.address) { online = ServerApi.ping(server.address) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    Box {
        ListItem(
            leadingContent = {
                val color = when (online) {
                    true -> Color(0xFF4CAF50)
                    false -> MaterialTheme.colorScheme.error
                    null -> Color.Gray
                }
                Box(Modifier.size(10.dp).background(color, CircleShape))
            },
            headlineContent = { Text(server.displayName) },
            supportingContent = { Text("${server.host}\nKey: ${server.key.ifEmpty { stringResource(R.string.none) }}") },
            trailingContent = if (current) ({ Icon(Icons.Default.Check, null) }) else null,
            modifier = Modifier.clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.copyAddressAndKey)) }, onClick = {
                menu = false
                context.copyAndToast(server.addressAndKey)
            })
            DropdownMenuItem({ Text(stringResource(R.string.resetKey)) }, onClick = { menu = false; dialog = "reset" })
            DropdownMenuItem({ Text(stringResource(R.string.setAsDefaultServer)) }, onClick = {
                menu = false
                Prefs.setCurrentServer(server.id)
                context.toast(res.getString(R.string.setSuccessfully))
            })
            DropdownMenuItem({ Text(stringResource(R.string.setServerName)) }, onClick = { menu = false; dialog = "name" })
            DropdownMenuItem({ Text(stringResource(R.string.deleteServer), color = MaterialTheme.colorScheme.error) }, onClick = {
                menu = false
                if (isLast) context.toast(res.getString(R.string.deleteFailed)) else dialog = "delete"
            })
        }
    }

    when (dialog) {
        "reset" -> TextDialog(stringResource(R.string.resetKey), stringResource(R.string.resetKeyDesc), "", stringResource(R.string.resetKeyPlaceholder), { dialog = null }) { newKey ->
            val token = Prefs.fcmToken
            if (token == null) {
                context.toast(res.getString(R.string.resetFailed2))
                return@TextDialog
            }
            scope.launch {
                ServerApi.register(server.address, DELETED_TOKEN, server.key) // retire the old key first
                ServerApi.register(server.address, token, newKey.trim())
                    .onSuccess {
                        Prefs.updateServer(server.copy(key = it))
                        context.toast(res.getString(R.string.setSuccessfully))
                    }
                    .onFailure { context.toast(res.getString(R.string.resetFailed) + ": " + it.message) }
            }
        }
        "name" -> TextDialog(stringResource(R.string.setServerName), null, server.name.orEmpty(), null, { dialog = null }) { name ->
            Prefs.updateServer(server.copy(name = name.trim().ifEmpty { null }))
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(stringResource(R.string.confirmDeleteServer)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        if (server.key.isNotEmpty()) ServerApi.register(server.address, DELETED_TOKEN, server.key)
                        val rest = Prefs.servers.value.filter { it.id != server.id }
                        Prefs.saveServers(rest)
                        if (current) Prefs.setCurrentServer(rest.first().id)
                        context.toast(res.getString(R.string.deletedSuccessfully))
                    }
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.Cancel)) } },
        )
    }
}

@Composable
fun TextDialog(title: String, message: String?, initial: String, placeholder: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                OutlinedTextField(text, { text = it }, placeholder = placeholder?.let { { Text(it) } }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(text) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.Cancel)) } },
    )
}
