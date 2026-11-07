package me.fin.bark.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.fin.bark.R

/** Height of the iOS large title area below the 44dp bar. */
private val LargeTitleHeight = 52.dp

/**
 * iOS large-title navigation bar: a 34sp title that scrolls away with the content, after which a centered
 * 17sp title and a hairline fade in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    background: Color = bk.bgPrimary,
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val density = LocalDensity.current
    val limit = with(density) { -LargeTitleHeight.toPx() }
    SideEffect { if (behavior.state.heightOffsetLimit != limit) behavior.state.heightOffsetLimit = limit }
    val collapsed = behavior.state.collapsedFraction > 0.99f
    val smallTitle by animateFloatAsState(if (collapsed) 1f else 0f, tween(200))
    val barColor by animateColorAsState(if (collapsed) bk.bar else background, tween(200))
    val separator = bk.separator
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            Column(
                Modifier
                    .background(barColor)
                    .drawBehind { drawLine(separator.copy(alpha = smallTitle), Offset(0f, size.height), Offset(size.width, size.height), 1f) }
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets),
            ) {
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    if (onBack != null) {
                        BarButton(painterResource(R.drawable.ic_back), stringResource(R.string.back), onBack, Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
                    }
                    Text(
                        title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = bk.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp).graphicsLayer { alpha = smallTitle },
                    )
                    Row(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                val height = with(density) { (LargeTitleHeight.toPx() + behavior.state.heightOffset).coerceAtLeast(0f).toDp() }
                Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
                    Text(
                        title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = bk.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true)
                            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                }
            }
        },
        content = content,
    )
}

/** Navigation bar icon button: 24dp template image in a 40dp target, tinted like iOS (grey.darken4). */
@Composable
fun BarButton(icon: Painter, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = bk.greyDarken4) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp)) { Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** Home card: iOS GlassView's flat look (secondary background, hairline border, radius 22). */
@Composable
fun BarkCard(modifier: Modifier = Modifier, spacing: Dp = 14.dp, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(bk.bgSecondary).border(Dp.Hairline, bk.legacyBorder, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** iOS insetGrouped section: rounded group of rows with hairline separators, optional header and footer. */
@Composable
fun InsetGroup(header: (@Composable () -> Unit)? = null, footer: String? = null, rows: List<@Composable () -> Unit>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) header() else Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bk.bgSecondary)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = Dp.Hairline, color = bk.greyLighten3)
                row()
            }
        }
        if (footer != null) Text(footer, fontSize = 12.sp, color = bk.greyDarken1, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp))
        else Spacer(Modifier.height(10.dp))
    }
}

/** Settings section header (iOS SettingSectionHeader). */
@Composable
fun SectionHeader(text: String) {
    Text(text, fontSize = 12.sp, color = bk.greyDarken1, modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp))
}

/** iOS value-style table cell: title, optional detail on the right, optional disclosure chevron or trailing view. */
@Composable
fun GroupRow(
    title: String,
    titleColor: Color = bk.label,
    detail: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, color = titleColor, modifier = Modifier.weight(1f))
        detail?.let { Text(it, fontSize = 17.sp, color = bk.greyDarken2, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
        trailing?.invoke()
        if (chevron) Icon(painterResource(R.drawable.ic_sym_chevron_right), null, tint = bk.tertiaryLabel, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/**
 * iOS trailing swipe action: dragging left reveals a red "Remove" button; a full swipe calls [onSwiped].
 * Return false from it to snap the row back (e.g. to ask for confirmation first).
 */
@Composable
fun SwipeToDelete(onSwiped: () -> Boolean, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(bk.systemRed), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.removeMessage), color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(end = 20.dp))
            }
        },
        onDismiss = { if (!onSwiped()) scope.launch { state.reset() } },
    ) {
        // Like an iOS cell's contentView: opaque, so the red only shows where the row has slid away.
        Box(Modifier.background(bk.bgPrimary)) { content() }
    }
}

/** Home's flat button (iOS HomeFlatButton): title on the left, icon pushed to the right edge, dims to 0.78 when pressed. */
@Composable
fun FlatButton(title: String, icon: Painter?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(44.dp).graphicsLayer { alpha = if (pressed) 0.78f else 1f }
            .clip(shape).background(bk.legacyButton).border(Dp.Hairline, bk.legacyBorder, shape)
            .clickable(source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 16.sp, color = bk.label, maxLines = 1, modifier = Modifier.weight(1f))
        icon?.let { Icon(it, null, tint = bk.label, modifier = Modifier.size(18.dp)) }
    }
}

/** Home's Copy button (iOS HomeSplitButton): title area plus a 42dp chevron segment that opens [menu]. */
@Composable
fun SplitButton(title: String, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier, menu: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(44.dp).clip(shape).background(bk.legacyButton).border(Dp.Hairline, bk.legacyBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, fontSize = 16.sp, color = bk.label.copy(alpha = if (pressed) 0.5f else 1f), maxLines = 1,
            modifier = Modifier.weight(1f).fillMaxHeight().clickable(source, indication = null, onClick = onClick).padding(start = 14.dp).wrapContentHeight(),
        )
        Box(Modifier.width(1.dp).height(20.dp).background(bk.divider))
        Box(Modifier.width(42.dp).fillMaxHeight().clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_sym_expand_more), null, tint = bk.label, modifier = Modifier.size(16.dp))
            menu()
        }
    }
}
