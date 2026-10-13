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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val name = hhName(call.peerUri)
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
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun HHAnswerSlider(onAnswer: () -> Unit, onReject: () -> Unit) {
    val red = HHEndRed
    val green = colorResource(R.color.colorTrafficGreen)
    val dark = hhDark()
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    val answer = stringResource(R.string.hh_answer)
    val decline = stringResource(R.string.hh_decline)
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).height(72.dp)
        .semantics { customActions = listOf(CustomAccessibilityAction(answer) { onAnswer(); true }, CustomAccessibilityAction(decline) { onReject(); true }) }) {
        val half = with(LocalDensity.current) { (maxWidth / 2 - 36.dp).toPx() }
        Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(red, red.copy(alpha = 0.25f), green.copy(alpha = 0.25f), green)), RoundedCornerShape(36.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CallEnd, stringResource(R.string.hh_decline), Modifier.padding(start = 20.dp), tint = Color.White)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Call, stringResource(R.string.hh_answer), Modifier.padding(end = 20.dp), tint = Color.White)
        }
        Box(Modifier.align(Alignment.Center).offset { IntOffset(x.value.roundToInt(), 0) }.size(72.dp)
            .background(if (dark) Color.White else MaterialTheme.colorScheme.surface, CircleShape)
            .draggable(rememberDraggableState { d -> scope.launch { x.snapTo((x.value + d).coerceIn(-half, half)) } },
                Orientation.Horizontal,
                onDragStopped = {
                    when {
                        x.value > half * 0.8f -> onAnswer()
                        x.value < -half * 0.8f -> onReject()
                    }
                    x.animateTo(0f)
                }),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).background(Color(0xFF8A8A8A), CircleShape))
        }
    }
}
