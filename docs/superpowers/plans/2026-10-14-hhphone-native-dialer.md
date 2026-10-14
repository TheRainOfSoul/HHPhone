# HHPhone 0.6.0 — системный журнал, системные SMS, роли — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HHPhone показывает и пишет звонки в системный `CallLog`, SMS — в системный `Telephony`, и один раз просит роли звонилки и SMS.

**Architecture:** Чистая логика отображения строк CallLog и сравнения номеров — в `HHUiLogic` (JUnit). Доступ к провайдерам — два новых файла `HHCallLog.kt` и `HHSms.kt`. Точки подключения: `CallHistoryNew.add()` (все звонки станции проходят через него), `SmsReceiver`, `ChatScreen` (отправка/загрузка/удаление SIM-сообщений), вкладки `HHHome`, `MainActivity.onResume` (роли).

**Tech Stack:** Kotlin, Jetpack Compose, Android ContentProvider (`CallLog.Calls`, `Telephony.Sms`), SmsManager, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-14-hhphone-native-dialer-design.md`

## Global Constraints

- Пакет `com.tutpro.baresip.plus`, файлы `app/src/main/kotlin/com/tutpro/baresip/plus/`; строки HH — `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml` (ru/hy/en).
- minSdk 28; провайдеры читаются только при выданном разрешении, иначе — прежнее поведение (`CallHistoryNew`, `BaresipService.messages`).
- SIM-аккаунт: aor `sip:mobile@pstn`, собеседник `tel:<номер>`.
- Сборка: `./gradlew :app:testHhDebugUnitTest`, `:app:assembleHhRelease`; на эмулятор ставить release (подпись совпадает с установленной).
- Версия выпуска 0.6.0, versionCode 11 (flavor hh в `app/build.gradle.kts`), выпуск `scripts/release.ps1`.
- Ничего из ключей/паролей не коммитить (репозиторий публичный).

## Review Focus

1. Звонок станции не дублируется в журнале (CallLog пишет только `CallHistoryNew.add` для не-SIM aor; SIM пишет сама система).
2. Номер +374… и 0… — одна беседа (`HHUiLogic.samePhone`).
3. Без ролей/разрешений журнал и чаты работают как в 0.5.1.
4. Длинное SMS уходит частями, статус в системе становится SENT/FAILED.
5. Перенос (журнал, SMS) выполняется один раз и не создаёт дублей при повторном запуске.

---

### Task 1: Чистая логика CallLog и номеров

**Files:**
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/HHUiLogic.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHUiLogicTest.kt`

**Interfaces:**
- Produces:
  - `HHUiLogic.callLogEntry(number: String, type: Int, date: Long, durationSec: Long, accountId: String?, allowed: Boolean): HHLogEntry`
  - `HHUiLogic.callLogType(dir: String, start: Long?, rejected: Boolean): Int`
  - `HHUiLogic.samePhone(a: String, b: String): Boolean`
  - `HHUiLogic.sameTime(a: Long, b: Long): Boolean` (±5 с)
  - константы типов CallLog: `HHUiLogic.CL_IN=1, CL_OUT=2, CL_MISSED=3, CL_VOICEMAIL=4, CL_REJECTED=5, CL_BLOCKED=6, CL_EXTERNAL=7`

- [ ] **Step 1: тесты**

