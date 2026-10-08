package com.tutpro.baresip.plus

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

private val HH_LANGUAGES = setOf("ru", "hy", "en")

/** Язык, который надо выставить приложению, или null — оставить как есть. */
fun hhFallbackLanguage(systemLanguage: String, appLocalesEmpty: Boolean): String? =
    if (appLocalesEmpty && systemLanguage !in HH_LANGUAGES) "ru" else null

object HHLocale {
    fun apply(context: Context) {
        // ponytail: per-app locale есть только с Android 13; на 9–12 при чужом системном языке будет английский.
        // Если понадобится — переопределять Configuration в attachBaseContext всех Activity и сервиса.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        // Не Locale.getDefault(): в процессе приложения он уже подобран под наши ru/hy/en (de,en → en).
        val system = lm.systemLocales.get(0)?.language ?: return
        val lang = hhFallbackLanguage(system, lm.applicationLocales.isEmpty) ?: return
        lm.applicationLocales = LocaleList.forLanguageTags(lang)
    }
}
