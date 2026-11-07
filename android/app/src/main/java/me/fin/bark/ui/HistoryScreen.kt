package me.fin.bark.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.fin.bark.R
import me.fin.bark.data.GroupInfo
import me.fin.bark.data.Message
import me.fin.bark.data.Messages
import me.fin.bark.data.Prefs
import me.fin.bark.data.TimeRange
import me.fin.bark.push.Markdown
import me.fin.bark.toast

private const val PAGE = 20

/** iOS MessageListViewController. [group] non-null shows one group ("" = messages without a group). */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HistoryScreen(nav: Nav, group: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { Messages.dao(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var groupMode by remember { mutableStateOf(Prefs.groupMode) }
    var exact by remember { mutableStateOf(Prefs.exactDate) }
    var clearMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    /** Pending destructive action awaiting confirmation. */
    var confirm by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val showGroups = group == null && groupMode && query.isEmpty()

    val item: @Composable (Message) -> Unit = { m ->
        MessageItem(
            m, exact,
            onToggleDate = { exact = !exact; Prefs.exactDate = exact },
            onDelete = { scope.launch { Messages.delete(context) { delete(m.id) } } },
            onImage = { viewing = it },
        )
    }

    Page(
        title = group?.ifEmpty { stringResource(R.string.default_) } ?: stringResource(R.string.historyMessage),
        onBack = if (group != null) nav.pop else null,
        actions = {
            if (group == null) {
                IconButton(onClick = { groupMode = !groupMode; Prefs.groupMode = groupMode }) {
                    Icon(
                        if (groupMode) Icons.AutoMirrored.Filled.List else Icons.Default.Menu,
                        stringResource(if (groupMode) R.string.list_mode else R.string.group_mode),
                    )
                }
            }
            Box {
                IconButton(onClick = { clearMenu = true }) { Icon(Icons.Default.Delete, stringResource(R.string.clear)) }
                DropdownMenu(expanded = clearMenu, onDismissRequest = { clearMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.clearFrom)) }, enabled = false, onClick = {})
                    TimeRange.main.forEach { range ->
                        DropdownMenuItem({ Text(stringResource(range.label)) }, onClick = {
                            clearMenu = false
                            confirm = { range.bounds().let { (s, e) -> Messages.delete(context) { deleteRange(s, e, group) } } }
                        })
                    }
                    DropdownMenuItem({ Text(stringResource(R.string.more)) }, onClick = { clearMenu = false; moreMenu = true })
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    TimeRange.more.forEach { range ->
                        DropdownMenuItem({ Text(stringResource(range.label)) }, onClick = {
                            moreMenu = false
                            confirm = { range.bounds().let { (s, e) -> Messages.delete(context) { deleteRange(s, e, group) } } }
                        })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (showGroups) {
                val limit = remember { MutableStateFlow(PAGE) }
                val groups by remember { limit.flatMapLatest { dao.groups(it) } }.collectAsState(emptyList())
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { "g:" + it.name }) { g ->
                        GroupCard(g, item, onOpen = { nav.push("group:" + g.name) }, onClear = {
                            confirm = { Messages.delete(context) { deleteGroup(g.name) } }
                        })
                    }
                    item { LaunchedEffect(groups.size) { if (groups.size >= limit.value) limit.value += PAGE } }
                }
            } else {
                val q = query.trim()
                val limit = remember(q) { MutableStateFlow(PAGE) }
                val messages by remember(q) { limit.flatMapLatest { dao.list(group, q, it) } }.collectAsState(emptyList())
                if (messages.isEmpty()) {
                    Text(
                        stringResource(R.string.widget_empty_title),
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(messages, key = { it.id }) { m -> Card { item(m) } }
                    item { LaunchedEffect(messages.size) { if (messages.size >= limit.value) limit.value += PAGE } }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(stringResource(R.string.removeNotice)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { action() }
                }) { Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.Cancel)) } },
        )
    }
    viewing?.let { url -> ImageViewer(url) { viewing = null } }
}

@Composable
private fun GroupCard(info: GroupInfo, item: @Composable (Message) -> Unit, onOpen: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(info.name) { mutableStateOf(false) }
    val messages by remember(info.name, expanded) {
        Messages.dao(context).list(info.name, "", if (expanded) 5 else 1)
    }.collectAsState(emptyList())
    Card {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.name.ifEmpty { stringResource(R.string.default_) }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        }
        messages.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider()
            item(m)
        }
        if (info.count > 1) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                if (expanded) {
                    TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.showLess)) }
                } else {
                    // Up to five messages expand in place; larger groups open their own list.
                    TextButton(onClick = { if (info.count <= 5) expanded = true else onOpen() }) {
                        Text(stringResource(R.string.viewAllMessages, info.count))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(m: Message, exact: Boolean, onToggleDate: () -> Unit, onDelete: () -> Unit, onImage: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().clickable { menu = true }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(m.title.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val date = if (exact) Dates.exact(m.createDate) else Dates.ago(context, m.createDate)
                Text(
                    date + (m.expireDate?.let { " · " + Dates.expiry(context, it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(onClick = onToggleDate).padding(start = 8.dp),
                )
            }
            m.subtitle?.let { Text(it, fontWeight = FontWeight.Bold) }
            m.body?.let { body -> if (m.isMarkdown) MarkdownText(body) else Text(body) }
            m.url?.let { url ->
                Text(url, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { context.openUrl(url) })
            }
            m.image?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)).clickable { onImage(url) },
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.Copy2)) }, onClick = { menu = false; context.copyAndToast(m.copyText()) })
            DropdownMenuItem({ Text(stringResource(R.string.removeMessage), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        factory = { TextView(it).apply { movementMethod = LinkMovementMethod.getInstance() } },
        update = {
            it.setTextColor(color)
            it.setLinkTextColor(link)
            Markdown.get(it.context).setMarkdown(it, markdown)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageViewer(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            TextButton(
                onClick = {
                    scope.launch {
                        context.toast(res.getString(if (saveImage(context, url)) R.string.saveSuccess else R.string.noPermission))
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            ) { Text(stringResource(R.string.save), color = Color.White) }
        }
    }
}

private suspend fun saveImage(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as SuccessResult).image.toBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bark_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Bark")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.isSuccess
}
