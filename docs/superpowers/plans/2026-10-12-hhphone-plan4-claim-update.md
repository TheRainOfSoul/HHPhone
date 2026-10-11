# HHPhone план 4 — QR с одноразовым токеном и автообновление (v0.3.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HHPhone принимает QR `hhphone://claim?c=…` из панели HHPBX 1.8.0, один раз забирает учётку по HTTPS (доверяя системе или ключу из QR), применяет доменный PIN; сам находит и ставит новые версии с GitHub Releases.

**Architecture:** Чистая логика — `HHQr` (разбор claim-QR), `HHClaim.kt` (разбор ответа, применение блокировки, проверка ключа, обмен по `HttpsURLConnection`), `HHUpdate.kt` (разбор `latest.json`, правило «новее», интервал 6 ч, скачивание, установка). UI — существующий `HHHost` (диалоги плана 2). Выпуски — `scripts/release.ps1` (APK + `latest.json` с постоянными именами).

**Tech Stack:** Kotlin, Compose M3, kotlinx.serialization, kotlinx.coroutines, `javax.net.ssl`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-12-hhphone-tokens-design.md` (разделы 1, 3, 4, 6)

## Global Constraints

- Claim-QR: `hhphone://claim?c=<base64url(JSON)>`, `{"v":1,"server":"https://хост[:порт]","token":"<43 base64url>","spki":"<44 base64>"}`; `v ≠ 1` → «обновите HHPhone».
- Обмен: `POST <server>/app/hhphone/claim.php`, тело `token=…`, таймаут 10 с; 200 → учётка, 410 → «уже использован или отозван».
- Доверие: сертификат признан системой ИЛИ SHA-256(SubjectPublicKeyInfo) листового сертификата = `spki`; проверка имени хоста — только если ключ не совпал.
- Ошибки: нет сети/таймаут/5xx → «Нет связи с АТС. QR не потрачен — попробуйте снова.»; сертификат → «Сервер АТС не прошёл проверку безопасности.»; 410 → «Этот QR уже использован или отозван.»; неверный ответ → «АТС вернула неверные данные учётной записи.»; «Заменить?» для claim сообщает, что QR уже потрачен.
- Заблокированный телефон без сессии админа: **сначала PIN, потом обмен**.
- После обмена: `lock: true` → телефон хранит `pinHash`/`pinSalt` из ответа; `lock: false` → блокировка снимается.
- PBKDF2: контрольный пример PIN `4821`, соль `AAAAAAAAAAAAAAAAAAAAAA==` → `7GQnXhxDTntM3jRYt+FQO5RMtdZhX6cFxBLU+etr25o=` (общий с HHPBX `t_hhphone.php`).
- Автообновление: `https://github.com/TheRainOfSoul/HHPhone/releases/latest/download/latest.json` (`{"versionCode","versionName","notes"}`) и `…/HHPhone.apk`; проверка при запуске/возврате не чаще раза в 6 ч, пункт меню «Проверить обновления» (оба режима); «Позже» — через 6 ч; `REQUEST_INSTALL_PACKAGES`; установка разрешена и сотруднику.
- Строки — ru/hy/en во flavor `hh`; `scripts/missing-strings.py` = 0. Версия flavor `hh`: `versionCode = 3`, `versionName = "0.3.0"`.

## Review Focus

1. QR с токеном отсканирован, сеть пропала посреди ответа (сервер сжёг токен, телефон не получил) → «Нет связи…, QR не потрачен» — честно неверно, но неизбежно (спека «Declined»); проверить, что телефон хотя бы не падает и не создаёт полу-учётку (Task 2: исключение чтения ответа → NoConnection, учётка не создаётся).
2. HHPBX с сертификатом Let's Encrypt, чей ключ сменился после печати QR → система признаёт сертификат, обмен проходит (Task 2 тест `systemTrustedButOtherKeyPasses` логикой TrustManager: системный путь не требует совпадения ключа).
3. Пользователь нажал «Обновить», но запретил установку из неизвестных источников → понятный повтор, без зацикливания экрана настроек (Task 4: после возврата из настроек без разрешения — сообщение, повторный экран не открывается сам).
4. GitHub недоступен / `latest.json` битый → тихо при автопроверке, сообщение при ручной (Task 4 тест `parse*`, логика `manual`).
5. Обновление скачано наполовину (обрыв) → не запускать установку битого файла (Task 4: длина файла сверяется с `Content-Length`, иначе — сообщение «Не удалось скачать»).

---

### Task 1: Разбор claim-QR

**Files:**
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/HHQr.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHQrClaimTest.kt`

**Interfaces:**
- Produces: `HHQrResult.Claim(val server: String, val token: String, val spki: String)`; `HHQr.parse` распознаёт `hhphone://claim?c=`; `HHQr.validAccount(q: HHAccountQr): Boolean` (бывший приватный `valid`, теперь `internal` — нужен Task 2).

