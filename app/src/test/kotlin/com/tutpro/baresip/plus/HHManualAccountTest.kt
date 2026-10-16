package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HHManualAccountTest {
    @Test fun issabelFieldsMakeValidAccount() {
        val q = HHUiLogic.manualAccount("Reception", "201", "s3cret", "192.168.1.10", "udp", "", "")!!
        assertEquals("sip:201@192.168.1.10", HHQr.aor(q))
        assertEquals("udp", q.transport)
        assertEquals("Reception", q.name)
    }

    @Test fun serverPrefixAndProxyNormalized() {
        val q = HHUiLogic.manualAccount("", "201", "pw", " sip:pbx.local:5060 ", "tls", "auth1", "pbx.local:5060")!!
        assertEquals("pbx.local:5060", q.domain)       // убран ведущий sip: и пробелы
        assertEquals("sip:pbx.local:5060", q.proxy)    // к прокси без схемы подставлен sip:
        assertEquals("auth1", q.authUser)
    }

    @Test fun missingRequiredRejected() {
        assertNull(HHUiLogic.manualAccount("", "", "pw", "pbx.local", "udp", "", ""))    // нет пользователя
        assertNull(HHUiLogic.manualAccount("", "201", "", "pbx.local", "udp", "", ""))   // нет пароля
        assertNull(HHUiLogic.manualAccount("", "201", "pw", "", "udp", "", ""))          // нет сервера
    }

    @Test fun unsafeCharsRejected() {
        assertNull(HHUiLogic.manualAccount("", "201", "pa\"ss", "pbx.local", "udp", "", ""))  // кавычка в пароле ломает строку учётки
    }
}
