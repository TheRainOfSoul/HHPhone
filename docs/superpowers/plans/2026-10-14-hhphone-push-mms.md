# HHPhone 0.8.0 + HHPBX 1.12.0 — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** push без постоянной службы, push и хранение сообщений станции, MMS из системы, отчёты о доставке, удаление/объединение контактов, меню контакта с мессенджерами, мелочи 0.7.0.

**Architecture:** HHPBX: chatplan → `app/hhphone_push/message.lua` (push `t=msg`, ожидание, иначе `v_hhphone_messages`), hook `sofia::register` → `deliver.lua`; `hhphone_fcm::send($token, $type)`. HHPhone: чистая логика в `HHUiLogic`; простой службы — `HHIdle` (новый, в `HHPushService.kt`); MMS/доставка — `HHSms.kt`; контакты — `HHContact.kt`; меню — `HHCallSheet.kt`.

**Tech Stack:** Kotlin/Compose, ContactsContract, Telephony MMS provider, FreeSWITCH Lua (mod_sms chatplan, event hooks), PHP, PostgreSQL, JUnit, PHP-тесты `windows/tests/t_hhphone.php`, стенд `linux/tests/vm/hhphone-push-scenarios.sh`.

**Spec:** `docs/superpowers/specs/2026-10-14-hhphone-push-mms-design.md`

## Global Constraints

- Строки HHPhone ru/hy/en; HHPBX — без интерфейса (строк нет).
- HHPBX: bump `windows/VERSION` 1.12.0, коммит и пуш до сборки; ключ FCM стенда и токены — сохранять (тест подменяет и восстанавливает); перед звонком/сообщением на 101 — предупредить пользователя (S24 тоже 101).
- Без push у любой учётки станции — прежний постоянный режим.
- HHPhone 0.8.0 versionCode 13; HHPBX выпуск — сборка, образы, `image-test.sh`, filebox.

## Review Focus

1. Служба не останавливается во время звонка, входящего вызова, открытого экрана, отправки MMS.
2. Push `t=msg` при уже запущенной службе — только перерегистрация.
3. Сохранённые сообщения доставляются один раз и по порядку; повторная регистрация не дублирует.
4. Номер с 0 → E.164 по стране SIM; без страны — ссылки мессенджеров не показываются.
5. Объединение контактов не трогает сырые контакты аккаунтов только для чтения.

---

### Task 1 (HHPhone): чистая логика
`HHUiLogic`: `pushOnly(stations: List<Boolean>, keepAlive: Boolean)`, `numberOps` (id ≤ 0 — новый), `mmsAddresses(rows: List<Pair<String, Int>>, own: String): Pair<String?, List<String>>` (137 — от кого, 151/130/129 — кому, без своего), `deliveryMark(type: Int, status: Int): String`, `toE164(number, countryCode: String?): String?`, `messengerLinks(e164): Map<String, String>`. Тесты на каждую. Commit.

### Task 2 (HHPhone): режим push без постоянной службы
- `HHIdle`: после ухода с экрана (`MainActivity.onStop`), конца звонка, старта от push/SMS — таймер 30 с; при срабатывании, если `pushOnly(...)`, нет звонков, экран не открыт — `BaresipService` action `Stop`.
- `HHPushService.onMessageReceived`: `t == "call" || t == "msg"`.
- Меню ⋮ «Постоянное подключение» (`hh_ui/keep_alive`).
- Commit.

### Task 3 (HHPhone): MMS из системы
`HHSms.threads/thread` + `content://mms` (части, адреса), групповые беседы; `Message.images` — `content://mms/part/<id>`. Commit.

### Task 4 (HHPhone): отчёты о доставке
`deliveryIntents` → `HHSmsSentReceiver` (`STATUS`), отметки в `ChatScreen`. Commit.

### Task 5 (HHPhone): удалить / объединить контакт
Меню ⋮ в карточке; `delete(Contacts/<id>)`; `AggregationExceptions.TYPE_KEEP_TOGETHER` (только записываемые сырые контакты); выбор контакта — диалог со списком и поиском. Commit.

### Task 6 (HHPhone): меню контакта и мессенджеры
Нажатие на контакт → `HHContactSheet` (шапка, номера с вызовом/SMS/копировать, история по всем номерам, карточка, мессенджеры); мессенджеры и в `HHCallSheet`; `<queries>` в манифесте; маршрут истории по нескольким номерам. Commit.

### Task 7 (HHPhone): мелочи 1–4, 6
sip чужого домена в «Создать контакт»; `key` и `rememberSaveable` в правке; запасное удаление по номеру + одна запись; `HHPeople` ключ — полный sip чужого домена; без автооткрытия беседы. Commit.

### Task 8 (HHPBX): push и хранение сообщений
- `hhphone_fcm::send($token, $type = 'call')`, `push_send.php <domain> <ext> [type]`; тест `t_hhphone.php` на тело push.
- `app_config.php`: таблица `v_hhphone_messages` (uuid, domain_uuid, extension_uuid, from_user, from_host, body, insert_date).
- `chatplan/default.xml` → `message.lua`; `lua.conf.xml` hook `CUSTOM sofia::register` → `deliver.lua`.
- стенд: сценарии сообщений в `hhphone-push-scenarios.sh` (+ `sipreg.py` MESSAGE).
- VERSION 1.12.0, коммит, пуш, сборка, образы, проверка образа, filebox.

### Task 9: проверка и выпуск HHPhone 0.8.0
Эмулятор по спеке, финальное ревью обеих веток, выпуск.