- [ ] **Step 1: Тест (падает)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHQrClaimTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class HHQrClaimTest {
    private val token = "dQljapB8rs-SxVF8J3LNaspyRb_sq4hYvVC3LpKOR7k"
    private val spki = "tBmgFl4fdcJjbAg68yA+mZ88wSdaur2Y2eZGO+n3YIU="
    private fun claim(json: String) =
        "hhphone://claim?c=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun validClaim() {
        assertEquals(
            HHQrResult.Claim("https://192.168.109.132", token, spki),
            HHQr.parse(claim("""{"v":1,"server":"https://192.168.109.132","token":"$token","spki":"$spki"}"""))
        )
        assertEquals(
            HHQrResult.Claim("https://pbx.orbeli.am:8443", token, spki),
            HHQr.parse(claim("""{"v":1,"server":"https://pbx.orbeli.am:8443","token":"$token","spki":"$spki"}"""))
        )
    }

    @Test fun newerClaim() {
        assertEquals(HHQrResult.Newer, HHQr.parse(claim("""{"v":2,"server":"https://a.am","token":"$token","spki":"$spki"}""")))
    }

    @Test fun badClaims() {
        val bad = listOf(
            """{"v":1,"server":"http://a.am","token":"$token","spki":"$spki"}""",          // не https
            """{"v":1,"server":"https://a.am/x","token":"$token","spki":"$spki"}""",       // путь
            """{"v":1,"server":"https://a am","token":"$token","spki":"$spki"}""",
            """{"v":1,"server":"https://a.am","token":"short","spki":"$spki"}""",
            """{"v":1,"server":"https://a.am","token":"$token","spki":"nope"}""",
            """{"v":1,"server":"https://a.am","token":"$token"}""",
        )
        bad.forEach { assertEquals(it, HHQrResult.Invalid, HHQr.parse(claim(it))) }
        assertEquals(HHQrResult.Invalid, HHQr.parse("hhphone://claim?c=%%%"))
    }

    @Test fun addLinksStillWork() {
        val add = HHQr.encode(HHAccountQr(user = "1", pass = "p", domain = "d.am"))
        assertEquals(true, HHQr.parse(add) is HHQrResult.Ok)
    }
}
```
Run: `./gradlew testHhDebugUnitTest --tests '*HHQrClaimTest*'`
Expected: FAIL — `Unresolved reference: Claim`.

- [ ] **Step 2: Реализация в `HHQr.kt`**

В `sealed interface HHQrResult` добавить:
```kotlin
    data class Claim(val server: String, val token: String, val spki: String) : HHQrResult
```
Над `object HHQr` добавить:
```kotlin
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class HHClaimQr(@EncodeDefault val v: Int = 1, val server: String, val token: String, val spki: String)
```
В `object HHQr`:
```kotlin
    private const val CLAIM_PREFIX = "hhphone://claim?c="
    private val SERVER = Regex("""https://[A-Za-z0-9.\-]{1,253}(:\d{1,5})?""")
    private val TOKEN = Regex("""[A-Za-z0-9_\-]{43}""")
    private val SPKI = Regex("""[A-Za-z0-9+/]{43}=""")
```
В начале `parse` (после `val t = text.trim()`):
```kotlin
        if (t.startsWith(CLAIM_PREFIX)) {
            val c = try {
                val raw = String(Base64.getUrlDecoder().decode(t.removePrefix(CLAIM_PREFIX).substringBefore('&')), Charsets.UTF_8)
                json.decodeFromString(HHClaimQr.serializer(), raw)
            } catch (e: Exception) {
                return HHQrResult.Invalid
            }
            if (c.v > 1) return HHQrResult.Newer
            return if (c.v == 1 && SERVER.matches(c.server) && TOKEN.matches(c.token) && SPKI.matches(c.spki))
                HHQrResult.Claim(c.server, c.token, c.spki) else HHQrResult.Invalid
        }
```
Переименовать `private fun valid(q: HHAccountQr)` в `internal fun validAccount(q: HHAccountQr)` и обновить его вызов в `parse`.

В `HHHost.startProvision` ветка `when` должна стать исчерпывающей: временно добавить `is HHQrResult.Claim -> { HHUi.message.value = ctx.getString(R.string.hh_qr_newer); return }` (заменяется в Task 3).

Run: `./gradlew testHhDebugUnitTest`
Expected: PASS (все, включая 4 новых).

- [ ] **Step 3: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHQr.kt app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt app/src/test/kotlin/com/tutpro/baresip/plus/HHQrClaimTest.kt
git commit -m "QR с одноразовым токеном: разбор hhphone://claim"
```

---

