package me.fin.bark.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import me.fin.bark.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal stack navigation: routes are plain strings. */
class Nav(val push: (String) -> Unit, val pop: () -> Unit)

/** iOS BKColor (Assets.xcassets/Colors) plus the iOS system colors the screens use. */
@Immutable
class BkColors(
    val bgPrimary: Color, val bgSecondary: Color, val black: Color, val white: Color,
    val greyBase: Color, val greyDarken1: Color, val greyDarken2: Color, val greyDarken3: Color, val greyDarken4: Color,
    val greyLighten2: Color, val greyLighten3: Color, val greyLighten4: Color, val greyLighten5: Color,
    val blueBase: Color, val blueDarken1: Color, val blueDarken5: Color, val lightBlueDarken3: Color,
    val legacyButton: Color, val legacyBorder: Color, val accentBlue: Color, val accentTeal: Color, val accentOrange: Color,
    val codePanel: Color, val divider: Color, val codeCommand: Color, val codeFlag: Color, val codeString: Color,
    val label: Color, val secondaryLabel: Color, val tertiaryLabel: Color,
    val systemBlue: Color, val systemGreen: Color, val systemOrange: Color, val systemTeal: Color, val systemRed: Color, val systemGray: Color,
    val searchFill: Color, val bar: Color, val separator: Color,
)

private val LightBk = BkColors(
    bgPrimary = Color(0xFFF5F5F5), bgSecondary = Color.White, black = Color.Black, white = Color.White,
    greyBase = Color(0xFF9E9E9E), greyDarken1 = Color(0xFF757575), greyDarken2 = Color(0xFF616161), greyDarken3 = Color(0xFF424242), greyDarken4 = Color(0xFF212121),
    greyLighten2 = Color(0xFFD6D6D6), greyLighten3 = Color(0xFFEEEEEE), greyLighten4 = Color(0xFFF5F5F5), greyLighten5 = Color(0xFFFAFAFA),
    blueBase = Color(0xFF2196F3), blueDarken1 = Color(0xFF1E88E5), blueDarken5 = Color(0xFF222ED8), lightBlueDarken3 = Color(0xFF0277BD),
    legacyButton = Color(0xFFF7F8FA), legacyBorder = Color(0xFFD6DAE1), accentBlue = Color(0xFFD6EAFF), accentTeal = Color(0xFFDEF2F6), accentOrange = Color(0xFFFFEED6),
    codePanel = Color(0xFFF1F3F6), divider = Color(0xFFD9DDE3), codeCommand = Color(0xFF8250DF), codeFlag = Color(0xFFCF222E), codeString = Color(0xFF0A3069),
    label = Color.Black, secondaryLabel = Color(0x993C3C43), tertiaryLabel = Color(0x4D3C3C43),
    systemBlue = Color(0xFF007AFF), systemGreen = Color(0xFF34C759), systemOrange = Color(0xFFFF9500), systemTeal = Color(0xFF30B0C7), systemRed = Color(0xFFFF3B30), systemGray = Color(0xFF8E8E93),
    searchFill = Color(0x1F767680), bar = Color(0xFFF9F9F9), separator = Color(0xFFC6C6C8),
)

private val DarkBk = BkColors(
    bgPrimary = Color.Black, bgSecondary = Color(0xFF161616), black = Color.White, white = Color.Black,
    greyBase = Color(0xFF616161), greyDarken1 = Color(0xFFBDBDBD), greyDarken2 = Color(0xFFE0E0E0), greyDarken3 = Color(0xFFEEEEEE), greyDarken4 = Color(0xFFF5F5F5),
    greyLighten2 = Color(0xFF5C5C5C), greyLighten3 = Color(0xFF424242), greyLighten4 = Color(0xFF212121), greyLighten5 = Color(0xFF1C1C1C),
    blueBase = Color(0xFF2196F3), blueDarken1 = Color(0xFF42A5F5), blueDarken5 = Color(0xFF462CE9), lightBlueDarken3 = Color(0xFF81D4FA),
    legacyButton = Color(0xFF1C1F24), legacyBorder = Color(0xFF35383E), accentBlue = Color(0xFF0F345C), accentTeal = Color(0xFF1E444B), accentOrange = Color(0xFF5C3C0F),
    codePanel = Color(0xFF202226), divider = Color(0xFF30333A), codeCommand = Color(0xFFD2A8FF), codeFlag = Color(0xFFFF7B72), codeString = Color(0xFFA5D6FF),
    label = Color.White, secondaryLabel = Color(0x99EBEBF5), tertiaryLabel = Color(0x4DEBEBF5),
    systemBlue = Color(0xFF0A84FF), systemGreen = Color(0xFF30D158), systemOrange = Color(0xFFFF9F0A), systemTeal = Color(0xFF40C8E0), systemRed = Color(0xFFFF453A), systemGray = Color(0xFF8E8E93),
    searchFill = Color(0x3D767680), bar = Color(0xFF161616), separator = Color(0xFF38383A),
)

private val LocalBk = staticCompositionLocalOf { LightBk }

/** The current iOS palette. */
val bk: BkColors
    @Composable @ReadOnlyComposable get() = LocalBk.current

@Composable
fun BarkTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkBk else LightBk
    // Material dialogs, menus and switches keep Android behaviour but take the iOS colors.
    val base = if (c === DarkBk) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.systemBlue, onPrimary = Color.White,
        background = c.bgPrimary, onBackground = c.greyDarken4,
        surface = c.bgSecondary, onSurface = c.greyDarken4, onSurfaceVariant = c.greyDarken1,
        surfaceContainer = c.bgSecondary, surfaceContainerHigh = c.bgSecondary, surfaceContainerHighest = c.greyLighten3, surfaceContainerLow = c.bgSecondary,
        outline = c.greyLighten2, outlineVariant = c.greyLighten3, error = c.systemRed,
        secondaryContainer = c.bgSecondary, onSecondaryContainer = c.label,
    )
    CompositionLocalProvider(LocalBk provides c) { MaterialTheme(colorScheme = scheme, content = content) }
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
