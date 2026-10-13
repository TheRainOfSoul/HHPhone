package com.tutpro.baresip.plus

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.net.URLEncoder

/**
 * Push через HHPBX (спека 2026-10-13). Учётка — push, если станция приняла текущий FCM-токен;
 * остальные («чужие», старые, без связи) держат постоянную связь.
 * Ключи "hh_push": "s|<aor>" = "server\nspki", "ok|<aor>" = токен, принятый станцией, "token" = текущий FCM-токен.
 */
object HHPush {
    private const val TAG = "HHPush"
    private lateinit var prefs: SharedPreferences
    @Volatile private var lastActive = 0L   // touch() при старте сервиса и при push

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("hh_push", Context.MODE_PRIVATE) }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit { putString("token", value) }

    fun remember(aor: String, server: String, spki: String) =
        prefs.edit { putString("s|$aor", "$server\n$spki"); remove("ok|$aor") }

    private fun station(aor: String): Pair<String, String>? =
        prefs.getString("s|$aor", null)?.split("\n")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun isPush(aor: String): Boolean = token.let { it != null && prefs.getString("ok|$aor", null) == it }

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun idleMs(): Long = SystemClock.elapsedRealtime() - lastActive

    /** Отправить токен станциям, которые его ещё не приняли. Сеть — вызывать не из UI-потока. */
    @Synchronized fun sync() {
        val t = token ?: return
        for (key in prefs.all.keys.filter { it.startsWith("s|") }) {
            val aor = key.removePrefix("s|")
            if (prefs.getString("ok|$aor", null) == t) continue
            val (server, spki) = station(aor) ?: continue
            val acc = Account.ofAor(aor) ?: continue
            val (user, domain) = userDomain(aor)
            if (post(server, spki, form("op" to "set", "user" to user, "domain" to domain, "pass" to acc.authPass, "token" to t)))
                prefs.edit { putString("ok|$aor", t) }
        }
    }

    /** Учётка удаляется: снять токен со станции (до удаления — нужен пароль), забыть станцию. */
    fun forget(aor: String) {
        val st = station(aor)
        val pass = Account.ofAor(aor)?.authPass
        val t = token
        prefs.edit { remove("s|$aor"); remove("ok|$aor") }
        if (st == null || pass == null || t == null) return
        val (user, domain) = userDomain(aor)
        Thread { post(st.first, st.second, form("op" to "del", "user" to user, "domain" to domain, "pass" to pass, "token" to t)) }.start()
    }

    internal fun userDomain(aor: String): Pair<String, String> =
        aor.removePrefix("sip:").substringBefore(";").let { it.substringBefore("@") to it.substringAfter("@") }

    internal fun form(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun post(server: String, spki: String, body: String): Boolean = try {
        val c = HHClaim.open(server, spki, "/app/hhphone/push.php")
        try { c.outputStream.use { it.write(body.toByteArray()) }; c.responseCode == 200 } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "push.php: ${e.message}"); false }
}
