# HHPhone 0.7.0 — карточка контакта, меню звонка, плавный журнал — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** контакт — одна строка; меню звонка вместо мгновенного вызова; своя карточка контакта с правкой и блокировкой; журнал без фризов; тихое статус-уведомление; мелочи ревью 0.6.0.

**Architecture:** чистая логика (группировка, строки CallLog, разница номеров) — `HHUiLogic` + JUnit. Новые файлы: `HHContact.kt` (экран карточки + `HHContactStore` запись в ContactsContract + `HHBlock`), `HHCallSheet.kt` (меню звонка + экран истории номера). Кеш собеседников `HHPeople` в `HHParts.kt`. Подключение — `HHHome.kt`, `MainScreen.kt` (маршруты), `BaresipService.kt` (канал уведомления, isBlocked).

**Tech Stack:** Kotlin, Compose Material3 (`ModalBottomSheet`), ContactsContract, BlockedNumberContract, CallLog, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-14-hhphone-contacts-design.md`

## Global Constraints

- Строки ru/hy/en в `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`.
- Без разрешений/ролей — поведение как в 0.6.0 (блокировка скрыта без `canCurrentUserBlockNumbers`).
- Запись в ContactsContract — только `applyBatch` по `_ID`, никаких выборок по имени.
- Провайдеры — не в главном потоке (кроме одиночных IPC по нажатию).
- Выпуск 0.7.0, versionCode 12, `scripts/release.ps1`; на эмулятор — release.

## Review Focus

1. Контакт из нескольких сырых контактов (Google + SIM): правка не портит чужие строки, новые номера идут в сырой контакт с именем.
2. Пустой номер в правке = удаление; номер без изменений — без записи.
3. Блокировка: SIM-звонок/SMS с заблокированного номера не доходит; звонок через станцию — 486; разблокировка снимает все номера.
4. Журнал: после правки контакта имена в журнале обновляются (кеш сбрасывается).
5. Удаление из журнала удаляет все звонки группы и только их.

---

### Task 1: Чистая логика

**Files:** `HHUiLogic.kt`, `HHUiLogicTest.kt`

**Produces:**
- `HHLogEntry(..., val id: Long = 0)`; `HHLogGroup(peer, aor, last, count, val entries: List<HHLogEntry> = listOf(last))`
- `HHUiLogic.group` — объединение по `peerKey ==` или `samePhone`, `entries` группы
- `HHUiLogic.callLogEntry(number, type, date, durationSec, accountId, allowed, component: String? = null, id: Long = 0)` — SIM / станция / другое приложение (`aor = ""`); номер станции с `@` → `sip:<number>`
- `HHUiLogic.callLogNumber(peerUri, aor): String` — `user` или `user@домен` (чужой домен)
- `data class HHNum(val id: Long, val number: String, val type: Int, val label: String = "")`
- `sealed class HHNumOp { Update(id, num) ; Delete(id) ; Insert(num) }` + `HHUiLogic.numberOps(old, new): List<HHNumOp>`

- [ ] тесты:

```kotlin
    @Test fun groupMergesSamePhoneAndKeepsEntries() {
        val g = HHUiLogic.group(listOf(e("tel:+37491123456", 30), e("tel:091123456", 20), e("tel:100", 10)))
        assertEquals(2, g.size); assertEquals(2, g[0].count); assertEquals(listOf(30L, 20L), g[0].entries.map { it.stop })
    }
    @Test fun callLogEntryOtherAppIsNotSim() {
        val e = HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "acc1", true, "com.whatsapp/.Voip", 7)
        assertEquals("", e.aor); assertEquals(7L, e.id)
        assertEquals("sip:mobile@pstn", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "1",
            true, "com.android.phone/com.android.services.telephony.TelephonyConnectionService").aor)
        assertEquals("sip:mobile@pstn", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "1", true, null).aor)
    }
    @Test fun callLogEntryForeignDomain() {
        assertEquals("sip:alice@other.com", HHUiLogic.callLogEntry("alice@other.com", HHUiLogic.CL_OUT, 1, 0, "sip:101@pbx", true).peer)
    }
    @Test fun callLogNumberKeepsForeignDomain() {
        assertEquals("100", HHUiLogic.callLogNumber("sip:100@pbx", "sip:101@pbx"))
        assertEquals("alice@other.com", HHUiLogic.callLogNumber("sip:alice@other.com;transport=tcp", "sip:101@pbx"))
        assertEquals("+37491", HHUiLogic.callLogNumber("tel:+37491", "sip:101@pbx"))
    }
    @Test fun numberOpsDiff() {
        val old = listOf(HHNum(1, "100", 2), HHNum(2, "200", 1), HHNum(3, "300", 3))
        val new = listOf(HHNum(1, "100", 2), HHNum(2, "201", 1), HHNum(3, " ", 3), HHNum(0, "400", 2), HHNum(0, "", 2))
        assertEquals(listOf(HHNumOp.Update(2, HHNum(2, "201", 1)), HHNumOp.Delete(3), HHNumOp.Insert(HHNum(0, "400", 2))),
            HHUiLogic.numberOps(old, new))
        assertEquals(listOf(HHNumOp.Delete(1)), HHUiLogic.numberOps(listOf(HHNum(1, "1", 2)), emptyList()))
    }
