# HHPhone план 2 — QR-код и блокировка (v0.2.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сотрудник добавляет учётку по QR (системной камерой или встроенным сканером), QR может заблокировать телефон PIN-кодом; админ показывает QR учётки со своего телефона и снимает блокировку PIN-ом.

**Architecture:** Чистая логика (разбор/сборка QR, строка учётки baresip, PIN и решения блокировки) — в `HHQr.kt`, `HHPin.kt`, `HHStaff.kt` и покрыта JVM-тестами. Весь наш UI (диалоги PIN, ошибок, «Заменить?», сканер) рисует один composable `HHHost` в `MainActivity`; в файлах upstream — только однострочные точки входа: перехват deep link, пункты меню, запреты режима сотрудника, кнопка «Показать QR». Учётка создаётся тем же путём, что и в upstream: строка учётки baresip → `UserAgent.uaAlloc` → `ua.add()` → `Account.saveAccounts()`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, kotlinx.serialization (уже в проекте), `com.journeyapps:zxing-android-embedded:4.3.0` (сканер), `com.google.zxing:core:3.5.3` (генерация и тест-декодирование), PBKDF2 (`javax.crypto`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-08-hhphone-design.md` (разделы 4 «QR-код» и 5 «Блокировка»)

## Global Constraints

- QR — ссылка `hhphone://add?c=<base64url(JSON без паддинга)>`; JSON: обязательные `v`, `user`, `pass`, `domain`; необязательные `name`, `authUser`, `proxy`, `transport` (`udp|tcp|tls`, по умолчанию `udp`), `mediaEnc`, `lock`, `pin` (4–8 цифр, требуется при `lock: true`). `v ≠ 1` → «обновите HHPhone». Неизвестные поля игнорируются. Одна учётка на QR.
- Сканер — `zxing-android-embedded` (Apache 2.0, без Google-сервисов). Генерация — `zxing-core`.
- Блокировка — на весь телефон; PIN хранится только как PBKDF2-хэш с солью; 5 неверных PIN подряд → пауза 5 минут, переживает перезапуск.
- Режим сотрудника: можно звонить, история, контакты, чат, голосовая почта, громкость, «О программе», перезапуск, выход, «Добавить по QR». Нельзя: учётки (добавить вручную/изменить/удалить/вкл-выкл регистрацию), видеть пароль, настройки, резервная копия/восстановление, logcat, «Показать QR».
- Новый QR на заблокированном телефоне: PIN в QR совпадает с текущим (или идёт сессия админа) → добавляется сразу; иначе спрашиваем текущий PIN. После авторизации блокировка и PIN берутся из нового QR.
- «Админ» → PIN → полный доступ до ухода приложения в фон; там же «Снять блокировку».
- Kotlin-пакет `com.tutpro.baresip.plus` не трогаем; наш код — `HH*.kt`; правки upstream точечные. Языки ru/hy/en — все новые строки во flavor `hh` (`app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`), `scripts/missing-strings.py` должен давать 0.
- Версия: flavor `hh` `versionCode = 2`, `versionName = "0.2.0"`.

## Отклонения от спеки (уточнения при планировании)

1. «Добавить по QR» — пункт главного меню (а не кнопка в списке учёток): список учёток в режиме сотрудника закрыт, а добавлять по QR сотруднику нужно.
2. `mediaEnc` принимает все значения baresip: `srtp`, `srtp-mand`, `srtp-mandf`, `dtls_srtp`, `dtls_srtpf`, `zrtp` — чтобы админ мог показать QR любой своей учётки.
3. В пароле, имени, логине запрещены символы, ломающие строку учётки baresip (`"`, `;`, `<`, `>`, перевод строки) — такой QR считается неверным.

## Review Focus

1. QR чужого формата (Wi-Fi, ссылка на сайт, `hhphone://add` без `c`, обрезанный base64) → понятное «Это не QR учётки HHPhone», без падения (Task 1 тесты `rejects*`; Task 3 — сканер передаёт любую строку).
2. Пароль с кавычкой/точкой с запятой или кириллица в имени → строка учётки не ломается: опасные символы отклонены, кириллица допустима в `name` (Task 1 тест `accountLineEscapes`/`rejectsUnsafeChars`).
3. Перезапуск приложения во время паузы после 5 ошибок PIN → пауза продолжается (Task 2 тест `lockoutSurvivesRestart` — состояние передаётся как данные, время — параметром).
4. Deep link при незапущенном сервисе baresip (QR сканируется системной камерой при выключенном приложении) → учётка добавляется после старта сервиса (Task 3, ожидание `BaresipService.isServiceRunning`, живая проверка шаг «холодный старт»).
5. Админ ушёл в фон и вернулся → снова режим сотрудника; при этом диалог сканера (отдельная Activity) сессию не сбрасывает (Task 4, `ProcessLifecycleOwner` ON_STOP; живая проверка).

---

### Task 1: QR — разбор, сборка, строка учётки baresip

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHQr.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHQrTest.kt`

**Interfaces:**
- Produces:
  - `data class HHAccountQr(v: Int = 1, name: String = "", user: String, pass: String, domain: String, authUser: String = "", proxy: String = "", transport: String = "udp", mediaEnc: String = "", lock: Boolean = false, pin: String = "")` (`@Serializable`)
  - `sealed interface HHQrResult { data class Ok(val qr: HHAccountQr); data object NotHHPhone; data object Newer; data object Invalid }`
  - `object HHQr { fun parse(text: String): HHQrResult; fun encode(qr: HHAccountQr): String; fun aor(qr: HHAccountQr): String; fun accountLine(qr: HHAccountQr): String }`

- [ ] **Step 1: Тесты (падают)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHQrTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HHQrTest {
    private val full = HHAccountQr(
        name = "Отель Орбели", user = "201", pass = "s3cr!t", domain = "pbx.orbeli.am",
        authUser = "201a", proxy = "sip:pbx.orbeli.am:5061;transport=tls", transport = "tls",
        mediaEnc = "srtp", lock = true, pin = "4821"
    )

    private fun link(json: String) =
        "hhphone://add?c=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun roundTrip() {
        val r = HHQr.parse(HHQr.encode(full))
        assertEquals(HHQrResult.Ok(full), r)
    }

    @Test fun encodedLinkHasNoPadding() {
        val l = HHQr.encode(full)
        assertTrue(l.startsWith("hhphone://add?c="))
        assertTrue(!l.endsWith("="))
    }

    @Test fun encodedJsonAlwaysHasVersion() {
        val payload = HHQr.encode(HHAccountQr(user = "1", pass = "p", domain = "d.am")).removePrefix("hhphone://add?c=")
        assertTrue(String(Base64.getUrlDecoder().decode(payload)).contains("\"v\":1"))
    }

    @Test fun minimalWithDefaults() {
        val r = HHQr.parse(link("""{"v":1,"user":"100","pass":"p","domain":"192.168.1.10"}"""))
        assertEquals(HHQrResult.Ok(HHAccountQr(user = "100", pass = "p", domain = "192.168.1.10")), r)
    }

    @Test fun unknownFieldsIgnored() {
        val r = HHQr.parse(link("""{"v":1,"user":"100","pass":"p","domain":"d.am","color":"red"}"""))
        assertTrue(r is HHQrResult.Ok)
    }

    @Test fun newerVersion() {
        assertEquals(HHQrResult.Newer, HHQr.parse(link("""{"v":2,"user":"100","pass":"p","domain":"d.am"}""")))
    }

    @Test fun rejectsForeignQr() {
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("WIFI:S:hotel;T:WPA;P:12345678;;"))
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("https://example.com"))
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("hhphone://add"))
    }

    @Test fun rejectsBrokenPayload() {
        assertEquals(HHQrResult.Invalid, HHQr.parse("hhphone://add?c=%%%"))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"100""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"pass":"p","domain":"d.am"}""")))
    }

    @Test fun rejectsLockWithoutPin() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true,"pin":"12a4"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true,"pin":"123"}""")))
    }

    @Test fun rejectsBadEnums() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","transport":"sctp"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","mediaEnc":"aes"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","proxy":"pbx.am"}""")))
    }

    @Test fun rejectsUnsafeChars() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"a\"b","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"a;b","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1@x","pass":"p","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"name":"a;b","user":"1","pass":"p","domain":"d.am"}""")))
    }

    @Test fun aorWithTransport() {
        assertEquals("sip:201@pbx.orbeli.am;transport=tls", HHQr.aor(full))
        assertEquals("sip:100@d.am", HHQr.aor(HHAccountQr(user = "100", pass = "p", domain = "d.am")))
    }

    @Test fun accountLineFull() {
        assertEquals(
            "<sip:201@pbx.orbeli.am;transport=tls>;auth_user=\"201a\";auth_pass=\"s3cr!t\"" +
                ";outbound=\"sip:pbx.orbeli.am:5061;transport=tls\";sipnat=outbound;mediaenc=srtp" +
                ";stunserver=\"stun:stun.l.google.com:19302\";regq=0.5;pubint=0;regint=900;check_origin=no;mwi=no" +
                ";extra=\"nickname=Отель Орбели;last=empty\"",
            HHQr.accountLine(full)
        )
    }

    @Test fun accountLineMinimal() {
        assertEquals(
            "<sip:100@d.am>;auth_pass=\"p\";stunserver=\"stun:stun.l.google.com:19302\"" +
                ";regq=0.5;pubint=0;regint=900;check_origin=no;mwi=no;extra=\"last=empty\"",
            HHQr.accountLine(HHAccountQr(user = "100", pass = "p", domain = "d.am"))
        )
    }
}
```

Run: `./gradlew testHhDebugUnitTest --tests '*HHQrTest*'`
Expected: FAIL — `Unresolved reference: HHAccountQr`.

- [ ] **Step 2: `HHQr.kt`**

```kotlin
package com.tutpro.baresip.plus

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** Учётка из QR `hhphone://add?c=<base64url(JSON)>` (спека, раздел 4). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HHAccountQr(
    @EncodeDefault val v: Int = 1,   // спека: v обязателен в QR

    val name: String = "",
    val user: String,
    val pass: String,
    val domain: String,
    val authUser: String = "",
    val proxy: String = "",
    val transport: String = "udp",
    val mediaEnc: String = "",
    val lock: Boolean = false,
    val pin: String = "",
)