### Task 2: Обмен токена — ответ, блокировка, проверка ключа, сеть

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHClaim.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHClaimTest.kt`
- Test resource: `app/src/test/resources/pbx-test.pem` (сертификат ниже)

**Interfaces:**
- Consumes: `HHQrResult.Claim`, `HHQr.validAccount`, `HHAccountQr` (Task 1), `HHLockState`, `HHPin` (план 2).
- Produces:
  - `data class HHClaimAnswer(val account: HHAccountQr, val lock: Boolean, val pinHash: String, val pinSalt: String)`
  - `sealed interface HHClaimOutcome { data class Ok(val answer: HHClaimAnswer); data object NoConnection; data object BadCert; data object Used; data object BadData }`
  - `object HHClaim { fun parseAnswer(json: String): HHClaimAnswer?; fun spkiOf(cert: X509Certificate): String; fun exchange(c: HHQrResult.Claim): HHClaimOutcome }` (`exchange` блокирующая — вызывать на `Dispatchers.IO`)
  - `fun hhApplyServerLock(a: HHClaimAnswer): HHLockState`
  - `class HHPinningTrust(spki: String, system: X509TrustManager) : X509TrustManager { val pinned: Boolean }`

- [ ] **Step 1: Тестовый сертификат**

`app/src/test/resources/pbx-test.pem` — тот же, что в `G:\HHPBX\windows\tests\t_hhphone.php` (SPKI `Lff/tP4lL2yRjkbrVZ9497NHo0hI4rrnWjDr4TLJahI=`):
```
-----BEGIN CERTIFICATE-----
MIIBfTCCASOgAwIBAgIUGQUIjgwFVsA3qIbI3AO7WhusDF0wCgYIKoZIzj0EAwIw
EzERMA8GA1UEAwwIcGJ4LnRlc3QwIBcNMjYxMDExMjExODQyWhgPMjEyNjA5MTcy
MTE4NDJaMBMxETAPBgNVBAMMCHBieC50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAE2pgpv871HkeG59xoh1LyR/x2H+Tc/g5VRTPY9kgKXC1l+p8b9mlAmsuE
uUGRcUgEgeXrLOsTXGBYe2D83CJVkqNTMFEwHQYDVR0OBBYEFJDLRD6aBU0+VIeZ
R5SVndLi4TtRMB8GA1UdIwQYMBaAFJDLRD6aBU0+VIeZR5SVndLi4TtRMA8GA1Ud
EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgbelRV019rVvwBZGitULXz786
mw/ObXfc4bCcZs8gyZMCIQDf8NjkiiWGOjKU4T2JMSTpsdJyDsuZZjlry6CSNgfB
Qg==
-----END CERTIFICATE-----
```

- [ ] **Step 2: Тест (падает)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHClaimTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class HHClaimTest {
    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/pbx-test.pem")) as X509Certificate
    private val certSpki = "Lff/tP4lL2yRjkbrVZ9497NHo0hI4rrnWjDr4TLJahI="

    /** «Система» в тестах: признаёт только то, что ей скажут. */
    private class FakeSystem(val accept: Boolean) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { if (!accept) throw CertificateException("untrusted") }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test fun pbkdf2SharedVectorWithHhpbx() {
        assertEquals("7GQnXhxDTntM3jRYt+FQO5RMtdZhX6cFxBLU+etr25o=", HHPin.hash("4821", "AAAAAAAAAAAAAAAAAAAAAA=="))
    }

    @Test fun spkiOfCertMatchesOpenssl() {
        assertEquals(certSpki, HHClaim.spkiOf(cert))
    }

    @Test fun pinnedSelfSignedPasses() {
        val t = HHPinningTrust(certSpki, FakeSystem(accept = false))
        t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertTrue(t.pinned)
    }

    @Test fun otherKeyUntrustedFails() {
        val t = HHPinningTrust("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", FakeSystem(accept = false))
        try { t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA"); fail("must throw") } catch (_: CertificateException) {}
        assertFalse(t.pinned)
    }

    @Test fun systemTrustedButOtherKeyPasses() {
        val t = HHPinningTrust("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", FakeSystem(accept = true))
        t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")   // Let's Encrypt продлил сертификат с новым ключом
        assertFalse(t.pinned)                                 // → имя хоста проверит стандартный верификатор
    }

    @Test fun answerWithLock() {
        val a = HHClaim.parseAnswer("""{"v":1,"name":"Иван","user":"201","pass":"Ab12","domain":"pbx.am","authUser":"","proxy":"",
            "transport":"udp","mediaEnc":"","lock":true,"pinHash":"H","pinSalt":"S"}""")!!
        assertEquals(HHAccountQr(name = "Иван", user = "201", pass = "Ab12", domain = "pbx.am"), a.account)
        assertTrue(a.lock); assertEquals("H", a.pinHash); assertEquals("S", a.pinSalt)
    }

    @Test fun answerWithoutLock() {
        val a = HHClaim.parseAnswer("""{"v":1,"name":"","user":"201","pass":"p","domain":"192.168.109.132","lock":false}""")!!
        assertFalse(a.lock)
        assertEquals("192.168.109.132", a.account.domain)
    }

    @Test fun badAnswers() {
        assertNull(HHClaim.parseAnswer("not json"))
        assertNull(HHClaim.parseAnswer("""{"v":1,"user":"201","pass":"a\"b","domain":"pbx.am","lock":false}"""))   // опасный символ
        assertNull(HHClaim.parseAnswer("""{"v":1,"user":"201","pass":"p","domain":"pbx.am","lock":true}"""))      // lock без хэша
        assertNull(HHClaim.parseAnswer("""{"error":"invalid"}"""))
    }

    @Test fun serverLockApplied() {
        val acc = HHAccountQr(user = "201", pass = "p", domain = "d")
        val locked = hhApplyServerLock(HHClaimAnswer(acc, true, HHPin.hash("4821", "AAAAAAAAAAAAAAAAAAAAAA=="), "AAAAAAAAAAAAAAAAAAAAAA=="))
        assertTrue(locked.locked)
        assertEquals(HHPinCheck.Ok, HHPin.check(locked, "4821", 0).first)
        assertEquals(HHLockState(), hhApplyServerLock(HHClaimAnswer(acc, false, "", "")))
    }
}
```
Run: `./gradlew testHhDebugUnitTest --tests '*HHClaimTest*'`
Expected: FAIL — `Unresolved reference: HHClaim`.

- [ ] **Step 3: `HHClaim.kt`**

