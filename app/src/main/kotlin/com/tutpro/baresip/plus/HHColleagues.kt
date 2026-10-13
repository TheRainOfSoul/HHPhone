package com.tutpro.baresip.plus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/** Коллега со станции HHPBX: номер, имя, через какую учётку звонить. */
data class HHColleague(val number: String, val name: String, val aor: String)

/**
 * Коллеги со станций HHPBX (directory.php, HHPBX 1.11.0): обновление при старте не чаще раза в сутки, кэш в prefs —
 * при недоступности станции показываем сохранённый. Звёздочки — свои, в prefs.
 */
object HHColleagues {
    private const val TAG = "HHColleagues"
    private const val DAY_MS = 24 * 3_600_000L
    private lateinit var prefs: SharedPreferences
    val list = MutableStateFlow<List<HHColleague>>(emptyList())
    val stars = MutableStateFlow<Set<String>>(emptySet())

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("hh_colleagues", Context.MODE_PRIVATE)
        list.value = load()
        stars.value = prefs.getStringSet("stars", emptySet())!!.toSet()
    }

    private fun load(): List<HHColleague> = HHPush.stations().flatMap { aor ->
        val (self, _) = HHPush.userDomain(aor)
        HHUiLogic.parseDirectory(prefs.getString("d|$aor", "") ?: "")
            .filter { it.first != self }.map { (n, name) -> HHColleague(n, name, aor) }
    }

    /** Сеть — не из UI-потока. force — игнорировать суточный интервал. */
    @Synchronized fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        for (aor in HHPush.stations()) {
            if (!force && now - prefs.getLong("t|$aor", 0L) < DAY_MS) continue
            val (server, spki) = HHPush.station(aor) ?: continue
            val pass = Account.ofAor(aor)?.authPass ?: continue
            val (user, domain) = HHPush.userDomain(aor)
            val body = try {
                val c = HHClaim.open(server, spki, "/app/hhphone/directory.php")
                try {
                    c.outputStream.use { it.write(HHPush.form("user" to user, "domain" to domain, "pass" to pass).toByteArray()) }
                    if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
                } finally { c.disconnect() }
            } catch (e: Exception) { Log.w(TAG, "directory.php: ${e.message}"); null }
            // старая HHPBX (404) или ошибка — кэш остаётся
            if (body != null && HHUiLogic.parseDirectory(body).isNotEmpty())
                prefs.edit { putString("d|$aor", body); putLong("t|$aor", now) }
        }
        list.value = load()
    }

    fun isStar(number: String) = number in stars.value

    fun toggleStar(number: String) {
        stars.value = if (number in stars.value) stars.value - number else stars.value + number
        prefs.edit { putStringSet("stars", stars.value) }
    }
}