sealed interface HHQrResult {
    data class Ok(val qr: HHAccountQr) : HHQrResult
    data object NotHHPhone : HHQrResult
    data object Newer : HHQrResult
    data object Invalid : HHQrResult
}

object HHQr {
    private const val PREFIX = "hhphone://add?c="
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val TRANSPORTS = setOf("udp", "tcp", "tls")
    private val MEDIA_ENC = setOf("", "srtp", "srtp-mand", "srtp-mandf", "dtls_srtp", "dtls_srtpf", "zrtp")
    private val USER = Regex("""[^@;:<>"\s\\]{1,64}""")
    private val DOMAIN = Regex("""[A-Za-z0-9.\-]{1,253}(:\d{1,5})?""")
    private val PIN = Regex("""\d{4,8}""")
    // Символы, ломающие строку учётки baresip (кавычки и разделители параметров)
    private val UNSAFE = Regex("""[";<>\\\r\n]""")

    fun encode(qr: HHAccountQr): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(HHAccountQr.serializer(), qr).toByteArray())

    fun parse(text: String): HHQrResult {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return HHQrResult.NotHHPhone
        val qr = try {
            val raw = String(Base64.getUrlDecoder().decode(t.removePrefix(PREFIX).substringBefore('&')), Charsets.UTF_8)
            json.decodeFromString(HHAccountQr.serializer(), raw)
        } catch (e: Exception) {
            return HHQrResult.Invalid
        }
        if (qr.v > 1) return HHQrResult.Newer
        return if (valid(qr)) HHQrResult.Ok(qr) else HHQrResult.Invalid
    }

    private fun valid(q: HHAccountQr): Boolean =
        q.v == 1 &&
            USER.matches(q.user) && DOMAIN.matches(q.domain) &&
            q.pass.length in 1..64 && !UNSAFE.containsMatchIn(q.pass) &&
            (q.authUser.isEmpty() || USER.matches(q.authUser)) &&
            q.name.length <= 40 && !UNSAFE.containsMatchIn(q.name) &&
            (q.proxy.isEmpty() || (q.proxy.startsWith("sip:") && !q.proxy.contains('"') && !q.proxy.contains('>'))) &&
            q.transport in TRANSPORTS && q.mediaEnc in MEDIA_ENC &&
            (!q.lock || PIN.matches(q.pin)) && (q.pin.isEmpty() || PIN.matches(q.pin))

    fun aor(q: HHAccountQr): String =
        "sip:${q.user}@${q.domain}" + if (q.transport == "udp") "" else ";transport=${q.transport}"

    /** Строка учётки baresip в том же виде, что пишет Account.print(); регистрация включена (regint=900). */
    fun accountLine(q: HHAccountQr): String = buildString {
        append("<${aor(q)}>")
        if (q.authUser.isNotEmpty()) append(";auth_user=\"${q.authUser}\"")
        append(";auth_pass=\"${q.pass}\"")
        if (q.proxy.isNotEmpty()) append(";outbound=\"${q.proxy}\";sipnat=outbound")
        if (q.mediaEnc.isNotEmpty()) append(";mediaenc=${q.mediaEnc}")
        append(";stunserver=\"stun:stun.l.google.com:19302\";regq=0.5;pubint=0;regint=$REGISTRATION_INTERVAL;check_origin=no;mwi=no")
        append(";extra=\"")
        if (q.name.isNotEmpty()) append("nickname=${q.name};")
        append("last=empty\"")
    }
}
```

Run: `./gradlew testHhDebugUnitTest --tests '*HHQrTest*'`
Expected: PASS (14 тестов).

- [ ] **Step 3: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHQr.kt app/src/test/kotlin/com/tutpro/baresip/plus/HHQrTest.kt
git commit -m "QR учётки HHPhone: разбор, сборка, строка учётки baresip"
```

