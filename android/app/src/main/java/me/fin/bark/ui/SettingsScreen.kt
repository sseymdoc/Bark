package me.fin.bark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.fin.bark.BuildConfig
import me.fin.bark.R
import me.fin.bark.data.MessageJson
import me.fin.bark.data.Messages
import me.fin.bark.data.Prefs
import me.fin.bark.push.SseService
import me.fin.bark.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings tab (iOS MessageSettingsViewController without donations). */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val dao = remember { Messages.dao(context) }
    val count by remember { dao.count() }.collectAsState(0)
    var archive by remember { mutableStateOf(Prefs.isArchive) }
    var sse by remember { mutableStateOf(Prefs.sse) }
    val token: String? = Prefs.deviceToken

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val messages = dao.all()
                    val json = MessageJson.encode(messages)
                    (context.contentResolver.openOutputStream(uri) ?: error("no output stream")).use { it.write(json.toByteArray()) }
                    messages.size
                }
            }
                .onSuccess { context.toast(res.getString(R.string.export_success, it)) }
                .onFailure { context.toast(res.getString(R.string.export_failed, it.message.orEmpty())) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val messages = withContext(Dispatchers.IO) {
                    MessageJson.decode(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                }
                Messages.save(context, messages)
                messages.size
            }
                .onSuccess { context.toast(res.getString(R.string.import_success, it)) }
                .onFailure { context.toast(res.getString(R.string.import_failed, it.message.orEmpty())) }
        }
    }

    var ioMenu by remember { mutableStateOf(false) }
    Page(stringResource(R.string.settings)) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.historyMessage))
            Box {
                ListItem(
                    headlineContent = { Text("${stringResource(R.string.export)}/${stringResource(R.string.import_)}") },
                    supportingContent = { Text("$count ${stringResource(R.string.items)}") },
                    modifier = Modifier.clickable { ioMenu = true },
                )
                DropdownMenu(expanded = ioMenu, onDismissRequest = { ioMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.export)) }, onClick = {
                        ioMenu = false
                        export.launch("bark_messages_${SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())}.json")
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.import_)) }, onClick = {
                        ioMenu = false
                        import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    })
                }
            }
            SwitchRow(stringResource(R.string.defaultArchiveSettings), stringResource(R.string.archiveNote), archive) { archive = it; Prefs.isArchive = it }

            SectionHeader(stringResource(R.string.sse_title))
            SwitchRow(stringResource(R.string.sse_switch), stringResource(R.string.sse_footer), sse) {
                sse = it
                Prefs.sse = it
                if (it) SseService.start(context) else SseService.stop(context)
            }

            SectionHeader(stringResource(R.string.info))
            // Masked as ab****wxyz like iOS; tapping copies the full token.
            ListItem(
                headlineContent = { Text("Device Token") },
                supportingContent = { Text(token?.let { it.take(2) + "****" + it.takeLast(4) } ?: stringResource(R.string.unknown)) },
                modifier = Modifier.clickable { token?.let { context.copyAndToast(it) } },
            )

            SectionHeader(stringResource(R.string.other))
            listOf(
                R.string.faq to res.getString(R.string.faqUrl),
                R.string.documentation to res.getString(R.string.docUrl),
                R.string.sourceCode to "https://github.com/Finb/Bark",
            ).forEach { (label, url) ->
                ListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { context.openUrl(url) })
            }
            Text(
                "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
    }
}

/** A list row whose whole area toggles the trailing switch. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}
