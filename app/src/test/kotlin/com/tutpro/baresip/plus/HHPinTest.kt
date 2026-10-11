package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HHPinTest {
    private val locked = HHPin.withPin(HHLockState(locked = true), "4821")

    @Test fun hashIsSaltedAndNotPlain() {
        assertNotEquals("4821", locked.pinHash)
        assertNotEquals(HHPin.hash("4821", "a"), HHPin.hash("4821", "b"))
        assertEquals(HHPin.hash("4821", "a"), HHPin.hash("4821", "a"))
    }

    @Test fun rightPinOkAndResetsFails() {
        val (_, after2) = HHPin.check(locked, "0000", 0)
        val (r, s) = HHPin.check(after2, "4821", 0)
        assertEquals(HHPinCheck.Ok, r)
        assertEquals(0, s.fails)
    }

    @Test fun fiveWrongLockOutForFiveMinutes() {
        var s = locked
        repeat(4) { s = HHPin.check(s, "0000", 1_000).second }
        val (r5, s5) = HHPin.check(s, "0000", 1_000)
        assertEquals(HHPinCheck.LockedOut(5), r5)
        val (r, _) = HHPin.check(s5, "4821", 1_000 + 60_000)
        assertEquals(HHPinCheck.LockedOut(4), r)
    }

    @Test fun lockoutSurvivesRestart() {
        var s = locked
        repeat(5) { s = HHPin.check(s, "0000", 0).second }
        val restored = s.copy()   // состояние читается из хранилища как данные
        assertTrue(HHPin.check(restored, "4821", 299_000).first is HHPinCheck.LockedOut)
        assertEquals(HHPinCheck.Ok, HHPin.check(restored, "4821", 300_000).first)
    }
}
