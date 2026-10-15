package com.tutpro.baresip.plus

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Тёмная ли тема HHPhone сейчас (Preferences: Как на телефоне / Светлая / Тёмная). */
@Composable
fun hhDark(): Boolean = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }

/** Шапка звонка: фото/буква, имя, номер, линия. Светлая — круг; тёмная — фото на весь экран (спека: экраны следуют теме). */
@Composable
fun HHCallBackdrop(call: Call, content: @Composable () -> Unit) {
    val dark = hhDark()
    val photo = (Contact.findContact(call.peerUri) as? Contact.AndroidContact)?.thumbnailUri
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (dark) {
            if (photo != null)
                AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7A0C2A), Color(0xFF3D0717), Color(0xFF141414)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0xCC000000)))))
        }
        content()
    }
}

@Composable
fun HHCallTitle(call: Call, status: String) {
    val dark = hhDark()
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val colleagues by HHColleagues.list.collectAsState()
    val name = remember(colleagues, call.peerUri) { hhName(call.peerUri) }
    val number = HHUiLogic.peerKey(call.peerUri)
    val line = if (call.ua.account.isMobile) "SIM" else "HH " + HHPush.userDomain(call.ua.account.aor).first
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(status, color = fg.copy(alpha = 0.8f), fontSize = 15.sp)
        if (!dark) {
            Spacer(Modifier.height(20.dp))
            HHAvatar(name, call.peerUri, 140.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(name, color = fg, fontSize = 32.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
        if (name != number) Text(number, color = fg.copy(alpha = 0.8f), fontSize = 18.sp)
        Text(stringResource(R.string.hh_via, line), color = fg.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}

/** Входящий: ползунок — влево «Отклонить», вправо «Ответить». */
@Composable
fun HHIncoming(call: Call) {
    val ctx = LocalContext.current
    HHCallBackdrop(call) {
        Column(Modifier.fillMaxSize()) {
            HHCallTitle(call, stringResource(R.string.hh_incoming_call))
            Spacer(Modifier.weight(1f))
            HHAnswerSlider(onAnswer = { HHCalls.answer(ctx, call) }, onReject = { HHCalls.reject(call) })
            // выше низа экрана: туда голосовой ассистент (Gemini «быстрые фразы», Bixby) выводит свои подсказки
            Spacer(Modifier.height(200.dp))
        }
    }
}

@Composable
private fun HHAnswerSlider(onAnswer: () -> Unit, onReject: () -> Unit) {
    val red = HHEndRed
    val green = colorResource(R.color.colorTrafficGreen)
    val dark = hhDark()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var off by remember { mutableFloatStateOf(0f) }          // смещение кружка, пиксели
    var dragging by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var armed by remember { mutableIntStateOf(0) }           // −1/1 — за порогом (лёгкая вибрация при пересечении)
    val answer = stringResource(R.string.hh_answer)
    val decline = stringResource(R.string.hh_decline)
    // в покое кружок слегка покачивается — подсказка «тяни»
    val sway by androidx.compose.animation.core.rememberInfiniteTransition(label = "sway").animateFloat(-1f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "sway")
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).height(72.dp)
        .semantics { customActions = listOf(CustomAccessibilityAction(answer) { onAnswer(); true }, CustomAccessibilityAction(decline) { onReject(); true }) }) {
        val half = with(LocalDensity.current) { (maxWidth / 2 - 36.dp).toPx() }
        val swayPx = with(LocalDensity.current) { 6.dp.toPx() }
        val p = (off / half).coerceIn(-1f, 1f)
        // тянуть можно с любого места полосы, кружок идёт за пальцем без задержки
        val drag = Modifier.draggable(rememberDraggableState { d ->
                if (done) return@rememberDraggableState
                off = (off + d).coerceIn(-half, half)
                val a = when { off > half * 0.6f -> 1; off < -half * 0.6f -> -1; else -> 0 }
                if (a != armed) { armed = a; if (a != 0) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
            }, Orientation.Horizontal, startDragImmediately = true,
            onDragStarted = { dragging = true },
            onDragStopped = { v ->
                dragging = false
                if (done) return@draggable
                val q = off / half
                // порог — 60 % пути или быстрый взмах в ту же сторону
                val side = when { q > 0.6f || (q > 0.2f && v > 1500f) -> 1; q < -0.6f || (q < -0.2f && v < -1500f) -> -1; else -> 0 }
                if (side != 0) {
                    done = true
                    androidx.compose.animation.core.animate(off, side * half, animationSpec = androidx.compose.animation.core.tween(120)) { x, _ -> off = x }
                    if (side > 0) onAnswer() else onReject()
                } else
                    androidx.compose.animation.core.animate(off, 0f, initialVelocity = v,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.65f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { x, _ -> off = x }
            })
        Row(Modifier.fillMaxSize().then(drag).background(Brush.horizontalGradient(listOf(red, red.copy(alpha = 0.25f), green.copy(alpha = 0.25f), green)), RoundedCornerShape(36.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CallEnd, decline, Modifier.padding(start = 20.dp), tint = Color.White.copy(alpha = 1f - maxOf(p, 0f)))
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Call, answer, Modifier.padding(end = 20.dp), tint = Color.White.copy(alpha = 1f + minOf(p, 0f)))
        }
        val rest = if (!dragging && !done && off == 0f) sway * swayPx else 0f
        val base = if (dark) Color.White else MaterialTheme.colorScheme.surface
        val knob = androidx.compose.ui.graphics.lerp(base, if (p >= 0) green else red, Math.abs(p))
        Box(Modifier.align(Alignment.Center).offset { IntOffset((off + rest).roundToInt(), 0) }.size(72.dp)
            .background(knob, CircleShape).then(drag),
            contentAlignment = Alignment.Center) {
            // трубка: поворачивается к «положить» влево, белеет на цвете
            Icon(Icons.Default.Call, null, Modifier.size(32.dp).graphicsLayer { rotationZ = minOf(p, 0f) * -135f },
                tint = androidx.compose.ui.graphics.lerp(Color(0xFF8A8A8A), Color.White, Math.abs(p)))
        }
    }
}
