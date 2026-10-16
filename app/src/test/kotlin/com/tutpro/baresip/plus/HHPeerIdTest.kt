package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHPeerIdTest {
    @Test fun ownStationShowsNumberWhenCallHasPort() {
        // звонок пришёл с доменом с портом, учётка записана без порта — всё равно своя станция
        assertEquals("27", HHUiLogic.peerId("sip:27@123.123.123.123:5060", listOf("123.123.123.123")))
    }

    @Test fun ownStationPortOnAccountSide() {
        assertEquals("27", HHUiLogic.peerId("sip:27@123.123.123.123", listOf("123.123.123.123:5060")))
    }

    @Test fun foreignServerKeepsFullAddress() {
        assertEquals("sip:27@other.example.com", HHUiLogic.peerId("sip:27@other.example.com", listOf("123.123.123.123")))
    }

    @Test fun letteredNameKeepsFullAddress() {
        assertEquals("sip:reception@123.123.123.123",
            HHUiLogic.peerId("sip:reception@123.123.123.123", listOf("123.123.123.123")))
    }

    @Test fun nonSipReturnsNumber() {
        assertEquals("077111222", HHUiLogic.peerId("tel:077111222", emptyList()))
    }
}
