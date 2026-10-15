package com.tutpro.baresip.plus

import java.io.File

/**
 * Обработка записи после звонка (16-бит моно PCM → то же): гул ниже 120 Гц → шумоподавление (HHNoise) →
 * выравнивание громкости речи (тихий собеседник из динамика поднимается до своего голоса), фон в паузах тише →
 * мягкий ограничитель. Всё по файлам в несколько проходов: в памяти только уровни кадров и спектр шума, не сам звук.
 */
object HHVoice {
    private const val TARGET = 3277.0      // −20 dBFS (в единицах 16-битного отсчёта) — уровень речи
    private const val MAX_GAIN = 40.0      // +32 дБ: SIM-запись микрофоном бывает на −55 dBFS
    private const val GATE = 0.25          // −12 дБ фону в паузах

    fun processFile(inp: File, out: File, rate: Int, denoise: Boolean = true) {
        val a = File(out.path + ".hp"); val b = File(out.path + ".ns")
        try {
            val hp = HighPass(rate)
            writeSamples(a) { put -> samples(inp) { put(hp.next(it)) } }
            if (denoise) HHNoise.suppress(a, b, rate) else a.copyTo(b, true)
            level(b, out, rate)
        } finally { a.delete(); b.delete() }
    }

    /** Выравнивание громкости: уровни кадров по всей записи (проход 1), усиление и ограничитель (проход 2). */
    private fun level(inp: File, out: File, rate: Int) {
        val frame = rate / 50                                   // 20 мс
        val rmsList = ArrayList<Double>()
        run {
            var e = 0.0; var k = 0
            samples(inp) { y -> e += y * y; if (++k == frame) { rmsList += Math.sqrt(e / k); e = 0.0; k = 0 } }
            if (k > 0) rmsList += Math.sqrt(e / k)
        }
        val n = rmsList.size
        if (n == 0) { out.writeBytes(ByteArray(0)); return }
        val rms = rmsList.toDoubleArray()
        // шум — 10-я перцентиль кадров; речь — кадры заметно громче шума
        val noise = rms.sorted()[n / 10].coerceAtLeast(1.0)
        val speech = BooleanArray(n) { rms[it] > noise * 3 }
        // уровень речи — по речевым кадрам в окне ±300 мс (как у динамической нормализации)
        val w = 15
        val raw = DoubleArray(n) { f ->
            var sum = 0.0; var k = 0
            for (j in maxOf(0, f - w)..minOf(n - 1, f + w)) if (speech[j]) { sum += rms[j] * rms[j]; k++ }
            val g = if (k == 0) 1.0 else (TARGET / Math.sqrt(sum / k)).coerceIn(1.0, MAX_GAIN)
            if (speech[f]) g else g * GATE
        }
        // плавно: быстро вниз (5 кадров), медленно вверх (25 кадров) — без «дыхания» и щелчков
        val gain = DoubleArray(n)
        var g = raw[0]
        for (f in 0 until n) { g += (raw[f] - g) / (if (raw[f] < g) 5.0 else 25.0); gain[f] = g }
        var i = 0
        writeSamples(out) { put ->
            samples(inp) { v ->
                val f = i / frame; val t = (i % frame).toDouble() / frame
                put(v * (gain[f] * (1 - t) + gain[minOf(f + 1, n - 1)] * t))
                i++
            }
        }
    }

    internal inline fun samples(f: File, each: (Double) -> Unit) {
        f.inputStream().buffered(65536).use { s ->
            while (true) {
                val lo = s.read(); val hi = s.read()
                if (lo < 0 || hi < 0) break
                each(((hi shl 8) or lo).toShort().toDouble())
            }
        }
    }

    /** Запись отсчётов через мягкий ограничитель: до −3 dBFS без изменений, выше — плавно к 0 dBFS. */
    internal inline fun writeSamples(f: File, body: ((Double) -> Unit) -> Unit) {
        f.outputStream().buffered(65536).use { o ->
            body { v ->
                val k = 23170.0
                val a = Math.abs(v)
                val y = (Math.signum(v) * (if (a <= k) a else k + (32767 - k) * Math.tanh((a - k) / (32767 - k)))).toInt()
                    .coerceIn(-32767, 32767)
                o.write(y and 0xff); o.write((y shr 8) and 0xff)
            }
        }
    }

    /** Баттерворт 2-го порядка, 120 Гц. */
    private class HighPass(rate: Int) {
        private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w0 = 2 * Math.PI * 120 / rate
            val alpha = Math.sin(w0) / (2 * Math.sqrt(0.5))
            val c = Math.cos(w0); val a0 = 1 + alpha
            b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        }
        fun next(v: Double): Double {
            val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = o
            return o
        }
    }
}