---

### Task 2: PIN и правила блокировки

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHPin.kt`
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHStaff.kt`
- Create: `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/BaresipApp.kt` (`HHStaff.init(this)`)
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHPinTest.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHStaffTest.kt`

**Interfaces:**
- Consumes: `HHAccountQr` (Task 1).
- Produces:
  - `data class HHLockState(val locked: Boolean = false, val pinHash: String = "", val salt: String = "", val fails: Int = 0, val lockoutUntil: Long = 0)`
  - `object HHPin { fun hash(pin: String, salt: String): String; fun newSalt(): String; fun withPin(state: HHLockState, pin: String): HHLockState; fun check(state: HHLockState, pin: String, now: Long): Pair<HHPinCheck, HHLockState> }`
  - `sealed interface HHPinCheck { data object Ok; data object Wrong; data class LockedOut(val minutesLeft: Int) }`
  - `fun hhQrNeedsPin(state: HHLockState, adminSession: Boolean, qr: HHAccountQr): Boolean`
  - `fun hhApplyQrLock(state: HHLockState, qr: HHAccountQr): HHLockState`
  - `object HHStaff { val restricted: StateFlow<Boolean>; val adminSession: StateFlow<Boolean>; fun init(context: Context); fun state(): HHLockState; fun save(state: HHLockState); fun setAdminSession(on: Boolean); fun blockedAndToast(ctx: Context): Boolean }`

- [ ] **Step 1: Тесты (падают)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHPinTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HHPinTest {
    private val locked = HHPin.withPin(HHLockState(locked = true), "4821")

    @Test fun hashIsSaltedAndNotPlain() {
        assertNotEquals("4821", locked.pinHash)
        assertNotEquals(HHPin.hash("4821", "a"), HHPin.hash("4821", "b"))
        assertEquals(HHPin.hash("4821", "a"), HHPin.hash("4821", "a"))
    }

    @Test fun rightPinOkAndResetsFails() {
        val (_, after2) = HHPin.check(locked, "0000", 0)
        val (r, s) = HHPin.check(after2, "4821", 0)
        assertEquals(HHPinCheck.Ok, r)
        assertEquals(0, s.fails)
    }

    @Test fun fiveWrongLockOutForFiveMinutes() {
        var s = locked
        repeat(4) { s = HHPin.check(s, "0000", 1_000).second }
        val (r5, s5) = HHPin.check(s, "0000", 1_000)
        assertEquals(HHPinCheck.LockedOut(5), r5)
        val (r, _) = HHPin.check(s5, "4821", 1_000 + 60_000)
        assertEquals(HHPinCheck.LockedOut(4), r)
    }

    @Test fun lockoutSurvivesRestart() {
        var s = locked
        repeat(5) { s = HHPin.check(s, "0000", 0).second }
        val restored = s.copy()   // состояние читается из хранилища как данные
        assertTrue(HHPin.check(restored, "4821", 299_000).first is HHPinCheck.LockedOut)
        assertEquals(HHPinCheck.Ok, HHPin.check(restored, "4821", 300_000).first)
    }
}
```

`app/src/test/kotlin/com/tutpro/baresip/plus/HHStaffTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HHStaffTest {
    private val qr = HHAccountQr(user = "201", pass = "p", domain = "d.am")
    private val lockedState = HHPin.withPin(HHLockState(locked = true), "4821")

    @Test fun unlockedPhoneNeverAsks() {
        assertFalse(hhQrNeedsPin(HHLockState(), adminSession = false, qr = qr.copy(lock = true, pin = "1111")))
    }

    @Test fun lockedPhoneSamePinAddsDirectly() {
        assertFalse(hhQrNeedsPin(lockedState, false, qr.copy(lock = true, pin = "4821")))
    }

    @Test fun lockedPhoneOtherOrNoPinAsks() {
        assertTrue(hhQrNeedsPin(lockedState, false, qr.copy(lock = true, pin = "1111")))
        assertTrue(hhQrNeedsPin(lockedState, false, qr))
    }

    @Test fun adminSessionNeverAsks() {
        assertFalse(hhQrNeedsPin(lockedState, adminSession = true, qr = qr))
    }

    @Test fun lockComesFromQr() {
        val locked = hhApplyQrLock(HHLockState(), qr.copy(lock = true, pin = "1111"))
        assertTrue(locked.locked)
        assertEquals(HHPinCheck.Ok, HHPin.check(locked, "1111", 0).first)
        val unlocked = hhApplyQrLock(lockedState, qr)
        assertFalse(unlocked.locked)
        assertEquals("", unlocked.pinHash)
    }
}
```

Run: `./gradlew testHhDebugUnitTest --tests '*HHPinTest*' --tests '*HHStaffTest*'`
Expected: FAIL — `Unresolved reference: HHPin`.

- [ ] **Step 2: `HHPin.kt`**

```kotlin
package com.tutpro.baresip.plus

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class HHLockState(
    val locked: Boolean = false,
    val pinHash: String = "",
    val salt: String = "",
    val fails: Int = 0,
    val lockoutUntil: Long = 0,
)

sealed interface HHPinCheck {
    data object Ok : HHPinCheck
    data object Wrong : HHPinCheck
    data class LockedOut(val minutesLeft: Int) : HHPinCheck
}

object HHPin {
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 5 * 60_000L

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 100_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun withPin(state: HHLockState, pin: String): HHLockState {
        val salt = newSalt()
        return state.copy(pinHash = hash(pin, salt), salt = salt, fails = 0, lockoutUntil = 0)
    }

    fun check(state: HHLockState, pin: String, now: Long): Pair<HHPinCheck, HHLockState> {
        if (now < state.lockoutUntil)
            return HHPinCheck.LockedOut(minutesLeft(state.lockoutUntil - now)) to state
        if (hash(pin, state.salt) == state.pinHash)
            return HHPinCheck.Ok to state.copy(fails = 0, lockoutUntil = 0)
        val fails = state.fails + 1
        return if (fails >= MAX_FAILS)
            HHPinCheck.LockedOut(minutesLeft(LOCKOUT_MS)) to state.copy(fails = 0, lockoutUntil = now + LOCKOUT_MS)
        else
            HHPinCheck.Wrong to state.copy(fails = fails)
    }

    private fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).toInt()
}

/** Нужно ли спрашивать текущий PIN перед добавлением учётки из QR (спека, раздел 5). */
fun hhQrNeedsPin(state: HHLockState, adminSession: Boolean, qr: HHAccountQr): Boolean {
    if (!state.locked || adminSession) return false
    return qr.pin.isEmpty() || HHPin.hash(qr.pin, state.salt) != state.pinHash
}

/** После авторизации блокировка и PIN берутся из нового QR. */
fun hhApplyQrLock(state: HHLockState, qr: HHAccountQr): HHLockState =
    if (qr.lock) HHPin.withPin(state.copy(locked = true), qr.pin) else HHLockState()
```

