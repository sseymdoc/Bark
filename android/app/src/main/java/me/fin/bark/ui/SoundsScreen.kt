package me.fin.bark.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.fin.bark.R
import me.fin.bark.push.Level
import me.fin.bark.push.SoundPlayer
import java.io.File

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
            item { SectionTitle(stringResource(R.string.customSounds)) }
            items(custom, key = { "c:" + it.name }) { file ->
                SoundRow(file.nameWithoutExtension) {
                    IconButton(onClick = { file.delete(); version++ }) { Icon(Icons.Default.Delete, stringResource(R.string.removeMessage)) }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.uploadSound)) },
                    leadingContent = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.clickable { pick.launch(arrayOf("audio/*")) },
                )
            }
            item { SectionTitle(stringResource(R.string.defaultSounds)) }
            items(builtIn, key = { "b:$it" }) { SoundRow(it) }
        }
    }
}

@Composable
private fun SoundRow(name: String, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            Row {
                TextButton(onClick = { context.copyAndToast(name) }) { Text(stringResource(R.string.Copy2)) }
                extra()
            }
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
