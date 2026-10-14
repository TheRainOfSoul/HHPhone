package com.tutpro.baresip.plus

import android.content.Context
import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.tutpro.baresip.plus.BaresipService.Companion.circleGreen
import com.tutpro.baresip.plus.BaresipService.Companion.colorblind

/** Главный экран HHPhone 0.5.0: вкладки, журнал, клавиатура, кнопки линий (спека 2026-10-13 ui). */
@Composable
fun HHHome(navController: NavController, viewModel: ViewModel, onQuitClick: () -> Unit, onReturnToCall: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = 1) { 4 }
    val scope = rememberCoroutineScope()
    val allowContacts = hhContactsAccess(ctx)
    LaunchedEffect(Unit) {   // HHPhone заменяет звонилку — контакты телефона нужны сразу; спрашиваем один раз
        while (!Config.isInitialized()) delay(200)   // первый запуск: config читает сервис
        val p = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
        if (BaresipService.contactsMode == "baresip" && !p.getBoolean("contacts_asked", false) && !Call.inCall()) {
            p.edit().putBoolean("contacts_asked", true).apply()
            allowContacts()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HHHeader(navController, onQuitClick)
        if (onReturnToCall != null)
            Text(stringResource(R.string.hh_back_to_call), Modifier.fillMaxWidth().background(colorResource(R.color.colorTrafficGreen))
                .clickable(onClick = onReturnToCall).padding(12.dp), color = Color.White, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        val titles = listOf(R.string.hh_tab_favorites, R.string.hh_tab_phone, R.string.hh_tab_contacts, R.string.hh_tab_chats)
        val tab = pager.currentPage
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = MaterialTheme.colorScheme.primary) }) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = {
                    Text(stringResource(t), maxLines = 1, color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> HHPeopleTab(ctx, viewModel, favoritesOnly = true, allowContacts)
                1 -> HHPhoneTab(ctx, navController, viewModel)
                2 -> HHPeopleTab(ctx, viewModel, favoritesOnly = false, allowContacts)
                else -> HHChatsTab(ctx, navController)
            }
        }
    }
}

/** Доступ к контактам телефона: есть разрешение — режим «both», нет — спрашиваем; отказано навсегда — настройки приложения. */
@Composable
private fun hhContactsAccess(ctx: Context): () -> Unit {
    val perms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) Contact.setMode(ctx, "both")
        else if (asked) try {   // повторный отказ без системного окна — запрещено «больше не спрашивать»
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        } catch (_: Exception) {}
        asked = true
    }
    return {
        if (Utils.checkPermissions(ctx, perms)) Contact.setMode(ctx, "both") else launcher.launch(perms)
    }
}

@Composable
private fun HHHeader(navController: NavController, onQuitClick: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val settings = stringResource(R.string.configuration)
    val accounts = stringResource(R.string.accounts)
    val quit = stringResource(R.string.quit)
    val vibe = stringResource(R.string.hh_key_vibration)
    Row(Modifier.fillMaxWidth().background(hhHeader).statusBarsPadding().height(56.dp).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.hh_menu), tint = MaterialTheme.colorScheme.onPrimary)
            }
            CustomElements.DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                menuItems = hhMenu(listOf(
                    MenuItem(accounts, Icons.Outlined.ManageAccounts),
                    MenuItem(settings, Icons.Outlined.Settings),
                    MenuItem(vibe, if (HHKeyVibe.enabled(ctx)) Icons.Filled.Check else Icons.Outlined.Vibration),
                    MenuItem(quit, Icons.AutoMirrored.Filled.Logout)
                ), hidden = setOf(settings, accounts, quit)),
                onItemClick = { item ->
                    menu = false
                    if (item == vibe) { HHKeyVibe.toggle(ctx); return@DropdownMenu }
                    if (hhMenuClick(ctx, item) || checkLockedAndToast(ctx)) return@DropdownMenu
                    when (item) {
                        settings -> navController.navigate("settings")
                        accounts -> navController.navigate("accounts")
                        quit -> onQuitClick()
                    }
                })
        }
    }
}