- [ ] **Step 3: `HHStaff.kt` (хранение и сессия админа)**

```kotlin
package com.tutpro.baresip.plus

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Режим сотрудника: блокировка из QR и сессия админа до ухода приложения в фон. */
object HHStaff {
    private lateinit var prefs: SharedPreferences
    private val _restricted = MutableStateFlow(false)
    private val _adminSession = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    val adminSession: StateFlow<Boolean> = _adminSession.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("hh_staff", Context.MODE_PRIVATE)
        update()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = setAdminSession(false)
        })
    }

    fun state() = HHLockState(
        locked = prefs.getBoolean("locked", false),
        pinHash = prefs.getString("pinHash", "")!!,
        salt = prefs.getString("salt", "")!!,
        fails = prefs.getInt("fails", 0),
        lockoutUntil = prefs.getLong("lockoutUntil", 0),
    )

    fun save(s: HHLockState) {
        prefs.edit(commit = true) {
            putBoolean("locked", s.locked); putString("pinHash", s.pinHash); putString("salt", s.salt)
            putInt("fails", s.fails); putLong("lockoutUntil", s.lockoutUntil)
        }
        update()
    }

    fun setAdminSession(on: Boolean) {
        _adminSession.value = on
        update()
    }

    private fun update() {
        _restricted.value = prefs.getBoolean("locked", false) && !_adminSession.value
    }

    /** Для точек входа upstream: в режиме сотрудника показать подсказку и запретить действие. */
    fun blockedAndToast(ctx: Context): Boolean {
        if (!_restricted.value) return false
        Toast.makeText(ctx, ctx.getString(R.string.hh_admin_only), Toast.LENGTH_SHORT).show()
        return true
    }
}
```

В `BaresipApp.onCreate()` после строки `HHLocale.apply(this)` добавить `HHStaff.init(this)`.

- [ ] **Step 3a: Строки ru/hy/en для всего плана 2** (`HHStaff.kt` уже использует `R.string.hh_admin_only`; без строк не скомпилируются и JVM-тесты)

Files: create `app/src/hh/res/values/hh_feature_strings.xml`, `app/src/hh/res/values-ru/hh_feature_strings.xml`, `app/src/hh/res/values-hy/hh_feature_strings.xml`.

`app/src/hh/res/values/hh_feature_strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hh_add_by_qr">Add by QR</string>
    <string name="hh_show_qr">Show QR</string>
    <string name="hh_qr_lock">Lock for employee</string>
    <string name="hh_pin">PIN (4–8 digits)</string>
    <string name="hh_qr_warning">The QR code contains the password. Do not publish it.</string>
    <string name="hh_admin">Administrator</string>
    <string name="hh_enter_pin">Enter the administrator PIN</string>
    <string name="hh_wrong_pin">Wrong PIN</string>
    <string name="hh_pin_locked_out">Too many attempts. Try again in %1$d min.</string>
    <string name="hh_unlock_device">Remove lock</string>
    <string name="hh_lock_removed">Lock removed</string>
    <string name="hh_admin_only">Available to the administrator only</string>
    <string name="hh_qr_not_hhphone">This QR code is not an HHPhone account.</string>
    <string name="hh_qr_invalid">The QR code is damaged or contains invalid data.</string>
    <string name="hh_qr_newer">This QR code is from a newer HHPhone version. Update the app.</string>
    <string name="hh_account_added">Account %1$s added</string>
    <string name="hh_replace_account">Account %1$s already exists. Replace it?</string>
    <string name="hh_no_password">The account has no saved password, a QR code cannot be created.</string>
    <string name="hh_need_pin_digits">Enter a PIN of 4–8 digits</string>
    <string name="hh_service_not_running">HHPhone is not running yet. Open the app and scan again.</string>
</resources>
```
`app/src/hh/res/values-ru/hh_feature_strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hh_add_by_qr">Добавить по QR</string>
    <string name="hh_show_qr">Показать QR</string>
    <string name="hh_qr_lock">Блокировать у сотрудника</string>
    <string name="hh_pin">PIN (4–8 цифр)</string>
    <string name="hh_qr_warning">QR содержит пароль — не публикуйте его.</string>
    <string name="hh_admin">Администратор</string>
    <string name="hh_enter_pin">Введите PIN администратора</string>
    <string name="hh_wrong_pin">Неверный PIN</string>
    <string name="hh_pin_locked_out">Слишком много попыток. Повторите через %1$d мин.</string>
    <string name="hh_unlock_device">Снять блокировку</string>
    <string name="hh_lock_removed">Блокировка снята</string>
    <string name="hh_admin_only">Доступно только администратору</string>
    <string name="hh_qr_not_hhphone">Это не QR учётной записи HHPhone.</string>
    <string name="hh_qr_invalid">QR повреждён или содержит неверные данные.</string>
    <string name="hh_qr_newer">QR от более новой версии HHPhone. Обновите приложение.</string>
    <string name="hh_account_added">Учётная запись %1$s добавлена</string>
    <string name="hh_replace_account">Учётная запись %1$s уже есть. Заменить?</string>
    <string name="hh_no_password">В учётной записи нет сохранённого пароля — QR создать нельзя.</string>
    <string name="hh_need_pin_digits">Введите PIN из 4–8 цифр</string>
    <string name="hh_service_not_running">HHPhone ещё не запущен. Откройте приложение и отсканируйте снова.</string>
</resources>
```
`app/src/hh/res/values-hy/hh_feature_strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="hh_add_by_qr">Ավելացնել QR-ով</string>
    <string name="hh_show_qr">Ցույց տալ QR</string>
    <string name="hh_qr_lock">Արգելափակել աշխատակցի մոտ</string>
    <string name="hh_pin">PIN (4–8 թվանշան)</string>
    <string name="hh_qr_warning">QR-ը պարունակում է գաղտնաբառը․ մի՛ հրապարակեք այն։</string>
    <string name="hh_admin">Ադմինիստրատոր</string>
    <string name="hh_enter_pin">Մուտքագրեք ադմինիստրատորի PIN-ը</string>
    <string name="hh_wrong_pin">Սխալ PIN</string>
    <string name="hh_pin_locked_out">Չափազանց շատ փորձեր։ Կրկնեք %1$d րոպեից։</string>
    <string name="hh_unlock_device">Հանել արգելափակումը</string>
    <string name="hh_lock_removed">Արգելափակումը հանված է</string>
    <string name="hh_admin_only">Հասանելի է միայն ադմինիստրատորին</string>
    <string name="hh_qr_not_hhphone">Սա HHPhone-ի հաշվի QR չէ։</string>
    <string name="hh_qr_invalid">QR-ը վնասված է կամ պարունակում է սխալ տվյալներ։</string>
    <string name="hh_qr_newer">QR-ը HHPhone-ի ավելի նոր տարբերակից է։ Թարմացրեք հավելվածը։</string>
    <string name="hh_account_added">%1$s հաշիվն ավելացված է</string>
    <string name="hh_replace_account">%1$s հաշիվն արդեն կա։ Փոխարինե՞լ։</string>
    <string name="hh_no_password">Հաշվում պահված գաղտնաբառ չկա, QR ստեղծել հնարավոր չէ։</string>
    <string name="hh_need_pin_digits">Մուտքագրեք 4–8 թվանշանից PIN</string>
    <string name="hh_service_not_running">HHPhone-ը դեռ գործարկված չէ։ Բացեք հավելվածը և նորից սկանավորեք։</string>
</resources>
```
Run: `python -I scripts/missing-strings.py`
Expected: `ru: 0`, `hy: 0`.


