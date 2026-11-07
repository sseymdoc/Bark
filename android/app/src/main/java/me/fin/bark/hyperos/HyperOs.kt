package me.fin.bark.hyperos

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import me.fin.bark.R
import org.json.JSONObject

/** Xiaomi HyperOS specifics: detection, settings shortcuts, focus notifications and launcher badges. */
object HyperOs {
    @SuppressLint("PrivateApi")
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /** e.g. "OS2.0"; empty on MIUI and other systems. */
    val version: String by lazy { prop("ro.mi.os.version.name") }

    val isXiaomi: Boolean by lazy {
        version.isNotEmpty() || Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
    }

    /** 0 = unsupported, 1/2/3 = OS1/OS2/OS3 focus notification protocol (3 adds Super Island). */
    fun focusProtocol(context: Context): Int =
        runCatching { Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) }.getOrDefault(0)

    /**
     * Adds HyperOS focus notification / Super Island data. The system only shows it for apps Xiaomi has
     * allow-listed; everyone else gets the normal notification, so this is safe to send always.
     * shortcut: minimal text template built from dev.mi.com field names, not verified on an allow-listed build; check the
     * official template library once Xiaomi approves the app.
     */
    fun applyFocus(context: Context, builder: NotificationCompat.Builder, title: String, content: String, icon: Bitmap?) {
        val protocol = focusProtocol(context)
        if (protocol <= 0) return
        val pic = "miui.focus.pic_app"
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "bark")
            .put("enableFloat", true)
            .put("updatable", true)
            .put("ticker", title)
            .put("tickerPic", pic)
            .put("aodTitle", title)
            .put("baseInfo", JSONObject().put("title", title).put("content", content).put("type", 2))
        if (protocol >= 3) {
            val picInfo = JSONObject().put("type", 1).put("pic", pic)
            param.put(
                "param_island",
                JSONObject()
                    .put("islandProperty", 1)
                    .put(
                        "bigIslandArea",
                        JSONObject().put(
                            "imageTextInfoLeft",
                            JSONObject().put("type", 1).put("picInfo", picInfo)
                                .put("textInfo", JSONObject().put("title", title).put("content", content)),
                        ),
                    )
                    .put("smallIslandArea", JSONObject().put("picInfo", picInfo))
                    .put("shareData", JSONObject().put("title", title).put("content", content)),
            )
        }
        val pics = Bundle().apply {
            putParcelable(pic, icon?.let { Icon.createWithBitmap(it) } ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
        }
        builder.addExtras(
            Bundle().apply {
                putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
                putBundle("miui.focus.pics", pics)
            },
        )
    }

    /** MIUI/HyperOS launcher badge; other launchers read Notification.number. */
    fun setBadge(notification: Notification, count: Int) {
        runCatching {
            val extra = notification.javaClass.getDeclaredField("extraNotification").get(notification) ?: return
            extra.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType).invoke(extra, count)
        }
    }

    /** GMS is disabled on China ROMs until the user enables "Google basic services". */
    fun googleServicesEnabled(context: Context): Boolean =
        runCatching { context.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled }.getOrDefault(false)

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Opens the first intent that resolves; Xiaomi screens move between versions. */
    private fun open(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun openAutostart(context: Context) = open(
        context,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetails(context),
    )

    @SuppressLint("BatteryLife")
    fun openBattery(context: Context) = open(
        context,
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.getString(R.string.app_name)),
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        appDetails(context),
    )

    fun openNotificationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context),
    )

    fun openDndAccess(context: Context) = open(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), appDetails(context))
}
