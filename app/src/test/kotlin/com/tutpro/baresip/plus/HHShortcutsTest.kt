package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHShortcutsTest {
    private fun bc(name: String, favorite: Boolean, vararg uris: String) =
        Contact.BaresipContact(name, ArrayList(uris.map { Contact.ContactUri(it) }), "", 0, 0, favorite)

    @Test fun callUriNormalizesSchemeAndWhitespace() {
        assertEquals("tel:+37477111111", HHShortcuts.callUri("tel:+374 77 11 11 11"))
        assertEquals("tel:077111111", HHShortcuts.callUri("077 11 11 11"))        // без схемы → tel:
        assertEquals("sip:alice@example.com", HHShortcuts.callUri("sip:alice@example.com"))
        assertEquals("tel:+37477111111", HHShortcuts.callUri("  tel:+37477111111 "))
    }

    @Test fun idStableAndSchemeAgnostic() {
        assertEquals(HHShortcuts.idFor("tel:077 111 111"), HHShortcuts.idFor("077111111"))
        assertEquals("hhcall:tel:077111111", HHShortcuts.idFor("077111111"))
    }

    @Test fun favoriteTargetsOnlyFavoritesCappedPrimaryUri() {
        val contacts = listOf(
            bc("Бек", true, "tel:077111111", "tel:077222222"),   // берём первый номер
            bc("Нет", false, "tel:077333333"),                   // не избранный — пропуск
            bc("Пусто", true, "", "tel:077444444"),              // первый непустой
            bc("A", true, "tel:1"), bc("B", true, "tel:2"), bc("C", true, "tel:3"),
        )
        val t = HHShortcuts.favoriteTargets(contacts, max = 4)
        assertEquals(listOf("Бек", "Пусто", "A", "B"), t.map { it.label })   // cap 4, по порядку
        assertEquals("tel:077111111", t[0].callUri)
        assertEquals("tel:077444444", t[1].callUri)
    }

    @Test fun favoriteTargetsDedupSameNumber() {
        val contacts = listOf(bc("X", true, "tel:077 111 111"), bc("Y", true, "077111111"))
        assertEquals(1, HHShortcuts.favoriteTargets(contacts).size)
    }
}