/**
 * Шумоподавление: кратковременный спектр (окно ≈32 мс, перекрытие 50 %, корень из окна Ханна на анализе и синтезе),
 * спектр шума — среднее по самым тихим 20 % кадров записи, подавление — фильтр Винера с «решением по предыдущему»
 * (Ephraim–Malah) и полом −15 дБ: глубже — «булькающий» призвук.
 * ponytail: шум считается постоянным на всю запись (вентиляция, гул, шипение); меняющийся шум — оценка минимумов по времени.
 */
object HHNoise {
    private const val FLOOR = 0.18         // −15 дБ — меньше не давим
    private const val ALPHA = 0.98         // сглаживание априорного SNR

    fun suppress(inp: File, out: File, rate: Int) {
        var size = 256; while (size < rate * 0.032) size *= 2      // 512 при 16 кГц, 2048 при 48 кГц
        val hop = size / 2
        val win = DoubleArray(size) { Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * it / size)) }
        val bins = size / 2 + 1
        // проход 1: энергия кадров → порог тихих; проход 2: средний спектр тихих кадров
        val energy = ArrayList<Double>()
        frames(inp, size, hop, win) { re, im -> var e = 0.0; for (k in 0 until bins) e += re[k] * re[k] + im[k] * im[k]; energy += e }
        if (energy.isEmpty()) { inp.copyTo(out, true); return }
        val quiet = energy.sorted()[(energy.size * 0.2).toInt().coerceAtMost(energy.size - 1)]
        val noise = DoubleArray(bins); var nq = 0; var fi = 0
        frames(inp, size, hop, win) { re, im ->
            if (energy[fi++] <= quiet) { for (k in 0 until bins) noise[k] += re[k] * re[k] + im[k] * im[k]; nq++ }
        }
        for (k in 0 until bins) noise[k] = (noise[k] / nq.coerceAtLeast(1)).coerceAtLeast(1e-3)
        // проход 3: подавление и сложение с перекрытием
        val prevG = DoubleArray(bins) { 1.0 }; val prevPost = DoubleArray(bins) { 1.0 }
        val acc = DoubleArray(size)
        val total = inp.length() / 2
        var written = 0L
        var first = true
        HHVoice.writeSamples(out) { put ->
            frames(inp, size, hop, win) { re, im ->
                for (k in 0 until bins) {
                    val post = (re[k] * re[k] + im[k] * im[k]) / noise[k]
                    val prio = ALPHA * prevG[k] * prevG[k] * prevPost[k] + (1 - ALPHA) * maxOf(post - 1, 0.0)
                    val g = maxOf(prio / (1 + prio), FLOOR)
                    prevG[k] = g; prevPost[k] = post
                    re[k] *= g; im[k] *= g
                }
                for (k in 1 until bins - 1) { re[size - k] = re[k]; im[size - k] = -im[k] }   // симметрия — вещественный сигнал
                fft(re, im, true)
                for (j in 0 until size) acc[j] += re[j] * win[j]
                // первая половина первого кадра — нули перед записью, не пишем
                if (!first) for (j in 0 until hop) if (written < total) { put(acc[j]); written++ }
                first = false
                System.arraycopy(acc, hop, acc, 0, size - hop); java.util.Arrays.fill(acc, size - hop, size, 0.0)
            }
        }
    }

    /**
     * Кадры с окном: первый начинается за hop до начала записи (нули) — так перекрытие покрывает каждый отсчёт дважды.
     * В конце — два кадра с нулями, чтобы хвост прошёл через оба. re/im — спектр кадра (можно менять).
     */
    private inline fun frames(f: File, size: Int, hop: Int, win: DoubleArray, each: (DoubleArray, DoubleArray) -> Unit) {
        val buf = DoubleArray(size)
        val re = DoubleArray(size); val im = DoubleArray(size)
        var fill = hop
        var any = false
        HHVoice.samples(f) { v ->
            buf[fill++] = v; any = true
            if (fill == size) {
                for (j in 0 until size) { re[j] = buf[j] * win[j]; im[j] = 0.0 }
                fft(re, im, false); each(re, im)
                System.arraycopy(buf, hop, buf, 0, size - hop); fill = size - hop
            }
        }
        if (!any) return
        repeat(2) {
            java.util.Arrays.fill(buf, fill, size, 0.0)
            for (j in 0 until size) { re[j] = buf[j] * win[j]; im[j] = 0.0 }
            fft(re, im, false); each(re, im)
            System.arraycopy(buf, hop, buf, 0, size - hop); fill = size - hop
        }
    }

    /** Быстрое преобразование Фурье по основанию 2, на месте; inverse — с делением на n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * Math.PI / len * (if (inverse) 1 else -1)
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                val h = len / 2
                for (k in 0 until h) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + h] * cr - im[i + k + h] * ci
                    val bi = re[i + k + h] * ci + im[i + k + h] * cr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + h] = ar - br; im[i + k + h] = ai - bi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
