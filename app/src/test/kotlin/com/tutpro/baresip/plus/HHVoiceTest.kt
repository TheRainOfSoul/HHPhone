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

    private fun ns(x: ShortArray): ShortArray {
        val i = File.createTempFile("hhvoice", ".raw").apply { deleteOnExit() }
        val o = File.createTempFile("hhvoice", ".ns").apply { deleteOnExit() }
        i.writeBytes(ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { x.forEach { putShort(it) } }.array())
        HHNoise.suppress(i, o, rate)
        val b = ByteBuffer.wrap(o.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(b.remaining() / 2) { b.short }
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(512) { Math.sin(it * 0.3) * 100 + it % 7 }; val im = DoubleArray(512)
        val orig = re.clone()
        HHNoise.fft(re, im, false); HHNoise.fft(re, im, true)
        assertTrue(re.indices.all { Math.abs(re[it] - orig[it]) < 1e-6 })
    }

    @Test fun noiseReducedSpeechKeptInPlace() {
        // 2 с шума (σ=300), затем 2 с тона (амплитуда 6000) + тот же шум
        val r = java.util.Random(7)
        val x = ShortArray(rate * 4) { i -> (r.nextGaussian() * 300 + (if (i >= rate * 2) 6000 * Math.sin(2 * Math.PI * 500 * i / rate) else 0.0)).toInt().toShort() }
        val y = ns(x)
        assertTrue("same length", y.size == x.size)
        val noiseIn = rms(x, rate / 2, rate * 3 / 2); val noiseOut = rms(y, rate / 2, rate * 3 / 2)
        assertTrue("noise −10 dB: $noiseIn → $noiseOut", noiseOut < noiseIn / 3.16)
        val toneIn = rms(x, rate * 5 / 2, rate * 7 / 2); val toneOut = rms(y, rate * 5 / 2, rate * 7 / 2)
        assertTrue("tone within 3 dB: $toneIn → $toneOut", toneOut > toneIn / 1.41 && toneOut < toneIn * 1.41)
        // не сдвинуто во времени: корреляция с исходным тоном на участке речи высокая
        var c = 0.0; var a = 0.0; var b = 0.0
        for (i in rate * 5 / 2 until rate * 7 / 2) { c += x[i] * y[i].toDouble(); a += x[i] * x[i].toDouble(); b += y[i] * y[i].toDouble() }
        assertTrue("aligned: ${c / Math.sqrt(a * b)}", c / Math.sqrt(a * b) > 0.9)
    }

    /** Прогон настоящей записи: HH_VOICE_IN=файл.raw (s16le моно 16 кГц) → рядом .out.raw. */
    @Test fun realRecording() {
        val path = System.getenv("HH_VOICE_IN") ?: return
        HHVoice.processFile(File(path), File("$path.out.raw"), rate)
    }
}