```kotlin
    @Test fun callLogSimIncoming() {
        val e = HHUiLogic.callLogEntry("+37491123456", HHUiLogic.CL_IN, 1000, 30, "8937401", true)
        assertEquals(HHLogEntry("tel:+37491123456", "sip:mobile@pstn", "in", 1000, 31000, false), e)
    }
    @Test fun callLogStationOutgoing() {
        val e = HHUiLogic.callLogEntry("100", HHUiLogic.CL_OUT, 1000, 5, "sip:101@pbx.local", true)
        assertEquals(HHLogEntry("sip:100@pbx.local", "sip:101@pbx.local", "out", 1000, 6000, false), e)
    }
    @Test fun callLogMissedRejectedBlocked() {
        assertEquals(null, HHUiLogic.callLogEntry("1", HHUiLogic.CL_MISSED, 1000, 0, null, true).start)
        assertEquals(false, HHUiLogic.callLogEntry("1", HHUiLogic.CL_MISSED, 1000, 0, null, true).rejected)
        assertTrue(HHUiLogic.callLogEntry("1", HHUiLogic.CL_REJECTED, 1000, 0, null, true).rejected)
        assertTrue(HHUiLogic.callLogEntry("1", HHUiLogic.CL_BLOCKED, 1000, 0, null, true).rejected)
        assertEquals(null, HHUiLogic.callLogEntry("1", HHUiLogic.CL_VOICEMAIL, 1000, 0, null, true).start)
    }
    @Test fun callLogHiddenNumber() {
        assertEquals("tel:anonymous", HHUiLogic.callLogEntry("", HHUiLogic.CL_IN, 1, 0, null, true).peer)
        assertEquals("tel:anonymous", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, null, false).peer)
    }
    @Test fun callLogTypeForStationCalls() {
        assertEquals(HHUiLogic.CL_OUT, HHUiLogic.callLogType("out", null, false))
        assertEquals(HHUiLogic.CL_IN, HHUiLogic.callLogType("in", 5, false))
        assertEquals(HHUiLogic.CL_REJECTED, HHUiLogic.callLogType("in", null, true))
        assertEquals(HHUiLogic.CL_MISSED, HHUiLogic.callLogType("in", null, false))
    }
    @Test fun samePhoneIgnoresCountryPrefix() {
        assertTrue(HHUiLogic.samePhone("+37491123456", "091123456"))
        assertTrue(HHUiLogic.samePhone("tel:+374 91 12-34-56", "+37491123456"))
        assertEquals(false, HHUiLogic.samePhone("+37491123456", "+37491123457"))
        assertTrue(HHUiLogic.samePhone("100", "100"))
        assertEquals(false, HHUiLogic.samePhone("100", "2100"))
    }
    @Test fun sameTimeWithinFiveSeconds() {
        assertTrue(HHUiLogic.sameTime(10_000, 14_999))
        assertEquals(false, HHUiLogic.sameTime(10_000, 15_001))
    }
```

- [ ] **Step 2: запустить — FAIL (Unresolved reference)** — `./gradlew -q :app:testHhDebugUnitTest --tests "*HHUiLogicTest*"`

- [ ] **Step 3: реализация в `HHUiLogic`**

```kotlin
    const val CL_IN = 1; const val CL_OUT = 2; const val CL_MISSED = 3; const val CL_VOICEMAIL = 4
    const val CL_REJECTED = 5; const val CL_BLOCKED = 6; const val CL_EXTERNAL = 7

    /** Строка системного журнала → запись журнала HHPhone; accountId «sip:…» — звонок станции, иначе SIM. */
    fun callLogEntry(number: String, type: Int, date: Long, durationSec: Long, accountId: String?, allowed: Boolean): HHLogEntry {
        val station = accountId != null && accountId.startsWith("sip:")
        val aor = if (station) accountId!! else "sip:mobile@pstn"
        val peer = when {
            !allowed || number.isBlank() -> "tel:anonymous"
            station -> "sip:$number@" + accountId!!.substringAfter("@")
            else -> "tel:$number"
        }
        val answered = type == CL_IN || type == CL_OUT || type == CL_EXTERNAL
        return HHLogEntry(peer, aor, if (type == CL_OUT) "out" else "in", if (answered) date else null,
            date + durationSec * 1000, type == CL_REJECTED || type == CL_BLOCKED)
    }

    fun callLogType(dir: String, start: Long?, rejected: Boolean): Int = when {
        dir == "out" -> CL_OUT
        start != null -> CL_IN
        rejected -> CL_REJECTED
        else -> CL_MISSED
    }

    /** Один номер: цифры совпадают, или у обоих ≥ 8 цифр и совпадают последние 8 (+374 91… = 091…). */
    fun samePhone(a: String, b: String): Boolean {
        val x = peerKey(a).filter { it.isDigit() }; val y = peerKey(b).filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (x.length >= 8 && y.length >= 8 && x.takeLast(8) == y.takeLast(8))
    }

    fun sameTime(a: Long, b: Long) = kotlin.math.abs(a - b) < 5000
```

