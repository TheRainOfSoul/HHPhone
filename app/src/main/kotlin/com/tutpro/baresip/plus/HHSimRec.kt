package com.tutpro.baresip.plus

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Запись SIM-разговора: MediaRecorder (AAC) из первого источника звука, который система разрешит во время звонка. */
object HHSimRec {
    private const val TAG = "HHSimRec"
    private var rec: MediaRecorder? = null
    private var tmp: File? = null
    private var peer = ""
    private var started = 0L

    // ponytail: один SIM-разговор за раз (две линии SIM одновременно не записываются)
    // VOICE_CALL — обе стороны, но только системным приложениям (CAPTURE_AUDIO_OUTPUT); иначе микрофон: его во время
    // звонка Android не глушит лишь приложению с включённой службой специальных возможностей (HHRecAccessibility),
    // собеседник слышен в микрофон в основном на громкой связи
    private fun sources(ctx: Context) =
        if (ctx.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            listOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        // без разрешения VOICE_CALL на части прошивок «пишет» тишину. Службе спец. возможностей во время звонка AOSP
        // оставляет только VOICE_RECOGNITION/HOTWORD (AudioPolicyService::updateUidStates_l, isA11yOnTop), MIC глушит
        else listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

    @Synchronized fun start(ctx: Context, call: Call) {
        if (rec != null) return
        val f = File(HHRec.dir(ctx), "sim.tmp.m4a")
        for (src in sources(ctx)) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000)
                r.setAudioEncodingBitRate(32_000)
                r.setOutputFile(f.path)
                r.prepare()
                r.start()
                rec = r; tmp = f; peer = call.peerUri; started = System.currentTimeMillis()
                Log.i(TAG, "recording SIM call, source $src")
                if (src != MediaRecorder.AudioSource.VOICE_CALL) warnIfSilenced(ctx, r)
                return
            } catch (e: Exception) {
                Log.w(TAG, "source $src refused: $e")
                try { r.release() } catch (_: Exception) {}
            }
        }
        call.hhRec.value = false
        HHUi.message.value = ctx.getString(R.string.hh_rec_failed)
    }

    /** Микрофон заглушён системой (нет службы специальных возможностей) — подсказка, запись не прерываем. */
    private fun warnIfSilenced(ctx: Context, r: MediaRecorder) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val silenced = Build.VERSION.SDK_INT >= 29 && rec === r &&
                (try { r.activeRecordingConfiguration?.isClientSilenced } catch (_: Exception) { null } == true)
            if (silenced || !HHRecAccessibility.enabled(ctx)) HHUi.message.value = ctx.getString(R.string.hh_rec_need_a11y)
        }, 1500)
    }

    @Synchronized fun stop(ctx: Context, @Suppress("UNUSED_PARAMETER") call: Call?) {
        val r = rec ?: return
        rec = null
        val ok = try { r.stop(); true } catch (e: Exception) { Log.w(TAG, "stop: $e"); false }   // слишком короткая — исключение
        r.release()
        val f = tmp ?: return
        val sec = ((System.currentTimeMillis() - started) / 1000).toInt()
        if (ok && f.length() > 0) { f.renameTo(File(HHRec.dir(ctx), HHRec.fileName(started, sec, true, peer))); HHHistory.bump() }
        else f.delete()
    }
}
