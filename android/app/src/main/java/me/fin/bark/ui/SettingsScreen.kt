package me.fin.bark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    var ioMenu by remember { mutableStateOf(false) }
    Page(stringResource(R.string.settings)) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            InsetGroup(
                header = { SectionHeader(stringResource(R.string.historyMessage)) },
                footer = stringResource(R.string.archiveNote),
                rows = listOf(
                    {
                        Box {
                            GroupRow(
                                "${stringResource(R.string.export)}/${stringResource(R.string.import_)}",
                                titleColor = bk.blueDarken1,
                                detail = "$count ${stringResource(R.string.items)}",
                                onClick = { ioMenu = true },
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
                    },
                    {
                        GroupRow(stringResource(R.string.defaultArchiveSettings), trailing = {
                            Switch(
                                archive, { archive = it; Prefs.isArchive = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = bk.systemGreen, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent),
                            )
                        })
                    },
                ),
            )
            InsetGroup(
                header = { SectionHeader(stringResource(R.string.info)) },
                rows = listOf({
                    // iOS masks the token as ab****wxyz and copies it on tap.
                    GroupRow("Device Token", detail = token?.let { it.take(2) + "****" + it.takeLast(4) } ?: stringResource(R.string.unknown), onClick = {
                        token?.let { context.copyAndToast(it) }
                    })
                }),
            )
            InsetGroup(
                header = { SectionHeader(stringResource(R.string.other)) },
                rows = listOf(
                    R.string.faq to res.getString(R.string.faqUrl),
                    R.string.documentation to res.getString(R.string.docUrl),
                    R.string.sourceCode to "https://github.com/Finb/Bark",
                ).map { (label, url) -> { GroupRow(stringResource(label), chevron = true, onClick = { context.openUrl(url) }) } },
            )
            Text(
                "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 12.sp, color = bk.greyDarken1, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
    }
}
