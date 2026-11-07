package me.fin.bark.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import me.fin.bark.MainActivity
import me.fin.bark.R
import me.fin.bark.data.Message
import me.fin.bark.data.Messages
import me.fin.bark.ui.Dates

/** iOS "Recent Messages" widget: the three newest messages; tapping opens history. */
class RecentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = Messages.dao(context).list(null, "", 3)
        provideContent {
            val messages by flow.collectAsState(emptyList())
            GlanceTheme { Content(messages) }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { RecentWidget().updateAll(context) }
        }
    }
}

class RecentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RecentWidget()
}

@Composable
private fun Content(messages: List<Message>) {
    val context = LocalContext.current
    val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("bark://history"))
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity(open)),
    ) {
        if (messages.isEmpty()) {
            Text(context.getString(R.string.widget_empty_title), style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold))
            Text(context.getString(R.string.widget_empty_subtitle), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
        messages.forEach { m ->
            Row(GlanceModifier.fillMaxWidth()) {
                Text(
                    m.group ?: "Bark",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(Dates.ago(context, m.createDate), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
            }
            listOfNotNull(m.title, m.subtitle).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            }
            m.body?.let { Text(it, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp)) }
            Spacer(GlanceModifier.height(8.dp))
        }
    }
}
