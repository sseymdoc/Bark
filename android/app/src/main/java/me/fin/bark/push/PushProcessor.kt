package me.fin.bark.push

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.fin.bark.copyText
import me.fin.bark.data.Message
import me.fin.bark.data.Messages
import me.fin.bark.data.Prefs
import me.fin.bark.data.ServerApi
import java.util.UUID

/** Receives FCM data messages; the Android counterpart of the iOS notification service extension. */
class BarkMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs.fcmToken = token
        runBlocking { ServerApi.syncAllServers(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // FCM gives a high-priority message roughly 20s before the process may be frozen.
        runBlocking { withTimeoutOrNull(18_000) { PushProcessor.process(applicationContext, message.data) } }
    }
}

object Markdown {
    @Volatile
    private var markwon: Markwon? = null

    private fun builder(context: Context) = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())

    fun get(context: Context): Markwon = markwon ?: synchronized(this) {
        markwon ?: builder(context.applicationContext).build().also { markwon = it }
    }

    /** History list rendering, styled like iOS MarkdownParser: underlined links, tinted inline code, 20/18/16/14 headings, no quote bar. */
    fun styled(context: Context, link: Int, text: Int, codeBackground: Int): Markwon = builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(link).isLinkUnderlined(true)
                    .codeTextColor(link).codeBackgroundColor(codeBackground)
                    .codeBlockTextColor(text).codeBlockBackgroundColor(codeBackground)
                    .blockQuoteColor(0x01000000) // 0 would mean "default"; nearly transparent hides the bar
                    .headingBreakHeight(0)
                    .headingTextSizeMultipliers(floatArrayOf(20f / 14, 18f / 14, 16f / 14, 1f, 1f, 1f))
            }
        })
        .build()

    /** Notification text for a markdown body, as iOS MarkdownProcessor renders it. */
    fun plain(context: Context, markdown: String) = get(context).toMarkdown(markdown).toString().trim().replace(Regex("\n{2,}"), "\n")
}

/** Same order as iOS NotificationService: decrypt, markdown, level, badge, autoCopy, archive, mute, call, image, icon. */
object PushProcessor {
    suspend fun process(context: Context, raw: Map<String, String>) {
        // The same push may arrive over FCM and SSE; the server tags both copies with one `_msgid`.
        raw["_msgid"]?.let { if (!Prefs.markDelivered(it)) return }
        val push = try {
            Push.decrypt(Push.of(raw), Prefs.crypto)
        } catch (e: CryptoException) {
            Notifier.showError(context, e.message)
            return
        }
        // After decryption, so `{"id":…,"delete":1}` sent encrypted deletes too instead of overwriting the message.
        if (push.isDelete) {
            push.id?.let { id ->
                Notifier.cancel(context, id)
                Messages.delete(context) { delete(id) }
            }
            return
        }

        val now = System.currentTimeMillis()
        val id = push.id ?: UUID.randomUUID().toString()
        val bodyText = push.markdown?.let { Markdown.plain(context, it) } ?: push.body
        if (push.autoCopy) context.copyText(push.copy ?: bodyText.orEmpty())

        if (push.shouldArchive(Prefs.isArchive)) {
            Messages.save(
                context,
                listOf(
                    Message(
                        id = id,
                        title = push.title,
                        subtitle = push.subtitle,
                        body = push.markdown ?: push.body,
                        bodyType = if (push.markdown != null) "markdown" else null,
                        url = push.url,
                        image = push.image,
                        group = push.group,
                        createDate = now,
                        expireDate = push.expireDate(now),
                    ),
                ),
            )
        }

        val level = if (Prefs.isMuted(push.group.orEmpty())) Level.passive else push.level
        val (image, icon) = coroutineScope {
            val image = async { load(context, push.image, 1080) }
            val icon = async { load(context, push.icon, 256) }
            image.await() to icon.await()
        }
        Notifier.show(context, id, push, bodyText, level, image, icon)

        if (!SoundPlayer.shouldPlay(context, level)) return
        if (push.isCall && CallService.start(context, id, push.sound, level, push.volume)) return
        // The user or HyperOS may have given the channel a sound; the system already played it, so don't play a second one.
        if (level != Level.critical && context.getSystemService(NotificationManager::class.java).getNotificationChannel(level.name)?.sound != null) return
        SoundPlayer.start(context, push.sound, level, push.volume, loop = false)
    }

    /** Shares Coil's disk cache with the history list, so an image is downloaded once. */
    private suspend fun load(context: Context, url: String?, size: Int): Bitmap? {
        url ?: return null
        return withTimeoutOrNull(8_000) {
            val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
    }
}