- [ ] **Step 4: тесты — PASS**
- [ ] **Step 5: commit** `feat(hh): логика системного журнала и сравнения номеров`

---

### Task 2: Системный журнал звонков

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHCallLog.kt`
- Modify: `CallHistory.kt` (`add()`), `BaresipService.kt` (убрать `deleteSystemCallLog` и вызов), `HHHome.kt` (`HHPhoneTab`), `app/src/main/AndroidManifest.xml` (`READ_CALL_LOG`)

**Interfaces:**
- Consumes: Task 1 (`callLogEntry`, `callLogType`, `samePhone`, `sameTime`, `CL_*`)
- Produces: `HHCallLog.read(ctx): List<HHLogEntry>?` (null — нет разрешения), `HHCallLog.write(ctx, aor, peerUri, dir, start: Long?, stop: Long, rejected)`, `HHCallLog.migrate(ctx)`, `HHCallLog.observe(ctx, onChange): () -> Unit` (возвращает отписку)

- [ ] **Step 1: `HHCallLog.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls

/** Системный журнал звонков — единый журнал HHPhone (спека 2026-10-14). */
object HHCallLog {
    private fun can(ctx: Context, p: String) = Utils.checkPermissions(ctx, arrayOf(p))

    fun read(ctx: Context, limit: Int = 500): List<HHLogEntry>? {
        if (!can(ctx, Manifest.permission.READ_CALL_LOG)) return null
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Calls.DATE))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return try {
            ctx.contentResolver.query(Calls.CONTENT_URI, arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION,
                Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION), args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(HHUiLogic.callLogEntry(c.getString(0) ?: "", c.getInt(1), c.getLong(2),
                        c.getLong(3), c.getString(4), c.getInt(5) == Calls.PRESENTATION_ALLOWED))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog read failed: $e"); null
        }
    }

    /** Звонок станции → системный журнал (SIM-звонки пишет сама система). */
    fun write(ctx: Context, aor: String, peerUri: String, dir: String, start: Long?, stop: Long, rejected: Boolean) {
        if (!can(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        val type = HHUiLogic.callLogType(dir, start, rejected)
        val v = ContentValues().apply {
            put(Calls.NUMBER, HHUiLogic.peerKey(peerUri))
            put(Calls.TYPE, type)
            put(Calls.DATE, start ?: stop)
            put(Calls.DURATION, if (start != null) (stop - start) / 1000 else 0L)
            put(Calls.PHONE_ACCOUNT_ID, aor)
            put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, BaresipService.getPhoneAccountHandle(ctx).componentName.flattenToString())
            put(Calls.NEW, if (type == HHUiLogic.CL_MISSED) 1 else 0)
            put(Calls.NUMBER_PRESENTATION, Calls.PRESENTATION_ALLOWED)
        }
        try { ctx.contentResolver.insert(Calls.CONTENT_URI, v) } catch (e: Exception) { Log.e(TAG, "CallLog write failed: $e") }
    }

    /** Один раз: записи CallHistoryNew, которых нет в системном журнале (SIM, удалённые прежними версиями, и станции). */
    fun migrate(ctx: Context) {
        val p = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
        if (p.getBoolean("calllog_migrated", false) || !can(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        val have = read(ctx, 5000) ?: return
        for (h in HHHistory.snapshot()) {
            val start = h.startTime?.timeInMillis; val stop = h.stopTime.timeInMillis
            val date = start ?: stop
            if (have.any { HHUiLogic.samePhone(it.peer, h.peerUri) && HHUiLogic.sameTime(it.start ?: (it.stop), date) ||
                    HHUiLogic.samePhone(it.peer, h.peerUri) && HHUiLogic.sameTime(it.stop, stop) }) continue
            val sim = h.aor.endsWith("@pstn")
            write(ctx, if (sim) "" else h.aor, h.peerUri, h.direction, start, stop, h.rejected)
        }
        p.edit().putBoolean("calllog_migrated", true).apply()
    }

    fun observe(ctx: Context, onChange: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) = onChange() }
        return try {
            ctx.contentResolver.registerContentObserver(Calls.CONTENT_URI, true, o)
            { ctx.contentResolver.unregisterContentObserver(o) }
        } catch (_: SecurityException) { {} }
    }

    private const val TAG = "HHCallLog"
}
```

Для SIM-записей переноса `PHONE_ACCOUNT_ID = ""` → читается как SIM.

- [ ] **Step 2: `CallHistoryNew.add()`** — после `HHHistory.bump()`:

```kotlin
        if (!aor.endsWith("@pstn")) BaresipService.instance?.let { ctx ->   // SIM-звонки в системный журнал пишет Telecom
            Thread { HHCallLog.write(ctx, aor, peerUri, direction, startTime?.timeInMillis, stopTime.timeInMillis, rejected) }.start()
        }
