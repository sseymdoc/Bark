package me.fin.bark.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import me.fin.bark.MainActivity
import me.fin.bark.R
import me.fin.bark.hyperos.HyperOs
import java.util.UUID

/** Builds and posts notifications (iOS Level/Badge/Image/Icon processors and the notification category). */
object Notifier {
    /** Extra holding the rendered body (markdown as plain text), used by "copy". */
    const val BODY_TEXT = "_bodytext"
    const val ID = "_id"
    private const val NOTIFY_ID = 1
    private const val SUMMARY_TAG = "summary:"

    fun createChannels(context: Context) {
        fun channel(level: Level, name: Int, importance: Int) = NotificationChannel(level.name, context.getString(name), importance).apply {
            setSound(null, null) // SoundPlayer plays the per-push sound
            enableVibration(level != Level.passive)
            // Only honoured once the user granted Do Not Disturb access; otherwise they can toggle it per channel.
            if (level == Level.critical || level == Level.timeSensitive) setBypassDnd(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                channel(Level.active, R.string.channel_active, NotificationManager.IMPORTANCE_HIGH),
                channel(Level.timeSensitive, R.string.channel_time_sensitive, NotificationManager.IMPORTANCE_HIGH),
                channel(Level.passive, R.string.channel_passive, NotificationManager.IMPORTANCE_LOW),
                channel(Level.critical, R.string.criticalAlert, NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun bundle(push: Push, bodyText: String?, id: String) = Bundle().apply {
        push.data.forEach { (k, v) -> putString(k, v) }
        putString(BODY_TEXT, bodyText)
        putString(ID, id)
    }

    fun dataOf(bundle: Bundle?): Map<String, String> =
        bundle?.keySet()?.filter { !it.startsWith("_") }?.associateWith { bundle.getString(it).orEmpty() }.orEmpty()

    fun show(context: Context, id: String, push: Push, bodyText: String?, level: Level, image: Bitmap?, icon: Bitmap?) {
        val extras = bundle(push, bodyText, id)
        // Android has no subtitle line: show it bold above the body.
        val text = SpannableStringBuilder().apply {
            push.subtitle?.let { append(SpannableString(it).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, 0) }) }
            if (!push.subtitle.isNullOrEmpty() && !bodyText.isNullOrEmpty()) append("\n")
            bodyText?.let { append(it) }
        }
        val tap = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NOTIFICATION).putExtras(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, level.name)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(push.title)
            .setContentText(text)
            .setStyle(
                if (image != null) NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(text)
                else NotificationCompat.BigTextStyle().bigText(text),
            )
            .setLargeIcon(icon)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setPriority(if (level == Level.passive) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                when (level) {
                    Level.critical -> NotificationCompat.CATEGORY_ALARM
                    Level.timeSensitive -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setContentIntent(tap)
            .setDeleteIntent(ActionReceiver.intent(context, ActionReceiver.DISMISS, id, extras))
            .addAction(0, context.getString(R.string.Copy2), ActionReceiver.intent(context, ActionReceiver.COPY, id, extras))
            .addAction(0, context.getString(R.string.muteGroup1Hour), ActionReceiver.intent(context, ActionReceiver.MUTE, id, extras))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        push.group?.let { builder.setGroup(it) }
        push.badge?.let { builder.setNumber(it) }
        push.ttlMillis?.let { builder.setTimeoutAfter(it) }
        if (level == Level.timeSensitive || level == Level.critical || push.isCall) {
            HyperOs.applyFocus(context, builder, push.title ?: context.getString(R.string.app_name), bodyText.orEmpty(), icon)
        }
        val notification = builder.build()
        push.badge?.let { HyperOs.setBadge(notification, it) }

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(id, NOTIFY_ID, notification)
        push.group?.let { group ->
            nm.notify(
                SUMMARY_TAG + group, NOTIFY_ID,
                NotificationCompat.Builder(context, level.name)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setSubText(group)
                    .setGroup(group)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun showError(context: Context, message: String?) {
        val title = context.getString(R.string.decryption_failed)
        show(context, UUID.randomUUID().toString(), Push(mapOf("title" to title, "body" to message.orEmpty())), message, Level.active, null, null)
    }

    fun cancel(context: Context, id: String) = context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFY_ID)
}
