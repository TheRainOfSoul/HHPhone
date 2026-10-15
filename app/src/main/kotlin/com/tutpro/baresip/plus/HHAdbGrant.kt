package com.tutpro.baresip.plus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.Date

/**
 * Выдать себе WRITE_SECURE_SETTINGS без компьютера (Android 11+): «Отладка по Wi-Fi» → «Подключить по коду» → код из окна
 * Настроек вводится в уведомление HHPhone (окно при этом остаётся открытым) → сопряжение и подключение к своему же adbd →
 * pm grant. С разрешением служба записи SIM включается только на время звонка (HHRecAccessibility.set) — банки её не видят.
 */
object HHAdbGrant {
    private const val NOTIF_ID = 120
    private const val KEY_CODE = "code"
    /** null — не начато; иначе текст последнего шага для экрана настроек. */
    val state = mutableStateOf<String?>(null)
    @Volatile private var pairHost: InetAddress? = null
    @Volatile private var pairPort = 0
    private var mdns: AdbMdns? = null
    @Volatile private var startedAt = 0L

    /** Идёт настройка — HHPhone не засыпает (иначе процесс уйдёт вместе с уведомлением и поиском порта). */
    fun busy() = mdns != null && System.currentTimeMillis() - startedAt < 10 * 60_000

    fun supported() = Build.VERSION.SDK_INT >= 30

    fun devOptionsOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Слушать порт сопряжения, показать уведомление с полем для кода и открыть «Для разработчиков». */
    @Synchronized fun begin(ctx: Context) {
        val app = ctx.applicationContext
        startedAt = System.currentTimeMillis()
        if (mdns == null) mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0) { pairHost = host; pairPort = port }
        }.apply { start() }
        notify(app, app.getString(R.string.hh_adb_enter_code), input = true)
        state.value = app.getString(R.string.hh_adb_enter_code)
        try {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Ответ из уведомления: код сопряжения. Работа — в потоке: служба HHPhone держит процесс, goAsync не хватило бы по времени. */
    class Reply : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.filter { it.isDigit() }?.toString()
            val app = ctx.applicationContext
            if (code.isNullOrEmpty()) return
            notify(app, app.getString(R.string.hh_adb_working), input = false)
            Thread {
                try { grant(app, code) } catch (e: Exception) { Log.w("HHAdbGrant", "grant failed: $e") }
                // итог — по самому разрешению: отключение отладки в конце может оборвать соединение раньше ответа
                val ok = HHRecAccessibility.canToggle(app)
                if (ok) { HHRecAccessibility.set(app, false); stopMdns() }
                val msg = app.getString(if (ok) R.string.hh_adb_done else R.string.hh_adb_failed)
                notify(app, msg, input = !ok)
                state.value = msg
            }.start()
        }
    }

    private fun grant(ctx: Context, code: String) {
        val port = pairPort
        check(port > 0) { "pairing port not found" }
        val m = Manager()
        try {
            check(m.pair(pairHost?.hostAddress ?: "127.0.0.1", port, code)) { "pairing refused" }
            check(m.connectTls(ctx, 20_000)) { "connect failed" }
            // разрешение, затем «Отладку по Wi-Fi» выключаем — после настройки она не нужна
            m.openStream("shell:pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS; settings put global adb_wifi_enabled 0")
                .use { s -> s.openInputStream().use { it.readBytes() } }
        } finally { try { m.close() } catch (_: Exception) {} }
    }

    @Synchronized private fun stopMdns() { mdns?.stop(); mdns = null }

    private fun notify(ctx: Context, text: String, input: Boolean) {
        val nb = NotificationCompat.Builder(ctx, MEDIUM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(ctx.getString(R.string.hh_adb_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(!input)
        if (input) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel(ctx.getString(R.string.hh_adb_code)).build()
            val pi = PendingIntent.getBroadcast(ctx, NOTIF_ID, Intent(ctx, Reply::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            nb.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification_call, ctx.getString(R.string.hh_adb_code), pi)
                .addRemoteInput(ri).build())
        }
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, nb.build()) } catch (_: SecurityException) {}
    }

    /** Ключ и самоподписанный сертификат — на один сеанс: сопряжение и подключение идут сразу друг за другом. */
    private class Manager : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            key = kp.private
            val alg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=HHPhone")
            val now = System.currentTimeMillis()
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(now))
                setIssuer(name); setSubject(name)
                setStartDate(Time(Date(now - 86_400_000))); setEndDate(Time(Date(now + 86_400_000L * 365)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
                setSignature(alg)
            }.generateTBSCertificate()
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs.encoded) }.sign()
            val der = DERSequence(ASN1EncodableVector().apply { add(tbs); add(alg); add(DERBitString(sig)) }).encoded
            cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "HHPhone"
    }
}