```

- [ ] **Step 3: `BaresipService.kt`** — удалить `if (isMobile) deleteSystemCallLog(peerUri)` и функцию `deleteSystemCallLog` (и переменные `peerUri`/`isMobile`, если больше не используются).

- [ ] **Step 4: манифест** — рядом с `WRITE_CALL_LOG`: `<uses-permission android:name="android.permission.READ_CALL_LOG" tools:ignore="SmsAndCallLogPolicy" />`

- [ ] **Step 5: `HHPhoneTab`** — источник журнала:

```kotlin
    val history by HHHistory.version.collectAsState()
    DisposableEffect(Unit) { val off = HHCallLog.observe(ctx) { HHHistory.bump() }; onDispose { off() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HHCallLog.migrate(ctx) } }
    val log by produceState(emptyList<HHLogGroup>(), history, colleagues) {
        value = withContext(Dispatchers.IO) {
            HHUiLogic.group(HHCallLog.read(ctx) ?: HHHistory.snapshot().map {
                HHLogEntry(it.peerUri, it.aor, it.direction, it.startTime?.timeInMillis, it.stopTime.timeInMillis, it.rejected)
            })
        }
    }
```

- [ ] **Step 6: сборка + тесты PASS**, commit `feat(hh): единый журнал — системный CallLog`

---

### Task 3: Системные SMS

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHSms.kt`
- Modify: `SmsReceiver.kt`, `Message.kt` (поле `smsId`), `ChatScreen.kt` (загрузка, отправка, удаление), `HHHome.kt` (`HHChatsTab`), `AndroidManifest.xml` (receiver `HHSmsSentReceiver`)

**Interfaces:**
- Consumes: Task 1 (`samePhone`, `sameTime`)
- Produces: `HHSms.canRead(ctx)`, `HHSms.isDefault(ctx)`, `HHSms.storeIncoming(ctx, address, body, sentAt, subId)`, `HHSms.send(ctx, address, text): Boolean`, `HHSms.threads(ctx): List<Message>` (последнее сообщение беседы, `new` = есть непрочитанные), `HHSms.thread(ctx, peerUri): List<Message>` (новые сверху), `HHSms.markRead(ctx, peerUri)`, `HHSms.delete(ctx, id)`, `HHSms.migrate(ctx)`, `HHSms.observe(ctx, onChange): () -> Unit`, `HHSms.MOBILE = "sip:mobile@pstn"`

- [ ] **Step 1: `Message.kt`** — последнее поле конструктора `val smsId: Long = 0` (0 — сообщение HHPhone, иначе `_id` в системе; сериализация совместима — поле с умолчанием).

