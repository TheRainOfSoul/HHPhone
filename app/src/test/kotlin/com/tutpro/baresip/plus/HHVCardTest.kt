package com.tutpro.baresip.plus

import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Test

class HHVCardTest {
    @Test fun phonesSipAndEscaping() {
        val v = hhVCard("Ани; Иванова, 2", listOf(HHNum(1, "+374 91 123456", Phone.TYPE_MOBILE), HHNum(2, "", Phone.TYPE_HOME),
            HHNum(3, "010 55 11", Phone.TYPE_OTHER), HHNum(4, "sip:100@pbx.hotel", -2)))
        assertEquals(listOf("BEGIN:VCARD", "VERSION:3.0", "FN:Ани\\; Иванова\\, 2", "N:Ани\\; Иванова\\, 2;;;;",
            "TEL;TYPE=CELL:+374 91 123456", "TEL;TYPE=VOICE:010 55 11", "IMPP:sip:100@pbx.hotel", "END:VCARD", ""), v.split("\r\n"))
    }
}
