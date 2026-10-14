package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HHUiLogicTest {
    private fun e(peer: String, stop: Long, dir: String = "in", start: Long? = stop - 10) =
        HHLogEntry(peer, "sip:101@pbx", dir, start, stop, false)

    @Test fun groupsConsecutiveSamePeer() {
        val g = HHUiLogic.group(listOf(e("sip:200@pbx", 30), e("sip:200@pbx;transport=tcp", 20), e("sip:200@pbx", 10)))
        assertEquals(1, g.size)
        assertEquals(3, g[0].count)
        assertEquals(30L, g[0].last.stop)
    }

    @Test fun groupBrokenByOtherPeer() {
        val g = HHUiLogic.group(listOf(e("sip:200@pbx", 30), e("tel:093015454", 20), e("sip:200@pbx", 10)))
        assertEquals(listOf(1, 1, 1), g.map { it.count })
    }

    @Test fun groupSortsNewestFirst() {
        val g = HHUiLogic.group(listOf(e("sip:200@pbx", 10), e("tel:1", 30)))
        assertEquals("tel:1", g[0].peer)
    }

    @Test fun peerKey() {
        assertEquals("200", HHUiLogic.peerKey("sip:200@pbx.local;transport=tcp"))
        assertEquals("+37493015454", HHUiLogic.peerKey("tel:+374 93 015454"))
        assertEquals("093015454", HHUiLogic.peerKey("093-015-454"))
        assertEquals("*9196", HHUiLogic.peerKey("sip:*9196@pbx"))
        assertEquals("*21#", HHUiLogic.peerKey("tel:*21#"))
    }

    @Test fun avatarLetter() {
        assertEquals("Ա", HHUiLogic.avatarLetter("Արմեն DGS"))
        assertEquals("И", HHUiLogic.avatarLetter("иван"))
        assertEquals("J", HHUiLogic.avatarLetter("  john"))
        assertEquals("0", HHUiLogic.avatarLetter("093015454"))
        assertEquals("#", HHUiLogic.avatarLetter(""))
        assertEquals("#", HHUiLogic.avatarLetter("+"))
    }

    @Test fun avatarColorStable() {
        val c = HHUiLogic.avatarColor("Արմեն")
        assertEquals(c, HHUiLogic.avatarColor("արմեն"))
        listOf("", "a", "Արմեն", "093015454", "Иван").forEach { assertTrue(HHUiLogic.avatarColor(it) in 0..7) }
    }

    @Test fun agoByCalendarDay() {
        val day = 86_400_000L
        val now = 20 * day + 10 * 3_600_000L          // 10:00 (UTC)
        assertEquals(0 to 0L, HHUiLogic.ago(now, now - 59_000, 0))
        assertEquals(1 to 30L, HHUiLogic.ago(now, now - 30 * 60_000L, 0))
        assertEquals(2 to 9L, HHUiLogic.ago(now, 20 * day + 3_600_000L, 0))       // 01:00 того же дня
        assertEquals(3 to 0L, HHUiLogic.ago(now, 19 * day + 23 * 3_600_000L, 0))   // вчера 23:00
        assertEquals(4, HHUiLogic.ago(now, 18 * day + 23 * 3_600_000L, 0).first)  // позавчера
        assertEquals(3 to 0L, HHUiLogic.ago(now, 20 * day + 3_600_000L, -2 * 3_600_000L))  // UTC−2: это ещё вчера 23:00
    }

    @Test fun linesSimFirstNoDuplicates() {
        val l = HHUiLogic.lines(listOf(
            HHLine("sip:101@pbx", false, true), HHLine("sip:mobile@pstn", true, true), HHLine("sip:101@pbx", false, true)))
        assertEquals(listOf("sip:mobile@pstn", "sip:101@pbx"), l.map { it.aor })
    }

    @Test fun linesEmpty() = assertEquals(emptyList<HHLine>(), HHUiLogic.lines(emptyList()))

    @Test fun screenFor() {
        assertEquals("home", HHUiLogic.screenFor(emptyList(), false))
        assertEquals("home", HHUiLogic.screenFor(listOf("closed"), false))
        assertEquals("incoming", HHUiLogic.screenFor(listOf("incoming"), false))
        assertEquals("call", HHUiLogic.screenFor(listOf("connected", "incoming"), false))
        assertEquals("call", HHUiLogic.screenFor(listOf("answered", "incoming"), false))
        assertEquals("call", HHUiLogic.screenFor(listOf("outgoing"), false))
        assertEquals("video", HHUiLogic.screenFor(listOf("connected"), true))
    }

    @Test fun parseDirectory() {
        assertEquals(listOf("200" to "Ресепшн", "201" to "A \"B\""),
            HHUiLogic.parseDirectory("""{"ok":true,"items":[{"n":"200","name":"Ресепшн"},{"n":"201","name":"A \"B\""}]}"""))
        assertEquals(emptyList<Pair<String, String>>(), HHUiLogic.parseDirectory("<html>"))
    }

    private val sim = HHLine("sip:mobile@pstn", true, true)
    private val st1 = HHLine("sip:101@pbx.hotel", false, true)
    private val st2 = HHLine("sip:7@other.org", false, true)

    @Test fun dialSipPeerViaMatchingStationKeepsUri() {
        assertEquals("sip:7@other.org" to "sip:alice@other.org",
            HHUiLogic.dialTarget("sip:alice@other.org;transport=tcp", listOf(sim, st1, st2), null))
        assertEquals("sip:101@pbx.hotel" to "sip:200@pbx.hotel", HHUiLogic.dialTarget("sip:200@pbx.hotel", listOf(sim, st1), null))
    }

    @Test fun dialSipPeerNeverViaSim() = assertEquals(null, HHUiLogic.dialTarget("sip:200@pbx.hotel", listOf(sim), null))

    @Test fun dialNumberPrefersGivenLineElseSim() {
        assertEquals("sip:101@pbx.hotel" to "200", HHUiLogic.dialTarget("tel:200", listOf(sim, st1), "sip:101@pbx.hotel"))
        assertEquals("sip:mobile@pstn" to "+37493015454", HHUiLogic.dialTarget("tel:+374 93 015454", listOf(sim, st1), null))
        assertEquals("sip:101@pbx.hotel" to "093015454", HHUiLogic.dialTarget("093015454", listOf(st1), null))
    }

    @Test fun dialAnonymousIsNull() {
        assertEquals(null, HHUiLogic.dialTarget("sip:anonymous@anonymous.invalid", listOf(sim, st1), null))
        assertEquals(null, HHUiLogic.dialTarget("", listOf(sim, st1), null))
        assertEquals(null, HHUiLogic.dialTarget("tel:200", emptyList(), null))
    }

    @Test fun colleagueOnlyViaItsStation() {
        assertEquals("sip:101@pbx.hotel" to "200", HHUiLogic.dialColleague("200", "sip:101@pbx.hotel", listOf(sim, st1)))
        assertEquals(null, HHUiLogic.dialColleague("200", "sip:101@pbx.hotel", listOf(sim)))
    }

    @Test fun callButtons() {
        assertEquals(listOf(listOf(sim)), HHUiLogic.callButtons(listOf(sim)))
        assertEquals(listOf(listOf(sim), listOf(st1)), HHUiLogic.callButtons(listOf(sim, st1)))
        assertEquals(listOf(listOf(sim), listOf(st1, st2)), HHUiLogic.callButtons(listOf(sim, st1, st2)))
        assertEquals(listOf(listOf(st1), listOf(st2, HHLine("sip:9@x", false, false))),
            HHUiLogic.callButtons(listOf(st1, st2, HHLine("sip:9@x", false, false))))
        assertEquals(emptyList<List<HHLine>>(), HHUiLogic.callButtons(emptyList()))
    }

    @Test fun missedOnlyWhenNotDeclined() {
        assertTrue(HHUiLogic.missed(HHLogEntry("tel:1", "a", "in", null, 1, false)))
        assertEquals(false, HHUiLogic.missed(HHLogEntry("tel:1", "a", "in", null, 1, true)))
        assertEquals(false, HHUiLogic.missed(HHLogEntry("tel:1", "a", "out", null, 1, false)))
    }

    @Test fun suggestMatchesNumberDigits() {
        assertTrue(HHUiLogic.suggestMatch("9112", "Ани", "+374 91 123456"))
        assertEquals(false, HHUiLogic.suggestMatch("555", "Ани", "+374 91 123456"))
    }

    @Test fun suggestMatchesNationalZeroPrefix() {
        assertTrue(HHUiLogic.suggestMatch("0911", "Ани", "+37491123456"))
    }

    @Test fun suggestMatchesNameLettersByKeypad() {
        assertTrue(HHUiLogic.suggestMatch("266", "Anna", "100"))            // A2 N6 N6
        assertEquals(false, HHUiLogic.suggestMatch("264", "Anna", "100"))
        assertTrue(HHUiLogic.suggestMatch("73", "Hayk Petrosyan", "100"))   // начало второго слова: P7 E3
        assertTrue(HHUiLogic.suggestMatch("254", "Ани Иванова", "100"))     // кириллица: А2 Н5 И4
        assertEquals(false, HHUiLogic.suggestMatch("2", "Anna", "100"))     // одна цифра — не ищем по имени
    }

    @Test fun callLogSimIncoming() {
        val e = HHUiLogic.callLogEntry("+37491123456", HHUiLogic.CL_IN, 1000, 30, "8937401", true)
        assertEquals(HHLogEntry("tel:+37491123456", "sip:mobile@pstn", "in", 1000, 31000, false), e)
    }
    @Test fun callLogStationOutgoing() {
        val e = HHUiLogic.callLogEntry("100", HHUiLogic.CL_OUT, 1000, 5, "sip:101@pbx.local", true)
        assertEquals(HHLogEntry("sip:100@pbx.local", "sip:101@pbx.local", "out", 1000, 6000, false), e)
    }
    @Test fun callLogMissedRejectedBlocked() {
        assertEquals(null, HHUiLogic.callLogEntry("1", HHUiLogic.CL_MISSED, 1000, 0, null, true).start)
        assertEquals(false, HHUiLogic.callLogEntry("1", HHUiLogic.CL_MISSED, 1000, 0, null, true).rejected)
        assertTrue(HHUiLogic.callLogEntry("1", HHUiLogic.CL_REJECTED, 1000, 0, null, true).rejected)
        assertTrue(HHUiLogic.callLogEntry("1", HHUiLogic.CL_BLOCKED, 1000, 0, null, true).rejected)
        assertEquals(null, HHUiLogic.callLogEntry("1", HHUiLogic.CL_VOICEMAIL, 1000, 0, null, true).start)
    }
    @Test fun callLogHiddenNumber() {
        assertEquals("tel:anonymous", HHUiLogic.callLogEntry("", HHUiLogic.CL_IN, 1, 0, null, true).peer)
        assertEquals("tel:anonymous", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, null, false).peer)
    }
    @Test fun callLogTypeForStationCalls() {
        assertEquals(HHUiLogic.CL_OUT, HHUiLogic.callLogType("out", null, false))
        assertEquals(HHUiLogic.CL_IN, HHUiLogic.callLogType("in", 5, false))
        assertEquals(HHUiLogic.CL_REJECTED, HHUiLogic.callLogType("in", null, true))
        assertEquals(HHUiLogic.CL_MISSED, HHUiLogic.callLogType("in", null, false))
    }
    @Test fun samePhoneIgnoresCountryPrefix() {
        assertTrue(HHUiLogic.samePhone("+37491123456", "091123456"))
        assertTrue(HHUiLogic.samePhone("tel:+374 91 12-34-56", "+37491123456"))
        assertEquals(false, HHUiLogic.samePhone("+37491123456", "+37491123457"))
        assertTrue(HHUiLogic.samePhone("100", "100"))
        assertEquals(false, HHUiLogic.samePhone("100", "2100"))
    }
    @Test fun sameTimeWithinFiveSeconds() {
        assertTrue(HHUiLogic.sameTime(10_000, 14_999))
        assertEquals(false, HHUiLogic.sameTime(10_000, 15_001))
    }

    @Test fun samePhoneAlphanumericSender() {
        assertTrue(HHUiLogic.samePhone("tel:Ucom", "Ucom"))
        assertTrue(HHUiLogic.samePhone("tel:UCOM", "ucom"))
        assertEquals(false, HHUiLogic.samePhone("tel:Ucom", "Viva"))
        assertEquals(false, HHUiLogic.samePhone("tel:Ucom", "100"))
        assertEquals(false, HHUiLogic.samePhone("", ""))
    }
    @Test fun sameLoggedCallToleratesRingTime() {
        // система: DATE = начало звонка (с гудками), DURATION = разговор; у HHPhone start = ответ, stop = конец
        assertTrue(HHUiLogic.sameLoggedCall(100_000, 160_000, 130_000, 190_000))   // 30 с гудков
        assertTrue(HHUiLogic.sameLoggedCall(100_000, 100_000, null, 140_000))      // пропущенный, 40 с звонил
        assertEquals(false, HHUiLogic.sameLoggedCall(100_000, 160_000, 400_000, 460_000))
    }

    @Test fun groupMergesSamePhoneAndKeepsEntries() {
        val g = HHUiLogic.group(listOf(e("tel:+37491123456", 30), e("tel:091123456", 20), e("tel:100", 10)))
        assertEquals(2, g.size)
        assertEquals(2, g[0].count)
        assertEquals(listOf(30L, 20L), g[0].entries.map { it.stop })
    }
    @Test fun callLogEntryOtherAppIsNotSim() {
        val e = HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "acc1", true, "com.whatsapp/.Voip", 7)
        assertEquals("", e.aor)
        assertEquals(7L, e.id)
        assertEquals("sip:mobile@pstn", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "1",
            true, "com.android.phone/com.android.services.telephony.TelephonyConnectionService").aor)
        assertEquals("sip:mobile@pstn", HHUiLogic.callLogEntry("555", HHUiLogic.CL_IN, 1, 0, "1", true, null).aor)
    }
    @Test fun callLogEntryForeignDomain() {
        assertEquals("sip:alice@other.com", HHUiLogic.callLogEntry("alice@other.com", HHUiLogic.CL_OUT, 1, 0, "sip:101@pbx", true).peer)
    }
    @Test fun callLogNumberKeepsForeignDomain() {
        assertEquals("100", HHUiLogic.callLogNumber("sip:100@pbx", "sip:101@pbx"))
        assertEquals("alice@other.com", HHUiLogic.callLogNumber("sip:alice@other.com;transport=tcp", "sip:101@pbx"))
        assertEquals("+37491", HHUiLogic.callLogNumber("tel:+37491", "sip:101@pbx"))
    }
    @Test fun numberOpsDiff() {
        val old = listOf(HHNum(1, "100", 2), HHNum(2, "200", 1), HHNum(3, "300", 3))
        val new = listOf(HHNum(1, "100", 2), HHNum(2, "201", 1), HHNum(3, " ", 3), HHNum(0, "400", 2), HHNum(0, "", 2))
        assertEquals(listOf(HHNumOp.Update(2, HHNum(2, "201", 1)), HHNumOp.Delete(3), HHNumOp.Insert(HHNum(0, "400", 2))),
            HHUiLogic.numberOps(old, new))
        assertEquals(listOf(HHNumOp.Delete(1)), HHUiLogic.numberOps(listOf(HHNum(1, "1", 2)), emptyList()))
    }

    @Test fun mergeSameNumbersAcrossAccounts() {
        // Google +374 91… и SIM 091… — один номер; представитель — из записываемого аккаунта
        val m = HHUiLogic.mergeSame(listOf(HHNum(5, "091123456", 2), HHNum(7, "+37491123456", 2), HHNum(9, "100", 1))) { it.id == 7L }
        assertEquals(2, m.size)
        assertEquals(7L, m[0].first.id)
        assertEquals(listOf(5L, 7L), m[0].second.sorted())
        assertEquals(listOf(9L), m[1].second)
    }

    @Test fun numberOpsNegativeIdIsNew() {
        assertEquals(listOf(HHNumOp.Insert(HHNum(-1, "5", 2))), HHUiLogic.numberOps(emptyList(), listOf(HHNum(-1, "5", 2))))
    }
    @Test fun mmsAddressesSplitsSenderAndRecipients() {
        val (from, to) = HHUiLogic.mmsAddresses(listOf("+37491111111" to 137, "+37493333333" to 151, "+37477000000" to 151), "077000000")
        assertEquals("+37491111111", from)
        assertEquals(listOf("+37493333333"), to)   // свой номер (+374 77… = 077…) убран
    }
    @Test fun deliveryMarks() {
        assertEquals("✓✓", HHUiLogic.deliveryMark(2, 0))      // SENT + STATUS_COMPLETE
        assertEquals("✓", HHUiLogic.deliveryMark(2, -1))      // SENT, статуса нет
        assertEquals("!", HHUiLogic.deliveryMark(5, -1))      // FAILED
        assertEquals("!", HHUiLogic.deliveryMark(2, 64))      // STATUS_FAILED
        assertEquals("", HHUiLogic.deliveryMark(1, -1))       // входящее
    }
    @Test fun toE164() {
        assertEquals("+37491123456", HHUiLogic.toE164("091 12-34-56", "374"))
        assertEquals("+37491123456", HHUiLogic.toE164("+37491123456", null))
        assertEquals("+37491123456", HHUiLogic.toE164("0037491123456", "374"))
        assertEquals(null, HHUiLogic.toE164("091123456", null))
        assertEquals(null, HHUiLogic.toE164("100", "374"))   // короткий внутренний номер
    }
    @Test fun messengerLinks() {
        val m = HHUiLogic.messengerLinks("+37491123456")
        assertEquals("https://wa.me/37491123456", m["whatsapp"])
        assertEquals("viber://chat?number=%2B37491123456", m["viber"])
        assertEquals("tg://resolve?phone=37491123456", m["telegram"])
    }
}