Run: `./gradlew testHhDebugUnitTest`
Expected: PASS (все тесты: HHLocale 7, HHQr 14, HHPin 4, HHStaff 5).

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHPin.kt app/src/main/kotlin/com/tutpro/baresip/plus/HHStaff.kt app/src/main/kotlin/com/tutpro/baresip/plus/BaresipApp.kt app/src/test app/src/hh/res
git commit -m "PIN (PBKDF2, пауза после 5 ошибок) и правила блокировки из QR"
```

---

### Task 3: Добавление учётки по QR — deep link, сканер, диалоги

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (зависимости zxing)
- Modify: `app/src/hh/AndroidManifest.xml` (deep link, ориентация сканера)
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/MainActivity.kt` (2 строки `HHProvisioning.offer(intent)` + `HHHost()` после `NavHost`)
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/MainScreen.kt` (меню: пункт «Добавить по QR»)

**Interfaces:**
- Consumes: `HHQr.parse/aor/accountLine` (Task 1), `HHStaff`, `hhQrNeedsPin`, `hhApplyQrLock`, `HHPin.check` (Task 2).
- Produces:
  - `object HHProvisioning { val pending: MutableStateFlow<String?>; val scanRequests: MutableStateFlow<Int>; fun offer(intent: Intent?): Boolean; fun requestScan() }`
  - `object HHUi { val pinPrompt: MutableStateFlow<HHPinPrompt?> }`, `data class HHPinPrompt(val onOk: () -> Unit)` — общий диалог ввода PIN (Task 4 использует для «Админ»).
  - `@Composable fun HHHost()` — рисует все наши диалоги и держит лаунчер сканера.
  - `@Composable fun hhMenu(items: List<MenuItem>, hidden: Set<String>): List<MenuItem>` и `fun hhMenuClick(ctx: Context, item: String): Boolean` — Task 4 расширит.
  - Строки (созданы в Task 2 Step 3a): `hh_add_by_qr`, `hh_show_qr`, `hh_qr_lock`, `hh_pin`, `hh_qr_warning`, `hh_admin`, `hh_enter_pin`, `hh_wrong_pin`, `hh_pin_locked_out`, `hh_unlock_device`, `hh_lock_removed`, `hh_admin_only`, `hh_qr_not_hhphone`, `hh_qr_invalid`, `hh_qr_newer`, `hh_account_added`, `hh_replace_account`, `hh_no_password`, `hh_need_pin_digits`, `hh_service_not_running`.

- [ ] **Step 1: Зависимости zxing**

`gradle/libs.versions.toml`: в `[versions]` — `zxingCore = "3.5.3"` и `zxingEmbedded = "4.3.0"`; в `[libraries]`:
```toml
zxing-core = { module = "com.google.zxing:core", version.ref = "zxingCore" }
zxing-embedded = { module = "com.journeyapps:zxing-android-embedded", version.ref = "zxingEmbedded" }
```
`app/build.gradle.kts` в `dependencies`: `implementation(libs.zxing.core)` и `implementation(libs.zxing.embedded)`.

- [ ] **Step 2: Манифест flavor**

`app/src/hh/AndroidManifest.xml` целиком:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:localeConfig="@xml/locales_config"
        tools:replace="android:localeConfig">
        <activity android:name="com.tutpro.baresip.plus.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="hhphone" android:host="add" />
            </intent-filter>
        </activity>
        <activity
            android:name="com.journeyapps.barcodescanner.CaptureActivity"
            android:screenOrientation="fullSensor"
            tools:replace="android:screenOrientation" />
    </application>
</manifest>
```

