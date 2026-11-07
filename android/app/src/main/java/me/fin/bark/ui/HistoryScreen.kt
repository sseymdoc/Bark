package me.fin.bark.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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

/** What a message card needs besides the message: date style, delete and image viewer. */
private class CardActions(val exact: Boolean, val onToggleDate: () -> Unit, val onDelete: (Message) -> Unit, val onImage: (String) -> Unit)

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
    val actions = CardActions(
        exact,
        onToggleDate = { exact = !exact; Prefs.exactDate = exact },
        onDelete = { m -> scope.launch { Messages.delete(context) { delete(m.id) } } },
        onImage = { viewing = it },
    )

    Page(
        title = group?.ifEmpty { stringResource(R.string.default_) } ?: stringResource(R.string.historyMessage),
        onBack = if (group != null) nav.pop else null,
        actions = {
            if (group == null) {
                IconButton(onClick = { groupMode = !groupMode; Prefs.groupMode = groupMode }) {
                    Icon(
                        painterResource(if (groupMode) R.drawable.ic_group_collapse else R.drawable.ic_group_expand),
                        stringResource(if (groupMode) R.string.list_mode else R.string.group_mode),
                    )
                }
            }
            Box {
                IconButton(onClick = { clearMenu = true }) { Icon(painterResource(R.drawable.ic_delete_outline), stringResource(R.string.clear)) }
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
            val listPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
            if (showGroups) {
                val limit = remember { MutableStateFlow(PAGE) }
                val groups by remember { limit.flatMapLatest { dao.groups(it) } }.collectAsState(emptyList())
                LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { SearchField(query) { query = it } }
                    items(groups, key = { "g:" + it.name }) { g ->
                        GroupItem(g, actions, onOpen = { nav.push("group:" + g.name) }, onClear = {
                            confirm = { Messages.delete(context) { deleteGroup(g.name) } }
                        })
                    }
                    item { LaunchedEffect(groups.size) { if (groups.size >= limit.value) limit.value += PAGE } }
                }
            } else {
                val q = query.trim()
                val limit = remember(q) { MutableStateFlow(PAGE) }
                val messages by remember(q) { limit.flatMapLatest { dao.list(group, q, it) } }.collectAsState(emptyList())
                LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { SearchField(query) { query = it } }
                    items(messages, key = { it.id }) { m ->
                        SwipeToDelete({ actions.onDelete(m); true }) { MessageCard(m, actions) }
                    }
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

/** Pill-shaped search field in the Material search bar style. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        query, onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) } }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fill, unfocusedContainerColor = fill,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A group in group mode; a group holding one message is shown as a plain card, as on iOS. */
@Composable
private fun GroupItem(info: GroupInfo, actions: CardActions, onOpen: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val messages by remember(info.name) { Messages.dao(context).list(info.name, "", 5) }.collectAsState(emptyList())
    if (messages.size <= 1) {
        messages.firstOrNull()?.let { m -> SwipeToDelete({ actions.onDelete(m); true }) { MessageCard(m, actions) } }
        return
    }
    SwipeToDelete({ onClear(); false }) { GroupStack(info, messages, actions, onOpen, onClear) }
}

/**
 * A group as a notification-style stack. Collapsed: the newest card on top of a stack whose next card peeks out 8dp,
 * narrowed by 4% per level and slightly masked. Expanded: header, up to five cards 8dp apart and a
 * "View all" pill. Both states are one layout driven by a spring (iOS: 0.6s, damping 0.8).
 */
@Composable
private fun GroupStack(info: GroupInfo, messages: List<Message>, actions: CardActions, onOpen: () -> Unit, onClear: () -> Unit) {
    var expanded by rememberSaveable(info.name) { mutableStateOf(false) }
    val t by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 120f))
    Layout(
        content = {
            GroupHeader(info.name, onCollapse = { expanded = false }, onClear = onClear, modifier = Modifier.graphicsLayer { alpha = t.coerceIn(0f, 1f) })
            MorePill(info.count, onOpen, Modifier.graphicsLayer { alpha = t.coerceIn(0f, 1f) })
            messages.forEachIndexed { i, m ->
                MessageCard(
                    m, actions,
                    // Collapsed: the top card expands the group; the peeking ones do nothing.
                    onClick = if (expanded) null else if (i == 0) ({ expanded = true }) else ({}),
                    contentAlpha = if (i == 0) 1f else t.coerceIn(0f, 1f),
                    mask = if (i == 0) 0f else (1 - t).coerceIn(0f, 1f) * (i + 1) * 0.01f,
                    modifier = Modifier.graphicsLayer { scaleX = lerp(1 - i * 0.04f, 1f, t) },
                )
            }
        },
        modifier = Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val header = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
        val pill = measurables[1].measure(loose)
        val cards = measurables.drop(2).map { it.measure(loose.copy(minWidth = constraints.maxWidth)) }
        val gap = 8.dp.roundToPx()
        val top = cards[0].height
        val expandedY = ArrayList<Int>()
        var y = header.height + gap
        cards.forEach { expandedY += y; y += it.height + gap }
        val pillY = y - gap + 18.dp.roundToPx()
        val expandedHeight = pillY + pill.height + 18.dp.roundToPx()
        val collapsedHeight = top + 2 * gap
        layout(constraints.maxWidth, lerp(collapsedHeight.toFloat(), expandedHeight.toFloat(), t).toInt().coerceAtLeast(0)) {
            header.place(0, lerp(15.dp.toPx(), 0f, t).toInt())
            pill.place((constraints.maxWidth - pill.width) / 2, lerp((top + gap - pill.height).toFloat(), pillY.toFloat(), t).toInt())
            // Drawn bottom-up so the newest card stays on top.
            for (i in cards.indices.reversed()) {
                val collapsedY = if (i == 0) 0 else top + gap - cards[i].height
                cards[i].place(0, lerp(collapsedY.toFloat(), expandedY[i].toFloat(), t).toInt())
            }
        }
    }
}

/** Group name with "Show less" and a clear button (which asks for confirmation). */
@Composable
private fun GroupHeader(name: String, onCollapse: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onCollapse),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name.ifEmpty { stringResource(R.string.default_) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
        TextButton(onClick = onCollapse) { Text(stringResource(R.string.showLess)) }
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, stringResource(R.string.clear)) }
    }
}

