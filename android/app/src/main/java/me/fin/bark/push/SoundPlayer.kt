package me.fin.bark.push

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import me.fin.bark.R
import java.io.File

/**
 * Notification channels are created without sound, because Android fixes a channel's sound at creation
 * and Bark picks the sound per push. This player covers built-in, custom, critical and 30s call sounds.
 */
object SoundPlayer {
    /** Keeps the current player reachable so it is not collected mid-sound. */
    private var current: MediaPlayer? = null

    fun customDir(context: Context) = File(context.filesDir, "sounds").apply { mkdirs() }

    fun builtIn(): List<String> = R.raw::class.java.fields.map { it.name }.sorted()

    fun custom(context: Context): List<File> = customDir(context).listFiles()?.sortedBy { it.name }.orEmpty()

    /** Custom sound first, then built-in, like iOS (Library/Sounds before the bundle). */
    fun uri(context: Context, name: String?): Uri? {
        if (name.isNullOrEmpty()) return null
        custom(context).firstOrNull { it.nameWithoutExtension == name }?.let { return Uri.fromFile(it) }
        val id = runCatching { R.raw::class.java.getField(name).getInt(null) }.getOrNull() ?: return null
        return Uri.parse("android.resource://${context.packageName}/$id")
    }

    /**
     * Respects notifications blocked for the whole app, ringer mode, Do Not Disturb and a channel the user turned down;
     * critical plays unless the app's notifications are blocked (then no notification shows to explain the sound).
     */
    fun shouldPlay(context: Context, level: Level): Boolean {
        if (level == Level.passive) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        if (level == Level.critical) return true
        val am = context.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        val channel = nm.getNotificationChannel(level.name) ?: return true
        if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        return nm.currentInterruptionFilter <= NotificationManager.INTERRUPTION_FILTER_ALL || channel.canBypassDnd()
    }

    /** Starts playback; a looping player must be released by the caller. */
    fun start(context: Context, name: String?, level: Level, volume: Float, loop: Boolean): MediaPlayer? {
        val fallback = if (loop) uri(context, "multiwayinvitation") else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val uri = uri(context, name) ?: fallback ?: return null
        val critical = level == Level.critical
        val player = MediaPlayer()
        return runCatching {
            player.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // Alarm usage still plays in silent mode, the closest Android has to iOS critical alerts.
                        .setUsage(if (critical) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = loop
                if (critical) setVolume(volume, volume)
                if (!loop) setOnCompletionListener { it.release(); if (current === it) current = null }
                prepare()
                start()
                if (!loop) {
                    current?.release()
                    current = this
                }
            }
        }.onFailure { player.release() }.getOrNull() // an unreadable sound must not leak a native player per push
    }
}