- [ ] **Step 3: `HHHost.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Вход QR: deep link hhphone://add и встроенный сканер кладут строку в pending. */
object HHProvisioning {
    val pending = MutableStateFlow<String?>(null)
    val scanRequests = MutableStateFlow(0)

    /** Забирает hhphone:// из intent и гасит его action, чтобы upstream не принял ссылку за звонок. */
    fun offer(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "hhphone") return false
        pending.value = data.toString()
        intent.action = null
        return true
    }

    fun requestScan() { scanRequests.value++ }
}

data class HHPinPrompt(val onOk: () -> Unit)

object HHUi {
    val pinPrompt = MutableStateFlow<HHPinPrompt?>(null)
    val message = MutableStateFlow<String?>(null)
    val confirm = MutableStateFlow<Pair<String, () -> Unit>?>(null)
}

/** Наши диалоги поверх любого экрана; вызывается один раз в MainActivity после NavHost. */
@Composable
fun HHHost() {
    val ctx = LocalContext.current
    val pending by HHProvisioning.pending.collectAsState()
    val scans by HHProvisioning.scanRequests.collectAsState()
    val message by HHUi.message.collectAsState()
    val confirm by HHUi.confirm.collectAsState()
    val pinPrompt by HHUi.pinPrompt.collectAsState()

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { HHProvisioning.pending.value = it }
    }
    LaunchedEffect(scans) {
        if (scans > 0) scanner.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt(ctx.getString(R.string.hh_add_by_qr))
        )
    }

    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        // Холодный старт по deep link: учётку можно создать только после старта сервиса baresip
        repeat(100) { if (!BaresipService.isServiceRunning) delay(200) }
        HHProvisioning.pending.value = null
        if (!BaresipService.isServiceRunning) {
            HHUi.message.value = ctx.getString(R.string.hh_service_not_running); return@LaunchedEffect
        }
        startProvision(ctx, link)
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { HHUi.message.value = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { HHUi.message.value = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    confirm?.let { (text, onYes) ->
        AlertDialog(
            onDismissRequest = { HHUi.confirm.value = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { HHUi.confirm.value = null; onYes() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { HHUi.confirm.value = null }) { Text(stringResource(R.string.no)) } },
        )
    }
    pinPrompt?.let { prompt -> PinDialog(ctx, prompt) }
}

@Composable
private fun PinDialog(ctx: Context, prompt: HHPinPrompt) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { HHUi.pinPrompt.value = null },
        title = { Text(stringResource(R.string.hh_enter_pin)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.hh_pin)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error.isNotEmpty()) Text(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (r, s) = HHPin.check(HHStaff.state(), pin, System.currentTimeMillis())
                HHStaff.save(s)
                when (r) {
                    HHPinCheck.Ok -> { HHUi.pinPrompt.value = null; prompt.onOk() }
                    HHPinCheck.Wrong -> { error = ctx.getString(R.string.hh_wrong_pin); pin = "" }
                    is HHPinCheck.LockedOut -> { error = ctx.getString(R.string.hh_pin_locked_out, r.minutesLeft); pin = "" }
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { HHUi.pinPrompt.value = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun startProvision(ctx: Context, link: String) {
    val qr = when (val r = HHQr.parse(link)) {
        is HHQrResult.Ok -> r.qr
        HHQrResult.NotHHPhone -> { HHUi.message.value = ctx.getString(R.string.hh_qr_not_hhphone); return }
        HHQrResult.Newer -> { HHUi.message.value = ctx.getString(R.string.hh_qr_newer); return }
        HHQrResult.Invalid -> { HHUi.message.value = ctx.getString(R.string.hh_qr_invalid); return }
    }
    val proceed = { replaceIfNeeded(ctx, qr) }
    if (hhQrNeedsPin(HHStaff.state(), HHStaff.adminSession.value, qr)) HHUi.pinPrompt.value = HHPinPrompt(proceed)
    else proceed()
}

private fun replaceIfNeeded(ctx: Context, qr: HHAccountQr) {
    val aor = HHQr.aor(qr)
    val existing = UserAgent.ofAor(aor)
    if (existing == null) { addAccount(ctx, qr); return }
    HHUi.confirm.value = ctx.getString(R.string.hh_replace_account, aor.removePrefix("sip:")) to {
        existing.remove()
        Api.ua_destroy(existing.uap)
        addAccount(ctx, qr)
    }
}

private fun addAccount(ctx: Context, qr: HHAccountQr) {
    val uap = UserAgent.uaAlloc(HHQr.accountLine(qr))
    if (uap == 0L) { HHUi.message.value = ctx.getString(R.string.account_allocation_failure); return }
    val ua = UserAgent(uap)
    ua.add()
    ua.account.checkOrigin = true
    Account.saveAccounts()
    ua.reRegister()
    HHStaff.save(hhApplyQrLock(HHStaff.state(), qr))
    Toast.makeText(ctx, ctx.getString(R.string.hh_account_added, HHQr.aor(qr).removePrefix("sip:")), Toast.LENGTH_LONG).show()
}

/** Пункты меню HHPhone: прячет запрещённые в режиме сотрудника и добавляет наши. */
@Composable
fun hhMenu(items: List<MenuItem>, hidden: Set<String>): List<MenuItem> {
    val restricted by HHStaff.restricted.collectAsState()
    val addByQr = stringResource(R.string.hh_add_by_qr)
    val shown = if (restricted) items.filter { it.text !in hidden } else items
    return listOf(MenuItem(addByQr, Icons.Outlined.QrCodeScanner)) + shown
}

/** true — пункт наш и обработан. */
fun hhMenuClick(ctx: Context, item: String): Boolean = when (item) {
    ctx.getString(R.string.hh_add_by_qr) -> { HHProvisioning.requestScan(); true }
    else -> false
}
```
(`material-icons-extended`, где лежит `QrCodeScanner`, уже подключён в upstream.)

- [ ] **Step 4: Точки входа в upstream**

`MainActivity.kt`:
1. В `onCreate` прямо перед строкой `when (intent?.action) {` (около строки 177) вставить `HHProvisioning.offer(intent)`.
2. В `onNewIntent` прямо перед `when {` (после строки `Log.d(TAG, "onNewIntent action/type/data: ...`) вставить `HHProvisioning.offer(intent)`.
3. Сразу после закрывающей `}` блока `NavHost(navController, startDestination = "main") { … }` вставить строку `HHHost()`.

`MainScreen.kt` (меню, около строк 776–797):
1. `menuItems = listOfNotNull(` → `menuItems = hhMenu(listOfNotNull(` и закрыть скобку после списка: `), hidden = setOf(settings, accounts, backup, restore, logcat)),`.
2. В `onItemClick` сразу после `menuExpanded = false` вставить `if (hhMenuClick(ctx, selectedItem)) return@DropdownMenu`.

Run: `./gradlew testHhDebugUnitTest assembleHhRelease`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Живая проверка (эмулятор Pixel_9_Pro, release-APK — debug не помещается; стенд hhpbx-test)**

1. Собрать QR-ссылку для номера 101 на JVM: временный тест не нужен — получить строку командой
   `python -I -c "import base64,json;print('hhphone://add?c='+base64.urlsafe_b64encode(json.dumps({'v':1,'name':'Тест','user':'101','pass':'Tst101pass!x','domain':'192.168.109.132','proxy':'sip:192.168.109.132;transport=tcp'},ensure_ascii=False).encode()).decode().rstrip('='))"`.
2. Deep link при запущенном приложении: `adb shell am start -a android.intent.action.VIEW -d '<ссылка>'` → тост «Учётная запись 101@192.168.109.132 добавлена», `fs_cli -x "show registrations"` показывает 101 с user-agent baresip.
3. Повтор той же ссылки → «уже есть. Заменить?» → «Да» → учётка одна, зарегистрирована.
4. Холодный старт: `adb shell am force-stop am.dgsolutions.hhphone`, затем та же команда `am start` с ссылкой номера 100 → учётка добавлена после старта сервиса.
5. Чужой QR: `adb shell am start -a android.intent.action.VIEW -d 'hhphone://add?c=xyz'` → «QR повреждён…».
6. Сканер: меню → «Добавить по QR» открывает камеру (скриншот); на виртуальной сцене эмулятора QR нет — проверяется только открытие и возврат кнопкой «Назад» без падения.
Скриншоты сохранять в scratchpad и просматривать.