```kotlin
package com.tutpro.baresip.plus

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Ответ claim.php HHPBX (спека токенов, раздел 1). */
data class HHClaimAnswer(val account: HHAccountQr, val lock: Boolean, val pinHash: String, val pinSalt: String)

sealed interface HHClaimOutcome {
    data class Ok(val answer: HHClaimAnswer) : HHClaimOutcome
    data object NoConnection : HHClaimOutcome
    data object BadCert : HHClaimOutcome
    data object Used : HHClaimOutcome
    data object BadData : HHClaimOutcome
}

@Serializable
private data class HHClaimJson(
    val v: Int = 0, val name: String = "", val user: String = "", val pass: String = "", val domain: String = "",
    val authUser: String = "", val proxy: String = "", val transport: String = "udp", val mediaEnc: String = "",
    val lock: Boolean = false, val pinHash: String = "", val pinSalt: String = "",
)

/** Доверие: система ИЛИ ключ листового сертификата = spki из QR (самоподписанный HHPBX в LAN). */
class HHPinningTrust(private val spki: String, private val system: X509TrustManager) : X509TrustManager {
    @Volatile var pinned = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isNotEmpty() && HHClaim.spkiOf(chain[0]) == spki) { pinned = true; return }
        system.checkServerTrusted(chain, authType)
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** После обмена: блокировка и доменный PIN — из ответа АТС. */
fun hhApplyServerLock(a: HHClaimAnswer): HHLockState =
    if (a.lock) HHLockState(locked = true, pinHash = a.pinHash, salt = a.pinSalt) else HHLockState()

object HHClaim {
    private val json = Json { ignoreUnknownKeys = true }

    fun spkiOf(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    fun parseAnswer(text: String): HHClaimAnswer? {
        val j = try { json.decodeFromString(HHClaimJson.serializer(), text) } catch (e: Exception) { return null }
        val acc = HHAccountQr(name = j.name, user = j.user, pass = j.pass, domain = j.domain, authUser = j.authUser,
            proxy = j.proxy, transport = j.transport, mediaEnc = j.mediaEnc)
        if (j.v != 1 || !HHQr.validAccount(acc)) return null
        if (j.lock && (j.pinHash.isEmpty() || j.pinSalt.isEmpty())) return null
        return HHClaimAnswer(acc, j.lock, j.pinHash, j.pinSalt)
    }

    /** Блокирующий обмен; вызывать на Dispatchers.IO. */
    fun exchange(c: HHQrResult.Claim): HHClaimOutcome {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val trust = HHPinningTrust(c.spki, system)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val conn = (URL("${c.server}/app/hhphone/claim.php").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            val standard = HttpsURLConnection.getDefaultHostnameVerifier()
            hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session -> trust.pinned || standard.verify(host, session) }
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write("token=${URLEncoder.encode(c.token, "UTF-8")}".toByteArray()) }
            when (conn.responseCode) {
                200 -> parseAnswer(conn.inputStream.bufferedReader().use { it.readText() })?.let { HHClaimOutcome.Ok(it) } ?: HHClaimOutcome.BadData
                410 -> HHClaimOutcome.Used
                else -> HHClaimOutcome.NoConnection
            }
        } catch (e: SSLException) {
            if (e.cause is CertificateException || e.message?.contains("Trust", ignoreCase = true) == true || e.message?.contains("hostname", ignoreCase = true) == true)
                HHClaimOutcome.BadCert else HHClaimOutcome.NoConnection
        } catch (e: IOException) {
            HHClaimOutcome.NoConnection
        } finally {
            conn.disconnect()
        }
    }
}
```
(Если `HttpURLConnection` не нужен как импорт — убрать предупреждение, оставив только используемые.)

Run: `./gradlew testHhDebugUnitTest`
Expected: PASS (все, включая 9 новых).

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHClaim.kt app/src/test/kotlin/com/tutpro/baresip/plus/HHClaimTest.kt app/src/test/resources/pbx-test.pem
git commit -m "Обмен одноразового токена: ответ HHPBX, доменный PIN, ключ сертификата из QR"
```

---

### Task 3: Claim в интерфейсе (`HHHost`)

**Files:**
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt`
- Modify: `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`

**Interfaces:**
- Consumes: `HHClaim.exchange`, `HHClaimOutcome`, `hhApplyServerLock` (Task 2); `HHUi`, `HHStaff`, `UserAgent`, `HHQr.accountLine` (план 2).
- Produces: `addAccount(ctx, qr, newLock: () -> HHLockState)` (лок после создания — функция), новые строки `hh_claiming`, `hh_claim_no_connection`, `hh_claim_bad_cert`, `hh_claim_used`, `hh_claim_bad_data`, `hh_replace_account_spent`.

- [ ] **Step 1: Строки**

В конец `app/src/hh/res/values/hh_feature_strings.xml` (перед `</resources>`):
```xml
    <string name="hh_claiming">Getting the account from the PBX…</string>
    <string name="hh_claim_no_connection">No connection to the PBX. The QR code was not used — try again.</string>
    <string name="hh_claim_bad_cert">The PBX server failed the security check.</string>
    <string name="hh_claim_used">This QR code has already been used or revoked.</string>
    <string name="hh_claim_bad_data">The PBX returned invalid account data.</string>
    <string name="hh_replace_account_spent">Account %1$s already exists. Replace it? The QR code has already been used either way.</string>
```
`values-ru`:
```xml
    <string name="hh_claiming">Получаю учётную запись с АТС…</string>
    <string name="hh_claim_no_connection">Нет связи с АТС. QR не потрачен — попробуйте снова.</string>
    <string name="hh_claim_bad_cert">Сервер АТС не прошёл проверку безопасности.</string>
    <string name="hh_claim_used">Этот QR уже использован или отозван.</string>
    <string name="hh_claim_bad_data">АТС вернула неверные данные учётной записи.</string>
    <string name="hh_replace_account_spent">Учётная запись %1$s уже есть. Заменить? QR в любом случае уже потрачен.</string>
```
`values-hy`:
```xml
    <string name="hh_claiming">Հաշիվը ստանում եմ ԱՀԿ-ից…</string>
    <string name="hh_claim_no_connection">ԱՀԿ-ի հետ կապ չկա։ QR-ը չի ծախսվել՝ փորձեք կրկին։</string>
    <string name="hh_claim_bad_cert">ԱՀԿ սերվերը չանցավ անվտանգության ստուգումը։</string>
    <string name="hh_claim_used">Այս QR-ն արդեն օգտագործված է կամ չեղարկված։</string>
    <string name="hh_claim_bad_data">ԱՀԿ-ն վերադարձրեց հաշվի սխալ տվյալներ։</string>
    <string name="hh_replace_account_spent">%1$s հաշիվն արդեն կա։ Փոխարինե՞լ։ QR-ն ամեն դեպքում արդեն ծախսված է։</string>
```
Run: `python -I scripts/missing-strings.py` → `ru: 0`, `hy: 0`.

