package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HHDialableTest {
    @Test fun phoneNumbersKeepDigitsPlusStarHash() {
        assertEquals("+37477111111", HHUiLogic.dialableFromClipboard("+374 77 11 11 11"))
        assertEquals("077111111", HHUiLogic.dialableFromClipboard("(077) 11-11-11"))
        assertEquals("+37477111111", HHUiLogic.dialableFromClipboard("tel:+37477111111"))
        assertEquals("*100#", HHUiLogic.dialableFromClipboard(" *100# "))
    }

    @Test fun sipUriKeptWhole() {
        assertEquals("sip:alice@example.com", HHUiLogic.dialableFromClipboard("sip:alice@example.com"))
        assertEquals("sip:101@pbx.local", HHUiLogic.dialableFromClipboard("sip:101@pbx.local;transport=tls"))
    }

    @Test fun lettersAndJunkRejected() {
        assertNull(HHUiLogic.dialableFromClipboard("Позвони мне завтра"))
        assertNull(HHUiLogic.dialableFromClipboard("John Doe"))
        assertNull(HHUiLogic.dialableFromClipboard(""))
        assertNull(HHUiLogic.dialableFromClipboard("   "))
        assertNull(HHUiLogic.dialableFromClipboard(null))
    }
}