- [ ] **Step 6: Коммит**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/hh app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt app/src/main/kotlin/com/tutpro/baresip/plus/MainActivity.kt app/src/main/kotlin/com/tutpro/baresip/plus/MainScreen.kt
git commit -m "Добавление учётки по QR: deep link hhphone://add, сканер, PIN и замена"
```

---

### Task 4: Режим сотрудника в интерфейсе и «Администратор»

**Files:**
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt` (пункты «Администратор» / «Снять блокировку»)
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/MainScreen.kt` (запреты на учётку: кнопка учёток, точка статуса, долгое нажатие)

**Interfaces:**
- Consumes: `HHStaff.restricted/adminSession/setAdminSession/blockedAndToast/save` (Task 2), `HHUi.pinPrompt` (Task 3), `hhMenu`/`hhMenuClick` (Task 3).

- [ ] **Step 1: Меню администратора**

В `HHHost.kt` заменить `hhMenu` и `hhMenuClick` на:
```kotlin
@Composable
fun hhMenu(items: List<MenuItem>, hidden: Set<String>): List<MenuItem> {
    val restricted by HHStaff.restricted.collectAsState()
    val admin by HHStaff.adminSession.collectAsState()
    val addByQr = MenuItem(stringResource(R.string.hh_add_by_qr), Icons.Outlined.QrCodeScanner)
    val adminItem = MenuItem(stringResource(R.string.hh_admin), Icons.Outlined.AdminPanelSettings)
    val unlockItem = MenuItem(stringResource(R.string.hh_unlock_device), Icons.Outlined.LockOpen)
    return when {
        restricted -> listOf(addByQr) + items.filter { it.text !in hidden } + adminItem
        admin -> listOf(addByQr) + items + unlockItem
        else -> listOf(addByQr) + items
    }
}

