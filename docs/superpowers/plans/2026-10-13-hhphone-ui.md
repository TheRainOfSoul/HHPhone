# HHPhone 0.5.0 — новый интерфейс: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** заменить главный экран, входящий и разговор HHPhone на экраны по образцу True Phone в HHDesign (светлая/тёмная), с коллегами со станции HHPBX.

**Architecture:** новые Compose-экраны в `HH*.kt`; маршрут `"main"` показывает `HHMain`: есть звонок → `HHIncoming`/`HHInCall`, нет → `HHHome` (вкладки + журнал + клавиатура). Действия со звонками — через фасад `HHCalls` поверх существующих функций baresip (private → internal точечно). Чистая логика (журнал, кружки, время, линии, разбор коллег) — в `HHUiLogic.kt`, тестируется JUnit. HHPBX 1.11.0 отдаёт коллег через `directory.php` (та же проверка, что push).

**Tech Stack:** Kotlin + Jetpack Compose (Material3), JUnit4; HHPBX PHP 8.4 + PostgreSQL; VM-сценарии bash.

**Spec:** `docs/superpowers/specs/2026-10-13-hhphone-ui-design.md`

## Global Constraints

- Языки ru/hy/en — каждая новая строка в `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`.
- Цвета только из темы (`MaterialTheme.colorScheme`, `hhHeader`) + зелёный вызова `colorResource(R.color.colorTrafficGreen)`, красный `MaterialTheme.colorScheme.error`.
- Светлая и тёмная тема на всех новых экранах (экраны звонка следуют теме).
- Пакет `com.tutpro.baresip.plus` не переименовывать; файлы baresip править точечно.
- Видео-звонок (`call.videoCall`) — прежний экран baresip (`MainScreen`), новый интерфейс его не рисует.
- Версии: HHPhone 0.5.0 (versionCode 8), HHPBX 1.11.0; перед сборкой HHPBX — bump `windows\VERSION`, коммит, пуш.
- Kotlin-комментарии без `/*` внутри KDoc.

## Review Focus

1. Звонок приходит, пока открыт `HHHome` (или приложение свёрнуто) — `HHMain` переключается на `HHIncoming` по `viewModel.calls`, и обратно на `HHHome`, когда звонок закрыт. Тест: на устройстве; логика выбора экрана — `HHUiLogicTest.screenFor`.
2. Нет SIM (эмулятор/планшет) или нет станций — кнопок вызова ровно столько, сколько линий; ноль линий — кнопка «Добавить учётку». Тест: `HHUiLogicTest.lines*`.
3. Армянские/кириллические/латинские имена, номера, пустые имена — буква кружка и цвет стабильны. Тест: `HHUiLogicTest.avatar*`.
4. `directory.php` — только свой домен, неверный пароль → 403 и счётчик, 429 после 10. Тест: VM-сценарий.
5. SIM-звонок: «Перевести» неактивна, «Пауза»/«Клавиши» работают через ExternalCall. Тест: на S24.

---

### Task 1: Чистая логика интерфейса

**Files:** Create `app/src/main/kotlin/com/tutpro/baresip/plus/HHUiLogic.kt`; Test `app/src/test/kotlin/com/tutpro/baresip/plus/HHUiLogicTest.kt`

**Interfaces — Produces:**
- `data class HHLogEntry(val peer: String, val aor: String, val dir: String, val start: Long?, val stop: Long, val rejected: Boolean)` (`start` null — не отвечен).
- `data class HHLogGroup(val peer: String, val aor: String, val last: HHLogEntry, val count: Int)`
- `object HHUiLogic`:
  - `fun group(entries: List<HHLogEntry>): List<HHLogGroup>` — вход по убыванию `stop`; подряд идущие с тем же `peer` (номер, нормализованный `peerKey`) склеиваются, `last` — самый свежий.
  - `fun peerKey(uri: String): String` — `sip:200@host` → `200`, `tel:+374 93 015454` → `+37493015454`.
  - `fun avatarLetter(name: String): String` — первая буква/цифра в верхнем регистре (армянская, кириллица, латиница); пусто → `"#"`.
  - `fun avatarColor(name: String): Int` — индекс 0..7 палитры по стабильному хэшу (`name.lowercase().sumOf { it.code } % 8`).
  - `fun ago(nowMs: Long, thenMs: Long): Pair<Int, Long>` — (единица, значение): 0 «только что» (<1 мин), 1 минуты (<60), 2 часы (<24 ч), 3 «вчера», 4 дата.
  - `data class HHLine(val aor: String, val mobile: Boolean, val registered: Boolean)`; `fun lines(all: List<HHLine>): List<HHLine>` — SIM первой, затем станции; без дублей.
  - `fun screenFor(callStatuses: List<String>, video: Boolean): String` — `"video"` если video; `"incoming"` если есть `incoming` и нет `connected`; `"call"` если есть любой незакрытый; иначе `"home"`.
  - `fun parseDirectory(json: String): List<Pair<String, String>>` — из `{"ok":true,"items":[{"n":"200","name":"Ресепшн"}]}` (номер, имя); мусор → пустой список.

