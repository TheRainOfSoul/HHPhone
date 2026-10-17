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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.FiberManualRecord
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
import androidx.compose.material.icons.outlined.ContentPaste
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
    // внешний номер (tel:/sip:, Circle to Search, тап по номеру) — показать вкладку телефона, само поле заполнит HHPhoneTab
    val extDial by HHUi.dialNumber.collectAsState()
    LaunchedEffect(extDial) { if (extDial != null) pager.animateScrollToPage(1) }
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
        HHFullScreenBanner(ctx)
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
                0 -> HHPeopleTab(ctx, navController, viewModel, favoritesOnly = true, allowContacts)
                1 -> HHPhoneTab(ctx, navController, viewModel)
                2 -> HHPeopleTab(ctx, navController, viewModel, favoritesOnly = false, allowContacts)
                else -> HHChatsTab(ctx, navController)
            }
        }
    }
}

/** Звонок на заблокированном экране открывается только с разрешением «полноэкранные уведомления» (Android 14+). */
@Composable
private fun HHFullScreenBanner(ctx: Context) {
    if (android.os.Build.VERSION.SDK_INT < 34) return
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    var allowed by remember { mutableStateOf(nm.canUseFullScreenIntent()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {   // вернулся из настроек — проверить снова
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = nm.canUseFullScreenIntent()
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (allowed) return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.hh_fsi_banner), Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
            } catch (_: Exception) {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
        }) { Text(stringResource(R.string.hh_allow)) }
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
                    MenuItem(quit, Icons.AutoMirrored.Filled.Logout)
                ), hidden = setOf(accounts, quit)),
                onItemClick = { item ->
                    menu = false
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

/** Текст из буфера обмена (null — пусто/нет доступа). Читаем только по нажатию «Вставить». */
private fun hhClipboardText(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
}

/** Линии для кнопок вызова: SIM и станции. */
@Composable
internal fun hhLines(): List<HHLine> {
    val uas by BaresipService.uas
    val status by BaresipService.uasStatus
    return HHUiLogic.lines(uas.map { ua ->
        HHLine(ua.account.aor, ua.account.isMobile, (status[ua.account.aor] ?: ua.status) == circleGreen.getValue(colorblind))
    })
}

private fun lineLabel(@Suppress("UNUSED_PARAMETER") ctx: Context, l: HHLine): String = hhLineLabel(l)

internal fun hhLineLabel(l: HHLine): String = if (l.mobile) "SIM" else "HH " + HHPush.userDomain(l.aor).first

@Composable
private fun HHPhoneTab(ctx: Context, navController: NavController, viewModel: ViewModel) {
    val history by HHHistory.version.collectAsState()
    val colleagues by HHColleagues.list.collectAsState()
    DisposableEffect(Unit) { val off = HHCallLog.observe(ctx) { HHHistory.bump() }; onDispose { off() } }
    LaunchedEffect(history) { withContext(Dispatchers.IO) { HHCallLog.migrate(ctx) } }   // ждёт загрузки журнала (bump)
    // журнал — системный CallLog; без разрешения — как раньше, из CallHistoryNew
    val log by produceState(emptyList<HHLogGroup>(), history, colleagues) {
        value = withContext(Dispatchers.IO) {
            HHUiLogic.group(HHCallLog.read(ctx) ?: HHHistory.snapshot().map {
                HHLogEntry(it.peerUri, it.aor, it.direction, it.startTime?.timeInMillis, it.stopTime.timeInMillis, it.rejected)
            })
        }
    }
    var number by rememberSaveable { mutableStateOf("") }
    var keypad by rememberSaveable { mutableStateOf(true) }
    var missedOnly by rememberSaveable { mutableStateOf(false) }   // A5: фильтр «только пропущенные»
    var assignKey by remember { mutableStateOf<Char?>(null) }      // A2: клавиша, которую назначаем в быстрый набор
    // внешний номер (tel:/sip:, Circle to Search) — заполнить поле и показать клавиатуру, но не звонить сами
    val extDial by HHUi.dialNumber.collectAsState()
    LaunchedEffect(extDial) {
        val n = extDial ?: return@LaunchedEffect
        number = n; keypad = true
        // сигнал гасим с задержкой: navigateToHome пересоздаёт экран, пусть свежая вкладка тоже успеет применить номер
        // (умирающий экземпляр при пересоздании отменяет эту корутину, поэтому гонки «очистил до монтажа нового» нет)
        delay(500); if (HHUi.dialNumber.value == n) HHUi.dialNumber.value = null
    }
    val lines = hhLines()
    val green = colorResource(R.color.colorTrafficGreen)

    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress && number.isEmpty()) keypad = false }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    var sheet by remember { mutableStateOf<HHLogGroup?>(null) }
    sheet?.let { HHCallSheet(it, navController, viewModel, lines) { sheet = null } }
    assignKey?.let { key -> HHSpeedDialDialog(ctx, key, number) { assignKey = null } }   // A2
    val shown = if (missedOnly) HHUiLogic.missedGroups(log) else log   // A5
    Column(Modifier.fillMaxSize()) {
        if (!keypad) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HHChip(!missedOnly, stringResource(R.string.hh_filter_all)) { missedOnly = false }
            HHChip(missedOnly, stringResource(R.string.hh_filter_missed)) { missedOnly = true }
        }
        Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (shown.isEmpty()) item {
                Text(stringResource(if (missedOnly) R.string.hh_nothing_found else R.string.hh_no_calls),
                    Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { "${it.peer}|${it.last.stop}" }) { g ->   // tick не в ключе: строки не пересоздаются раз в минуту
                // нажатие — меню звонка (не звонит сразу)
                HHLogRow(ctx, g, tick, onCard = { navController.navigate(hhCardRoute(hhContact(g.peer), g.peer)) }) { sheet = g }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        // подсказки — поверх журнала над панелью: поле номера и клавиатура не сдвигаются
        if (keypad) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            HHSuggestions(number) { number = it }
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
                        else {   // пусто — вставить скопированный номер (буфер читаем только по нажатию)
                            val noNumber = stringResource(R.string.hh_paste_empty)
                            IconButton(onClick = {
                                val got = HHUiLogic.dialableFromClipboard(hhClipboardText(ctx))
                                if (got != null) number = got else Toast.makeText(ctx, noNumber, Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Outlined.ContentPaste, stringResource(R.string.hh_paste), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HHKeypad(onKey = { number += it }, onLong = { k ->
                        when (k) {
                            '0' -> { number += "+"; true }
                            '1' -> lines.firstOrNull { !it.mobile }?.let { l ->
                                UserAgent.ofAor(l.aor)?.account?.vmUri?.takeIf { it.isNotEmpty() }?.let { HHCalls.dial(ctx, viewModel, l.aor, it) }
                            } ?: false
                            in '2'..'9' -> {   // A2: быстрый набор — закреплён номер звоним, пусто предлагаем закрепить
                                val saved = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE).getString("speed_$k", null)
                                if (saved != null)
                                    HHUiLogic.speedLine(lines)?.let { if (HHCalls.dial(ctx, viewModel, it.aor, saved)) number = "" }
                                else assignKey = k
                                true
                            }
                            else -> false
                        }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HHRoundButton(Icons.Outlined.PersonAdd, stringResource(R.string.hh_add_contact)) {
                        if (!checkLockedAndToast(ctx)) navController.navigate("hh_contact/" + Uri.encode("n$number"))
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
                                    else if (number.isBlank())   // пусто — номер последнего звонка этой линии, второе нажатие звонит
                                        HHUiLogic.lastNumber(log.flatMap { it.entries }, group.map { it.aor }.toSet())?.let { number = hhPeerId(it) }
                                    else if (group.size > 1) choose = true
                                    else if (HHCalls.dial(ctx, viewModel, group[0].aor, number)) number = ""
                                }, shape = RoundedCornerShape(28.dp), color = green, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Call, null, tint = Color.White)
                                        Spacer(Modifier.width(6.dp))
                                        val l = group[0]
                                        Text(if (group.size > 1) "HH ▾" else lineLabel(ctx, l), color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Чип-фильтр (A5): выбран — бордовый, иначе приглушённый. */
@Composable
internal fun HHChip(selected: Boolean, label: String, onClick: () -> Unit) =
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 14.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }

/** Диалог быстрого набора (A2): задать/очистить номер для клавиши. initial — подставить набранное. Ключ хранится в prefs hh_ui как speed_<клавиша>. */
@Composable
internal fun HHSpeedDialDialog(ctx: Context, key: Char, initial: String, onDismiss: () -> Unit) {
    val prefs = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
    val existing = prefs.getString("speed_$key", null)
    var value by remember { mutableStateOf(existing ?: initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hh_speed_dial_set, key.toString())) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
                label = { Text(stringResource(R.string.hh_speed_dial_number)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = {
                prefs.edit().putString("speed_$key", value.trim()).apply(); onDismiss()
            }) { Text(stringResource(R.string.hh_save)) }
        },
        dismissButton = {
            if (existing != null) TextButton(onClick = { prefs.edit().remove("speed_$key").apply(); onDismiss() }) {
                Text(stringResource(R.string.hh_speed_dial_clear), color = MaterialTheme.colorScheme.error)
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.hh_cancel)) }
        }
    )
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
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HHLogRow(ctx: Context, g: HHLogGroup, tick: Int, onCard: () -> Unit, onClick: () -> Unit) {
    val name = hhName(g.peer)
    val contact = hhContact(g.peer)
    val e = g.last
    val missed = HHUiLogic.missed(e)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HHAvatar(name, g.peer, 44.dp, contact)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (g.count > 1) Text(" ${g.count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val blocked = e.rejected && e.dir == "in"   // отклонён / заблокирован
                val icon = when { missed -> Icons.AutoMirrored.Filled.CallMissed; blocked -> Icons.Outlined.Block
                    e.dir == "in" -> Icons.AutoMirrored.Filled.CallReceived; else -> Icons.AutoMirrored.Filled.CallMade }
                Icon(icon, null, Modifier.size(14.dp), tint = when { missed -> MaterialTheme.colorScheme.error
                    blocked -> MaterialTheme.colorScheme.onSurfaceVariant; else -> colorResource(R.color.colorTrafficGreen) })
                Spacer(Modifier.width(4.dp))
                val secs = e.start?.let { (e.stop - it) / 1000 }
                val sub = listOfNotNull(
                    if (name != HHUiLogic.peerKey(g.peer) && HHUiLogic.hiddenKind(g.peer) == null) HHUiLogic.peerKey(g.peer) else null,
                    secs?.let { hhDuration(it) },
                    if (e.aor.endsWith("@pstn")) "SIM" else null
                ).joinToString(" · ")   // звонки других приложений (aor "") — без пометки
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val ago = remember(tick, e.stop) { hhAgo(ctx, e.stop) }
        Text(ago, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onCard) {
            Icon(if (contact != null) Icons.Outlined.AccountBox else Icons.Outlined.PersonAdd,
                stringResource(R.string.hh_contact_card), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Контакты (с поиском и «Коллегами») или Избранное. */
@Composable
private fun HHPeopleTab(ctx: Context, navController: NavController, viewModel: ViewModel, favoritesOnly: Boolean, allowContacts: () -> Unit) {
    val colleagues by HHColleagues.list.collectAsState()
    val stars by HHColleagues.stars.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val lines = hhLines()
    fun call(target: Pair<String, String>?) {
        if (target == null) Toast.makeText(ctx, R.string.hh_no_line, Toast.LENGTH_SHORT).show()
        else HHCalls.dial(ctx, viewModel, target.first, target.second)
    }
    val contacts = BaresipService.contacts
    val col = remember(colleagues, stars, q, favoritesOnly) {
        colleagues.filter { (!favoritesOnly || HHColleagues.key(it) in stars) && (q.isEmpty() || it.name.contains(q, true) || it.number.contains(q)) }
    }
    val con = remember(contacts, q, favoritesOnly) {
        contacts.filter { c -> (!favoritesOnly || c.favorite()) &&
            (q.isEmpty() || c.name().contains(q, true) || c.uris().any { HHUiLogic.peerKey(it.uri).contains(q) }) }
    }
    var sheet by remember { mutableStateOf<Contact?>(null) }
    sheet?.let { HHContactSheet(it, navController, viewModel, lines) { sheet = null } }
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
        if (!favoritesOnly && col.isEmpty() && con.isEmpty()) item {
            Text(stringResource(if (q.isBlank()) R.string.hh_no_contacts else R.string.hh_nothing_found),
                Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (col.isNotEmpty()) item { HHSection(stringResource(R.string.hh_colleagues)) }
        items(col, key = { "c${it.aor}|${it.number}" }) { c ->
            HHPersonRow(c.name.ifBlank { c.number }, null, null, c.number, starred = HHColleagues.isStar(c),
                onStar = { HHColleagues.toggleStar(c) }) { call(HHUiLogic.dialColleague(c.number, c.aor, lines)) }
        }
        if (con.isNotEmpty()) item { HHSection(stringResource(R.string.hh_tab_contacts)) }
        // контакт — одна строка: первый номер и «+N»; нажатие — карточка со всеми номерами
        items(con, key = { "p" + hhCardKey(it) }) { c ->
            val uris = c.uris()
            val first = uris.firstOrNull()
            val type = first?.label?.ifBlank { null }?.let {
                android.provider.ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, Contact.stringToType(it), it).toString()
            }
            val sub = listOfNotNull(type, first?.let { HHUiLogic.peerKey(it.uri) }).joinToString(" · ") +
                if (uris.size > 1) "  +${uris.size - 1}" else ""
            HHPersonRow(c.name(), first?.uri, c, sub, starred = c.favorite(), onStar = { Contact.toggleFavorite(ctx, c) }) { sheet = c }
        }
    }
}

@Composable
private fun HHSection(title: String) =
    Text(title, Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontSize = 13.sp,
        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun HHPersonRow(name: String, peer: String?, contact: Contact?, sub: String, starred: Boolean, onStar: (() -> Unit)?,
                        onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HHAvatar(name, peer, 40.dp, contact)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var smsVer by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { val off = HHSms.observe(ctx) { smsVer++ }; onDispose { off() } }
    LaunchedEffect(Unit) {
        while (!HHHistory.restored) delay(300)
        withContext(Dispatchers.IO) { HHSms.migrate(ctx) }
        smsVer++
    }
    // SMS-беседы из системной базы + беседы HHPhone через станцию (и SIM, которых нет в системе)
    val chats by produceState(emptyList<Message>(), messages, smsVer) {
        value = withContext(Dispatchers.IO) {
            val sys = HHSms.threads(ctx)
            val own = messages.sortedByDescending { it.timeStamp }.distinctBy { it.aor + "|" + it.peerUri }
                .filter { m -> sys.isEmpty() || m.aor != HHSms.MOBILE || sys.none { HHUiLogic.samePhone(it.peerUri, m.peerUri) } }
            (sys + own).sortedByDescending { it.timeStamp }.distinctBy { it.aor + "|" + it.peerUri }   // ключ LazyColumn
        }
    }
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
                    Text(name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
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

/** Настройки HHPhone во вкладках «Настроек»: применяются сразу, без кнопки «Сохранить». */

/** Запись разговоров: режим, срок хранения, папка, служба для SIM. */
@Composable
internal fun HHRecSettings() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(HHRec.mode(ctx)) }
    var keep by remember { mutableStateOf(HHRec.keepDays(ctx)) }
    var keepMenu by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(HHRec.folderName(ctx)) }
    // служба специальных возможностей включается в системных настройках — проверка при возврате
    var a11y by remember { mutableStateOf(HHRecAccessibility.enabled(ctx)) }
    var devOn by remember { mutableStateOf(HHAdbGrant.devOptionsOn(ctx)) }
    var canToggle by remember { mutableStateOf(HHRecAccessibility.canToggle(ctx)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            a11y = HHRecAccessibility.enabled(ctx); devOn = HHAdbGrant.devOptionsOn(ctx); canToggle = HHRecAccessibility.canToggle(ctx)
        } }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) try { HHRec.setFolder(ctx, tree); folder = HHRec.folderName(ctx) }
            catch (e: Exception) { Toast.makeText(ctx, R.string.hh_rec_folder_failed, Toast.LENGTH_LONG).show() }
    }
    val keepLabel = @Composable { d: Int -> if (d == 0) stringResource(R.string.hh_rec_keep_forever) else stringResource(R.string.hh_rec_keep_days, d) }
    Column(Modifier.padding(end = 10.dp)) {
        Text(stringResource(R.string.hh_rec_title), fontSize = 18.sp)
        listOf(HHRec.OFF to R.string.hh_rec_off, HHRec.BUTTON to R.string.hh_rec_button, HHRec.AUTO to R.string.hh_rec_auto).forEach { (m, label) ->
            Row(Modifier.fillMaxWidth().clickable { mode = m; HHRec.setMode(ctx, m) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == m, onClick = { mode = m; HHRec.setMode(ctx, m) })
                Text(stringResource(label))
            }
        }
        if (mode != HHRec.OFF) {
            Box {
                Row(Modifier.fillMaxWidth().clickable { keepMenu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hh_rec_keep), Modifier.weight(1f))
                    Text(keepLabel(keep), color = MaterialTheme.colorScheme.primary)
                }
                androidx.compose.material3.DropdownMenu(expanded = keepMenu, onDismissRequest = { keepMenu = false }) {
                    HHRec.KEEP_DAYS.forEach { d ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(keepLabel(d)) }, onClick = {
                            keep = d; keepMenu = false; Thread { HHRec.setKeepDays(ctx, d) }.start()
                        })
                    }
                }
            }
            // папка записей: внутренняя (видна только HHPhone) или выбранная пользователем
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hh_rec_folder))
                    Text(folder ?: stringResource(R.string.hh_rec_folder_internal), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { try { pick.launch(null) } catch (_: Exception) {} }) { Text(stringResource(R.string.hh_rec_folder_choose)) }
            }
            if (folder != null) TextButton(onClick = { HHRec.setFolder(ctx, null); folder = null }) {
                Text(stringResource(R.string.hh_rec_folder_reset))
            }
            // SIM: без службы специальных возможностей Android глушит микрофон во время звонка
            if (canToggle)
                Text(stringResource(R.string.hh_rec_a11y_auto), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text(stringResource(R.string.hh_rec_a11y_adb, ctx.packageName), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // без компьютера: «Отладка по Wi-Fi» → код в уведомлении HHPhone (HHAdbGrant)
                if (HHAdbGrant.supported()) {
                    val step by HHAdbGrant.state
                    if (!devOn) {
                        Text(stringResource(R.string.hh_adb_need_dev), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        }) { Text(stringResource(R.string.hh_adb_open_about)) }
                    } else {
                        Text(stringResource(R.string.hh_adb_steps), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { HHAdbGrant.begin(ctx) }) { Text(stringResource(R.string.hh_adb_start)) }
                    }
                    step?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (!a11y && !canToggle) {
                Text(stringResource(R.string.hh_rec_sim_a11y), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.hh_rec_open_a11y)) }
            }
        }
        Text(stringResource(R.string.hh_rec_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Переключатель в стиле «Настроек»: подпись (по нажатию — пояснение, если есть) и Switch. */
@Composable
internal fun HHSettingSwitch(title: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).clickable(enabled = help != null) { Toast.makeText(ctx, help, Toast.LENGTH_LONG).show() }, fontSize = 18.sp)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Вибрация клавиш набора и постоянное подключение (без push у какой-то учётки — включено и не выключается). */
@Composable
internal fun HHGeneralSwitches() {
    val ctx = LocalContext.current
    var vibe by remember { mutableStateOf(HHKeyVibe.enabled(ctx)) }
    HHSettingSwitch(stringResource(R.string.hh_key_vibration), vibe) { HHKeyVibe.toggle(ctx); vibe = HHKeyVibe.enabled(ctx) }
    val noPush = HHPush.withoutPush()
    var keep by remember { mutableStateOf(HHPush.keepAlive) }
    HHSettingSwitch(stringResource(R.string.hh_keep_alive), keep || noPush.isNotEmpty()) {
        if (noPush.isNotEmpty())   // выключить нельзя: без push звонки на эти учётки не дойдут
            Toast.makeText(ctx, ctx.getString(R.string.hh_keep_alive_forced, noPush.joinToString { it.removePrefix("sip:") }), Toast.LENGTH_LONG).show()
        else { HHPush.keepAlive = it; keep = it }
    }
}
