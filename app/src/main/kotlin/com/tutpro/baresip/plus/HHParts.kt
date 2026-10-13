package com.tutpro.baresip.plus

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** Палитра кружков — приглушённые тона, совместимые с бордо HHDesign. */
private val hhAvatarColors = listOf(
    Color(0xFF7A0C2A), Color(0xFF424242), Color(0xFF0F6C6C), Color(0xFF2E7D32),
    Color(0xFF5C4B8A), Color(0xFFA15C00), Color(0xFFAD1457), Color(0xFF5D6B1E)
)

/** Имя для показа: контакт, коллега со станции или номер. */
fun hhName(peer: String): String {
    val n = Contact.contactName(peer)
    if (n != peer) return n
    val key = HHUiLogic.peerKey(peer)
    return HHColleagues.list.value.firstOrNull { it.number == key }?.name?.takeIf { it.isNotBlank() } ?: key
}

/** Фото контакта или первая буква в цветном круге. */
@Composable
fun HHAvatar(name: String, peer: String?, size: Dp) {
    when (val c = peer?.let { Contact.findContact(it) }) {
        is Contact.AndroidContact -> c.thumbnailUri?.let { t ->
            AsyncImage(model = t, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape))
            return
        }
        is Contact.BaresipContact -> c.avatarImage?.let { CustomElements.ImageAvatar(it, size); return }
        null -> {}
    }
    Box(Modifier.size(size).clip(CircleShape).background(hhAvatarColors[HHUiLogic.avatarColor(name)]),
        contentAlignment = Alignment.Center) {
        Text(HHUiLogic.avatarLetter(name), color = Color.White, fontSize = (size.value * 0.45).sp, fontWeight = FontWeight.Medium)
    }
}

fun hhAgo(ctx: Context, thenMs: Long): String {
    val (unit, v) = HHUiLogic.ago(System.currentTimeMillis(), thenMs)
    return when (unit) {
        0 -> ctx.getString(R.string.hh_just_now)
        1 -> ctx.getString(R.string.hh_min_ago, v.toInt())
        2 -> ctx.getString(R.string.hh_h_ago, v.toInt())
        3 -> ctx.getString(R.string.hh_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(v))
    }
}

private val keys = listOf('1' to "", '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '*' to "", '0' to "+", '#' to "")

/** Клавиатура 4×3 с буквами; onLong — долгое нажатие (0 → «+», 1 → голосовая почта). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HHKeypad(onKey: (Char) -> Unit, onLong: (Char) -> Unit = {}, modifier: Modifier = Modifier, light: Color? = null) {
    val fg = light ?: MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (k, letters) ->
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(MaterialTheme.shapes.medium)
                            .combinedClickable(onClick = { onKey(k) }, onLongClick = { onLong(k) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        Text(k.toString(), fontSize = 30.sp, color = fg)
                        if (letters.isNotEmpty()) Text(letters, fontSize = 11.sp, color = fg.copy(alpha = 0.6f), letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}
