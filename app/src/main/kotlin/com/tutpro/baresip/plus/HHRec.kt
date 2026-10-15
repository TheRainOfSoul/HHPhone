package com.tutpro.baresip.plus

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.net.URLEncoder

/** Запись разговора: файл <начало мс>_<длительность с>_<sip|sim>_<собеседник>.m4a в filesDir/hh_rec. */
data class HHRecFile(val file: File, val start: Long, val seconds: Int, val sim: Boolean, val peer: String)

/**
 * Запись разговоров SIP и SIM (спека 2026-10-15). Режим: off — нет; button — кнопка «Запись» на экране разговора;
 * auto — каждый разговор (кнопкой можно отменить).
 * SIP: модуль baresip sndfile пишет обе стороны во временные WAV с начала звука; в конце звонка, если запись
 * включали, — смесь в моно с момента нажатия → AAC; временные файлы удаляются всегда.
 */
object HHRec {
    const val OFF = "off"
    const val BUTTON = "button"
    const val AUTO = "auto"
    private const val TAG = "HHRec"

    fun mode(ctx: Context): String = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE).getString("rec_mode", OFF) ?: OFF
    fun setMode(ctx: Context, m: String) {
        ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE).edit().putString("rec_mode", m).apply()
        applySip(ctx)
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "hh_rec").apply { mkdirs() }

    /** Сколько записей сейчас обрабатывается: служба не засыпает (hhIdle), пока не ноль. */
    val busy = java.util.concurrent.atomic.AtomicInteger()

    // ---- имена файлов ----
    fun fileName(start: Long, seconds: Int, sim: Boolean, peer: String) =
        "${start}_${seconds}_${if (sim) "sim" else "sip"}_${URLEncoder.encode(peer, "UTF-8")}.m4a"

    fun parse(f: File): HHRecFile? {
        val p = f.name.removeSuffix(".m4a").split("_", limit = 4)
        if (p.size != 4 || !f.name.endsWith(".m4a")) return null
        val start = p[0].toLongOrNull() ?: return null
        val sec = p[1].toIntOrNull() ?: return null
        return HHRecFile(f, start, sec, p[2] == "sim", try { URLDecoder.decode(p[3], "UTF-8") } catch (_: Exception) { return null })
    }

    fun list(ctx: Context): List<HHRecFile> =
        (dir(ctx).listFiles() ?: emptyArray()).mapNotNull { parse(it) }.sortedByDescending { it.start }

    /** Записи звонка журнала: тот же собеседник, начало записи — внутри звонка (±10 с). */
    fun of(recs: List<HHRecFile>, e: HHLogEntry): List<HHRecFile> =
        recs.filter { r -> r.start in ((e.start ?: e.stop) - 10_000)..(e.stop + 10_000) &&
            (HHUiLogic.peerKey(r.peer) == HHUiLogic.peerKey(e.peer) || HHUiLogic.samePhone(r.peer, e.peer)) }

    // ---- состояние звонка ----
    /** Начало разговора: в режиме auto запись сразу (весь разговор). */
    fun onConnected(ctx: Context, call: Call) {
        if (mode(ctx) == AUTO) start(ctx, call)
    }

    /** Кнопка «Запись»: SIP — сохранить с этого момента; SIM — запустить запись. */
    fun toggle(ctx: Context, call: Call) {
        if (call.hhRec.value) stop(ctx, call) else start(ctx, call)
    }

    private fun start(ctx: Context, call: Call) {
        if (call.hhRec.value) return
        call.hhRec.value = true
        if (call.hhRecFrom == 0L) call.hhRecFrom = System.currentTimeMillis()
        if (call.ua.account.isMobile) HHSimRec.start(ctx, call)
    }

    private fun stop(ctx: Context, call: Call) {
        call.hhRec.value = false
        if (call.ua.account.isMobile) HHSimRec.stop(ctx, call)
    }

    /** sndfile открыл файл стороны разговора (событие «sndfile dump»). */
    fun onDump(call: Call) {
        if (call.dumpStart == 0L) call.dumpStart = System.currentTimeMillis()
    }

    // ---- SIP: sndfile ----
    private var sndfileLoaded = false

    /** Модуль sndfile загружен, пока запись не выключена; меняется только без звонков (поток уже открыт). */
    fun applySip(ctx: Context) {
        if (!BaresipService.isNativeReady || Call.inCall()) return
        val want = mode(ctx) != OFF
        if (want == sndfileLoaded) return
        if (want) Api.module_load("sndfile") else Api.module_unload("sndfile")
        sndfileLoaded = want
        BaresipService.isRecOn = want
    }

    /** Конец SIP-звонка: смесь двух сторон → m4a, если запись включали; временные WAV удаляются. */
    fun finishSip(ctx: Context, call: Call) {
        val tx = call.dumpfiles[0]; val rx = call.dumpfiles[1]
        if (tx.isEmpty() && rx.isEmpty()) return
        val keep = call.hhRecFrom != 0L && call.startTime != null && call.hhRec.value
        busy.incrementAndGet()
        Thread {
            try {
                Thread.sleep(500)   // sndfile закрывает файлы после события
                if (keep) {
                    val from = (call.hhRecFrom - call.dumpStart).coerceAtLeast(0)
                    val pcm = File(ctx.cacheDir, "hh_rec.pcm")
                    val rate = HHWav.mix(File(rx).takeIf { it.exists() }, File(tx).takeIf { it.exists() }, from, pcm)
                    if (rate > 0 && pcm.length() > 0)
                        save(ctx, pcm, rate, maxOf(call.hhRecFrom, call.dumpStart), false, call.peerUri)
                    pcm.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "SIP recording failed: $e")
            } finally {
                File(tx).delete(); File(rx).delete()
                busy.decrementAndGet()
            }
        }.start()
    }

    /** PCM разговора → обработка голоса (HHVoice) → m4a в папку записей. */
    fun save(ctx: Context, pcm: File, rate: Int, start: Long, sim: Boolean, peer: String) {
        val clean = File(pcm.path + ".voice")
        try {
            HHVoice.processFile(pcm, clean, rate)
            val sec = (clean.length() / 2 / rate).toInt()
            encodeAac(clean, rate, File(dir(ctx), fileName(start, sec, sim, peer)))
            HHHistory.bump()
        } finally { clean.delete() }
    }

    /** 16-битный моно PCM → AAC-LC 64 кбит/с в MP4 (≈ 0,5 МБ в минуту) — для речи без слышимых потерь, открывается везде. */
    fun encodeAac(pcm: File, rate: Int, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val tmp = File(out.path + ".tmp")
        val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var eos = false
        var samples = 0L
        val chunk = ByteArray(8192)
        pcm.inputStream().buffered().use { input ->
            while (true) {
                if (!eos) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = input.read(chunk, 0, minOf(chunk.size, buf.capacity()))
                        val pts = samples * 1_000_000 / rate
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else { buf.put(chunk, 0, n); codec.queueInputBuffer(i, 0, n, pts, 0); samples += n / 2 }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start() }
                else if (o >= 0) {
                    if (track >= 0 && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                        mux.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        codec.stop(); codec.release()
        if (track >= 0) mux.stop()
        mux.release()
        if (track >= 0) tmp.renameTo(out) else tmp.delete()
    }

    fun delete(r: HHRecFile) { r.file.delete(); HHHistory.bump() }
}

/** WAV 16 бит (sndfile) → смесь собеседников в моно PCM. Чистая логика — тесты на JVM. */
object HHWav {
    data class Info(val rate: Int, val channels: Int, val dataOffset: Long, val dataSize: Long)

    /** Разбор заголовка: fmt (PCM 16 бит) и data; null — не тот формат. */
    fun info(f: File): Info? = RandomAccessFile(f, "r").use { r ->
        val h = ByteArray(12)
        if (r.read(h) != 12 || String(h, 0, 4) != "RIFF" || String(h, 8, 4) != "WAVE") return null
        var rate = 0; var ch = 0; var bits = 0; var fmtTag = 0
        val c = ByteArray(8)
        while (r.read(c) == 8) {
            val id = String(c, 0, 4)
            val size = le32(c, 4).toLong() and 0xffffffffL
            val pos = r.filePointer
            if (id == "fmt ") {
                val b = ByteArray(16); r.readFully(b)
                fmtTag = le16(b, 0); ch = le16(b, 2); rate = le32(b, 4); bits = le16(b, 14)
            } else if (id == "data") {
                if (fmtTag != 1 || bits != 16 || ch < 1 || rate <= 0) return null
                // размер data не дописан (файл не закрыт) — до конца файла
                val real = if (size == 0L || size == 0xffffffffL || pos + size > r.length()) r.length() - pos else size
                return Info(rate, ch, pos, real)
            }
            r.seek(pos + size + (size and 1))
        }
        null
    }

    /** Сумма двух сторон (первый канал) с ограничением, начиная с fromMs; результат — моно PCM. Возвращает частоту или 0. */
    fun mix(a: File?, b: File?, fromMs: Long, out: File): Int {
        val ia = a?.let { info(it) }
        val ib = b?.let { info(it) }
        val rate = ia?.rate ?: ib?.rate ?: return 0
        val skip = fromMs * rate / 1000
        fun open(f: File?, i: Info?) = if (f == null || i == null || i.rate != rate) null else
            f.inputStream().buffered().also { it.skip(i.dataOffset + skip * 2 * i.channels) }
        val sa = open(a, ia); val sb = open(b, ib)
        fun next(s: java.io.InputStream?, i: Info?): Int? {
            if (s == null || i == null) return null
            val lo = s.read(); val hi = s.read()
            if (lo < 0 || hi < 0) return null
            repeat((i.channels - 1) * 2) { s.read() }
            return ((hi shl 8) or lo).toShort().toInt()
        }
        out.outputStream().buffered().use { o ->
            while (true) {
                val x = next(sa, ia); val y = next(sb, ib)
                if (x == null && y == null) break
                val v = ((x ?: 0) + (y ?: 0)).coerceIn(-32768, 32767)
                o.write(v and 0xff); o.write((v shr 8) and 0xff)
            }
        }
        sa?.close(); sb?.close()
        return rate
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
}