```

- [ ] RED → реализация → GREEN, commit `feat(hh): логика групп, строк журнала и правки номеров`

### Task 2: Плавный журнал и контакты одной строкой

**Files:** `HHParts.kt` (`HHPeople`, `HHAvatar(contact)`), `HHCallLog.kt` (чтение `_ID`, `PHONE_ACCOUNT_COMPONENT_NAME`; запись `callLogNumber`), `HHHome.kt` (ключи, `HHPeopleTab`)

- `object HHPeople { fun contact(peer): Contact?; fun name(peer): String }` — `HashMap` по `peerKey`, сброс при `BaresipService.contacts !== последнему` или смене `HHColleagues.list.value`.
- `hhName`/`hhContact` → `HHPeople`. `HHAvatar(name, peer, size, contact: Contact? = HHPeople.contact(peer))`.
- журнал: `key = "${it.peer}|${it.last.stop}"`, `tick` только читается в строке (подписи времени).
- `HHPeopleTab`: строки контактов — `items(con, key = { "p${it.id()}" })`, подпись `первый номер` + `" +N"`, нажатие → `navController.navigate("hh_contact/a<id>")` (`b<id>` для baresip).
- [ ] сборка, commit `perf(hh): журнал без повторных поисков контактов; контакт — одна строка`

### Task 3: Меню звонка и история номера

**Files:** создать `HHCallSheet.kt`; `HHHome.kt` (нажатие строки → лист), `MainScreen.kt` (маршрут `hh_history/{peer}`), `HHCallLog.kt` (`delete(ctx, ids)`), строки.

- `@Composable fun HHCallSheet(g: HHLogGroup, viewModel, navController, lines, onDismiss, showHistory = true)` — `ModalBottomSheet`: шапка, кнопки линий (`HHUiLogic.dialTarget` для каждой линии + `HHCalls.dial`), «SMS», «История звонков», «Карточка контакта»/«Создать контакт», «Скопировать номер», «Удалить из журнала».
- SMS: `tel:`/номер → `chat/sip:mobile@pstn/tel:<номер>` (если есть SIM-линия), `sip:` → `chat/<aor строки>/<peer>`.
- удаление: `HHCallLog.delete(ctx, g.entries.map { it.id })` если все id > 0, иначе `CallHistoryNew` — удалить записи с тем же `stopTime`.
- `hh_history/{peer}`: список `HHCallLog.read()` (или снимок) по `samePhone`, без группировки; строка → `HHCallSheet(showHistory=false)`.
- [ ] сборка, commit `feat(hh): меню звонка в журнале и история номера`

### Task 4: Карточка контакта

**Files:** создать `HHContact.kt` (`HHContactScreen`, `HHContactStore`, `HHBlock`); `MainScreen.kt` (маршрут `hh_contact/{key}`); `HHHome.kt` (иконка карточки, «Добавить контакт» → `hh_contact/n<номер>`); `BaresipService.isBlocked` (+ `HHBlock.isBlocked`); строки.

- `HHContactStore.load(ctx, key): HHCard?` — `HHCard(key, name, nums: List<HHNum>, photo: Uri?/Bitmap?, favorite, rawId)`; для `a<id>` читает `Data` по `CONTACT_ID` (Phone/Sip/StructuredName/Photo).
- `HHContactStore.save(ctx, before: HHCard?, after: HHCard, photo: PhotoChange): Boolean` — `applyBatch` по `numberOps` + имя + новый сырой контакт; фото через `RawContacts.DisplayPhoto`; baresip-контакт — через `Contact.update/addBaresipContact`.
- `HHBlock.can(ctx)`, `isBlocked(ctx, number)`, `set(ctx, numbers, block: Boolean)` через `BlockedNumberContract`.
- Экран: просмотр (фото 120 dp, имя, звезда, номера с кнопками линий и SMS, «Изменить», «Заблокировать»), правка (имя, номера с типом и удалением, «+ номер», фото выбрать/удалить, «Сохранить»/«Отмена»). Новый (`n…`) сразу в правке.
- [ ] сборка, commit `feat(hh): своя карточка контакта — правка имени, номеров, фото, блокировка`

### Task 5: Тихое уведомление и мелочи

**Files:** `Constants.kt`, `BaresipService.kt` (канал `STATUS_CHANNEL_ID` IMPORTANCE_MIN; `snb` и ветка без звонка в `updateStatusNotification`; `storeIncoming` в потоке), `HHSms.kt` (код PendingIntent), `ChatScreen.kt` (тост при удалении без роли), строки.
- [ ] сборка, commit `feat(hh): тихое статус-уведомление и мелочи ревью`

### Task 6: Проверка и выпуск

- [ ] эмулятор по спеке (контакт с 3 номерами, правка/фото/новый, блокировка `adb emu gsm call`, меню, удаление, история, 200+ строк журнала, уведомление без значка)
- [ ] финальное ревью, 0.7.0 / versionCode 12, `scripts/release.ps1`
