package me.fin.bark.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import me.fin.bark.R
import me.fin.bark.copyText
import me.fin.bark.data.Prefs
import me.fin.bark.toast

/** Notification buttons: copy, mute the group for an hour, and stopping the call sound. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A dismissal only silences its own call: the system also fires it when other notifications expire.
        if (intent.action != DISMISS || intent.getStringExtra(Notifier.ID) == CallService.ringingId) CallService.stop(context)
        val push = Push(Notifier.dataOf(intent.extras))
        when (intent.action) {
            COPY -> {
                val text = push.copy ?: listOfNotNull(push.title, push.subtitle, intent.getStringExtra(Notifier.BODY_TEXT), push.url)
                    .filter { it.isNotBlank() }.joinToString("\n").trim()
                context.copyText(text)
                context.toast(context.getString(R.string.Copy))
            }
            MUTE -> {
                val group = push.group.orEmpty()
                Prefs.muteGroup(group, System.currentTimeMillis() + 3_600_000)
                context.toast(context.getString(R.string.groupMuted, group.ifEmpty { context.getString(R.string.default_) }))
            }
        }
    }

    companion object {
        const val COPY = "copy"
        const val MUTE = "mute"
        const val DISMISS = "dismiss"
        private const val STOP = "stop"

        fun intent(context: Context, action: String, id: String, extras: Bundle): PendingIntent = PendingIntent.getBroadcast(
            context, (action + id).hashCode(),
            Intent(context, ActionReceiver::class.java).setAction(action).putExtras(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun stopIntent(context: Context) = intent(context, STOP, "", Bundle())
    }
}
