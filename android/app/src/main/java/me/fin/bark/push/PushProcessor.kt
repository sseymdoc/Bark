package me.fin.bark.push

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.noties.markwon.Markwon
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

    fun get(context: Context): Markwon = markwon ?: synchronized(this) {
        markwon ?: Markwon.builder(context.applicationContext)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .build().also { markwon = it }
    }

    /** Notification text for a markdown body, as iOS MarkdownProcessor renders it. */
    fun plain(context: Context, markdown: String) = get(context).toMarkdown(markdown).toString().trim().replace(Regex("\n{2,}"), "\n")
}

/** Same order as iOS NotificationService: decrypt, markdown, level, badge, autoCopy, archive, mute, call, image, icon. */
object PushProcessor {
    suspend fun process(context: Context, raw: Map<String, String>) {
        var push = Push.of(raw)
        if (push.isDelete) {
            push.id?.let { id ->
                Notifier.cancel(context, id)
                Messages.delete(context) { delete(id) }
            }
            return
        }
        push = try {
            Push.decrypt(push, Prefs.crypto)
        } catch (e: CryptoException) {
            Notifier.showError(context, e.message)
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
        if (push.isCall && CallService.start(context, push.sound, level, push.volume)) return
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
