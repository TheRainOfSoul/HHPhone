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
    data class Claim(val server: String, val token: String, val spki: String) : HHQrResult
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class HHClaimQr(@EncodeDefault val v: Int = 1, val server: String, val token: String, val spki: String)

object HHQr {
    private const val PREFIX = "hhphone://add?c="
    private const val CLAIM_PREFIX = "hhphone://claim?c="
    private val SERVER = Regex("""https://[A-Za-z0-9.\-]{1,253}(:\d{1,5})?""")
    private val TOKEN = Regex("""[A-Za-z0-9_\-]{43}""")
    private val SPKI = Regex("""[A-Za-z0-9+/]{43}=""")
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
        if (!t.startsWith(PREFIX)) return HHQrResult.NotHHPhone
        val qr = try {
            val raw = String(Base64.getUrlDecoder().decode(t.removePrefix(PREFIX).substringBefore('&')), Charsets.UTF_8)
            json.decodeFromString(HHAccountQr.serializer(), raw)
        } catch (e: Exception) {
            return HHQrResult.Invalid
        }
        if (qr.v > 1) return HHQrResult.Newer
        return if (validAccount(qr)) HHQrResult.Ok(qr) else HHQrResult.Invalid
    }

    internal fun validAccount(q: HHAccountQr): Boolean =
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