/** Линии для кнопок вызова: SIM и станции. */
@Composable
private fun hhLines(): List<HHLine> {
    val uas by BaresipService.uas
    val status by BaresipService.uasStatus
    return HHUiLogic.lines(uas.map { ua ->
        HHLine(ua.account.aor, ua.account.isMobile, (status[ua.account.aor] ?: ua.status) == circleGreen.getValue(colorblind))
    })
}

private fun lineLabel(ctx: Context, l: HHLine): String =
    if (l.mobile) "SIM" else "HH " + HHPush.userDomain(l.aor).first

@Composable
private fun HHPhoneTab(ctx: Context, navController: NavController, viewModel: ViewModel) {
    val history by HHHistory.version.collectAsState()
    val colleagues by HHColleagues.list.collectAsState()
    val log = remember(history, colleagues) {
        HHUiLogic.group(HHHistory.snapshot().map {
            HHLogEntry(it.peerUri, it.aor, it.direction, it.startTime?.timeInMillis, it.stopTime.timeInMillis, it.rejected)
        })
    }
    var number by rememberSaveable { mutableStateOf("") }
    var keypad by rememberSaveable { mutableStateOf(true) }
    val lines = hhLines()
    val green = colorResource(R.color.colorTrafficGreen)

    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && number.isEmpty()) keypad = false }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (log.isEmpty()) item {
                Text(stringResource(R.string.hh_no_calls), Modifier.fillMaxWidth().padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(log, key = { "${it.peer}|${it.last.stop}|$tick" }) { g ->
                HHLogRow(ctx, g, onCard = { navController.navigate("contact/${Uri.encode(HHUiLogic.peerKey(g.peer))}/" + if (Contact.findContact(g.peer) != null) "old" else "new") }) {
                    HHUiLogic.dialTarget(g.peer, lines, g.aor)?.let { (aor, n) -> HHCalls.dial(ctx, viewModel, aor, n) }
                }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding()) {
                if (keypad) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(number, Modifier.weight(1f), fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (number.isNotEmpty()) IconButton(onClick = { number = number.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.hh_backspace), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HHSuggestions(number) { number = it }
                    HHKeypad(onKey = { number += it }, onLong = { k ->
                        when (k) {
                            '0' -> { number += "+"; true }
                            '1' -> lines.firstOrNull { !it.mobile }?.let { l ->
                                UserAgent.ofAor(l.aor)?.account?.vmUri?.takeIf { it.isNotEmpty() }?.let { HHCalls.dial(ctx, viewModel, l.aor, it) }
                            } ?: false
                            else -> false
                        }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HHRoundButton(Icons.Outlined.PersonAdd, stringResource(R.string.hh_add_contact)) {
                        if (!checkLockedAndToast(ctx)) navController.navigate("contact/${Uri.encode(number)}/new")
                    }
                    Row(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lines.isEmpty())
                            Surface(onClick = { if (!checkLockedAndToast(ctx)) navController.navigate("accounts") },
                                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).height(52.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.hh_add_account), color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                                }
                            }
                        HHUiLogic.callButtons(lines).forEach { group ->
                            var choose by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f)) {
                                Surface(onClick = {
                                    if (!keypad) keypad = true
                                    else if (group.size > 1) choose = true
                                    else if (number.isNotBlank() && HHCalls.dial(ctx, viewModel, group[0].aor, number)) number = ""
                                }, shape = RoundedCornerShape(28.dp), color = green, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Call, null, tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        val l = group[0]
                                        Text(if (group.size > 1) "HH ▾" else lineLabel(ctx, l), color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                                        if (group.size == 1 && !l.mobile) {
                                            Spacer(Modifier.width(6.dp))
                                            // точка регистрации: цвет + подпись для TalkBack
                                            Box(Modifier.size(8.dp).background(if (l.registered) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
                                                .semantics { contentDescription = ctx.getString(if (l.registered) R.string.hh_registered else R.string.hh_not_registered) })
                                        }
                                    }
                                }
                                CustomElements.DropdownMenu(expanded = choose, onDismissRequest = { choose = false },
                                    items = group.map { lineLabel(ctx, it) }) { label ->
                                    choose = false
                                    group.firstOrNull { lineLabel(ctx, it) == label }?.let { if (HHCalls.dial(ctx, viewModel, it.aor, number)) number = "" }
                                }
                            }
                        }
                    }
                    HHRoundButton(Icons.Default.Dialpad, stringResource(R.string.hh_keypad), active = keypad) { keypad = !keypad }
                }
            }
        }
    }
}

