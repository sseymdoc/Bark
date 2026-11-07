package me.fin.bark.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.fin.bark.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal stack navigation: routes are plain strings. */
class Nav(val push: (String) -> Unit, val pop: () -> Unit)

private val BarkBlue = Color(0xFF2196F3)

@Composable
fun BarkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = BarkBlue)
        else -> lightColorScheme(primary = BarkBlue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Opens [url]; for http(s) an installed app that handles the link wins over the browser, like iOS universal links. */
fun Context.openUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme.isNullOrEmpty()) return false
    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if ((uri.scheme == "http" || uri.scheme == "https") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val app = Intent(view).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        if (runCatching { startActivity(app) }.isSuccess) return true
    }
    return runCatching { startActivity(view) }.isSuccess
}

object Dates {
    fun exact(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    /** iOS Date.agoFormatString. */
    fun ago(context: Context, time: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - time).coerceAtLeast(0)
        val hours = diff / 3_600_000
        val minutes = diff / 60_000 % 60
        return when {
            hours > 12 -> exact(time)
            hours > 1 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
            hours == 1L -> if (minutes > 0) context.getString(R.string.timeMinHourAgo, 1, minutes.toInt()) else context.getString(R.string.timeHourAgo, 1)
            minutes > 1 -> context.getString(R.string.timeMinAgo, minutes.toInt())
            else -> context.getString(R.string.timeJustNow)
        }
    }

    /** iOS Date.expiryTimeSinceNow. */
    fun expiry(context: Context, expireDate: Long, now: Long = System.currentTimeMillis()): String {
        val s = (expireDate - now) / 1000
        return when {
            s > 31_536_000 -> context.getString(R.string.expiryTimeYear, (s / 31_536_000).toInt())
            s > 2_592_000 -> context.getString(R.string.expiryTimeMonth, (s / 2_592_000).toInt())
            s > 86_400 -> context.getString(R.string.expiryTimeDay, (s / 86_400).toInt())
            s > 3_600 -> context.getString(R.string.expiryTimeHour, (s / 3_600).toInt())
            s > 60 -> context.getString(R.string.expiryTimeMinute, (s / 60).toInt())
            s > 0 -> context.getString(R.string.expiryTimeSecond, s.toInt())
            else -> context.getString(R.string.expired)
        }
    }
}
