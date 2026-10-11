package com.tutpro.baresip.plus

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Вход QR: deep link hhphone://add и встроенный сканер кладут строку в pending. */
object HHProvisioning {
    val pending = MutableStateFlow<String?>(null)
    /** Одноразовые запросы сканера: без replay, чтобы пересоздание Activity не открывало камеру снова. */
    val scanRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Забирает hhphone:// из intent и гасит его action, чтобы upstream не принял ссылку за звонок. */
    fun offer(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "hhphone") return false
        // Гасим и action, и data: иначе пересоздание Activity (тема, язык) или запуск из «Недавних»
        // повторно применили бы старый QR. Из «Недавних» ссылку не принимаем вовсе.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) pending.value = data.toString()
        intent.action = null
        intent.data = null
        return true
    }

    fun requestScan() { scanRequests.tryEmit(Unit) }
}

data class HHPinPrompt(val onOk: () -> Unit)

object HHUi {
    val pinPrompt = MutableStateFlow<HHPinPrompt?>(null)
    val message = MutableStateFlow<String?>(null)
    val confirm = MutableStateFlow<Pair<String, () -> Unit>?>(null)
}

/** Наши диалоги поверх любого экрана; вызывается один раз в MainActivity после NavHost. */
@Composable
fun HHHost(navController: NavController) {
    val ctx = LocalContext.current
    val pending by HHProvisioning.pending.collectAsState()
    val message by HHUi.message.collectAsState()
    val confirm by HHUi.confirm.collectAsState()
    val pinPrompt by HHUi.pinPrompt.collectAsState()

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { HHProvisioning.pending.value = it }
    }
    LaunchedEffect(Unit) {
        HHProvisioning.scanRequests.collect {
            scanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                    .setOrientationLocked(false).setPrompt(ctx.getString(R.string.hh_add_by_qr))
            )
        }
    }

    // Конец сессии админа: убрать открытые им экраны (настройки, учётки) и его незавершённые диалоги
    val restricted by HHStaff.restricted.collectAsState()
    LaunchedEffect(restricted) {
        if (!restricted) return@LaunchedEffect
        HHUi.confirm.value = null
        navController.popBackStack("main", inclusive = false)
    }

    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        // Холодный старт по deep link: учётку можно создать только когда baresip готов (started()).
        // Без таймаута: на свежем телефоне сотрудник сначала отвечает на запросы разрешений.
        while (!BaresipService.isNativeReady) delay(200)
        HHProvisioning.pending.value = null
        startProvision(ctx, link)
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { HHUi.message.value = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { HHUi.message.value = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    confirm?.let { (text, onYes) ->
        AlertDialog(
            onDismissRequest = { HHUi.confirm.value = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { HHUi.confirm.value = null; onYes() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { HHUi.confirm.value = null }) { Text(stringResource(R.string.no)) } },
        )
    }
    pinPrompt?.let { prompt -> PinDialog(ctx, prompt) }
}

@Composable
private fun PinDialog(ctx: Context, prompt: HHPinPrompt) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { HHUi.pinPrompt.value = null },
        title = { Text(stringResource(R.string.hh_enter_pin)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.hh_pin)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error.isNotEmpty()) Text(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (r, s) = HHPin.check(HHStaff.state(), pin, System.currentTimeMillis())
                HHStaff.save(s)
                when (r) {
                    HHPinCheck.Ok -> { HHUi.pinPrompt.value = null; prompt.onOk() }
                    HHPinCheck.Wrong -> { error = ctx.getString(R.string.hh_wrong_pin); pin = "" }
                    is HHPinCheck.LockedOut -> { error = ctx.getString(R.string.hh_pin_locked_out, r.minutesLeft); pin = "" }
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { HHUi.pinPrompt.value = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun startProvision(ctx: Context, link: String) {
    val qr = when (val r = HHQr.parse(link)) {
        is HHQrResult.Ok -> r.qr
        HHQrResult.NotHHPhone -> { HHUi.message.value = ctx.getString(R.string.hh_qr_not_hhphone); return }
        HHQrResult.Newer -> { HHUi.message.value = ctx.getString(R.string.hh_qr_newer); return }
        HHQrResult.Invalid -> { HHUi.message.value = ctx.getString(R.string.hh_qr_invalid); return }
        is HHQrResult.Claim -> { HHUi.message.value = ctx.getString(R.string.hh_qr_newer); return }   // временно: обмен — Task 3
    }
    val proceed = { replaceIfNeeded(ctx, qr) }
    if (hhQrNeedsPin(HHStaff.state(), HHStaff.adminSession.value, qr)) HHUi.pinPrompt.value = HHPinPrompt(proceed)
    else proceed()
}

private fun replaceIfNeeded(ctx: Context, qr: HHAccountQr) {
    val aor = HHQr.aor(qr)
    val existing = UserAgent.ofAor(aor)
    if (existing == null) { addAccount(ctx, qr); return }
    HHUi.confirm.value = ctx.getString(R.string.hh_replace_account, aor.removePrefix("sip:")) to {
        existing.remove()
        Api.ua_destroy(existing.uap)
        addAccount(ctx, qr)
    }
}

private fun addAccount(ctx: Context, qr: HHAccountQr) {
    val uap = UserAgent.uaAlloc(HHQr.accountLine(qr))
    if (uap == 0L) { HHUi.message.value = ctx.getString(R.string.account_allocation_failure); return }
    val ua = UserAgent(uap)
    ua.add()
    ua.account.checkOrigin = true
    Account.saveAccounts()
    ua.reRegister()
    HHStaff.save(hhApplyQrLock(HHStaff.state(), qr))
    Toast.makeText(ctx, ctx.getString(R.string.hh_account_added, HHQr.aor(qr).removePrefix("sip:")), Toast.LENGTH_LONG).show()
}

/** Пункты меню HHPhone: прячет запрещённые в режиме сотрудника и добавляет наши. */
@Composable
fun hhMenu(items: List<MenuItem>, hidden: Set<String>): List<MenuItem> {
    val restricted by HHStaff.restricted.collectAsState()
    val admin by HHStaff.adminSession.collectAsState()
    val addByQr = MenuItem(stringResource(R.string.hh_add_by_qr), Icons.Outlined.QrCodeScanner)
    val adminItem = MenuItem(stringResource(R.string.hh_admin), Icons.Outlined.AdminPanelSettings)
    val unlockItem = MenuItem(stringResource(R.string.hh_unlock_device), Icons.Outlined.LockOpen)
    return when {
        restricted -> listOf(addByQr) + items.filter { it.text !in hidden } + adminItem
        admin -> listOf(addByQr) + items + unlockItem
        else -> listOf(addByQr) + items
    }
}

fun hhMenuClick(ctx: Context, item: String): Boolean = when (item) {
    ctx.getString(R.string.hh_add_by_qr) -> { HHProvisioning.requestScan(); true }
    ctx.getString(R.string.hh_admin) -> { HHUi.pinPrompt.value = HHPinPrompt { HHStaff.setAdminSession(true) }; true }
    ctx.getString(R.string.hh_unlock_device) -> {
        HHStaff.save(HHLockState()); HHStaff.setAdminSession(false)
        Toast.makeText(ctx, ctx.getString(R.string.hh_lock_removed), Toast.LENGTH_SHORT).show(); true
    }
    else -> false
}