- [ ] **Step 1: тест** — `HHUiLogicTest` с кейсами: группировка (3 подряд одного → count 3; прерванный другим — две группы), `peerKey` для sip/tel, `avatarLetter("Արմեն")=="Ա"`, `("иван")=="И"`, `("")=="#"`, `("093015454")=="0"`; `avatarColor` стабилен и в 0..7; `ago` границы 59 с/60 с/23 ч/вчера; `lines` порядок SIM→станции; `screenFor` для всех ветвей; `parseDirectory` нормальный/битый JSON.
- [ ] **Step 2:** `.\gradlew.bat testHhDebugUnitTest --tests "*HHUiLogicTest*"` → FAIL (Unresolved reference).
- [ ] **Step 3:** реализация `HHUiLogic.kt` (org.json недоступен в JVM-тестах → разбор `parseDirectory` регуляркой `\{"n":"([^"]*)","name":"((?:[^"\\]|\\.)*)"\}` с раскодировкой `\uXXXX` и `\"`).
- [ ] **Step 4:** тот же запуск → PASS; полный `testHhDebugUnitTest` зелёный.
- [ ] **Step 5:** коммит `feat(hh): логика нового интерфейса (журнал, кружки, линии)`.

### Task 2: HHPBX — список коллег `directory.php`

**Files:** Modify `G:\HHPBX\app\hhphone\resources\classes\hhphone.php` (static `directory($db, array $ext): array`); Create `app/hhphone/directory.php`; Test `windows/tests/t_hhphone.php`, `linux/tests/vm/hhphone-push-scenarios.sh` (раздел directory). Ветка HHPBX `feat/hhphone-directory` от main.

**Produces:** `POST /app/hhphone/directory.php` (user, domain, pass) → `{"ok":true,"items":[{"n":"200","name":"..."}]}`; 400 формат, 403 + `fail_count`, 429, 405.

- [ ] **Step 1: тест** t_hhphone.php: `hhphone::directory($fake, ['domain_uuid'=>'d'])` — SQL фильтрует `domain_uuid = :d and enabled = 'true'`, сортировка по `extension`, имя = `effective_caller_id_name` или `description`. VM: 101 получает 100 и 101 своего домена; wrong pass 403; 10 неудач → 429.
- [ ] **Step 2:** прогон → FAIL.
- [ ] **Step 3:** код:
```php
	public static function directory($db, array $ext): array {
		$rows = $db->select("select extension, coalesce(nullif(effective_caller_id_name, ''), description, '') as name from v_extensions
			where domain_uuid = :d and enabled = 'true' order by extension", ['d' => $ext['domain_uuid']], 'all');
		return array_map(fn($r) => ['n' => (string)$r['extension'], 'name' => (string)$r['name']], is_array($rows) ? $rows : []);
	}
```
directory.php — копия начала push.php (405/429/auth/403), затем `echo json_encode(['ok' => true, 'items' => hhphone::directory($db, $ext)], JSON_UNESCAPED_UNICODE);`
- [ ] **Step 4:** прогон → PASS (Windows PHP + VM).
- [ ] **Step 5:** коммит в HHPBX.

### Task 3: Коллеги и звёздочки в HHPhone

**Files:** Create `HHColleagues.kt`; Modify `HHClaim.kt` не нужно (есть `open`).

**Consumes:** `HHPush` prefs `s|<aor>` (server, spki) — сделать `HHPush.station(aor)` internal; `Account.ofAor(aor)?.authPass`; `HHUiLogic.parseDirectory`.
**Produces:** `object HHColleagues { val list: MutableStateFlow<List<HHColleague>>; fun init(ctx); fun refresh(force: Boolean = false) /*сеть, не UI-поток*/; fun isStar(n: String): Boolean; fun toggleStar(n: String) }`, `data class HHColleague(val number: String, val name: String, val aor: String)`. Кэш JSON в prefs `hh_colleagues`, обновление не чаще 24 ч; свой номер скрыт.

