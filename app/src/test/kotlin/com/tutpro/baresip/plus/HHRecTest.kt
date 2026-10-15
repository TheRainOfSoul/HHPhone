package com.tutpro.baresip.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HHRecTest {
    private fun wav(samples: ShortArray, rate: Int = 8000, channels: Int = 1, extraChunk: Boolean = false): File {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val extra = if (extraChunk) ByteBuffer.allocate(8 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray()).array() else ByteArray(0)
        val b = ByteBuffer.allocate(12 + 24 + extra.size + 8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(4 + 24 + extra.size + 8 + data.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
        b.put(extra)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        return File.createTempFile("hhrec", ".wav").apply { writeBytes(b.array()); deleteOnExit() }
    }

    private fun pcm(f: File): List<Int> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return List(f.length().toInt() / 2) { b.short.toInt() }
    }

    private val out = File.createTempFile("hhrec", ".pcm").apply { deleteOnExit() }

    @Test fun mixSumsBothSides() {
        val rate = HHWav.mix(wav(shortArrayOf(100, 200, 300)), wav(shortArrayOf(1, 2, 3)), 0, out)
        assertEquals(8000, rate)
        assertEquals(listOf(101, 202, 303), pcm(out))
    }

    @Test fun mixClipsAndPadsShorterSide() {
        HHWav.mix(wav(shortArrayOf(30000, 5)), wav(shortArrayOf(10000)), 0, out)
        assertEquals(listOf(32767, 5), pcm(out))
    }

    @Test fun mixSkipsFromPress() {   // 8000 Гц: 1 мс = 8 отсчётов
        val a = ShortArray(20) { it.toShort() }
        HHWav.mix(wav(a), null, 1, out)
        assertEquals((8 until 20).toList(), pcm(out))
    }

    @Test fun mixTakesFirstChannelAndSkipsOtherChunks() {
        HHWav.mix(wav(shortArrayOf(7, 99, 8, 99), channels = 2, extraChunk = true), null, 0, out)
        assertEquals(listOf(7, 8), pcm(out))
    }

    @Test fun notWavIsIgnored() {
        val f = File.createTempFile("hhrec", ".wav").apply { writeText("hello world, not a wav"); deleteOnExit() }
        assertNull(HHWav.info(f))
        assertEquals(0, HHWav.mix(f, null, 0, out))
    }

    @Test fun fileNameRoundTrip() {
        val name = HHRec.fileName(1_700_000_000_000, 125, false, "sip:alice_1@other.com;user=phone")
        val r = HHRec.parse(name, "/x/$name")!!
        assertEquals(1_700_000_000_000, r.start); assertEquals(125, r.seconds); assertEquals(false, r.sim)
        assertEquals("sip:alice_1@other.com;user=phone", r.peer)
        assertNull(HHRec.parse("rec-old.wav", "/x/rec-old.wav"))
    }

    @Test fun recordingsMatchCallByPeerAndTime() {
        val r = HHRecFile("a", 1_000_000, 30, true, "tel:+37477111111")
        val call = HHLogEntry("tel:077111111", "sip:mobile@pstn", "in", 995_000, 1_040_000, false)
        assertEquals(1, HHRec.of(listOf(r), call).size)
        assertEquals(0, HHRec.of(listOf(r), call.copy(peer = "tel:091000000")).size)
        assertEquals(0, HHRec.of(listOf(r), call.copy(start = 2_000_000, stop = 2_100_000)).size)
    }

    @Test fun expiredOnlyOlderThanKeepDays() {
        val day = 86_400_000L; val now = 1_800_000_000_000
        val old = HHRecFile("o", now - 31 * day, 10, true, "tel:1")
        val fresh = HHRecFile("f", now - 29 * day, 10, true, "tel:1")
        assertEquals(listOf(old), HHRec.expired(listOf(old, fresh), now, 30))
        assertEquals(emptyList<HHRecFile>(), HHRec.expired(listOf(old, fresh), now, 0))   // 0 — не удалять никогда
    }

    @Test fun readableNameWithoutSpaces() {
        val start = java.util.GregorianCalendar(2026, 9, 15, 10, 57, 26).timeInMillis
        assertEquals("Ստեփան_DGS_2026-10-15_10-57.m4a", HHRec.readableName("Ստեփան  DGS", start, emptySet()))
        assertEquals("098823987_2026-10-15_10-57.m4a", HHRec.readableName("098823987", start, emptySet()))
        // недопустимые в именах файлов знаки убираются, повтор в ту же минуту — с номером
        assertEquals("alice@other.com_2026-10-15_10-57_2.m4a",
            HHRec.readableName("sip:alice@other.com", start, setOf("alice@other.com_2026-10-15_10-57.m4a")))
        assertEquals("unknown_2026-10-15_10-57.m4a", HHRec.readableName(" :/ ", start, emptySet()))
    }

    @Test fun recordingAfterLongRingingStillMatchesItsCall() {
        // журнал SIM: время — начало гудков, конец = начало + длительность разговора; звонило минуту
        val call = HHLogEntry("tel:077111111", "sip:mobile@pstn", "in", 1_000_000, 1_011_000, false)
        val r = HHRecFile("a", 1_060_000, 11, true, "tel:+37477111111")
        assertEquals(1, HHRec.of(listOf(r), call).size)
        // следующий звонок того же собеседника — запись его, не предыдущего
        val next = HHLogEntry("tel:077111111", "sip:mobile@pstn", "in", 1_050_000, 1_055_000, false)
        assertEquals(0, HHRec.of(listOf(r), call, listOf(call, next)).size)
        assertEquals(1, HHRec.of(listOf(r), next, listOf(call, next)).size)
    }
}
