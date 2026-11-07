package me.fin.bark.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.fin.bark.R

/** `call=1`: repeats the sound for 30 seconds (iOS CallProcessor). Stops when the push is tapped, dismissed or acted on. */
class CallService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, Level.passive.name)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.ringtone))
            .setSilent(true)
            .addAction(0, getString(R.string.stop_ringing), ActionReceiver.stopIntent(this))
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        player?.release()
        ringingId = intent?.getStringExtra("id")
        val level =runCatching { Level.valueOf(intent?.getStringExtra("level")!!) }.getOrDefault(Level.active)
        player = SoundPlayer.start(this, intent?.getStringExtra("sound"), level, intent?.getFloatExtra("volume", 0.5f) ?: 0.5f, loop = true)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 30_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        ringingId = null
    }

    companion object {
        private const val NOTIFICATION_ID = 30

        /** Id of the push that is ringing, so dismissing some other notification does not stop it. */
        @Volatile var ringingId: String? = null
            private set

        /** Returns false when Android refused to start it (e.g. the FCM message was deprioritized). */
        fun start(context: Context, id: String, sound: String?, level: Level, volume: Float): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra("id", id).putExtra("sound", sound).putExtra("level", level.name).putExtra("volume", volume),
            )
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
