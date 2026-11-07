package me.fin.bark.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import me.fin.bark.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal stack navigation: routes are plain strings. */
class Nav(val push: (String) -> Unit, val pop: () -> Unit)

private val BarkBlue = Color(0xFF2196F3)

/** Syntax colors for the curl example (GitHub light/dark); the only colors outside the Material scheme. */
@Immutable
class CodeColors(val command: Color, val flag: Color, val string: Color)

private val LightCode = CodeColors(Color(0xFF8250DF), Color(0xFFCF222E), Color(0xFF0A3069))
private val DarkCode = CodeColors(Color(0xFFD2A8FF), Color(0xFFFF7B72), Color(0xFFA5D6FF))

private val LocalCode = staticCompositionLocalOf { LightCode }

val codeColors: CodeColors
    @Composable @ReadOnlyComposable get() = LocalCode.current

/** Material You colors from the wallpaper on Android 12+, otherwise a scheme seeded with Bark blue. */
@Composable
fun BarkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = BarkBlue)
        else -> lightColorScheme(primary = BarkBlue)
    }
    CompositionLocalProvider(LocalCode provides if (dark) DarkCode else LightCode) { MaterialTheme(colorScheme = scheme, content = content) }
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
