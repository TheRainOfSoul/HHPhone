package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HHStaffTest {
    private val qr = HHAccountQr(user = "201", pass = "p", domain = "d.am")
    private val lockedState = HHPin.withPin(HHLockState(locked = true), "4821")

    @Test fun unlockedPhoneNeverAsks() {
        assertFalse(hhQrNeedsPin(HHLockState(), adminSession = false, qr = qr.copy(lock = true, pin = "1111")))
    }

    @Test fun lockedPhoneSamePinAddsDirectly() {
        assertFalse(hhQrNeedsPin(lockedState, false, qr.copy(lock = true, pin = "4821")))
    }

    @Test fun lockedPhoneOtherOrNoPinAsks() {
        assertTrue(hhQrNeedsPin(lockedState, false, qr.copy(lock = true, pin = "1111")))
        assertTrue(hhQrNeedsPin(lockedState, false, qr))
    }

    @Test fun adminSessionNeverAsks() {
        assertFalse(hhQrNeedsPin(lockedState, adminSession = true, qr = qr))
    }

    @Test fun lockComesFromQr() {
        val locked = hhApplyQrLock(HHLockState(), qr.copy(lock = true, pin = "1111"))
        assertTrue(locked.locked)
        assertEquals(HHPinCheck.Ok, HHPin.check(locked, "1111", 0).first)
        val unlocked = hhApplyQrLock(lockedState, qr)
        assertFalse(unlocked.locked)
        assertEquals("", unlocked.pinHash)
    }
}
