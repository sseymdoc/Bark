package me.fin.bark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import me.fin.bark.R
import me.fin.bark.data.Prefs

/** iOS PushQRCodeViewController: the current server's address+key, or the key alone. */
@Composable
fun QrScreen(nav: Nav) {
    val server = Prefs.currentServer
    var keyOnly by rememberSaveable { mutableStateOf(false) }
    val text = if (keyOnly) server.key else server.addressAndKey
    val bitmap = remember(text) {
        // zxing rejects empty content (no key yet)
        runCatching { BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)) }.getOrNull()
    }
    Page(stringResource(R.string.showQRCode), onBack = nav.pop) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SingleChoiceSegmentedButtonRow {
                listOf(R.string.qr_url to false, R.string.qr_key to true).forEachIndexed { i, (label, value) ->
                    SegmentedButton(selected = keyOnly == value, onClick = { keyOnly = value }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                        Text(stringResource(label))
                    }
                }
            }
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = text, modifier = Modifier.size(280.dp)) }
            Text(text)
        }
    }
}
