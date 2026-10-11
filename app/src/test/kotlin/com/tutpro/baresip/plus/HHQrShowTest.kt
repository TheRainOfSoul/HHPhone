package com.tutpro.baresip.plus

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HHQrShowTest {
    @Test fun fieldsFromAccount() {
        assertEquals(
            HHAccountQr(name = "Офис", user = "201", pass = "p", domain = "pbx.am:5070", authUser = "a201",
                proxy = "sip:pbx.am;transport=tcp", transport = "tcp", mediaEnc = "zrtp"),
            hhQrFields("sip:201@pbx.am:5070;transport=tcp", "a201", "p", "sip:pbx.am;transport=tcp", "zrtp", "Офис")
        )
    }

    @Test fun authUserSameAsUserIsOmitted() {
        assertEquals("", hhQrFields("sip:201@pbx.am", "201", "p", "", "", "")!!.authUser)
    }

    @Test fun noPasswordNoQr() {
        assertNull(hhQrFields("sip:201@pbx.am", "", "", "", "", ""))
        assertNull(hhQrFields("sip:201@pbx.am", "", NO_AUTH_PASS, "", "", ""))
    }

    @Test fun matrixHasStandardQuietZone() {
        val m = hhQrMatrix(HHQr.encode(HHAccountQr(user = "201", pass = "p", domain = "pbx.am")), 400)
        val y = (0 until m.height).first { row -> (0 until m.width).any { m.get(it, row) } }
        val x0 = (0 until m.width).first { m.get(it, y) }
        val finder = (x0 until m.width).takeWhile { m.get(it, y) }.count()   // верх finder pattern = 7 модулей
        assertTrue("тихая зона ${x0}px < 4 модулей по ${finder / 7.0}px", x0 >= 4 * finder / 7)
    }

    @Test fun matrixDecodesBackToLink() {
        val link = HHQr.encode(HHAccountQr(user = "201", pass = "p", domain = "pbx.am", lock = true, pin = "4821"))
        val m = hhQrMatrix(link, 400)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else -1 }
        val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px)))).text
        assertEquals(link, text)
    }
}
