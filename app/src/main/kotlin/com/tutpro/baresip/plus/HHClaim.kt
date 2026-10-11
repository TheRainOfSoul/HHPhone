package com.tutpro.baresip.plus

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
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
