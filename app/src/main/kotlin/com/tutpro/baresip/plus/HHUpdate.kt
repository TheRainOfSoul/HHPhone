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