@Composable
private fun HHRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = CircleShape, modifier = Modifier.size(52.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(28.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** Подсказки по набранным цифрам: контакты и коллеги. */
@Composable
private fun HHSuggestions(number: String, onPick: (String) -> Unit) {
    if (number.length < 2) return
    val colleagues by HHColleagues.list.collectAsState()
    val contacts = BaresipService.contacts
    val hits = remember(number, colleagues, contacts) {
        (colleagues.map { it.name.ifBlank { it.number } to it.number } +
            contacts.flatMap { c -> c.uris().map { c.name() to HHUiLogic.peerKey(it.uri) } })
            .filter { (name, n) -> HHUiLogic.suggestMatch(number, name, n) }.distinctBy { it.second }.take(3)
    }
    hits.forEach { (name, n) ->
        Row(Modifier.fillMaxWidth().clickable { onPick(n) }.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HHAvatar(name, null, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(name, Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
            Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HHLogRow(ctx: Context, g: HHLogGroup, onCard: () -> Unit, onClick: () -> Unit) {
    val name = hhName(g.peer)
    val e = g.last
    val missed = HHUiLogic.missed(e)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HHAvatar(name, g.peer, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (g.count > 1) Text(" ${g.count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when { missed -> Icons.AutoMirrored.Filled.CallMissed; e.dir == "in" -> Icons.AutoMirrored.Filled.CallReceived; else -> Icons.AutoMirrored.Filled.CallMade }
                Icon(icon, null, Modifier.size(14.dp), tint = if (missed) MaterialTheme.colorScheme.error else colorResource(R.color.colorTrafficGreen))
                Spacer(Modifier.width(4.dp))
                val secs = e.start?.let { (e.stop - it) / 1000 }
                val sub = listOfNotNull(
                    if (name != HHUiLogic.peerKey(g.peer)) HHUiLogic.peerKey(g.peer) else null,
                    secs?.let { "%d:%02d".format(it / 60, it % 60) },
                    if (e.aor.endsWith("@pstn")) "SIM" else null
                ).joinToString(" · ")
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(hhAgo(ctx, e.stop), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onCard) {
            Icon(if (Contact.findContact(g.peer) != null) Icons.Outlined.AccountBox else Icons.Outlined.PersonAdd,
                stringResource(R.string.hh_contact_card), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Контакты (с поиском и «Коллегами») или Избранное. */
@Composable
private fun HHPeopleTab(ctx: Context, viewModel: ViewModel, favoritesOnly: Boolean, allowContacts: () -> Unit) {
    val colleagues by HHColleagues.list.collectAsState()
    val stars by HHColleagues.stars.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val lines = hhLines()
    fun call(target: Pair<String, String>?) {
        if (target == null) Toast.makeText(ctx, R.string.hh_no_line, Toast.LENGTH_SHORT).show()
        else HHCalls.dial(ctx, viewModel, target.first, target.second)
    }
    val col = colleagues.filter { (!favoritesOnly || HHColleagues.key(it) in stars) && (q.isEmpty() || it.name.contains(q, true) || it.number.contains(q)) }
    val con = BaresipService.contacts.filter { c -> (!favoritesOnly || c.favorite()) &&
        (q.isEmpty() || c.name().contains(q, true) || c.uris().any { HHUiLogic.peerKey(it.uri).contains(q) }) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (BaresipService.contactsMode == "baresip") item {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hh_contacts_access), Modifier.weight(1f), fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = allowContacts) { Text(stringResource(R.string.hh_allow)) }
            }
        }
        if (favoritesOnly && col.isEmpty() && con.isEmpty()) item {
            Text(stringResource(R.string.hh_no_favorites), Modifier.fillMaxWidth().padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!favoritesOnly) item {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.hh_search)) }, shape = RoundedCornerShape(24.dp))
        }
        if (col.isNotEmpty()) item { HHSection(stringResource(R.string.hh_colleagues)) }
        items(col, key = { "c${it.aor}|${it.number}" }) { c ->
            HHPersonRow(c.name.ifBlank { c.number }, "sip:${c.number}@x", c.number, starred = HHColleagues.isStar(c),
                onStar = { HHColleagues.toggleStar(c) }) { call(HHUiLogic.dialColleague(c.number, c.aor, lines)) }
        }
        if (con.isNotEmpty()) item { HHSection(stringResource(R.string.hh_tab_contacts)) }
        items(con.flatMap { c -> c.uris().map { c to it } }, key = { (c, u) -> "p${c.id()}|${u.uri}" }) { (c, u) ->
            HHPersonRow(c.name(), u.uri, listOfNotNull(u.label.ifBlank { null }, HHUiLogic.peerKey(u.uri)).joinToString(" · "),
                starred = c.favorite(), onStar = { Contact.toggleFavorite(ctx, c) }) { call(HHUiLogic.dialTarget(u.uri, lines, null)) }
        }
    }
}

@Composable
private fun HHSection(title: String) =
    Text(title, Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontSize = 13.sp,
        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun HHPersonRow(name: String, peer: String, sub: String, starred: Boolean, onStar: (() -> Unit)?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HHAvatar(name, peer, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onStar != null) IconButton(onClick = onStar) {
            Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline, stringResource(R.string.hh_favorite),
                tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Чаты: последнее сообщение по собеседнику → прежний экран чата baresip. */
@Composable
private fun HHChatsTab(ctx: Context, navController: NavController) {
    val messages = BaresipService.messages
    val chats = remember(messages) { messages.sortedByDescending { it.timeStamp }.distinctBy { it.aor + "|" + it.peerUri } }
    val lines = hhLines()
    var choose by remember { mutableStateOf(false) }
    fun open(aor: String) { if (!checkLockedAndToast(ctx)) navController.navigate("chats/${Uri.encode(aor)}") }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (chats.isEmpty()) item {
            Text(stringResource(R.string.hh_no_chats), Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(chats, key = { it.aor + "|" + it.peerUri }) { m ->
            val name = hhName(m.peerUri)
            Row(Modifier.fillMaxWidth().clickable { navController.navigate("chat/${Uri.encode(m.aor)}/${Uri.encode(m.peerUri)}") }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HHAvatar(name, m.peerUri, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 16.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (m.new) FontWeight.Bold else FontWeight.Normal)
                    Text(m.message, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(hhAgo(ctx, m.timeStamp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (lines.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
        ExtendedFloatingActionButton(onClick = { if (lines.size == 1) open(lines[0].aor) else choose = true },
            icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, text = { Text(stringResource(R.string.hh_new_chat)) },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        CustomElements.DropdownMenu(expanded = choose, onDismissRequest = { choose = false },
            items = lines.map { lineLabel(ctx, it) }) { label ->
            choose = false
            lines.firstOrNull { lineLabel(ctx, it) == label }?.let { open(it.aor) }
        }
    }
    }
}
