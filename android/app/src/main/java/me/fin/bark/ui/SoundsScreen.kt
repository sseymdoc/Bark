package me.fin.bark.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.fin.bark.R
import me.fin.bark.push.Level
import me.fin.bark.push.SoundPlayer
import java.io.File
import java.math.BigDecimal
import java.math.MathContext

/** iOS SoundsViewController: tap to preview, copy the name to use as the `sound` parameter. */
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
            item {
                InsetGroup(
                    header = { SoundsHeader(stringResource(R.string.customSounds)) },
                    rows = custom.map<File, @Composable () -> Unit> { file ->
                        { SwipeToDelete({ file.delete(); version++; true }) { SoundRow(file.nameWithoutExtension) } }
                    } + listOf<@Composable () -> Unit>({ UploadRow { pick.launch(arrayOf("audio/*")) } }),
                )
            }
            item {
                InsetGroup(header = { SoundsHeader(stringResource(R.string.defaultSounds)) }, rows = builtIn.map { name -> { SoundRow(name) } })
            }
        }
    }
}

@Composable
private fun SoundsHeader(text: String) {
    Text(text, fontSize = 14.sp, color = bk.greyDarken3, modifier = Modifier.height(40.dp).padding(start = 12.dp).wrapContentHeight())
}

/** iOS SoundCell: name (14sp), duration (12sp) and a copy button; tapping the row previews the sound. */
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
    Row(
        Modifier.fillMaxWidth().background(bk.bgSecondary).clickable { SoundPlayer.start(context, name, Level.active, 1f, loop = false) }
            .padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, color = bk.greyDarken4)
            // iOS: String(format: "%.2g second(s)") — two significant digits, trailing zeros dropped.
            Text(
                seconds?.let { BigDecimal(it).round(MathContext(2)).stripTrailingZeros().toPlainString() + " second(s)" } ?: " ",
                fontSize = 12.sp, color = bk.greyDarken1, modifier = Modifier.padding(top = 5.dp),
            )
        }
        BarButton(painterResource(R.drawable.ic_file_copy), stringResource(R.string.Copy2), { context.copyAndToast(name) }, tint = bk.greyBase)
    }
}

/** iOS AddSoundCell: centered "Upload Sound" in light blue with a music note. */
@Composable
private fun UploadRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_sym_music_note), null, tint = bk.lightBlueDarken3, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.uploadSound), fontSize = 16.sp, color = bk.lightBlueDarken3, modifier = Modifier.padding(start = 4.dp))
    }
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