- [ ] **Step 2: Обмен в `HHHost.kt`**

1. В `object HHProvisioning` добавить `val scope = kotlinx.coroutines.MainScope()`.
2. `startProvision`: заменить временную ветку Task 1 на:
```kotlin
        is HHQrResult.Claim -> {
            val go = { claim(ctx, r) }
            // спека: на заблокированном телефоне — сначала PIN, потом обмен (иначе отмена PIN сожгла бы токен)
            if (HHStaff.state().locked && !HHStaff.adminSession.value) HHUi.pinPrompt.value = HHPinPrompt(go) else go()
            return
        }
```
Ветка `Ok` остаётся прежней, но вызывает `replaceIfNeeded(ctx, qr, R.string.hh_replace_account) { hhApplyQrLock(HHStaff.state(), qr) }`.
3. Новая функция:
```kotlin
private fun claim(ctx: Context, c: HHQrResult.Claim) {
    Toast.makeText(ctx, ctx.getString(R.string.hh_claiming), Toast.LENGTH_SHORT).show()
    HHProvisioning.scope.launch {
        val outcome = withContext(Dispatchers.IO) { HHClaim.exchange(c) }
        when (outcome) {
            is HHClaimOutcome.Ok -> replaceIfNeeded(ctx, outcome.answer.account, R.string.hh_replace_account_spent) { hhApplyServerLock(outcome.answer) }
            HHClaimOutcome.NoConnection -> HHUi.message.value = ctx.getString(R.string.hh_claim_no_connection)
            HHClaimOutcome.BadCert -> HHUi.message.value = ctx.getString(R.string.hh_claim_bad_cert)
            HHClaimOutcome.Used -> HHUi.message.value = ctx.getString(R.string.hh_claim_used)
            HHClaimOutcome.BadData -> HHUi.message.value = ctx.getString(R.string.hh_claim_bad_data)
        }
    }
}
```
4. `replaceIfNeeded(ctx, qr, question: Int, newLock: () -> HHLockState)` — текст вопроса из `question`, в обеих ветках вызывать `addAccount(ctx, qr, newLock)`.
5. `addAccount(ctx, qr, newLock: () -> HHLockState)`: вместо `HHStaff.save(hhApplyQrLock(HHStaff.state(), qr))` — `HHStaff.save(newLock())`.
6. Импорты: `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.withContext`.

Run: `./gradlew testHhDebugUnitTest assembleHhRelease`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Живая проверка (эмулятор + HHPBX 1.8.0 на hhpbx-test)**

