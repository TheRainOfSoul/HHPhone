package com.tutpro.baresip.plus

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Экраны HHPhone 0.7.0: история номера и карточка контакта (спека 2026-10-14-hhphone-contacts). */
fun NavGraphBuilder.hhRoutes(navController: NavController, viewModel: ViewModel) {
    composable("hh_history/{peer}", arguments = listOf(navArgument("peer") { type = NavType.StringType })) { b ->
        HHHistoryScreen(navController, viewModel, b.arguments?.getString("peer") ?: "")
    }
}

/** Куда звонить по каждой линии: (подпись, aor, что набрать). */
internal fun hhLineTargets(peer: String, lines: List<HHLine>): List<Triple<String, String, String>> =
    lines.mapNotNull { l -> HHUiLogic.dialTarget(peer, listOf(l), l.aor)?.let { (aor, n) -> Triple(hhLineLabel(l), aor, n) } }

/** Чат с собеседником: номер — SIM-чат (или станция, если SIM нет), sip: — чат станции. */
internal fun hhChatRoute(peer: String, aor: String, lines: List<HHLine>): String? {
    val number = HHUiLogic.peerKey(peer)
    val (a, p) = when {
        peer.startsWith("sip:") -> (lines.firstOrNull { it.aor == aor && !it.mobile } ?: lines.firstOrNull { !it.mobile })?.aor to peer
        lines.any { it.mobile } -> HHSms.MOBILE to "tel:$number"
        else -> lines.firstOrNull()?.let { it.aor to "sip:$number@" + it.aor.substringAfter("@") } ?: (null to "")
    }
    return a?.let { "chat/${Uri.encode(it)}/${Uri.encode(p)}" }
}

/** Удаление звонков группы: системный журнал по _ID, иначе записи CallHistoryNew с тем же временем. */
private fun deleteCalls(ctx: android.content.Context, entries: List<HHLogEntry>) {
    val ids = entries.map { it.id }
    if (ids.all { it > 0 }) HHCallLog.delete(ctx, ids)
    else {
        val stops = entries.map { it.stop }.toSet()
        HHHistory.snapshot().filter { it.stopTime.timeInMillis in stops }.forEach { CallHistoryNew.remove(it.startTime, it.stopTime) }
    }
    HHHistory.bump()
}

/** Меню звонка из журнала: позвонить, SMS, история, карточка, скопировать, удалить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HHCallSheet(g: HHLogGroup, navController: NavController, viewModel: ViewModel, lines: List<HHLine>,
                showHistory: Boolean = true, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val name = hhName(g.peer)
    val contact = hhContact(g.peer)
    val number = HHUiLogic.peerKey(g.peer)
    val anonymous = number.isEmpty() || number.equals("anonymous", true) || number.equals("unknown", true)
    val green = colorResource(R.color.colorTrafficGreen)
    val copied = stringResource(R.string.hh_copied)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                HHAvatar(name, g.peer, 56.dp, contact)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(listOfNotNull(number.takeIf { it != name }, hhLogLine(g.aor), hhAgo(ctx, g.last.stop)).joinToString(" · "),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (!anonymous) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hhLineTargets(g.peer, lines).forEach { (label, aor, target) ->
                        Surface(onClick = { onDismiss(); HHCalls.dial(ctx, viewModel, aor, target) }, shape = RoundedCornerShape(26.dp),
                            color = green, modifier = Modifier.weight(1f).height(52.dp)) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Call, null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(label, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                }
                hhChatRoute(g.peer, g.aor, lines)?.let { r ->
                    SheetItem(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.hh_sms)) { onDismiss(); navController.navigate(r) }
                }
            }
            if (showHistory)
                SheetItem(Icons.Outlined.History, stringResource(R.string.hh_call_history)) {
                    onDismiss(); navController.navigate("hh_history/${Uri.encode(g.peer)}")
                }
            if (!anonymous)
                SheetItem(if (contact != null) Icons.Outlined.AccountBox else Icons.Outlined.PersonAdd,
                    stringResource(if (contact != null) R.string.hh_contact_card else R.string.hh_create_contact)) {
                    onDismiss(); navController.navigate(hhCardRoute(contact, g.peer))
                }
            if (!anonymous)
                SheetItem(Icons.Outlined.ContentCopy, stringResource(R.string.hh_copy_number)) {
                    clipboard.setText(AnnotatedString(number))
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            if (!anonymous && !g.peer.startsWith("sip:")) HHMessengerItems(number, onDismiss)
            SheetItem(Icons.Outlined.Delete, stringResource(R.string.hh_delete_from_log), MaterialTheme.colorScheme.error) {
                deleteCalls(ctx, g.entries)
                onDismiss()
            }
        }
    }
}

private val messengers = listOf(
    Triple("whatsapp", listOf("com.whatsapp", "com.whatsapp.w4b"), Color(0xFF25D366)),
    Triple("viber", listOf("com.viber.voip"), Color(0xFF7360F2)),
    Triple("telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web"), Color(0xFF229ED9)))

/** Номер в международном виде: системой по стране SIM, иначе своей логикой; короткий внутренний — null. */
internal fun hhE164(ctx: android.content.Context, number: String): String? {
    val iso = (ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
        ?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
    val d = number.filter { it.isDigit() }
    if (d.length < 8) return null
    return iso?.let { android.telephony.PhoneNumberUtils.formatNumberToE164(number, it) } ?: HHUiLogic.toE164(number, null)
}

/** «WhatsApp», «Viber», «Telegram» — только установленные; чат открывается по номеру. */
@Composable
internal fun HHMessengerItems(number: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val e164 = remember(number) { hhE164(ctx, number) } ?: return
    val links = HHUiLogic.messengerLinks(e164)
    val names = mapOf("whatsapp" to "WhatsApp", "viber" to "Viber", "telegram" to "Telegram")
    messengers.forEach { (key, pkgs, color) ->
        val pkg = remember(key) { pkgs.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null } } ?: return@forEach
        SheetItem(Icons.AutoMirrored.Outlined.Chat, names.getValue(key), color) {
            onDone()
            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(links.getValue(key))).setPackage(pkg)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}

@Composable
internal fun SheetItem(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(text, fontSize = 16.sp, color = tint)
    }
}

