package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HHQrTest {
    private val full = HHAccountQr(
        name = "Отель Орбели", user = "201", pass = "s3cr!t", domain = "pbx.orbeli.am",
        authUser = "201a", proxy = "sip:pbx.orbeli.am:5061;transport=tls", transport = "tls",
        mediaEnc = "srtp", lock = true, pin = "4821"
    )

    private fun link(json: String) =
        "hhphone://add?c=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun roundTrip() {
        val r = HHQr.parse(HHQr.encode(full))
        assertEquals(HHQrResult.Ok(full), r)
    }

    @Test fun encodedLinkHasNoPadding() {
        val l = HHQr.encode(full)
        assertTrue(l.startsWith("hhphone://add?c="))
        assertTrue(!l.endsWith("="))
    }

    @Test fun encodedJsonAlwaysHasVersion() {
        val payload = HHQr.encode(HHAccountQr(user = "1", pass = "p", domain = "d.am")).removePrefix("hhphone://add?c=")
        assertTrue(String(Base64.getUrlDecoder().decode(payload)).contains("\"v\":1"))
    }

    @Test fun minimalWithDefaults() {
        val r = HHQr.parse(link("""{"v":1,"user":"100","pass":"p","domain":"192.168.1.10"}"""))
        assertEquals(HHQrResult.Ok(HHAccountQr(user = "100", pass = "p", domain = "192.168.1.10")), r)
    }

    @Test fun unknownFieldsIgnored() {
        val r = HHQr.parse(link("""{"v":1,"user":"100","pass":"p","domain":"d.am","color":"red"}"""))
        assertTrue(r is HHQrResult.Ok)
    }

    @Test fun newerVersion() {
        assertEquals(HHQrResult.Newer, HHQr.parse(link("""{"v":2,"user":"100","pass":"p","domain":"d.am"}""")))
    }

    @Test fun rejectsForeignQr() {
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("WIFI:S:hotel;T:WPA;P:12345678;;"))
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("https://example.com"))
        assertEquals(HHQrResult.NotHHPhone, HHQr.parse("hhphone://add"))
    }

    @Test fun rejectsBrokenPayload() {
        assertEquals(HHQrResult.Invalid, HHQr.parse("hhphone://add?c=%%%"))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"100""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"pass":"p","domain":"d.am"}""")))
    }

    @Test fun rejectsLockWithoutPin() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true,"pin":"12a4"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","lock":true,"pin":"123"}""")))
    }

    @Test fun rejectsBadEnums() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","transport":"sctp"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","mediaEnc":"aes"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d.am","proxy":"pbx.am"}""")))
    }

    @Test fun rejectsUnsafeChars() {
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"a\"b","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"a;b","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1@x","pass":"p","domain":"d.am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"user":"1","pass":"p","domain":"d am"}""")))
        assertEquals(HHQrResult.Invalid, HHQr.parse(link("""{"v":1,"name":"a;b","user":"1","pass":"p","domain":"d.am"}""")))
    }

    @Test fun aorWithTransport() {
        assertEquals("sip:201@pbx.orbeli.am;transport=tls", HHQr.aor(full))
        assertEquals("sip:100@d.am", HHQr.aor(HHAccountQr(user = "100", pass = "p", domain = "d.am")))
    }

    @Test fun accountLineFull() {
        assertEquals(
            "<sip:201@pbx.orbeli.am;transport=tls>;auth_user=\"201a\";auth_pass=\"s3cr!t\"" +
                ";outbound=\"sip:pbx.orbeli.am:5061;transport=tls\";sipnat=outbound;mediaenc=srtp" +
                ";stunserver=\"stun:stun.l.google.com:19302\";regq=0.5;pubint=0;regint=900;check_origin=no;mwi=no" +
                ";extra=\"nickname=Отель Орбели;last=empty\"",
            HHQr.accountLine(full)
        )
    }

    @Test fun accountLineMinimal() {
        assertEquals(
            "<sip:100@d.am>;auth_pass=\"p\";stunserver=\"stun:stun.l.google.com:19302\"" +
                ";regq=0.5;pubint=0;regint=900;check_origin=no;mwi=no;extra=\"last=empty\"",
            HHQr.accountLine(HHAccountQr(user = "100", pass = "p", domain = "d.am"))
        )
    }
}
