package com.tutpro.baresip.plus

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

fun hhQrFields(aor: String, authUser: String, authPass: String, outbound: String, mediaEnc: String, nickName: String): HHAccountQr? {
    if (authPass.isEmpty() || authPass == NO_AUTH_PASS) return null
    val uri = aor.removePrefix("sip:")
    val user = uri.substringBefore('@')
    val hostPart = uri.substringAfter('@')
    val domain = hostPart.substringBefore(';')
    val transport = Regex("""transport=(\w+)""").find(hostPart)?.groupValues?.get(1) ?: "udp"
    return HHAccountQr(
        name = nickName, user = user, pass = authPass, domain = domain,
        authUser = if (authUser == user) "" else authUser,
        proxy = outbound, transport = transport, mediaEnc = mediaEnc,
    )
}

fun hhQrFromAccount(acc: Account): HHAccountQr? =
    hhQrFields(acc.aor, acc.authUser, BaresipService.aorPasswords[acc.aor] ?: acc.authPass,
        acc.outbound.firstOrNull() ?: "", acc.mediaEnc, acc.nickName)

fun hhQrMatrix(text: String, size: Int): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))   // стандартная тихая зона 4 модуля: камеры телефонов читают надёжнее

private fun BitMatrix.toBitmap(): Bitmap {
    val px = IntArray(width * height) { i -> if (get(i % width, i / width)) 0xFF000000.toInt() else -1 }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/** Кнопка в верхней панели экрана учётки; в режиме сотрудника не показывается. */
@Composable
fun HHShowQrButton(aor: String) {
    val restricted by HHStaff.restricted.collectAsState()
    if (restricted) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.hh_show_qr)) }
    if (!open) return
    val base = Account.ofAor(aor)?.let { hhQrFromAccount(it) }
    if (base == null) {
        AlertDialog(onDismissRequest = { open = false }, text = { Text(stringResource(R.string.hh_no_password)) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } })
        return
    }
    var lock by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    val pinOk = !lock || pin.length in 4..8
    val bitmap = remember(lock, pin) {
        if (pinOk) hhQrMatrix(HHQr.encode(base.copy(lock = lock, pin = if (lock) pin else "")), 720).toBitmap() else null
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.hh_show_qr)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hh_qr_lock), Modifier.weight(1f))
                    Switch(checked = lock, onCheckedChange = { lock = it })
                }
                if (lock) OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.hh_pin)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                else Text(stringResource(R.string.hh_need_pin_digits))
                Text(stringResource(R.string.hh_qr_warning))
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } },
    )
}