- [ ] **Step 2: `HHSms.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import android.telephony.SmsManager

/** Системная база SMS — единая для HHPhone (спека 2026-10-14). */
object HHSms {
    const val MOBILE = "sip:mobile@pstn"
    fun canRead(ctx: Context) = Utils.checkPermissions(ctx, arrayOf(Manifest.permission.READ_SMS))
    fun isDefault(ctx: Context) = Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    fun storeIncoming(ctx: Context, address: String, body: String, sentAt: Long, subId: Int) {
        if (!isDefault(ctx)) return
        try {
            ctx.contentResolver.insert(Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Sms.ADDRESS, address); put(Sms.BODY, body); put(Sms.DATE, System.currentTimeMillis())
                put(Sms.DATE_SENT, sentAt); put(Sms.READ, 0); put(Sms.SEEN, 0)
                if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
            })
        } catch (e: Exception) { Log.e(TAG, "SMS store failed: $e") }
    }

    /** Отправка с делением длинного текста; SMS-приложение по умолчанию само пишет в базу (Outbox → Sent/Failed). */
    fun send(ctx: Context, address: String, text: String): Boolean = try {
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val row = if (isDefault(ctx)) ctx.contentResolver.insert(Sms.Outbox.CONTENT_URI, ContentValues().apply {
            put(Sms.ADDRESS, address); put(Sms.BODY, text); put(Sms.DATE, System.currentTimeMillis()); put(Sms.READ, 1); put(Sms.SEEN, 1)
        }) else null
        val parts = sm.divideMessage(text)
        val sent = ArrayList(parts.indices.map { i ->
            PendingIntent.getBroadcast(ctx, (System.nanoTime() % Int.MAX_VALUE).toInt() + i,
                Intent(ctx, HHSmsSentReceiver::class.java).setData(row), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        })
        sm.sendMultipartTextMessage(address, null, parts, sent, null)
        true
    } catch (e: Exception) { Log.e(TAG, "SMS send failed: $e"); false }

    private val cols = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ)

    private fun toMessage(c: android.database.Cursor) = Message(MOBILE, "tel:" + (c.getString(2) ?: ""), c.getString(3) ?: "",
        c.getLong(4), when (c.getInt(5)) {
            Sms.MESSAGE_TYPE_INBOX -> MESSAGE_DOWN
            Sms.MESSAGE_TYPE_FAILED -> MESSAGE_UP_FAIL
            Sms.MESSAGE_TYPE_OUTBOX, Sms.MESSAGE_TYPE_QUEUED -> MESSAGE_UP_WAIT
            else -> MESSAGE_UP
        }, 0, "", c.getInt(6) == 0 && c.getInt(5) == Sms.MESSAGE_TYPE_INBOX, smsId = c.getLong(0))

    /** Последнее сообщение каждой беседы (по THREAD_ID). */
    fun threads(ctx: Context): List<Message> {
        if (!canRead(ctx)) return emptyList()
        val seen = HashMap<Long, Message>()
        try {
            ctx.contentResolver.query(Sms.CONTENT_URI, cols, null, null, "${Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(1); val m = toMessage(c)
                    val have = seen[t]
                    if (have == null) seen[t] = m else if (m.new && !have.new) have.new = true
                }
            }
        } catch (e: Exception) { Log.e(TAG, "SMS threads failed: $e") }
        return seen.values.sortedByDescending { it.timeStamp }
    }

    private fun threadIds(ctx: Context, peerUri: String): List<Long> {
        val ids = mutableSetOf<Long>()
        ctx.contentResolver.query(Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) if (HHUiLogic.samePhone(c.getString(1) ?: "", peerUri)) ids += c.getLong(0)
        }
        return ids.toList()
    }

    /** Сообщения беседы с собеседником (новые сверху), +374… и 0… — одна беседа. */
    fun thread(ctx: Context, peerUri: String): List<Message> {
        if (!canRead(ctx)) return emptyList()
        return try {
            val ids = threadIds(ctx, peerUri)
            if (ids.isEmpty()) return emptyList()
            ctx.contentResolver.query(Sms.CONTENT_URI, cols, "${Sms.THREAD_ID} IN (${ids.joinToString(",")})", null,
                "${Sms.DATE} DESC")?.use { c -> buildList { while (c.moveToNext()) add(toMessage(c).copyPeer(peerUri)) } } ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "SMS thread failed: $e"); emptyList() }
    }

    fun markRead(ctx: Context, peerUri: String) {
        if (!isDefault(ctx)) return
        try {
            val ids = threadIds(ctx, peerUri); if (ids.isEmpty()) return
            ctx.contentResolver.update(Sms.CONTENT_URI, ContentValues().apply { put(Sms.READ, 1); put(Sms.SEEN, 1) },
                "${Sms.THREAD_ID} IN (${ids.joinToString(",")}) AND ${Sms.READ} = 0", null)
        } catch (e: Exception) { Log.e(TAG, "SMS mark read failed: $e") }
    }

    fun delete(ctx: Context, id: Long) {
        try { ctx.contentResolver.delete(ContentUris.withAppendedId(Sms.CONTENT_URI, id), null, null) }
        catch (e: Exception) { Log.e(TAG, "SMS delete failed: $e") }
    }

    /** Один раз: SIM-сообщения HHPhone, которых нет в системе, переносятся туда. */
    fun migrate(ctx: Context) {
        val p = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
        if (p.getBoolean("sms_migrated", false) || !isDefault(ctx) || !canRead(ctx)) return
        val mine = synchronized(BaresipService.messagesLock) { BaresipService.messages.filter { it.aor == MOBILE } }
        for (m in mine) {
            val addr = HHUiLogic.peerKey(m.peerUri)
            if (thread(ctx, m.peerUri).any { it.message == m.message && HHUiLogic.sameTime(it.timeStamp, m.timeStamp) }) continue
            try {
                ctx.contentResolver.insert(if (m.direction == MESSAGE_DOWN) Sms.Inbox.CONTENT_URI else Sms.Sent.CONTENT_URI,
                    ContentValues().apply { put(Sms.ADDRESS, addr); put(Sms.BODY, m.message); put(Sms.DATE, m.timeStamp)
                        put(Sms.READ, if (m.new) 0 else 1); put(Sms.SEEN, 1) })
            } catch (e: Exception) { Log.e(TAG, "SMS migrate failed: $e") }
        }
        p.edit().putBoolean("sms_migrated", true).apply()
    }

    fun observe(ctx: Context, onChange: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) = onChange() }
        return try {
            ctx.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, o)
            { ctx.contentResolver.unregisterContentObserver(o) }
        } catch (_: SecurityException) { {} }
    }

    private const val TAG = "HHSms"
}

/** Итог отправки части SMS: SENT или FAILED в системной базе. */
class HHSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val row = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_FAILED
        try {   // неудача одной части не перетирается успехом следующей
            ctx.contentResolver.update(row, ContentValues().apply { put(Sms.TYPE, type) },
                "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_FAILED}", null)
        } catch (_: Exception) {}
    }
}
```