На ВМ hhpbx-test должен стоять HHPBX с приложением `app/hhphone` (план HHPBX выполнен, на стенде развёрнуто) и номер 101 с `sip_force_contact` (стенд эмулятора).
1. Получить свежую ссылку без блокировки: `ssh hayk@192.168.109.132 'sudo bash /var/tmp/linux/tests/vm/hhphone-panel.sh >/dev/null; cat /var/tmp/hhphone-link.txt'` (последняя строка скрипта создаёт живую ссылку).
2. На эмуляторе (release-APK; приложение разблокировано через «Администратор» → «Снять блокировку», если было заблокировано): `adb shell am start -a android.intent.action.VIEW -d '<ссылка>'` → «Получаю учётную запись…» → «уже есть. Заменить? QR в любом случае уже потрачен.» (101 уже есть) → «Да» → учётка 101 зарегистрирована (`fs_cli -x "show registrations"`).
3. Та же ссылка повторно → «Этот QR уже использован или отозван.»
4. Ссылка с блокировкой: на ВМ создать токен с `lock` (функция `mk "$e101" true` из `hhphone-scenarios.sh` при заданном PIN 4821, ссылку собрать `hhphone::qr_link('https://192.168.109.132', $t, <spki из /var/tmp/hhphone-link.txt>)`) → на телефоне учётка заменена, меню — режим сотрудника, «Администратор» принимает PIN `4821`.
5. Ещё одна ссылка с блокировкой на уже заблокированном телефоне → сначала окно PIN; «Отмена» → токен цел (`select used_date from v_hhphone_tokens …` пусто); повтор с PIN `4821` → учётка получена.
6. Подменённый ключ: взять живую ссылку, заменить `spki` на другой (собрать JSON заново с `AAAA…=`) → «Сервер АТС не прошёл проверку безопасности.», токен цел.
7. ВМ выключена или неверный хост (`server: https://192.168.109.250`) → «Нет связи с АТС. QR не потрачен…».

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt app/src/hh/res
git commit -m "QR из панели HHPBX: обмен токена в интерфейсе, PIN до обмена, ошибки связи и сертификата"
```

---

### Task 4: Автообновление с GitHub Releases

**Files:**
- Create: `app/src/main/kotlin/com/tutpro/baresip/plus/HHUpdate.kt`
- Test: `app/src/test/kotlin/com/tutpro/baresip/plus/HHUpdateTest.kt`
- Modify: `app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt` (проверка при возврате, диалог, пункт меню)
- Modify: `app/src/hh/AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`)
- Modify: `app/src/hh/res/values{,-ru,-hy}/hh_feature_strings.xml`

**Interfaces:**
- Produces: `data class HHLatest(versionCode: Int, versionName: String, notes: String)`; `object HHUpdate { const val BASE; fun parse(json: String): HHLatest?; fun due(last: Long, now: Long): Boolean; fun check(): HHLatest? (блокирующая); fun download(dest: File, progress: (Int) -> Unit): Boolean (блокирующая); fun installIntent(ctx: Context, apk: File): Intent }`.

- [ ] **Step 1: Тест (падает)**

`app/src/test/kotlin/com/tutpro/baresip/plus/HHUpdateTest.kt`:
```kotlin
package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HHUpdateTest {
    @Test fun parseLatest() {
        assertEquals(HHLatest(4, "0.3.1", "Исправления"), HHUpdate.parse("""{"versionCode":4,"versionName":"0.3.1","notes":"Исправления"}"""))
        assertEquals(HHLatest(4, "0.3.1", ""), HHUpdate.parse("""{"versionCode":4,"versionName":"0.3.1","extra":1}"""))
    }

    @Test fun parseGarbage() {
        assertNull(HHUpdate.parse("<html>Not Found</html>"))
        assertNull(HHUpdate.parse("""{"versionCode":0,"versionName":"0.3.1"}"""))
        assertNull(HHUpdate.parse("""{"versionCode":4,"versionName":""}"""))
        assertNull(HHUpdate.parse(""))
    }

    @Test fun newerOnlyByVersionCode() {
        assertTrue(HHLatest(4, "0.3.1", "").newerThan(3))
        assertFalse(HHLatest(3, "0.3.0", "").newerThan(3))
        assertFalse(HHLatest(2, "9.9.9", "").newerThan(3))
    }

    @Test fun sixHoursBetweenChecks() {
        val h = 3_600_000L
        assertTrue(HHUpdate.due(0, 6 * h))
        assertFalse(HHUpdate.due(10 * h, 15 * h))
        assertTrue(HHUpdate.due(10 * h, 16 * h))
        assertTrue(HHUpdate.due(20 * h, 10 * h))   // часы перевели назад — проверяем
    }
}
```
Run: `./gradlew testHhDebugUnitTest --tests '*HHUpdateTest*'`
Expected: FAIL — `Unresolved reference: HHLatest`.

- [ ] **Step 2: `HHUpdate.kt`**

```kotlin
package com.tutpro.baresip.plus

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class HHLatest(val versionCode: Int = 0, val versionName: String = "", val notes: String = "") {
    fun newerThan(current: Int) = versionCode > current
}

/** Обновления с GitHub Releases: ссылки «последнего выпуска», не API (у API лимит 60 запросов/час на IP). */
object HHUpdate {
    const val BASE = "https://github.com/TheRainOfSoul/HHPhone/releases/latest/download/"
    private const val INTERVAL_MS = 6 * 3_600_000L
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): HHLatest? =
        try { json.decodeFromString(HHLatest.serializer(), text).takeIf { it.versionCode > 0 && it.versionName.isNotBlank() } } catch (e: Exception) { null }

    fun due(last: Long, now: Long) = now - last >= INTERVAL_MS || now < last

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000; instanceFollowRedirects = true
    }

    /** null — нет сети, нет выпуска или битый latest.json. */
    fun check(): HHLatest? = try {
        open(BASE + "latest.json").run { try { if (responseCode == 200) parse(inputStream.bufferedReader().use { it.readText() }) else null } finally { disconnect() } }
    } catch (e: Exception) { null }

    /** Скачивает APK; false — обрыв или длина не совпала с Content-Length (битый файл не ставим). */
    fun download(dest: File, progress: (Int) -> Unit): Boolean = try {
        dest.parentFile?.mkdirs()
        val c = open(BASE + "HHPhone.apk")
        try {
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input -> dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            } }
            total <= 0 || done == total
        } finally { c.disconnect() }
    } catch (e: Exception) { false }

    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
```
APK кладётся в `filesDir/update/HHPhone.apk` — его раздаёт существующий `FileProvider` (`file_paths.xml`: `<files-path name="mms" path="." />`).

Run: `./gradlew testHhDebugUnitTest --tests '*HHUpdateTest*'`
Expected: PASS (4 теста).

- [ ] **Step 3: Строки и манифест**

`values/hh_feature_strings.xml`:
```xml
    <string name="hh_check_updates">Check for updates</string>
    <string name="hh_update_available">Version %1$s is available</string>
    <string name="hh_update_now">Update</string>
    <string name="hh_update_later">Later</string>
    <string name="hh_update_downloading">Downloading %1$d%%…</string>
    <string name="hh_update_none">You have the latest version.</string>
    <string name="hh_update_no_connection">Cannot check for updates: no connection.</string>
    <string name="hh_update_failed">Could not download the update. Try again later.</string>
    <string name="hh_update_allow">Allow HHPhone to install apps, then tap Update again.</string>
