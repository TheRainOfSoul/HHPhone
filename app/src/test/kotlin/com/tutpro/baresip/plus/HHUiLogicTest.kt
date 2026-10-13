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

    @Test fun ago() {
        val now = 1_000_000_000L
        assertEquals(0 to 0L, HHUiLogic.ago(now, now - 59_000))
        assertEquals(1 to 1L, HHUiLogic.ago(now, now - 60_000))
        assertEquals(2 to 23L, HHUiLogic.ago(now, now - 23 * 3_600_000L - 59_000))
        assertEquals(3, HHUiLogic.ago(now, now - 30 * 3_600_000L).first)
        assertEquals(4, HHUiLogic.ago(now, now - 50 * 3_600_000L).first)
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
}