/** Подпись линии строки журнала: SIM, станция или ничего (звонок другого приложения). */
fun hhLogLine(aor: String): String? = when {
    aor.endsWith("@pstn") -> "SIM"
    aor.startsWith("sip:") -> "HH " + HHPush.userDomain(aor).first
    else -> null
}

/** Все звонки с номером, без группировки; нажатие — меню звонка без пункта «История». */
@Composable
private fun HHHistoryScreen(navController: NavController, viewModel: ViewModel, peer: String) {
    val ctx = LocalContext.current
    val version by HHHistory.version.collectAsState()
    val lines = hhLines()
    val calls by produceState(emptyList<HHLogEntry>(), version) {
        value = withContext(Dispatchers.IO) {
            (HHCallLog.read(ctx, 3000) ?: HHHistory.snapshot().map {
                HHLogEntry(it.peerUri, it.aor, it.direction, it.startTime?.timeInMillis, it.stopTime.timeInMillis, it.rejected)
            }).filter { e -> peer.split("\n").any { p -> HHUiLogic.peerKey(e.peer) == HHUiLogic.peerKey(p) || HHUiLogic.samePhone(e.peer, p) } }
                .sortedByDescending { it.stop }
        }
    }
    HHLightStatusBar()
    var sheet by remember { mutableStateOf<HHLogEntry?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(hhHeader).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hh_back), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(hhName(peer.substringBefore("\n")), color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(calls.withIndex().toList(), key = { (i, e) -> "${e.id}|${e.stop}|$i" }) { (_, e) ->
                val missed = HHUiLogic.missed(e)
                Row(Modifier.fillMaxWidth().clickable { sheet = e }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val blocked = e.rejected && e.dir == "in"
                    val icon = when { missed -> Icons.AutoMirrored.Filled.CallMissed; blocked -> Icons.Outlined.Block
                        e.dir == "in" -> Icons.AutoMirrored.Filled.CallReceived; else -> Icons.AutoMirrored.Filled.CallMade }
                    Icon(icon, null, Modifier.size(20.dp), tint = when { missed -> MaterialTheme.colorScheme.error
                        blocked -> MaterialTheme.colorScheme.onSurfaceVariant; else -> colorResource(R.color.colorTrafficGreen) })
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(fmt.format(Date(e.start ?: e.stop)), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        val secs = e.start?.let { (e.stop - it) / 1000 }
                        Text(listOfNotNull(secs?.let { "%d:%02d".format(it / 60, it % 60) }, hhLogLine(e.aor)).joinToString(" · "),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    sheet?.let { e -> HHCallSheet(HHLogGroup(e.peer, e.aor, e, 1), navController, viewModel, lines, showHistory = false) { sheet = null } }
}

/** Меню контакта (нажатие в «Контактах»/«Избранном»): номера с вызовом/SMS/копированием, история, карточка, мессенджеры. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HHContactSheet(c: Contact, navController: NavController, viewModel: ViewModel, lines: List<HHLine>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.hh_copied)
    val uris = c.uris()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                HHAvatar(c.name(), null, 56.dp, c)
                Spacer(Modifier.width(14.dp))
                Text(c.name(), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            uris.forEach { u ->
                val n = HHUiLogic.peerKey(u.uri)
                val targets = hhLineTargets(u.uri, lines)
                var choose by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (u.label.isNotBlank()) Text(android.provider.ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources,
                            Contact.stringToType(u.label), u.label).toString(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(n)); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.hh_copy_number), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    hhChatRoute(u.uri, "", lines)?.let { r ->
                        IconButton(onClick = { onDismiss(); navController.navigate(r) }) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.hh_sms), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(enabled = targets.isNotEmpty(), onClick = {
                            if (targets.size == 1) { onDismiss(); targets[0].let { (_, aor, t) -> HHCalls.dial(ctx, viewModel, aor, t) } } else choose = true
                        }) {
                            androidx.compose.foundation.layout.Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colorResource(R.color.colorTrafficGreen)),
                                contentAlignment = Alignment.Center) { Icon(Icons.Default.Call, stringResource(R.string.hh_call), tint = Color.White) }
                        }
                        androidx.compose.material3.DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                            targets.forEach { (label, aor, t) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) },
                                    onClick = { choose = false; onDismiss(); HHCalls.dial(ctx, viewModel, aor, t) })
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (uris.isNotEmpty())
                SheetItem(Icons.Outlined.History, stringResource(R.string.hh_call_history)) {
                    onDismiss(); navController.navigate("hh_history/" + Uri.encode(uris.joinToString("\n") { it.uri }))
                }
            SheetItem(Icons.Outlined.AccountBox, stringResource(R.string.hh_contact_card)) {
                onDismiss(); navController.navigate(hhCardRoute(c, uris.firstOrNull()?.uri ?: ""))
            }
            // мессенджеры — по первому номеру телефона (мобильный, если есть)
            (uris.firstOrNull { it.label.equals("Mobile", true) && !it.uri.startsWith("sip:") } ?: uris.firstOrNull { !it.uri.startsWith("sip:") })
                ?.let { HHMessengerItems(HHUiLogic.peerKey(it.uri), onDismiss) }
        }
    }
}

