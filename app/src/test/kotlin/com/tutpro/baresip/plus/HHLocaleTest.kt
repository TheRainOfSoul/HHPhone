package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HHLocaleTest {
    @Test fun supportedSystemLanguageKeepsSystem() {
        assertNull(hhFallbackLanguage("ru", appLocalesEmpty = true))
        assertNull(hhFallbackLanguage("hy", appLocalesEmpty = true))
        assertNull(hhFallbackLanguage("en", appLocalesEmpty = true))
    }

    @Test fun foreignSystemLanguageFallsBackToRussian() {
        assertEquals("ru", hhFallbackLanguage("de", appLocalesEmpty = true))
        assertEquals("ru", hhFallbackLanguage("zh", appLocalesEmpty = true))
    }

    @Test fun userChoiceIsNeverOverridden() {
        assertNull(hhFallbackLanguage("de", appLocalesEmpty = false))
    }
}
