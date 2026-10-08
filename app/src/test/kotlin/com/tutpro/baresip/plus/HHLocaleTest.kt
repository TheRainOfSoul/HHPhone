package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHLocaleTest {
    private val none = LocaleFlag.NONE
    private val auto = LocaleFlag.AUTO
    private val user = LocaleFlag.USER

    @Test fun supportedSystemLanguageKeepsSystem() {
        assertEquals(LocaleDecision(null, none), hhLocaleDecision(listOf("ru"), emptyList(), none))
        assertEquals(LocaleDecision(null, none), hhLocaleDecision(listOf("hy"), emptyList(), none))
        assertEquals(LocaleDecision(null, none), hhLocaleDecision(listOf("en"), emptyList(), none))
    }

    @Test fun foreignSystemLanguageFallsBackToRussian() {
        assertEquals(LocaleDecision(listOf("ru"), auto), hhLocaleDecision(listOf("de"), emptyList(), none))
        assertEquals(LocaleDecision(listOf("ru"), auto), hhLocaleDecision(listOf("zh", "ja"), emptyList(), none))
    }

    @Test fun supportedLanguageLowerInSystemListIsRespected() {
        assertEquals(LocaleDecision(null, none), hhLocaleDecision(listOf("de", "hy"), emptyList(), none))
    }

    @Test fun userChoiceIsNeverOverridden() {
        assertEquals(LocaleDecision(null, none), hhLocaleDecision(listOf("de"), listOf("en"), none))
        assertEquals(LocaleDecision(null, user), hhLocaleDecision(listOf("de"), emptyList(), user))
    }

    @Test fun ourRussianIsDroppedWhenSystemBecomesSupported() {
        assertEquals(LocaleDecision(emptyList(), none), hhLocaleDecision(listOf("hy"), listOf("ru"), auto))
    }

    @Test fun ourRussianStaysWhileSystemIsForeign() {
        assertEquals(LocaleDecision(null, auto), hhLocaleDecision(listOf("de"), listOf("ru"), auto))
    }

    @Test fun userChangeAfterOurFallbackIsRemembered() {
        // «Как в системе» (пустой список) или другой язык после нашего ru — это решение пользователя
        assertEquals(LocaleDecision(null, user), hhLocaleDecision(listOf("de"), emptyList(), auto))
        assertEquals(LocaleDecision(null, user), hhLocaleDecision(listOf("de"), listOf("en"), auto))
    }
}