`Message.copyPeer(peerUri)` — в `Message.kt`: `fun copyPeer(p: String) = Message(aor, p, message, timeStamp, direction, responseCode, responseReason, new, images, smsId)` (экран чата фильтрует по своему `peerUri`).

- [ ] **Step 3: манифест** — `<receiver android:name=".HHSmsSentReceiver" android:exported="false" />`

- [ ] **Step 4: `SmsReceiver.onReceive`** — после разбора, до запуска сервиса:

```kotlin
            HHSms.storeIncoming(context, sender, body, timestamp,
                intent.getIntExtra(android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1))
```

- [ ] **Step 5: `ChatScreen`**
  - `loadPeerMessages(aor, peerUri)` получает `ctx`: для `aor == HHSms.MOBILE && HHSms.canRead(ctx)` — `HHSms.thread(ctx, peerUri)` + сообщения HHPhone этой беседы с картинками или в статусе `MESSAGE_UP_WAIT`/`MESSAGE_UP_FAIL` без системной пары, отсортированные по времени (новые сверху); иначе как было.
  - `reloadMessages` дополнительно: `if (aor == HHSms.MOBILE) HHSms.markRead(ctx, peerUri)`.
  - наблюдатель `HHSms.observe(ctx) { reloadMessages() }` в том же `DisposableEffect`, что и `messageUpdate`.
  - отправка SIM без картинок: если `HHSms.canRead(ctx)` — `HHSms.send(ctx, destination, msgText)` без `msg.add()` (сообщение появится из системы); неудача → тост `messageFailed`. Иначе — как было (`Utils.sendSms`).
  - удаление: `if (message.smsId != 0L) HHSms.delete(ctx, message.smsId) else message.delete()`.

