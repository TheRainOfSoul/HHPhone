package com.tutpro.baresip.plus

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit

private val HH_LANGUAGES = setOf("ru", "hy", "en")

/** NONE — язык приложения не трогали, AUTO — ru выставили мы, USER — пользователь сам выбрал язык после нашего ru. */
enum class LocaleFlag { NONE, AUTO, USER }

/** set: null — ничего не менять, пустой список — «как в системе», иначе — выставить эти языки. */
data class LocaleDecision(val set: List<String>?, val flag: LocaleFlag)

fun hhLocaleDecision(systemLanguages: List<String>, appLanguages: List<String>, flag: LocaleFlag): LocaleDecision {
    val systemSupported = systemLanguages.any { it in HH_LANGUAGES }
    return when (flag) {
        LocaleFlag.USER -> LocaleDecision(null, flag)
        LocaleFlag.AUTO -> when {
            appLanguages != listOf("ru") -> LocaleDecision(null, LocaleFlag.USER)
            systemSupported -> LocaleDecision(emptyList(), LocaleFlag.NONE)
            else -> LocaleDecision(null, flag)
        }
        LocaleFlag.NONE ->
            if (appLanguages.isEmpty() && !systemSupported) LocaleDecision(listOf("ru"), LocaleFlag.AUTO)
            else LocaleDecision(null, flag)
    }
}

object HHLocale {
    private const val PREFS = "hh_locale"
    private const val FLAG = "flag"

    fun apply(context: Context) {
        // ponytail: per-app locale есть только с Android 13; на 9–12 при чужом системном языке будет английский.
        // Если понадобится — переопределять Configuration в attachBaseContext всех Activity и сервиса.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val flag = runCatching { LocaleFlag.valueOf(prefs.getString(FLAG, null)!!) }.getOrDefault(LocaleFlag.NONE)
        // Не Locale.getDefault(): в процессе приложения он уже подобран под наши ru/hy/en (de,en → en).
        val decision = hhLocaleDecision(lm.systemLocales.languages(), lm.applicationLocales.languages(), flag)
        decision.set?.let { lm.applicationLocales = LocaleList.forLanguageTags(it.joinToString(",")) }
        if (decision.flag != flag) prefs.edit { putString(FLAG, decision.flag.name) }
    }

    private fun LocaleList.languages() = (0 until size()).map { get(it).language }
}
