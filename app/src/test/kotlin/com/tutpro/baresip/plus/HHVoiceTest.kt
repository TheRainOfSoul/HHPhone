package com.tutpro.baresip.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HHVoiceTest {
    private val rate = 16000
    private fun tone(sec: Double, amp: Double, hz: Double = 400.0) =
        DoubleArray((sec * rate).toInt()) { amp * Math.sin(2 * Math.PI * hz * it / rate) }
    private fun rms(x: ShortArray, a: Int, b: Int) = Math.sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    private fun noise(sec: Double, amp: Double) = java.util.Random(1).let { r -> DoubleArray((sec * rate).toInt()) { amp * r.nextGaussian() } }
    private fun mixShort(vararg parts: DoubleArray) = parts.flatMap { it.toList() }.map { it.toInt().toShort() }.toShortArray()
    private fun run(x: ShortArray): ShortArray {
        val i = File.createTempFile("hhvoice", ".raw").apply { deleteOnExit() }
        val o = File.createTempFile("hhvoice", ".out").apply { deleteOnExit() }
        i.writeBytes(ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { x.forEach { putShort(it) } }.array())
        HHVoice.processFile(i, o, rate)
        val b = ByteBuffer.wrap(o.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(b.remaining() / 2) { b.short }
    }

    @Test fun quietFarVoiceRaisedTowardLoudNearVoice() {
        // фон −70 dB, свой голос −30 dB, собеседник −50 dB
        val bg = noise(1.0, 10.0)
        val near = tone(2.0, 1000.0).zip(noise(2.0, 10.0).toList()) { a, b -> a + b }.toDoubleArray()
        val far = tone(2.0, 100.0, 600.0).zip(noise(2.0, 10.0).toList()) { a, b -> a + b }.toDoubleArray()
        val x = mixShort(bg, near, bg, far, bg)
        val y = run(x)
        val nearR = rms(y, rate * 2, rate * 3); val farR = rms(y, rate * 5, rate * 6)
        assertTrue("far raised: ${rms(x, rate * 5, rate * 6)} → $farR", farR > rms(x, rate * 5, rate * 6) * 8)
        assertTrue("near/far within 6 dB: $nearR / $farR", nearR / farR < 2.0)
    }

    @Test fun pausesStayQuieterThanSpeech() {
        val x = mixShort(noise(1.0, 10.0), tone(2.0, 100.0), noise(1.0, 10.0))
        val y = run(x)
        assertTrue(rms(y, rate * 2, rate * 3) > rms(y, (rate * 3.5).toInt(), rate * 4) * 10)
    }

    @Test fun loudInputNotClipped() {
        val y = run(mixShort(tone(1.0, 32000.0)))
        assertTrue(y.all { it in -32767..32767 })
        assertTrue(y.maxOf { it.toInt() } < 32767)
    }

    @Test fun humRemoved() {
        val x = mixShort(tone(1.0, 5000.0, 50.0))
        val y = run(x)
        assertTrue(rms(y, rate / 2, rate) < rms(x, rate / 2, rate) * 0.2 * 40)   // 50 Гц: −20+ дБ до усиления
    }

    @Test fun sameLengthOut() {
        val x = mixShort(noise(1.3, 50.0))
        assertTrue(run(x).size == x.size)
    }

    /** Прогон настоящей записи: HH_VOICE_IN=файл.raw (s16le моно 16 кГц) → рядом .out.raw. */
    @Test fun realRecording() {
        val path = System.getenv("HH_VOICE_IN") ?: return
        HHVoice.processFile(File(path), File("$path.out.raw"), rate)
    }
}