- [ ] **Step 6: `HHChatsTab`**

```kotlin
    var smsVer by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { val off = HHSms.observe(ctx) { smsVer++ }; onDispose { off() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HHSms.migrate(ctx) } }
    val chats by produceState(emptyList<Message>(), messages, smsVer) {
        value = withContext(Dispatchers.IO) {
            val sys = HHSms.threads(ctx)
            val own = messages.sortedByDescending { it.timeStamp }.distinctBy { it.aor + "|" + it.peerUri }
                .filter { m -> sys.isEmpty() || m.aor != HHSms.MOBILE || sys.none { HHUiLogic.samePhone(it.peerUri, m.peerUri) } }
            (sys + own).sortedByDescending { it.timeStamp }
        }
    }
```
  (без `READ_SMS` `sys` пуст — как в 0.5.1).

- [ ] **Step 7: сборка + тесты PASS**, commit `feat(hh): SMS в системной базе, вся переписка во вкладке «Чаты»`

---

### Task 4: Роли по умолчанию

**Files:** Modify `MainActivity.kt`

- [ ] **Step 1** — поле и лаунчер (в `onCreate`, рядом с прочими `registerForActivityResult`):

```kotlin
    private lateinit var roleLauncher: ActivityResultLauncher<Intent>
    ...
        roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val rm = getSystemService(ROLE_SERVICE) as android.app.role.RoleManager
            if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) &&
                !Utils.checkPermissions(this, arrayOf(READ_PHONE_STATE, READ_PHONE_NUMBERS)))
                requestPermissionsLauncher.launch(arrayOf(READ_PHONE_STATE, READ_PHONE_NUMBERS))
            startService(Intent(this, BaresipService::class.java).setAction("Check Roles"))
        }
```

- [ ] **Step 2** — в `onResume` после блока полноэкранных уведомлений (если это окно не показано в этот раз):

```kotlin
        else if (Build.VERSION.SDK_INT >= 29 && !Call.inCall() && Utils.checkPermissions(this, arrayOf(RECORD_AUDIO))) {
            val rm = getSystemService(ROLE_SERVICE) as android.app.role.RoleManager
            val ui = getSharedPreferences("hh_ui", MODE_PRIVATE)
            for ((role, key) in listOf(android.app.role.RoleManager.ROLE_DIALER to "dialer_asked", android.app.role.RoleManager.ROLE_SMS to "sms_asked"))
                if (rm.isRoleAvailable(role) && !rm.isRoleHeld(role) && !ui.getBoolean(key, false)) {
                    ui.edit().putBoolean(key, true).apply()
                    roleLauncher.launch(rm.createRequestRoleIntent(role))
                    break   // следующая роль — при следующем onResume (после закрытия окна)
                }
        }
```

- [ ] **Step 3: сборка**, commit `feat(hh): один раз предлагаем роли звонилки и SMS`

---

### Task 5: Проверка на эмуляторе и выпуск 0.6.0

- [ ] Установить release на `HHPhone_Test` поверх; сбросить флаги ролей нельзя (release) — роли выдать через окно или `adb shell cmd role add-role-holder android.app.role.DIALER am.dgsolutions.hhphone` / `android.app.role.SMS`.
- [ ] Входящий SIM `adb emu gsm call 091123456`, сброс `adb emu gsm cancel 091123456` → пропущенный виден в журнале HHPhone и в `content query --uri content://call_log/calls`.
- [ ] Звонок станции (101 → *9196 или 100) → строка с `phone_account_id=sip:101@…` в системном журнале, в журнале HHPhone без «SIM».
- [ ] `adb emu sms send 091123456 Привет` → в `content://sms/inbox` и во вкладке «Чаты» (имя «Ани Иванова»); ответ уходит, `type=2`.
- [ ] Длинное SMS (300 знаков) уходит.
- [ ] SMS, вставленная `content insert --uri content://sms/inbox`, видна в «Чатах».
- [ ] Повторный запуск — дублей переноса нет.
- [ ] Финальное ревью ветки, bump 0.6.0 / versionCode 11, `scripts/release.ps1`.