```
`values-ru`:
```xml
    <string name="hh_check_updates">Проверить обновления</string>
    <string name="hh_update_available">Доступна версия %1$s</string>
    <string name="hh_update_now">Обновить</string>
    <string name="hh_update_later">Позже</string>
    <string name="hh_update_downloading">Скачивание %1$d%%…</string>
    <string name="hh_update_none">Установлена последняя версия.</string>
    <string name="hh_update_no_connection">Не удалось проверить обновления: нет связи.</string>
    <string name="hh_update_failed">Не удалось скачать обновление. Попробуйте позже.</string>
    <string name="hh_update_allow">Разрешите HHPhone устанавливать приложения и нажмите «Обновить» ещё раз.</string>
```
`values-hy`:
```xml
    <string name="hh_check_updates">Ստուգել թարմացումները</string>
    <string name="hh_update_available">Հասանելի է %1$s տարբերակը</string>
    <string name="hh_update_now">Թարմացնել</string>
    <string name="hh_update_later">Ավելի ուշ</string>
    <string name="hh_update_downloading">Ներբեռնում %1$d%%…</string>
    <string name="hh_update_none">Տեղադրված է վերջին տարբերակը։</string>
    <string name="hh_update_no_connection">Չհաջողվեց ստուգել թարմացումները․ կապ չկա։</string>
    <string name="hh_update_failed">Չհաջողվեց ներբեռնել թարմացումը։ Փորձեք ավելի ուշ։</string>
    <string name="hh_update_allow">Թույլատրեք HHPhone-ին տեղադրել հավելվածներ և կրկին սեղմեք «Թարմացնել»։</string>
```
`app/src/hh/AndroidManifest.xml` — в `<manifest>` перед `<application`: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`.

- [ ] **Step 4: Интерфейс обновления в `HHHost.kt`**

1. В `HHUi` добавить: `val update = MutableStateFlow<HHLatest?>(null)` и `val downloading = MutableStateFlow<Int?>(null)`.
2. В `HHProvisioning` добавить:
```kotlin
    fun checkUpdate(ctx: Context, manual: Boolean) {
        val prefs = ctx.getSharedPreferences("hh_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && !HHUpdate.due(prefs.getLong("last", 0), now)) return
        prefs.edit().putLong("last", now).apply()
        scope.launch {
            val latest = withContext(Dispatchers.IO) { HHUpdate.check() }
            when {
                latest != null && latest.newerThan(BuildConfig.VERSION_CODE) -> HHUi.update.value = latest
                manual && latest == null -> HHUi.message.value = ctx.getString(R.string.hh_update_no_connection)
                manual -> HHUi.message.value = ctx.getString(R.string.hh_update_none)
            }
        }
    }

    fun installUpdate(ctx: Context) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            HHUi.message.value = ctx.getString(R.string.hh_update_allow)
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return   // повторный экран настроек сам не открываем: пользователь снова нажмёт «Обновить»
        }
        val apk = java.io.File(ctx.filesDir, "update/HHPhone.apk")
        HHUi.downloading.value = 0
        scope.launch {
            val ok = withContext(Dispatchers.IO) { HHUpdate.download(apk) { p -> HHUi.downloading.value = p } }
            HHUi.downloading.value = null
            if (ok) ctx.startActivity(HHUpdate.installIntent(ctx, apk)) else HHUi.message.value = ctx.getString(R.string.hh_update_failed)
        }
    }
```
3. В `HHHost(navController)`:
```kotlin
    // автопроверка при запуске и возврате в приложение (не чаще раза в 6 ч)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) HHProvisioning.checkUpdate(ctx, manual = false) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val update by HHUi.update.collectAsState()
    val downloading by HHUi.downloading.collectAsState()
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { HHUi.update.value = null },
            title = { Text(stringResource(R.string.hh_update_available, u.versionName)) },
            text = { Text(if (downloading != null) stringResource(R.string.hh_update_downloading, downloading!!) else u.notes) },
            confirmButton = { TextButton(enabled = downloading == null, onClick = { HHProvisioning.installUpdate(ctx) }) { Text(stringResource(R.string.hh_update_now)) } },
            dismissButton = { TextButton(onClick = { HHUi.update.value = null }) { Text(stringResource(R.string.hh_update_later)) } },
        )
    }
```
(«Позже» просто закрывает диалог: следующая автопроверка — через 6 ч по `last`.)
4. Меню: в `hhMenu` добавить пункт «Проверить обновления» во всех трёх ветках `when` (иконка `Icons.Outlined.SystemUpdate`, импорт `androidx.compose.material.icons.outlined.SystemUpdate`); в `hhMenuClick` — `ctx.getString(R.string.hh_check_updates) -> { HHProvisioning.checkUpdate(ctx, manual = true); true }`.

