package com.tutpro.baresip.plus

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController

/** Какой экран показать: главный, входящий, разговор или прежний baresip (видео). */
@Composable
fun HHMain(navController: NavController, viewModel: ViewModel, onQuitClick: () -> Unit, classic: @Composable () -> Unit) {
    val calls by viewModel.calls.collectAsState()
    val open = calls.filter { it.status.value != "closed" }
    var homeDuringCall by remember { mutableStateOf(false) }
    val raw = HHUiLogic.screenFor(open.map { it.status.value }, open.any { it.videoCall })
    if (raw == "home" || raw == "incoming") homeDuringCall = false
    val inCall = raw == "call" || raw == "video"   // видео теперь тоже в HH-экране звонка
    val screen = if (inCall && homeDuringCall) "home" else raw
    val ctx = LocalContext.current
    val dark = hhDark()
    // строка состояния: на бордовой шапке и тёмных экранах звонка — светлые значки
    SideEffect {
        (ctx as? Activity)?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = screen != "home" && !dark }
    }
    // другие экраны (настройки, учётки) — как задаёт тема
    DisposableEffect(Unit) {
        onDispose { (ctx as? Activity)?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = !dark } }
    }
    when (screen) {
        "home" -> HHHome(navController, viewModel, onQuitClick, onReturnToCall = if (inCall) ({ homeDuringCall = false }) else null)
        "incoming" -> HHIncoming(open.first { it.status.value == "incoming" })
        "call", "video" -> {
            val active = open.firstOrNull { it.status.value != "incoming" && !it.callOnHold.value }
                ?: open.first { it.status.value != "incoming" }
            HHInCall(active, open.filter { it != active }, viewModel, onHome = { homeDuringCall = true })
        }
        else -> classic()
    }
}
