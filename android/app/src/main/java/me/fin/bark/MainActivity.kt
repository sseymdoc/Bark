package me.fin.bark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.fin.bark.data.Messages
import me.fin.bark.data.Prefs
import me.fin.bark.data.ServerApi
import me.fin.bark.push.CallService
import me.fin.bark.push.Notifier
import me.fin.bark.push.Push
import me.fin.bark.push.SseService
import me.fin.bark.ui.BarkTheme
import me.fin.bark.ui.CryptoScreen
import me.fin.bark.ui.HistoryScreen
import me.fin.bark.ui.HomeScreen
import me.fin.bark.ui.Nav
import me.fin.bark.ui.QrScreen
import me.fin.bark.ui.ServerListScreen
import me.fin.bark.ui.SettingsScreen
import me.fin.bark.ui.SoundsScreen
import me.fin.bark.ui.addServer
import me.fin.bark.ui.bk
import me.fin.bark.ui.openUrl

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)

    /** Push data of a tapped `action=alert` notification. */
    private val alert = mutableStateOf<Map<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab = Prefs.lastTab
        if (savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch {
            // Where Google is unreachable the token request can hang; register with the SSE token instead.
            withTimeoutOrNull(15_000) { ServerApi.fcmToken() }
            ServerApi.syncAllServers(Prefs.deviceToken)
        }
        SseService.start(this)
        setContent {
            BarkTheme {
                App(tab, { tab = it; Prefs.lastTab = it }, alert)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Messages.deleteExpired(this@MainActivity) }
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data
        when {
            intent.action == ACTION_NOTIFICATION -> {
                CallService.stop(this)
                handleNotification(Notifier.dataOf(intent.extras), intent.getStringExtra(Notifier.BODY_TEXT))
            }
            data?.scheme == "bark" -> handleDeepLink(data)
        }
    }

    /** iOS AppDelegate.handleNotification. */
    private fun handleNotification(data: Map<String, String>, bodyText: String?) {
        val push = Push(data)
        if (push.action == "none") return
        push.url?.let { if (openUrl(it)) return }
        if (push.action == "alert") {
            alert.value = data + (Notifier.BODY_TEXT to bodyText.orEmpty())
            return
        }
        tab = HISTORY
    }

    private fun handleDeepLink(uri: Uri) {
        when (uri.host) {
            "addServer" -> uri.getQueryParameter("address")?.let { lifecycleScope.launch { addServer(this@MainActivity, it) } }
            "history" -> tab = HISTORY
        }
    }

    companion object {
        const val ACTION_NOTIFICATION = "me.fin.bark.NOTIFICATION"
        const val HISTORY = 1
    }
}

@Composable
private fun App(tab: Int, onTab: (Int) -> Unit, alert: MutableState<Map<String, String>?>) {
    val stack = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val nav = Nav(push = { stack.add(it) }, pop = { stack.removeLastOrNull() })
    BackHandler(stack.isNotEmpty()) { stack.removeLastOrNull() }

    when (val route = stack.lastOrNull()) {
        null -> Scaffold(
            bottomBar = { TabBar(tab, onTab) },
        ) { padding ->
            // Only the bottom bar is handled here; the status bar inset is left for each tab's own top bar.
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                when (tab) {
                    0 -> HomeScreen(nav)
                    MainActivity.HISTORY -> HistoryScreen(nav, null)
                    else -> SettingsScreen()
                }
            }
        }
        "servers" -> ServerListScreen(nav)
        "crypto" -> CryptoScreen(nav)
        "sounds" -> SoundsScreen(nav)
        "qr" -> QrScreen(nav)
        else -> HistoryScreen(nav, route.removePrefix("group:"))
    }

    alert.value?.let { data -> AlertActionDialog(data) { alert.value = null } }
}

/** iOS tab bar: 49dp, template icons over 10sp labels, no selection indicator, hairline on top. */
@Composable
private fun TabBar(tab: Int, onTab: (Int) -> Unit) {
    val separator = bk.separator
    Row(
        Modifier.fillMaxWidth().background(bk.bar)
            .drawBehind { drawLine(separator, Offset.Zero, Offset(size.width, 0f), 1f) }
            .navigationBarsPadding().height(49.dp),
    ) {
        listOf(
            Triple(R.string.service, R.drawable.ic_tab_service, 0),
            Triple(R.string.historyMessage, R.drawable.ic_sym_history, MainActivity.HISTORY),
            Triple(R.string.settings, R.drawable.ic_tab_settings, 2),
        ).forEach { (label, icon, index) ->
            val color = if (tab == index) bk.greyDarken4 else bk.systemGray
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onTab(index) }.padding(top = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(26.dp))
                Text(stringResource(label), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

/** `action=alert`: copy the content, or share every parameter (iOS "More Actions"). */
@Composable
private fun AlertActionDialog(data: Map<String, String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val push = Push(data)
    val body = data[Notifier.BODY_TEXT]?.ifEmpty { null } ?: push.body
    AlertDialog(
        onDismissRequest = onDismiss,
        title = push.title?.let { { Text(it) } },
        text = body?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                context.copyText(push.copy ?: body.orEmpty())
                context.toast(res.getString(R.string.Copy))
                onDismiss()
            }) { Text(stringResource(R.string.CopyContent)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val skip = setOf("aps", "title", "subtitle", "body", "url", Notifier.BODY_TEXT)
                val text = (listOfNotNull(push.title, push.subtitle, body) +
                    data.filterKeys { it !in skip }.map { (k, v) -> "$k: $v" } +
                    listOfNotNull(push.url)).joinToString("\n")
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                onDismiss()
            }) { Text(stringResource(R.string.MoreActions)) }
        },
    )
}
