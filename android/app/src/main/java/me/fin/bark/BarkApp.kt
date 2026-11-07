package me.fin.bark

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import me.fin.bark.data.Prefs
import me.fin.bark.push.Notifier

class BarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifier.createChannels(this)
    }
}

fun Context.copyText(text: String) =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bark", text))

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
