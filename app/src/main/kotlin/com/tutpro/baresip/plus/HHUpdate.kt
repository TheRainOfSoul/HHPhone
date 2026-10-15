package com.tutpro.baresip.plus

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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

    /**
     * Ставит APK своим сеансом PackageInstaller: только так установщик может сохранить «полноэкранные уведомления»
     * (Android 14+ при установке через системный экран сбрасывает его к политике по умолчанию — для приложений
     * не из Play это «запрещено»). Подтверждение пользователя — через HHUpdateReceiver.
     */
    fun install(ctx: Context, apk: File) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 34)
                    setPermissionState(Manifest.permission.USE_FULL_SCREEN_INTENT, PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("HHPhone.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, HHUpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // система дописывает статус
                s.commit(done.intentSender)
            }
        } catch (e: Exception) {
            android.util.Log.w("HHUpdate", "session install failed, system installer: $e")
            ctx.startActivity(installIntent(ctx, apk))
        }
    }

    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/** Ответ сеанса установки: подтверждение пользователя или ошибка. */
class HHUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}   // пользователь отказался
            else -> {
                android.util.Log.w("HHUpdate", "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                HHUi.message.value = ctx.getString(R.string.hh_update_failed)
            }
        }
    }
}