Run: `./gradlew testHhDebugUnitTest assembleHhRelease` и `python -I scripts/missing-strings.py`
Expected: `BUILD SUCCESSFUL`, `ru: 0`, `hy: 0`.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/com/tutpro/baresip/plus/HHUpdate.kt app/src/main/kotlin/com/tutpro/baresip/plus/HHHost.kt app/src/test/kotlin/com/tutpro/baresip/plus/HHUpdateTest.kt app/src/hh
git commit -m "Автообновление HHPhone с GitHub Releases"
```

---

### Task 5: Скрипт выпуска и выпуск v0.3.0 + проверка обновления

**Files:**
- Create: `scripts/release.ps1`
- Modify: `app/build.gradle.kts` (flavor `hh`: `versionCode = 3`, `versionName = "0.3.0"`)
- Modify: `README.md` (раздел «Выпуск»), `docs/testing.md` (пункты 8–9)

- [ ] **Step 1: `scripts/release.ps1` (UTF-8 с BOM — PowerShell 5.1)**

```powershell
# Выпуск HHPhone: сборка, проверка подписи, HHPhone.apk + latest.json (постоянные имена — их ищет автообновление).
# usage: powershell -File scripts\release.ps1 -Notes "что нового" [-Prerelease]
param([Parameter(Mandatory = $true)][string]$Notes, [switch]$Prerelease)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$m = [regex]::Match($gradle, 'create\("hh"\)[\s\S]*?versionCode = (\d+)[\s\S]*?versionName = "([^"]+)"')
if (-not $m.Success) { throw 'не найдены versionCode/versionName flavor hh' }
$code = [int]$m.Groups[1].Value; $name = $m.Groups[2].Value
if (git status --porcelain app) { throw 'в app есть незакоммиченные изменения' }
& .\gradlew.bat testHhDebugUnitTest assembleHhRelease
if ($LASTEXITCODE -ne 0) { throw "сборка завершилась с кодом $LASTEXITCODE" }
$apk = Join-Path $root 'app\build\outputs\apk\hh\release\app-hh-release.apk'
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') | Sort-Object Name | Select-Object -Last 1
$cert = & (Join-Path $bt.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0 -or -not ($cert -match 'CN=HHPhone')) { throw 'APK не подписан ключом HHPhone' }
$out = Join-Path $env:TEMP "hhphone-release-$name"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item $apk (Join-Path $out 'HHPhone.apk') -Force
$latest = [ordered]@{ versionCode = $code; versionName = $name; notes = $Notes } | ConvertTo-Json -Compress
[IO.File]::WriteAllText((Join-Path $out 'latest.json'), $latest, (New-Object Text.UTF8Encoding $false))
git tag "v$name"; git push origin "v$name"
$ghArgs = @('release', 'create', "v$name", (Join-Path $out 'HHPhone.apk'), (Join-Path $out 'latest.json'), '--title', "HHPhone $name", '--notes', $Notes)
if ($Prerelease) { $ghArgs += '--prerelease' }
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release завершился с кодом $LASTEXITCODE" }
Write-Host "Выпущено: HHPhone $name (versionCode $code)$(if ($Prerelease) { ' — пре-релиз, телефоны его не видят' })"
```
Сохранить с BOM (как `scripts/fetch-native.ps1`).

- [ ] **Step 2: Версия, документы, коммит**

`app/build.gradle.kts` flavor `hh`: `versionCode = 3`, `versionName = "0.3.0"`.
`README.md` — заменить строку «Выпуск: …» на:
```markdown
Выпуск: `powershell -File scripts\release.ps1 -Notes "что нового"` (`-Prerelease` — тестовый выпуск, телефоны его не видят).
Ключ подписи — `%USERPROFILE%\.hhphone\keystore.properties`. Телефоны с 0.3.0 обновляются сами (GitHub Releases, раз в 6 часов);
0.2.0 и ниже — вручную.
```
`docs/testing.md`, раздел эмулятора, добавить:
```markdown
8. QR из панели HHPBX (`/var/tmp/hhphone-link.txt` на ВМ после `hhphone-panel.sh`): получение, повтор → «уже использован», PIN до обмена на заблокированном телефоне, подменённый ключ → «не прошёл проверку».
9. Обновление: выпуск `-Prerelease` не виден; после снятия отметки пре-релиза «Проверить обновления» находит версию и ставит её.
```
```bash
git add scripts/release.ps1 app/build.gradle.kts README.md docs/testing.md
git commit -m "HHPhone 0.3.0: скрипт выпуска, версия, документация"
```

- [ ] **Step 3: Выпуск v0.3.0** (после финального ревью и слияния в main)

`powershell -File scripts\release.ps1 -Notes "QR из панели HHPBX без пароля (одноразовый), PIN телефонов из панели, автообновление."`
Expected: релиз `v0.3.0` с файлами `HHPhone.apk` и `latest.json`; `curl -sL https://github.com/TheRainOfSoul/HHPhone/releases/latest/download/latest.json` → `{"versionCode":3,…}`.

- [ ] **Step 4: Проверка автообновления**

1. На эмуляторе поставить 0.3.0 из релиза (`adb install -r HHPhone.apk`).
2. Поднять версию до 0.3.1 (`versionCode = 4`), коммит, `release.ps1 -Notes "проверка обновления" -Prerelease` → на телефоне «Проверить обновления» → «Установлена последняя версия.» (пре-релиз не виден).
3. `gh release edit v0.3.1 --prerelease=false --latest` → «Проверить обновления» → «Доступна версия 0.3.1» → «Обновить» → если нет разрешения — экран настроек и сообщение; разрешить (`adb shell appops set am.dgsolutions.hhphone REQUEST_INSTALL_PACKAGES allow`) → «Обновить» → индикатор → системный установщик → подтвердить → `adb shell dumpsys package am.dgsolutions.hhphone | grep versionName` → `0.3.1`; учётки и блокировка сохранились.
4. 0.3.1 оставить последним выпуском (рабочая версия, отличается от 0.3.0 только номером) — либо, по решению пользователя, удалить релиз и тег.
