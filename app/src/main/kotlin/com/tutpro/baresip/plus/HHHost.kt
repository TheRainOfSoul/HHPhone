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
import androidx.compose.material.icons.outlined.SystemUpdate
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Вход QR: deep link hhphone://add и встроенный сканер кладут строку в pending. */
object HHProvisioning {
    val scope = MainScope()
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

    fun checkUpdate(ctx: Context, manual: Boolean) {
        val prefs = ctx.getSharedPreferences("hh_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && !HHUpdate.due(prefs.getLong("last", 0), now)) return
        prefs.edit().putLong("last", now).apply()
        scope.launch {
            val latest = withContext(Dispatchers.IO) { HHUpdate.check() }
            when {
                latest != null && latest.newerThan(BuildConfig.VERSION_CODE) -> HHUi.update.value = latest
                manual && latest == null -> HHUi.message.value = ctx.getString(R.string.hh_update_no_connection)
                manual -> HHUi.message.value = ctx.getString(R.string.hh_update_none)
            }
        }
    }

    fun installUpdate(ctx: Context) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            HHUi.message.value = ctx.getString(R.string.hh_update_allow)
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return   // повторный экран настроек сам не открываем: пользователь снова нажмёт «Обновить»
        }
        val apk = java.io.File(ctx.filesDir, "update/HHPhone.apk")
        HHUi.downloading.value = 0
        scope.launch {
            val ok = withContext(Dispatchers.IO) { HHUpdate.download(apk) { p -> HHUi.downloading.value = p } }
            HHUi.downloading.value = null
            if (ok) ctx.startActivity(HHUpdate.installIntent(ctx, apk)) else HHUi.message.value = ctx.getString(R.string.hh_update_failed)
        }
    }
}

data class HHPinPrompt(val onOk: () -> Unit)

object HHUi {
    val pinPrompt = MutableStateFlow<HHPinPrompt?>(null)
    val message = MutableStateFlow<String?>(null)
    val confirm = MutableStateFlow<Pair<String, () -> Unit>?>(null)
    val update = MutableStateFlow<HHLatest?>(null)
    val downloading = MutableStateFlow<Int?>(null)
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

    // автопроверка при запуске и возврате в приложение (не чаще раза в 6 ч)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) HHProvisioning.checkUpdate(ctx, manual = false) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val update by HHUi.update.collectAsState()
    val downloading by HHUi.downloading.collectAsState()
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { HHUi.update.value = null },
            title = { Text(stringResource(R.string.hh_update_available, u.versionName)) },
            text = { Text(if (downloading != null) stringResource(R.string.hh_update_downloading, downloading!!) else u.notes) },
            confirmButton = { TextButton(enabled = downloading == null, onClick = { HHProvisioning.installUpdate(ctx) }) { Text(stringResource(R.string.hh_update_now)) } },
            dismissButton = { TextButton(onClick = { HHUi.update.value = null }) { Text(stringResource(R.string.hh_update_later)) } },
        )
    }
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
        is HHQrResult.Claim -> {
            val go = { claim(ctx, r) }
            // спека: на заблокированном телефоне — сначала PIN, потом обмен (иначе отмена PIN сожгла бы токен)
            if (HHStaff.state().locked && !HHStaff.adminSession.value) HHUi.pinPrompt.value = HHPinPrompt(go) else go()
            return
        }
    }
    val proceed = { replaceIfNeeded(ctx, qr, R.string.hh_replace_account) { hhApplyQrLock(HHStaff.state(), qr) } }
    if (hhQrNeedsPin(HHStaff.state(), HHStaff.adminSession.value, qr)) HHUi.pinPrompt.value = HHPinPrompt(proceed)
    else proceed()
}

/** QR из панели HHPBX: один раз забрать учётку по токену (сеть — в фоне). */
private fun claim(ctx: Context, c: HHQrResult.Claim) {
    Toast.makeText(ctx, ctx.getString(R.string.hh_claiming), Toast.LENGTH_SHORT).show()
    HHProvisioning.scope.launch {
        when (val outcome = withContext(Dispatchers.IO) { HHClaim.exchange(c) }) {
            is HHClaimOutcome.Ok -> replaceIfNeeded(ctx, outcome.answer.account, R.string.hh_replace_account_spent) { hhApplyServerLock(outcome.answer) }
            HHClaimOutcome.NoConnection -> HHUi.message.value = ctx.getString(R.string.hh_claim_no_connection)
            HHClaimOutcome.BadCert -> HHUi.message.value = ctx.getString(R.string.hh_claim_bad_cert)
            HHClaimOutcome.Used -> HHUi.message.value = ctx.getString(R.string.hh_claim_used)
            HHClaimOutcome.BadData -> HHUi.message.value = ctx.getString(R.string.hh_claim_bad_data)
        }
    }
}

private fun replaceIfNeeded(ctx: Context, qr: HHAccountQr, question: Int, newLock: () -> HHLockState) {
    val aor = HHQr.aor(qr)
    val existing = UserAgent.ofAor(aor)
    if (existing == null) { addAccount(ctx, qr, newLock); return }
    HHUi.confirm.value = ctx.getString(question, aor.removePrefix("sip:")) to {
        existing.remove()
        Api.ua_destroy(existing.uap)
        addAccount(ctx, qr, newLock)
    }
}

private fun addAccount(ctx: Context, qr: HHAccountQr, newLock: () -> HHLockState) {
    val uap = UserAgent.uaAlloc(HHQr.accountLine(qr))
    if (uap == 0L) { HHUi.message.value = ctx.getString(R.string.account_allocation_failure); return }
    val ua = UserAgent(uap)
    ua.add()
    ua.account.checkOrigin = true
    Account.saveAccounts()
    ua.reRegister()
    HHStaff.save(newLock())
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
    val updates = MenuItem(stringResource(R.string.hh_check_updates), Icons.Outlined.SystemUpdate)
    return when {
        restricted -> listOf(addByQr) + items.filter { it.text !in hidden } + updates + adminItem
        admin -> listOf(addByQr) + items + updates + unlockItem
        else -> listOf(addByQr) + items + updates
    }
}

fun hhMenuClick(ctx: Context, item: String): Boolean = when (item) {
    ctx.getString(R.string.hh_add_by_qr) -> { HHProvisioning.requestScan(); true }
    ctx.getString(R.string.hh_check_updates) -> { HHProvisioning.checkUpdate(ctx, manual = true); true }
    ctx.getString(R.string.hh_admin) -> { HHUi.pinPrompt.value = HHPinPrompt { HHStaff.setAdminSession(true) }; true }
    ctx.getString(R.string.hh_unlock_device) -> {
        HHStaff.save(HHLockState()); HHStaff.setAdminSession(false)
        Toast.makeText(ctx, ctx.getString(R.string.hh_lock_removed), Toast.LENGTH_SHORT).show(); true
    }
    else -> false
}
