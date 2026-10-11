package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class HHQrClaimTest {
    private val token = "dQljapB8rs-SxVF8J3LNaspyRb_sq4hYvVC3LpKOR7k"
    private val spki = "tBmgFl4fdcJjbAg68yA+mZ88wSdaur2Y2eZGO+n3YIU="
    private fun claim(json: String) =
        "hhphone://claim?c=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun validClaim() {
        assertEquals(
            HHQrResult.Claim("https://192.168.109.132", token, spki),
            HHQr.parse(claim("""{"v":1,"server":"https://192.168.109.132","token":"$token","spki":"$spki"}"""))
        )
        assertEquals(
            HHQrResult.Claim("https://pbx.orbeli.am:8443", token, spki),
            HHQr.parse(claim("""{"v":1,"server":"https://pbx.orbeli.am:8443","token":"$token","spki":"$spki"}"""))
        )
    }

    @Test fun newerClaim() {
        assertEquals(HHQrResult.Newer, HHQr.parse(claim("""{"v":2,"server":"https://a.am","token":"$token","spki":"$spki"}""")))
    }

    @Test fun badClaims() {
        val bad = listOf(
            """{"v":1,"server":"http://a.am","token":"$token","spki":"$spki"}""",          // не https
            """{"v":1,"server":"https://a.am/x","token":"$token","spki":"$spki"}""",       // путь
            """{"v":1,"server":"https://a am","token":"$token","spki":"$spki"}""",
            """{"v":1,"server":"https://a.am","token":"short","spki":"$spki"}""",
            """{"v":1,"server":"https://a.am","token":"$token","spki":"nope"}""",
            """{"v":1,"server":"https://a.am","token":"$token"}""",
        )
        bad.forEach { assertEquals(it, HHQrResult.Invalid, HHQr.parse(claim(it))) }
        assertEquals(HHQrResult.Invalid, HHQr.parse("hhphone://claim?c=%%%"))
    }

    @Test fun addLinksStillWork() {
        val add = HHQr.encode(HHAccountQr(user = "1", pass = "p", domain = "d.am"))
        assertEquals(true, HHQr.parse(add) is HHQrResult.Ok)
    }
}