- [ ] Step 1–5: тест логики уже в Task 1 (`parseDirectory`); здесь — реализация, вызов `HHColleagues.refresh()` в `BaresipService.hhPushStart()` (поток), `init` в `BaresipApp`; сборка `assembleHhDebug` зелёная; коммит.

### Task 4: Фасад действий со звонками `HHCalls`

**Files:** Modify `MainScreen.kt` (private → internal: `makeCall`, `answer`, `transfer`, `showCall`, `spinToAor`); Create `HHCalls.kt`.

**Produces:** `object HHCalls`:
- `fun dial(ctx, viewModel, aor: String, number: String)` — выбирает ua по aor, `viewModel.dialerState` с номером, `makeCall(ctx, viewModel, number, dialerState, false)`.
- `fun answer(ctx, call)`, `fun reject(call)` (`call.reject()`), `fun hangup(call)` (`terminated=true; hangup(487,"Request Terminated")`).
- `fun toggleHold(call)` (`if (call.onhold) resume() else hold()`), `fun toggleMute(viewModel)`, `fun toggleSpeaker()`, `fun dtmf(call, c: Char)`.
- `fun transfer(ctx, viewModel, call, number)` — слепой перевод (`transfer(..., attended = false)`); для `ExternalCall` — недоступен.
- `fun canTransfer(call) = call !is Call.ExternalCall`.
- [ ] Шаги: правка видимости, фасад, сборка зелёная, коммит.

### Task 5: `HHMain` и главный экран `HHHome`

**Files:** Create `HHHome.kt`, `HHMain.kt`; Modify `MainScreen.kt:244` (`composable("main") { HHMain(...) }`, прежний `MainScreen` — для `"video"`).

`HHHome`: `Scaffold` с шапкой `hhHeader` (лого HH, «⋮» = `hhMenu` пунктов: Учётные записи, Настройки, QR, Выход), `TabRow` 4 вкладки (активная — бордо подчёркивание); «Телефон»: `LazyColumn` журнала (`HHUiLogic.group` из `BaresipService.callHistory` → строки `HHLogRow`: кружок `HHAvatar`, имя `Contact.contactName`, счётчик, стрелка, длительность, `ago`), снизу `HHKeypad` (12 клавиш с буквами, номер сверху, ⌫, подсказки по цифрам из контактов и коллег), ряд: добавить контакт | кнопки линий (`HHUiLogic.lines`, SIM — зелёная с «SIM», станция — зелёная с «HH 101» и точкой) | «⋮». «Контакты»: поиск, «Коллеги», контакты телефона (`BaresipService.contacts`), звезда. «Избранное»: `favorite()` + звёзды коллег. «Чаты»: последние сообщения из `BaresipService.messages` по собеседнику → `navigate("chat/aor/peer")`.

- [ ] Шаги: экран, подключение маршрута, сборка, снимки светлая/тёмная на эмуляторе, коммит.

### Task 6: Входящий `HHIncoming`

**Files:** Create `HHIncoming.kt`. Светлая: фон `surface`, круг 160 dp (фото/буква), тёмный текст; тёмная: фото на весь экран + затемнение, без фото — бордовый градиент. Ползунок: `Box` с `draggable`, порог ±40 % ширины → `HHCalls.reject`/`answer`; подписи «Отклонить»/«Ответить».
- [ ] Шаги: экран, сборка, проверка на эмуляторе входящим со станции (свет/тьма), коммит.

### Task 7: Разговор `HHInCall`

**Files:** Create `HHInCall.kt`. Имя/номер/таймер (`CallTimer` или свой от `startTime`), сетка 2×3 (`HHCallButton`), строка второго звонка (`Call.calls()` > 1 → «Переключить» = `resume()` второго), «Клавиши» — `HHKeypad` в режиме DTMF, «Перевести» — `ModalBottomSheet` с коллегами/контактами/набором → `HHCalls.transfer`; «Завершить» — красная кнопка во всю ширину.
- [ ] Шаги: экран, сборка, звонок 101↔100 на эмуляторе: пауза, без звука, динамик, DTMF, перевод на 100; снимки; коммит.

### Task 8: Строки, проверка на S24, выпуск

- [ ] Все новые строки ru/hy/en (проверка `scripts/missing-strings.py`).
- [ ] S24: SIM вход/выход, станция, входящий на блокировке, перевод, светлая/тёмная.
- [ ] Финальное ревью ветки; HHPBX 1.11.0 (VERSION, README раздел «Коллеги», merge, сборки, filebox); HHPhone 0.5.0 (versionCode 8, merge, `scripts\release.ps1`).
