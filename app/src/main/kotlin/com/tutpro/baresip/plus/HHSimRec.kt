package com.tutpro.baresip.plus

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Запись SIM-разговора: AudioRecord → PCM во время звонка, после — обработка голоса и AAC (HHRec.save).
 * VOICE_CALL (обе стороны с линии) — только системным приложениям (CAPTURE_AUDIO_OUTPUT); без разрешения на части прошивок
 * «пишет» тишину. Иначе VOICE_RECOGNITION: во время звонка AOSP не глушит его приложению с включённой службой специальных
 * возможностей (AudioPolicyService::updateUidStates_l, isA11yOnTop; MIC глушит) — собеседник слышен через динамик.
 */
object HHSimRec {
    private const val TAG = "HHSimRec"
    private const val RATE = 16_000
    @Volatile private var rec: AudioRecord? = null   // поток записи смотрит, не остановлена ли
    private var worker: Thread? = null
    private var pcm: File? = null
    private var peer = ""
    private var started = 0L

    private fun sources(ctx: Context) =
        if (ctx.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            listOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        else listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

    // ponytail: один SIM-разговор за раз (две линии SIM одновременно не записываются)
    @SuppressLint("MissingPermission")   // RECORD_AUDIO — без него AudioRecord не инициализируется, ловится ниже
    @Synchronized fun start(ctx: Context, call: Call) {
        if (rec != null) return
        val f = File(HHRec.dir(ctx), "sim.tmp.pcm")
        val buf = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)
        for (src in sources(ctx)) {
            val r = try { AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf) } catch (e: Exception) { null }
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); Log.w(TAG, "source $src refused"); continue }
            try { r.startRecording() } catch (e: Exception) { r.release(); Log.w(TAG, "source $src start: $e"); continue }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue }
            rec = r; pcm = f; peer = call.peerUri; started = System.currentTimeMillis()
            worker = Thread {
                val b = ByteArray(buf)
                f.outputStream().buffered().use { o ->
                    while (rec === r) { val n = r.read(b, 0, b.size); if (n > 0) o.write(b, 0, n) else if (n < 0) break }   // stop() снимает блокировку read
                }
            }.apply { start() }
            Log.i(TAG, "recording SIM call, source $src")
            if (src != MediaRecorder.AudioSource.VOICE_CALL) warnIfSilenced(ctx, r)
            return
        }
        call.hhRec.value = false
        HHUi.message.value = ctx.getString(R.string.hh_rec_failed)
    }

    /** Микрофон заглушён системой (нет службы специальных возможностей) — подсказка, запись не прерываем. */
    private fun warnIfSilenced(ctx: Context, r: AudioRecord) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val silenced = Build.VERSION.SDK_INT >= 29 && rec === r &&
                (try { r.activeRecordingConfiguration?.isClientSilenced } catch (_: Exception) { null } == true)
            if (silenced || !HHRecAccessibility.enabled(ctx)) HHUi.message.value = ctx.getString(R.string.hh_rec_need_a11y)
        }, 1500)
    }

    /** Конец записи: остановить, дописать файл, обработать и сохранить в фоне. */
    @Synchronized fun stop(ctx: Context, @Suppress("UNUSED_PARAMETER") call: Call?) {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: Exception) {}
        val w = worker; worker = null
        val f = pcm ?: return
        val start = started; val who = peer
        Thread {
            w?.join(3000)
            r.release()
            try {
                if (f.length() > RATE * 2) HHRec.save(ctx, f, RATE, start, true, who)   // меньше секунды — не запись
            } catch (e: Exception) { Log.e(TAG, "SIM recording save failed: $e") }
            finally { f.delete() }
        }.start()
    }
}
