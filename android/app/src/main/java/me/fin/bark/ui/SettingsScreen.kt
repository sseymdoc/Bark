package me.fin.bark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.fin.bark.BuildConfig
import me.fin.bark.R
import me.fin.bark.data.MessageJson
import me.fin.bark.data.Messages
import me.fin.bark.data.Prefs
import me.fin.bark.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** iOS MessageSettingsViewController, without donations. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val dao = remember { Messages.dao(context) }
    val count by remember { dao.count() }.collectAsState(0)
    var archive by remember { mutableStateOf(Prefs.isArchive) }
    val token = Prefs.fcmToken

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val json = MessageJson.encode(dao.all())
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
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

    Page(stringResource(R.string.settings)) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.historyMessage))
            ListItem(
                headlineContent = { Text(stringResource(R.string.export)) },
                supportingContent = { Text("$count ${stringResource(R.string.items)}") },
                modifier = Modifier.clickable {
                    export.launch("bark_messages_${SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())}.json")
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.import_)) },
                modifier = Modifier.clickable { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.defaultArchiveSettings)) },
                supportingContent = { Text(stringResource(R.string.archiveNote)) },
                trailingContent = { Switch(archive, { archive = it; Prefs.isArchive = it }) },
            )

            SectionTitle(stringResource(R.string.info))
            ListItem(
                headlineContent = { Text(stringResource(R.string.fcm_token)) },
                supportingContent = { Text(token?.let { it.take(2) + "••••" + it.takeLast(4) } ?: stringResource(R.string.unknown)) },
                modifier = Modifier.clickable { token?.let { context.copyAndToast(it) } },
            )

            SectionTitle(stringResource(R.string.other))
            listOf(
                R.string.faq to res.getString(R.string.faqUrl),
                R.string.documentation to res.getString(R.string.docUrl),
                R.string.sourceCode to "https://github.com/Finb/Bark",
            ).forEach { (label, url) ->
                ListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { context.openUrl(url) })
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
        }
    }
}
