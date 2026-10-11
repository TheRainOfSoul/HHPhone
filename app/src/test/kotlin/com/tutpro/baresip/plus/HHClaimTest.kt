package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class HHClaimTest {
    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/pbx-test.pem")) as X509Certificate
    private val certSpki = "Lff/tP4lL2yRjkbrVZ9497NHo0hI4rrnWjDr4TLJahI="

    /** «Система» в тестах: признаёт только то, что ей скажут. */
    private class FakeSystem(val accept: Boolean) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { if (!accept) throw CertificateException("untrusted") }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test fun pbkdf2SharedVectorWithHhpbx() {
        assertEquals("7GQnXhxDTntM3jRYt+FQO5RMtdZhX6cFxBLU+etr25o=", HHPin.hash("4821", "AAAAAAAAAAAAAAAAAAAAAA=="))
    }

    @Test fun spkiOfCertMatchesOpenssl() {
        assertEquals(certSpki, HHClaim.spkiOf(cert))
    }

    @Test fun pinnedSelfSignedPasses() {
        val t = HHPinningTrust(certSpki, FakeSystem(accept = false))
        t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertTrue(t.pinned)
    }

    @Test fun otherKeyUntrustedFails() {
        val t = HHPinningTrust("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", FakeSystem(accept = false))
        try { t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA"); fail("must throw") } catch (_: CertificateException) {}
        assertFalse(t.pinned)
    }

    @Test fun systemTrustedButOtherKeyPasses() {
        val t = HHPinningTrust("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", FakeSystem(accept = true))
        t.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")   // Let's Encrypt продлил сертификат с новым ключом
        assertFalse(t.pinned)                                 // → имя хоста проверит стандартный верификатор
    }

    @Test fun answerWithLock() {
        val a = HHClaim.parseAnswer("""{"v":1,"name":"Иван","user":"201","pass":"Ab12","domain":"pbx.am","authUser":"","proxy":"",
            "transport":"udp","mediaEnc":"","lock":true,"pinHash":"H","pinSalt":"S"}""")!!
        assertEquals(HHAccountQr(name = "Иван", user = "201", pass = "Ab12", domain = "pbx.am"), a.account)
        assertTrue(a.lock); assertEquals("H", a.pinHash); assertEquals("S", a.pinSalt)
    }

    @Test fun answerWithoutLock() {
        val a = HHClaim.parseAnswer("""{"v":1,"name":"","user":"201","pass":"p","domain":"192.168.109.132","lock":false}""")!!
        assertFalse(a.lock)
        assertEquals("192.168.109.132", a.account.domain)
    }

    @Test fun badAnswers() {
        assertNull(HHClaim.parseAnswer("not json"))
        assertNull(HHClaim.parseAnswer("""{"v":1,"user":"201","pass":"a\"b","domain":"pbx.am","lock":false}"""))   // опасный символ
        assertNull(HHClaim.parseAnswer("""{"v":1,"user":"201","pass":"p","domain":"pbx.am","lock":true}"""))      // lock без хэша
        assertNull(HHClaim.parseAnswer("""{"error":"invalid"}"""))
    }

    @Test fun serverLockApplied() {
        val acc = HHAccountQr(user = "201", pass = "p", domain = "d")
        val locked = hhApplyServerLock(HHClaimAnswer(acc, true, HHPin.hash("4821", "AAAAAAAAAAAAAAAAAAAAAA=="), "AAAAAAAAAAAAAAAAAAAAAA=="))
        assertTrue(locked.locked)
        assertEquals(HHPinCheck.Ok, HHPin.check(locked, "4821", 0).first)
        assertEquals(HHLockState(), hhApplyServerLock(HHClaimAnswer(acc, false, "", "")))
    }
}
