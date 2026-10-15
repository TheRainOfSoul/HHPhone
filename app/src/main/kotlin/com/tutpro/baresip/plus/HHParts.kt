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

/** Версия журнала звонков: CallHistoryNew — обычный ArrayList, Compose его изменений не видит. */
object HHHistory {
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun bump() { version.value++ }
    /** Журнал и сообщения HHPhone загружены из файлов (до этого переносить в систему нечего — флаг переноса не ставим). */
    @Volatile var restored = false
    /** Копия под замком: журнал пишется и из фонового потока. */
    fun snapshot(): List<CallHistoryNew> = synchronized(BaresipService.callHistory) { ArrayList(BaresipService.callHistory) }
}

/** Палитра кружков — приглушённые тона, совместимые с бордо HHDesign. */
private val hhAvatarColors = listOf(
    Color(0xFF7A0C2A), Color(0xFF424242), Color(0xFF0F6C6C), Color(0xFF2E7D32),
    Color(0xFF5C4B8A), Color(0xFFA15C00), Color(0xFFAD1457), Color(0xFF5D6B1E)
)

/**
 * Кеш «собеседник → контакт / имя» по peerKey: строки журнала и чатов не перебирают все контакты на каждой
 * перерисовке. Сбрасывается, когда меняется список контактов (contactsUpdate создаёт новый) или коллег.
 */
object HHPeople {
    private var contactsRef: Any? = null
    private var colleaguesRef: Any? = null
    private var uasRef: Any? = null
    private val contacts = HashMap<String, Contact?>()
    private val names = HashMap<String, String>()

    private fun fresh() {
        val c = BaresipService.contacts
        val k = HHColleagues.list.value
        val u = BaresipService.uas.value
        if (c !== contactsRef || k !== colleaguesRef || u !== uasRef) {
            contacts.clear(); names.clear(); contactsRef = c; colleaguesRef = k; uasRef = u
        }
    }

    /** Точное совпадение или тот же номер в другой записи (+374 91… = 091…). */
    @Synchronized fun contact(peer: String): Contact? {
        fresh()
        val key = hhPeerId(peer)   // sip:100@чужой и sip:100@свой — разные собеседники
        if (contacts.containsKey(key)) return contacts[key]
        val c = Contact.findContact(peer)
            ?: BaresipService.contacts.firstOrNull { c -> c.uris().any { HHUiLogic.samePhone(it.uri, peer) } }
        contacts[key] = c
        return c
    }

    /** Имя: контакт, коллега со станции или номер; групповая MMS-беседа — имена участников. */
    @Synchronized fun name(peer: String): String {
        if (HHSms.isGroup(peer)) return HHSms.groupAddresses(peer).joinToString(", ") { name("tel:$it") }.ifEmpty { "MMS" }
        fresh()
        val key = hhPeerId(peer)
        names[key]?.let { return it }
        val n = Contact.contactName(peer).takeIf { it != peer } ?: contact(peer)?.name()
            ?: HHColleagues.list.value.firstOrNull { it.number == key }?.name?.takeIf { it.isNotBlank() } ?: key.removePrefix("sip:")
        names[key] = n
        return n
    }
}

fun hhContact(peer: String): Contact? = HHPeople.contact(peer)
fun hhName(peer: String): String = HHPeople.name(peer)

/** Ключ карточки контакта HHPhone: a<id> — контакт телефона, b<id> — baresip, n<номер> — новый. */
fun hhCardKey(c: Contact): String = if (c is Contact.AndroidContact) "a${c.id}" else "b${c.id()}"
fun hhCardRoute(c: Contact?, peer: String): String =
    "hh_contact/" + android.net.Uri.encode(if (c != null) hhCardKey(c) else "n" + hhPeerId(peer))

/** Номер для своих станций, полный sip-адрес — для чужого сервера или буквенного имени. */
fun hhPeerId(peer: String): String {
    val key = HHUiLogic.peerKey(peer)
    if (!peer.startsWith("sip:")) return key
    val dom = peer.substringAfter("@", "").substringBefore(";")
    val own = BaresipService.uas.value.map { it.account.aor.substringAfter("@") }
    return if (key.any { it.isLetter() } || (dom.isNotEmpty() && dom !in own)) peer.substringBefore(";") else key
}

/** Фото контакта или первая буква в цветном круге. */
@Composable
fun HHAvatar(name: String, peer: String?, size: Dp, contact: Contact? = peer?.let { HHPeople.contact(it) }) {
    when (val c = contact) {
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

/** Activity за обёртками контекста (язык HHLocale оборачивает контекст). */
private fun Context.hhActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/** Экран с бордовой шапкой: светлые значки строки состояния, при уходе — как в теме. */
@Composable
fun HHLightStatusBar() {
    val act = androidx.compose.ui.platform.LocalContext.current.hhActivity()
    val dark = hhDark()
    fun light(on: Boolean) = act?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = on }
    androidx.compose.runtime.DisposableEffect(Unit) {
        light(false)
        onDispose { light(!dark) }
    }
    // главный экран при уходе (onDispose) возвращает тёмные значки уже после нас — повторяем, когда экран стал активным
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) light(false) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(1000); light(false) }
}

fun hhAgo(ctx: Context, thenMs: Long): String {
    val now = System.currentTimeMillis()
    val (unit, v) = HHUiLogic.ago(now, thenMs, java.util.TimeZone.getDefault().getOffset(now).toLong())
    return when (unit) {
        0 -> ctx.getString(R.string.hh_just_now)
        1 -> ctx.getString(R.string.hh_min_ago, v.toInt())
        2 -> ctx.getString(R.string.hh_h_ago, v.toInt())
        3 -> ctx.getString(R.string.hh_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(v))
    }
}

/** Вибрация при нажатии клавиш набора; выключается в меню ⋮ (по умолчанию включена). */
object HHKeyVibe {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("key_vibrate", true)
    fun toggle(ctx: Context) = prefs(ctx).edit().putBoolean("key_vibrate", !enabled(ctx)).apply()
    fun buzz(ctx: Context) {
        if (!enabled(ctx)) return
        val v = ctx.getSystemService(android.os.Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        v.vibrate(if (android.os.Build.VERSION.SDK_INT >= 29) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
            else android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private val latin = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
private val cyrillic = listOf("", "АБВГ", "ДЕЖЗ", "ИЙКЛ", "МНОП", "РСТУ", "ФХЦЧ", "ШЩЪЫ", "ЬЭЮЯ")

/**
 * Клавиатура 4×3 с буквами (русский интерфейс — кириллица, иначе латиница); onLong возвращает true, если долгое
 * нажатие обработано (0 → «+», 1 → почта), иначе вводится сама цифра.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HHKeypad(onKey: (Char) -> Unit, onLong: (Char) -> Boolean = { false }, modifier: Modifier = Modifier, light: Color? = null) {
    val fg = light ?: MaterialTheme.colorScheme.onSurface
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val letters = if (java.util.Locale.getDefault().language == "ru") cyrillic else latin
    val keys = ('1'..'9').mapIndexed { i, k -> k to letters[i] } + listOf('*' to "", '0' to "+", '#' to "")
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (k, letters) ->
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(MaterialTheme.shapes.medium)
                            .combinedClickable(onClick = { HHKeyVibe.buzz(ctx); onKey(k) }, onLongClick = { HHKeyVibe.buzz(ctx); if (!onLong(k)) onKey(k) }),
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
