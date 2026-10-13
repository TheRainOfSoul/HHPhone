package com.tutpro.baresip.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Красный «Завершить»/«Отклонить» — насыщенный в обеих темах (error тёмной темы светлый, для текста). */
val HHEndRed = Color(0xFFC50F1F)

/** Разговор: имя, таймер, сетка 2×3, второй звонок, «Завершить» (спека 2026-10-13 ui). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HHInCall(call: Call, others: List<Call>, viewModel: ViewModel, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val dark = hhDark()
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val status by call.status
    val onHold by call.callOnHold
    val speaker by viewModel.isSpeakerOn.collectAsState()
    val micIcon by viewModel.micIcon.collectAsState()   // baresip шлёт «mic muted» и при смене из Telecom/Bluetooth
    val muted = micIcon == Icons.Default.MicOff
    var routeMenu by remember { mutableStateOf(false) }
    var keypad by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }   // "transfer" | "add"
    var dtmf by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val state = when {
        onHold -> stringResource(R.string.hh_on_hold)
        status == "connected" || status == "answered" -> call.startTime?.let { s -> ((now - s.timeInMillis) / 1000).let { "%d:%02d".format(it / 60, it % 60) } } ?: "0:00"
        else -> stringResource(R.string.hh_calling)
    }
    HHCallBackdrop(call) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth()) {
                HHCallTitle(call, state)
                IconButton(onClick = onHome, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hh_home), tint = fg)
                }
            }
            others.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                    .background(fg.copy(alpha = 0.08f), RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val incoming = o.status.value == "incoming"
                    Column(Modifier.weight(1f)) {
                        Text(hhName(o.peerUri), color = fg, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(stringResource(when {
                            incoming -> R.string.hh_incoming_call
                            o.status.value == "outgoing" -> R.string.hh_calling
                            else -> R.string.hh_on_hold }), color = fg.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                    }
                    val st = o.status.value
                    when {
                        incoming -> {
                            TextButton(onClick = { HHCalls.reject(o) }) { Text(stringResource(R.string.hh_decline), color = HHEndRed) }
                            TextButton(onClick = { call.hold(); HHCalls.answer(ctx, o) }) { Text(stringResource(R.string.hh_answer)) }
                        }
                        st == "connected" || st == "answered" -> {
                            TextButton(onClick = { HHCalls.swap(o) }) {
                                Icon(Icons.Default.SwapCalls, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.hh_swap))
                            }
                        }
                        else -> TextButton(onClick = { HHCalls.hangup(o) }) { Text(stringResource(R.string.hh_end_call), color = HHEndRed) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (keypad) {
                Text(dtmf, Modifier.fillMaxWidth(), color = fg, fontSize = 28.sp, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                HHKeypad(onKey = { dtmf += it; HHCalls.dtmf(call, it) }, light = fg)
                TextButton(onClick = { keypad = false }, Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.hh_hide_keypad), color = fg)
                }
            } else {
                val connected = status == "connected" || status == "answered"   // как baresip: answered — уже разговор
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    HHCallButton(Icons.AutoMirrored.Filled.PhoneForwarded, R.string.hh_transfer, false, fg,
                        enabled = connected && HHCalls.canTransfer(call)) { picker = "transfer" }
                    HHCallButton(Icons.Default.Pause, R.string.hh_hold, onHold, fg, enabled = connected) { HHCalls.toggleHold(call) }
                    HHCallButton(Icons.Default.MicOff, R.string.hh_mute, muted, fg) { HHCalls.toggleMute(viewModel) }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    HHCallButton(Icons.Default.AddIcCall, R.string.hh_add_call, false, fg, enabled = connected && others.isEmpty()) { picker = "add" }
                    Box(Modifier.weight(1f)) {
                        Row {
                            HHCallButton(Icons.AutoMirrored.Filled.VolumeUp, R.string.hh_speaker, speaker, fg) {
                                if (HHCalls.btAvailable(ctx)) routeMenu = true else HHCalls.toggleSpeaker()
                            }
                        }
                        val routes = listOf("phone" to R.string.hh_route_phone, "speaker" to R.string.hh_speaker, "bt" to R.string.hh_route_bt)
                        val labels = routes.map { stringResource(it.second) }
                        CustomElements.DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }, items = labels) { l ->
                            routeMenu = false
                            HHCalls.route(ctx, call, routes[labels.indexOf(l)].first)
                        }
                    }
                    HHCallButton(Icons.Default.Dialpad, R.string.hh_keypad, false, fg, enabled = connected) { keypad = true }
                }
            }
            Spacer(Modifier.height(28.dp))
            Surface(onClick = { HHCalls.hangup(call) }, shape = RoundedCornerShape(32.dp), color = HHEndRed,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 48.dp).height(60.dp)) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CallEnd, null, tint = Color.White); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.hh_end_call), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    picker?.let { mode ->
        ModalBottomSheet(onDismissRequest = { picker = null }) {
            HHNumberPicker(stringResource(if (mode == "transfer") R.string.hh_transfer_to else R.string.hh_add_to)) { n ->
                picker = null
                if (mode == "transfer") HHCalls.transfer(ctx, call, n) else HHCalls.add(ctx, viewModel, call, n)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HHCallButton(icon: ImageVector, label: Int, active: Boolean, fg: Color,
                                                                     enabled: Boolean = true, onClick: () -> Unit) {
    val a = if (enabled) 1f else 0.35f
    val state = stringResource(if (active) R.string.hh_on else R.string.hh_off)
    Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClick).semantics(mergeDescendants = true) { stateDescription = state }
        .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = if (active) fg else fg.copy(alpha = 0.10f), modifier = Modifier.size(60.dp)) {
            Icon(icon, null, Modifier.padding(16.dp), tint = (if (active) MaterialTheme.colorScheme.background else fg).copy(alpha = a))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(label), color = fg.copy(alpha = a), fontSize = 13.sp, maxLines = 1)
    }
}

/** Кому перевести / позвонить: номер вручную, коллеги, контакты. */
@Composable
private fun HHNumberPicker(title: String, onPick: (String) -> Unit) {
    val colleagues by HHColleagues.list.collectAsState()
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).padding(vertical = 8.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.hh_search)) }, shape = RoundedCornerShape(24.dp))
            if (q.isNotBlank() && q.none { it == ' ' }) TextButton(onClick = { onPick(q.trim()) }) { Text(stringResource(R.string.ok)) }
        }
        val ctx = LocalContext.current
        val locked = (ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        // поверх блокировки — только коллеги: контакты телефона не показываем без разблокировки
        val people = colleagues.map { it.name.ifBlank { it.number } to it.number } +
            (if (locked) emptyList() else BaresipService.contacts.flatMap { c -> c.uris().map { c.name() to HHUiLogic.peerKey(it.uri) } })
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            items(people.filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q) }.distinctBy { it.second }) { (name, n) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HHAvatar(name, null, 36.dp); Spacer(Modifier.width(12.dp))
                    Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}
