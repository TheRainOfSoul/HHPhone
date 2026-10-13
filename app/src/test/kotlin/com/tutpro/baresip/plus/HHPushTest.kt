package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class HHPushTest {
    @Test fun userDomain() {
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am"))
        assertEquals("101" to "pbx.orbeli.am", HHPush.userDomain("sip:101@pbx.orbeli.am;transport=tcp"))
    }

    @Test fun formEncodes() {
        assertEquals("op=set&pass=a%26b%3Dc&token=x%3Ay", HHPush.form("op" to "set", "pass" to "a&b=c", "token" to "x:y"))
    }
}
