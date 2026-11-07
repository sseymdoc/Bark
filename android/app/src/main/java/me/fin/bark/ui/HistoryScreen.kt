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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                BarButton(
                    painterResource(if (groupMode) R.drawable.ic_group_collapse else R.drawable.ic_group_expand),
                    stringResource(if (groupMode) R.string.list_mode else R.string.group_mode),
                    onClick = { groupMode = !groupMode; Prefs.groupMode = groupMode },
                    tint = bk.black,
                )
            }
            Box {
                BarButton(painterResource(R.drawable.ic_delete_outline), stringResource(R.string.clear), { clearMenu = true })
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
            // iOS cells: 10dp gap under every card, no empty-state view.
            val listPadding = PaddingValues(top = 4.dp, bottom = 10.dp)
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
                }) { Text(stringResource(R.string.clear), color = bk.systemRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.Cancel)) } },
        )
    }
    viewing?.let { url -> ImageViewer(url) { viewing = null } }
}

/** iOS UISearchBar look: 36dp rounded field on a translucent grey fill. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        query, onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 17.sp, color = bk.label),
        cursorBrush = SolidColor(bk.systemBlue),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(bk.searchFill).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = bk.secondaryLabel, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(start = 6.dp)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search), fontSize = 17.sp, color = bk.secondaryLabel)
                    field()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Default.Clear, null, tint = bk.secondaryLabel, modifier = Modifier.size(18.dp).clickable { onChange("") })
                }
            }
        },
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
 * iOS MessageGroupTableViewCell. Collapsed: the newest card on top of a stack whose next card peeks out 8dp,
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

/** Group name with the "Show less" / clear control (iOS MessageGroupHeaderView). */
@Composable
private fun GroupHeader(name: String, onCollapse: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onCollapse),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name.ifEmpty { stringResource(R.string.default_) }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = bk.greyDarken4, maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
        )
        ShowLessAndClear(onCollapse, onClear)
    }
}

/**
 * iOS ShowLessAndClearView: a "Show less" pill and a round ✕. Tapping ✕ morphs (0.3s) the ✕ into a wide
 * "Clear" pill while "Show less" shrinks to a circle; the total width stays the same.
 */
@Composable
private fun ShowLessAndClear(onShowLess: () -> Unit, onClear: () -> Unit) {
    var clearing by remember { mutableStateOf(false) }
    val c by animateFloatAsState(if (clearing) 1f else 0f, tween(300))
    val pill = RoundedCornerShape(14.dp)
    Layout(
        content = {
            Row(
                Modifier.clip(pill).background(bk.greyLighten5).clickable { if (clearing) clearing = false else onShowLess() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_arrow_down), null, tint = bk.greyDarken2, modifier = Modifier.padding(start = 2.dp).size(24.dp))
                Text(stringResource(R.string.showLess), fontSize = 12.sp, color = bk.greyDarken3, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 2.dp, end = 10.dp))
            }
            Box(Modifier.clip(pill).background(bk.greyLighten5).clickable { if (clearing) onClear() else clearing = true }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear), tint = bk.greyDarken2, modifier = Modifier.size(20.dp).graphicsLayer { alpha = 1 - c })
                Text(stringResource(R.string.clear), fontSize = 12.sp, color = bk.greyDarken3, maxLines = 1, softWrap = false, modifier = Modifier.graphicsLayer { alpha = c })
            }
        },
    ) { measurables, _ ->
        val d = 28.dp.roundToPx()
        val gap = 5.dp.roundToPx()
        val full = measurables[0].maxIntrinsicWidth(d)
        val showLess = measurables[0].measure(Constraints.fixed(lerp(full.toFloat(), d.toFloat(), c).toInt(), d))
        val clear = measurables[1].measure(Constraints.fixed(lerp(d.toFloat(), full.toFloat(), c).toInt(), d))
        layout(full + gap + d, d) {
            showLess.place(0, 0)
            clear.place(full + gap + d - clear.width, 0)
        }
    }
}

/** "View all N messages" pill (iOS MessageGroupMoreView). */
@Composable
private fun MorePill(count: Int, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(bk.greyLighten5).clickable(onClick = onClick).padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.viewAllMessages, count), fontSize = 12.sp, color = bk.greyDarken3)
        Icon(painterResource(R.drawable.ic_sym_keyboard_arrow_right), null, tint = bk.greyDarken2, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

/**
 * iOS MessageItemView: a rounded panel inset 16dp with title/subtitle (16sp medium), body (14sp), link, image
 * and the date (11sp). Tapping opens Copy/Remove unless [onClick] overrides it.
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
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(bk.bgSecondary)
                .clickable { if (onClick != null) onClick() else menu = true }
                .padding(start = 12.dp, end = 12.dp, top = 16.dp)
                .graphicsLayer { alpha = contentAlpha },
        ) {
            m.title?.let {
                Text(it, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = bk.greyDarken4)
                Spacer(Modifier.height(6.dp))
            }
            m.subtitle?.let {
                Text(it, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = bk.greyDarken4)
                Spacer(Modifier.height(6.dp))
            }
            m.body?.let { body -> if (m.isMarkdown) MarkdownText(body) else Text(body, fontSize = 14.sp, color = bk.greyDarken4) }
            m.url?.let { url ->
                Text(url, fontSize = 14.sp, color = bk.systemBlue, modifier = Modifier.padding(top = 8.dp).clickable { context.openUrl(url) })
            }
            m.image?.let { url -> MessageImage(url) { actions.onImage(url) } }
            val date = if (actions.exact) Dates.exact(m.createDate) else Dates.ago(context, m.createDate)
            Text(
                date + (m.expireDate?.let { " · " + Dates.expiry(context, it) } ?: ""),
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = bk.greyBase,
                modifier = Modifier.padding(vertical = 12.dp).clickable(onClick = actions.onToggleDate),
            )
        }
        if (mask > 0f) Box(Modifier.matchParentSize().clip(shape).background(bk.black.copy(alpha = mask)))
        // iOS disables the card's own controls (image, link, date) while stacked: any tap is the card's tap.
        if (onClick != null) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClick))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.Copy2)) }, onClick = { menu = false; context.copyAndToast(m.copyText()) })
            DropdownMenuItem({ Text(stringResource(R.string.removeMessage), color = bk.systemRed) }, onClick = { menu = false; actions.onDelete(m) })
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
            modifier = Modifier.size(w, h).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val c = bk
    val markwon = remember(c) { Markdown.styled(context, c.blueBase.toArgb(), c.greyDarken4.toArgb(), c.greyLighten4.toArgb()) }
    AndroidView(
        factory = {
            TextView(it).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        },
        update = {
            it.setTextColor(c.greyDarken4.toArgb())
            it.setLinkTextColor(c.blueBase.toArgb())
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
