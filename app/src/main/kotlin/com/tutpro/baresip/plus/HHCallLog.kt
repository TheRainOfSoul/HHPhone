package com.tutpro.baresip.plus

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls

/** Системный журнал звонков — единый журнал HHPhone (спека 2026-10-14). */
object HHCallLog {
    private fun can(ctx: Context, p: String) = Utils.checkPermissions(ctx, arrayOf(p))

    /** null — нет разрешения (журнал тогда из CallHistoryNew). */
    fun read(ctx: Context, limit: Int = 500): List<HHLogEntry>? {
        if (!can(ctx, Manifest.permission.READ_CALL_LOG)) return null
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return try {
            ctx.contentResolver.query(Calls.CONTENT_URI, arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DATE, Calls.DURATION,
                Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION), args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(HHUiLogic.callLogEntry(c.getString(0) ?: "", c.getInt(1), c.getLong(2),
                        c.getLong(3), c.getString(4), c.getInt(5) == Calls.PRESENTATION_ALLOWED))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog read failed: $e")
            null
        }
    }

    /** Звонок станции → системный журнал (SIM-звонки пишет сама система); aor "" — SIM (перенос). */
    fun write(ctx: Context, aor: String, peerUri: String, dir: String, start: Long?, stop: Long, rejected: Boolean) {
        if (!can(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        val type = HHUiLogic.callLogType(dir, start, rejected)
        val v = ContentValues().apply {
            put(Calls.NUMBER, HHUiLogic.peerKey(peerUri))
            put(Calls.TYPE, type)
            put(Calls.DATE, start ?: stop)
            put(Calls.DURATION, if (start != null) (stop - start) / 1000 else 0L)
            put(Calls.PHONE_ACCOUNT_ID, aor)
            if (aor.isNotEmpty())
                put(Calls.PHONE_ACCOUNT_COMPONENT_NAME, BaresipService.getPhoneAccountHandle(ctx).componentName.flattenToString())
            put(Calls.NEW, if (type == HHUiLogic.CL_MISSED) 1 else 0)
            put(Calls.NUMBER_PRESENTATION, Calls.PRESENTATION_ALLOWED)
        }
        try { ctx.contentResolver.insert(Calls.CONTENT_URI, v) } catch (e: Exception) { Log.e(TAG, "CallLog write failed: $e") }
    }

    /** Один раз: записи CallHistoryNew, которых нет в системном журнале (SIM, удалённые прежними версиями, и станции). */
    fun migrate(ctx: Context) {
        val p = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
        if (p.getBoolean("calllog_migrated", false) || !can(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        val have = read(ctx, 5000) ?: return
        for (h in HHHistory.snapshot()) {
            val start = h.startTime?.timeInMillis
            val stop = h.stopTime.timeInMillis
            if (have.any { HHUiLogic.samePhone(it.peer, h.peerUri) &&
                    (HHUiLogic.sameTime(it.start ?: it.stop, start ?: stop) || HHUiLogic.sameTime(it.stop, stop)) }) continue
            write(ctx, if (h.aor.endsWith("@pstn")) "" else h.aor, h.peerUri, h.direction, start, stop, h.rejected)
        }
        p.edit().putBoolean("calllog_migrated", true).apply()
    }

    /** Подписка на изменения журнала; возвращает отписку. */
    fun observe(ctx: Context, onChange: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) = onChange() }
        return try {
            ctx.contentResolver.registerContentObserver(Calls.CONTENT_URI, true, o)
            val off: () -> Unit = { ctx.contentResolver.unregisterContentObserver(o) }
            off
        } catch (_: SecurityException) { {} }
    }

    private const val TAG = "HHCallLog"
}
