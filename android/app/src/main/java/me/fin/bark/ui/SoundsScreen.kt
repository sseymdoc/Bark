package me.fin.bark.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.fin.bark.R
import me.fin.bark.push.Level
import me.fin.bark.push.SoundPlayer
import java.io.File
import java.math.BigDecimal
import java.math.MathContext

/** Notification sounds: tap to preview, copy the name to use as the `sound` parameter. */
@Composable
fun SoundsScreen(nav: Nav) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bumped to re-list custom sounds
    val custom = remember(version) { SoundPlayer.custom(context) }
    val builtIn = remember { SoundPlayer.builtIn() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && importSound(context, uri)) version++
    }

    Page(stringResource(R.string.notificationSound), onBack = nav.pop) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader(stringResource(R.string.customSounds)) }
            items(custom, key = { it.path }) { file ->
                SwipeToDelete({ file.delete(); version++; true }) { SoundRow(file.nameWithoutExtension) }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.uploadSound), color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_sym_music_note), null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { pick.launch(arrayOf("audio/*")) },
                )
            }
            item { SectionHeader(stringResource(R.string.defaultSounds)) }
            items(builtIn) { name -> SoundRow(name) }
        }
    }
}

/** Name, duration and a copy button; tapping the row previews the sound. */
@Composable
private fun SoundRow(name: String) {
    val context = LocalContext.current
    val seconds by produceState<Double?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, SoundPlayer.uri(context, name))
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
                    } finally { release() }
                }
            }.getOrNull()
        }
    }
    ListItem(
        headlineContent = { Text(name) },
        // iOS: String(format: "%.2g second(s)") — two significant digits, trailing zeros dropped.
        supportingContent = { Text(seconds?.let { BigDecimal(it).round(MathContext(2)).stripTrailingZeros().toPlainString() + " second(s)" } ?: " ") },
        trailingContent = {
            IconButton(onClick = { context.copyAndToast(name) }) { Icon(painterResource(R.drawable.ic_file_copy), stringResource(R.string.Copy2)) }
        },
        modifier = Modifier.clickable { SoundPlayer.start(context, name, Level.active, 1f, loop = false) },
    )
}

/** Copies a picked audio file into the custom sounds folder, keeping a filesystem-safe version of its name. */
private fun importSound(context: Context, uri: Uri): Boolean = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "sound"
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        File(SoundPlayer.customDir(context), safe).outputStream().use { input.copyTo(it) }
    }
}.isSuccess