fun hhMenuClick(ctx: Context, item: String): Boolean = when (item) {
    ctx.getString(R.string.hh_add_by_qr) -> { HHProvisioning.requestScan(); true }
    ctx.getString(R.string.hh_admin) -> { HHUi.pinPrompt.value = HHPinPrompt { HHStaff.setAdminSession(true) }; true }
    ctx.getString(R.string.hh_unlock_device) -> {
        HHStaff.save(HHLockState()); HHStaff.setAdminSession(false)
        Toast.makeText(ctx, ctx.getString(R.string.hh_lock_removed), Toast.LENGTH_SHORT).show(); true
    }
    else -> false
}
```
Добавить импорты `androidx.compose.material.icons.outlined.AdminPanelSettings` и `androidx.compose.material.icons.outlined.LockOpen` (остальные уже есть с Task 3).

- [ ] **Step 2: Запреты в `MainScreen.kt`**

Каждую из точек ниже дополнить проверкой `HHStaff.blockedAndToast(ctx)` (строки ориентировочные, искать по содержимому):
1. Кнопка «Учётки» при пустом списке (`navController.navigate("accounts")`, ~1358): `if (checkLockedAndToast(ctx) || HHStaff.blockedAndToast(ctx)) return@OutlinedButton`.
2. Точка статуса учётки (`navController.navigate("account/$selected/old")`, ~1426): `if (checkLockedAndToast(ctx) || HHStaff.blockedAndToast(ctx)) return@clickable`.
3. Долгое нажатие вкл/выкл регистрации — оба места (`onLongPress = {` ~1388 и `onLongClick = {` ~1443): первой строкой внутри лямбды `if (HHStaff.blockedAndToast(ctx)) return@detectTapGestures` / `if (HHStaff.blockedAndToast(ctx)) return@combinedClickable` (метку взять по фактической лямбде; если return по метке невозможен — обернуть тело в `if (!HHStaff.blockedAndToast(ctx)) { … }`).

Run: `./gradlew testHhDebugUnitTest assembleHhRelease`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Живая проверка**

1. Deep link QR номера 101 с `"lock":true,"pin":"4821"` → учётка добавлена; меню: нет «Настройки/Учётки/Резервная копия/Восстановление/Logcat», есть «Добавить по QR» и «Администратор».
2. Тап по точке статуса и долгое нажатие на учётке → тост «Доступно только администратору», регистрация не выключилась (`show registrations`).
3. «Администратор» → PIN `0000` ×5 → «Повторите через 5 мин»; `force-stop` и запуск → «Администратор» → `4821` → всё ещё пауза. Дождаться окончания паузы (Bash `run_in_background` с `sleep 300` не использовать — ждать через Monitor/until-цикл по времени).
4. После паузы: «Администратор» → `4821` → меню полное + «Снять блокировку»; меню → «Добавить по QR» → «Назад» из сканера → меню всё ещё полное (сканер не сбрасывает сессию); Home → вернуться → снова режим сотрудника.
5. QR номера 100 без `pin` на заблокированном телефоне → спрашивает PIN; верный → учётка добавлена, блокировка снята (QR без lock).
6. «Администратор» → «Снять блокировку» → меню полное, после Home остаётся полным.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt app/src/main/kotlin/com/tutpro/baresip/plus/MainScreen.kt
git commit -m "Режим сотрудника: скрытые пункты, запрет изменения учёток, вход администратора по PIN"
```

---

### Task 5: «Показать QR» на телефоне админа

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHQrShow.kt`
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/AccountScreen.kt` (одна кнопка в `actions` верхней панели)
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHQrShowTest.kt`

**Interfaces:**
- Consumes: `HHAccountQr`, `HHQr.encode` (Task 1), `HHStaff.restricted` (Task 2).
- Produces: `fun hhQrFromAccount(acc: Account): HHAccountQr?` (null — нет пароля), `fun hhQrMatrix(text: String, size: Int): com.google.zxing.common.BitMatrix`, `fun hhQrFields(aor: String, authUser: String, authPass: String, outbound: String, mediaEnc: String, nickName: String): HHAccountQr?` (чистая, для теста), `@Composable fun HHShowQrButton(aor: String)`.

- [ ] **Step 1: Тест (падает)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHQrShowTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HHQrShowTest {
    @Test fun fieldsFromAccount() {
        assertEquals(
            HHAccountQr(name = "Офис", user = "201", pass = "p", domain = "pbx.am:5070", authUser = "a201",
                proxy = "sip:pbx.am;transport=tcp", transport = "tcp", mediaEnc = "zrtp"),
            hhQrFields("sip:201@pbx.am:5070;transport=tcp", "a201", "p", "sip:pbx.am;transport=tcp", "zrtp", "Офис")
        )
    }

    @Test fun authUserSameAsUserIsOmitted() {
        assertEquals("", hhQrFields("sip:201@pbx.am", "201", "p", "", "", "")!!.authUser)
    }

    @Test fun noPasswordNoQr() {
        assertNull(hhQrFields("sip:201@pbx.am", "", "", "", "", ""))
        assertNull(hhQrFields("sip:201@pbx.am", "", NO_AUTH_PASS, "", "", ""))
    }

    @Test fun matrixDecodesBackToLink() {
        val link = HHQr.encode(HHAccountQr(user = "201", pass = "p", domain = "pbx.am", lock = true, pin = "4821"))
        val m = hhQrMatrix(link, 400)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else -1 }
        val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px)))).text
        assertEquals(link, text)
    }
}
```
Run: `./gradlew testHhDebugUnitTest --tests '*HHQrShowTest*'`
Expected: FAIL — `Unresolved reference: hhQrFields`.

- [ ] **Step 2: `HHQrShow.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

fun hhQrFields(aor: String, authUser: String, authPass: String, outbound: String, mediaEnc: String, nickName: String): HHAccountQr? {
    if (authPass.isEmpty() || authPass == NO_AUTH_PASS) return null
    val uri = aor.removePrefix("sip:")
    val user = uri.substringBefore('@')
    val hostPart = uri.substringAfter('@')
    val domain = hostPart.substringBefore(';')
    val transport = Regex("""transport=(\w+)""").find(hostPart)?.groupValues?.get(1) ?: "udp"
    return HHAccountQr(
        name = nickName, user = user, pass = authPass, domain = domain,
        authUser = if (authUser == user) "" else authUser,
        proxy = outbound, transport = transport, mediaEnc = mediaEnc,
    )
}

fun hhQrFromAccount(acc: Account): HHAccountQr? =
    hhQrFields(acc.aor, acc.authUser, BaresipService.aorPasswords[acc.aor] ?: acc.authPass,
        acc.outbound.firstOrNull() ?: "", acc.mediaEnc, acc.nickName)

fun hhQrMatrix(text: String, size: Int): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))

private fun BitMatrix.toBitmap(): Bitmap {
    val px = IntArray(width * height) { i -> if (get(i % width, i / width)) 0xFF000000.toInt() else -1 }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/** Кнопка в верхней панели экрана учётки; в режиме сотрудника не показывается. */
@Composable
fun HHShowQrButton(aor: String) {
    val restricted by HHStaff.restricted.collectAsState()
    if (restricted) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.hh_show_qr)) }
    if (!open) return
    val base = Account.ofAor(aor)?.let { hhQrFromAccount(it) }
    if (base == null) {
        AlertDialog(onDismissRequest = { open = false }, text = { Text(stringResource(R.string.hh_no_password)) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } })
        return
    }
    var lock by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    val pinOk = !lock || pin.length in 4..8
    val bitmap = remember(lock, pin) {
        if (pinOk) hhQrMatrix(HHQr.encode(base.copy(lock = lock, pin = if (lock) pin else "")), 720).toBitmap() else null
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.hh_show_qr)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hh_qr_lock), Modifier.weight(1f))
                    Switch(checked = lock, onCheckedChange = { lock = it })
                }
                if (lock) OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.hh_pin)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(260.dp))
                else Text(stringResource(R.string.hh_need_pin_digits))
                Text(stringResource(R.string.hh_qr_warning))
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ok)) } },
    )
}
```
(Если `Modifier.weight` недоступен вне `RowScope`-импорта — добавить `import androidx.compose.foundation.layout.RowScope` не нужно: вызов внутри `Row {}` уже в его области.)

Run: `./gradlew testHhDebugUnitTest --tests '*HHQrShowTest*'`
Expected: PASS (4 теста).

- [ ] **Step 3: Кнопка в `AccountScreen.kt`**

В блоке `actions = {` верхней панели `AccountScreen` (около строки 191, где `IconButton(onClick = checkOnClick)`) перед этой `IconButton` вставить строку `HHShowQrButton(aor)` (`aor` — параметр `AccountScreen`).

Run: `./gradlew testHhDebugUnitTest assembleHhRelease`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Живая проверка**

1. Учётка 101 → «Показать QR» → скриншот: QR и предупреждение. Включить «Блокировать», PIN `12` → вместо QR «Введите PIN из 4–8 цифр»; PIN `4821` → QR.
2. Декодировать QR со скриншота: `python -c "import cv2;print(cv2.QRCodeDetector().detectAndDecode(cv2.imread('<скриншот>'))[0])"` (без `-I`, opencv в user site; если opencv нет — `pip install --user opencv-python-headless`) → ссылка `hhphone://add?c=…`; `adb shell am start -a android.intent.action.VIEW -d '<ссылка>'` → «уже есть. Заменить?» (круг замкнут).
3. Учётка без пароля (добавить вручную без пароля) → «Показать QR» → «нет сохранённого пароля».

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHQrShow.kt app/src/main/kotlin/com/tutpro/baresip/plus/AccountScreen.kt app/src/test/kotlin/com/tutpro/baresip/plus/HHQrShowTest.kt
git commit -m "Показать QR учётки с блокировкой и PIN (телефон админа)"
```

---

### Task 6: Документация и выпуск v0.2.0

**Files:**
- Modify: `app/build.gradle.kts` (flavor `hh`: `versionCode = 2`, `versionName = "0.2.0"`)
- Modify: `docs/testing.md` (раздел QR/блокировка)
- Create: `docs/qr.md` (памятка админу)
- Modify: `README.md` (строка о QR)

- [ ] **Step 1: Версия и документы**

`app/build.gradle.kts`: в `create("hh")` — `versionCode = 2`, `versionName = "0.2.0"`.

`docs/qr.md`:
```markdown
# Учётки по QR (памятка админу)

1. На своём телефоне: Учётки → нужная учётка → значок QR вверху.
2. Для сотрудника включите «Блокировать у сотрудника» и задайте PIN (4–8 цифр).
3. Сотрудник наводит камеру телефона на QR (или HHPhone → меню → «Добавить по QR»).
4. Заблокированный телефон: меню → «Администратор» → PIN — полный доступ до сворачивания приложения;
   там же «Снять блокировку».
5. Забыли PIN: Настройки Android → Приложения → HHPhone → Хранилище → Очистить данные, затем снова QR.

QR содержит пароль SIP — не публикуйте и не пересылайте его в открытые чаты.
Формат: `hhphone://add?c=<base64url(JSON)>`, поля — в спеке `docs/superpowers/specs/2026-10-08-hhphone-design.md`, раздел 4.
```
`docs/testing.md` — добавить в раздел «На эмуляторе» пункт:
`7. QR: ссылка через adb shell am start -a android.intent.action.VIEW -d 'hhphone://add?c=…' (сборка ссылки — план 2, Task 3 Step 5); блокировка и «Администратор» — план 2, Task 4 Step 3.`
`README.md` — после первой строки описания добавить: `Учётки сотрудникам — по QR с блокировкой PIN (docs/qr.md).`

- [ ] **Step 2: Сборка, проверка, коммит, тег, релиз**

```bash
./gradlew testHhDebugUnitTest assembleHhRelease
python -I scripts/missing-strings.py
cp app/build/outputs/apk/hh/release/app-hh-release.apk "$SCRATCH/HHPhone-0.2.0.apk"
git add app/build.gradle.kts docs/qr.md docs/testing.md README.md
git commit -m "HHPhone 0.2.0: QR и блокировка — документация, версия"
```
Тег `v0.2.0` и `gh release create v0.2.0 "$SCRATCH/HHPhone-0.2.0.apk" --title "HHPhone 0.2.0" --notes "Учётки по QR (камера или встроенный сканер), блокировка у сотрудника PIN-кодом из QR, «Показать QR» у админа."` — после финального ревью и слияния в main.