/** "View all N messages" button under an expanded group. */
@Composable
private fun MorePill(count: Int, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.viewAllMessages, count))
        Icon(painterResource(R.drawable.ic_sym_keyboard_arrow_right), null, Modifier.padding(start = 4.dp).size(ButtonDefaults.IconSize))
    }
}

/**
 * A message card with title/subtitle, body, link, image and the date. Tap or long-press opens Copy/Remove
 * unless [onClick] overrides it.
 */
@Composable
private fun MessageCard(
    m: Message,
    actions: CardActions,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentAlpha: Float = 1f,
    mask: Float = 0f,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .combinedClickable(onLongClick = { if (onClick == null) menu = true }) { if (onClick != null) onClick() else menu = true }
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .graphicsLayer { alpha = contentAlpha },
            ) {
                m.title?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }
                m.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                m.body?.let { body -> if (m.isMarkdown) MarkdownText(body) else Text(body, style = MaterialTheme.typography.bodyMedium) }
                m.url?.let { url ->
                    Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp).clickable { context.openUrl(url) })
                }
                m.image?.let { url -> MessageImage(url) { actions.onImage(url) } }
                val date = if (actions.exact) Dates.exact(m.createDate) else Dates.ago(context, m.createDate)
                Text(
                    date + (m.expireDate?.let { " · " + Dates.expiry(context, it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp).clickable(onClick = actions.onToggleDate),
                )
            }
        }
        if (mask > 0f) Box(Modifier.matchParentSize().clip(CardDefaults.elevatedShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = mask)))
        // While stacked, the card's own controls (image, link, date) are disabled: any tap is the card's tap.
        if (onClick != null) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClick))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.Copy2)) }, onClick = { menu = false; context.copyAndToast(m.copyText()) })
            DropdownMenuItem({ Text(stringResource(R.string.removeMessage), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; actions.onDelete(m) })
        }
    }
}

/** iOS sizing: aspect-fit, width ≤ min(500, available, image width), height ≤ 400; 200×100 while loading. */
@Composable
private fun MessageImage(url: String, onClick: () -> Unit) {
    var pixels by remember(url) { mutableStateOf<Pair<Int, Int>?>(null) }
    BoxWithConstraints(Modifier.padding(top = 8.dp)) {
        // Downloaded images have scale 1 on iOS, so pixels count as points.
        val (w, h) = pixels?.let { (iw, ih) ->
            var w = minOf(500f, maxWidth.value, iw.toFloat())
            var h = w * ih / iw
            if (h > 400f) { h = 400f; w = 400f * iw / ih }
            w.dp to h.dp
        } ?: (200.dp to 100.dp)
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { pixels = it.result.image.width to it.result.image.height },
            modifier = Modifier.size(w, h).clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val markwon = remember(c) { Markdown.styled(context, c.primary.toArgb(), c.onSurface.toArgb(), c.surfaceContainerHighest.toArgb()) }
    AndroidView(
        factory = {
            TextView(it).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        },
        update = {
            it.setTextColor(c.onSurface.toArgb())
            it.setLinkTextColor(c.primary.toArgb())
            markwon.setMarkdown(it, markdown)
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
                        context.toast(res.getString(if (saveImage(context, url)) R.string.saveSuccess else R.string.save_image_failed))
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
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: error("image not loaded")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bark_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Bark")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no MediaStore entry")
        // A failed write must not leave an empty bark_*.png in Pictures/Bark.
        runCatching { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            .onFailure { resolver.delete(uri, null, null) }
            .getOrThrow()
    }.isSuccess
}
