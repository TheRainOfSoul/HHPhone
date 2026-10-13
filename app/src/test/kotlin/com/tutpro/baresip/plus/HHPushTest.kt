package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHPushTest {
    @Test fun userDomain() {
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am"))
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am;transport=tcp"))
    }

    // станция без ключа Firebase не будит: учётка не push, HHPhone держит связь
    @Test fun acceptedOnlyWithPushTrue() {
        assertEquals(true, HHPush.accepted(200, """{"ok":true,"push":true}"""))
        assertEquals(false, HHPush.accepted(200, """{"ok":true,"push":false}"""))
        assertEquals(false, HHPush.accepted(200, """{"ok":true}"""))
        assertEquals(null, HHPush.accepted(403, """{"error":"denied"}"""))
        assertEquals(null, HHPush.accepted(500, ""))
    }

    @Test fun formEncodes() {
        assertEquals("op=set&pass=a%26b%3Dc&token=x%3Ay", HHPush.form("op" to "set", "pass" to "a&b=c", "token" to "x:y"))
    }
}
