package com.tutpro.baresip.plus

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Заметки к звонкам: ключ — «номер|время конца звонка», хранятся в SharedPreferences hh_notes.
 * Карта — состояние Compose: журнал, история и карточка обновляются сразу, пока заметку печатают.
 */
object HHNotes {
    private val notes = mutableStateMapOf<String, String>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hh_notes", Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        prefs(ctx).all.forEach { (k, v) -> if (v is String) notes[k] = v }
        loaded = true
    }

    fun key(e: HHLogEntry): String = HHUiLogic.peerKey(e.peer) + "|" + e.stop

    fun get(ctx: Context, e: HHLogEntry): String? { load(ctx); return notes[key(e)] }

    /** Пустой текст — удалить заметку. */
    fun set(ctx: Context, e: HHLogEntry, text: String) {
        load(ctx)
        val k = key(e)
        if (text.isBlank()) { notes.remove(k); prefs(ctx).edit().remove(k).apply() }
        else { notes[k] = text; prefs(ctx).edit().putString(k, text).apply() }
    }

    /** Заметки по номерам контакта: (время конца звонка, текст), свежие первыми. */
    fun forPeers(ctx: Context, peers: List<String>): List<Pair<Long, String>> {
        load(ctx)
        return notes.mapNotNull { (k, v) ->
            val n = k.substringBeforeLast("|")
            val stop = k.substringAfterLast("|").toLongOrNull() ?: return@mapNotNull null
            if (peers.any { HHUiLogic.peerKey(it) == n || HHUiLogic.samePhone(it, n) }) stop to v else null
        }.sortedByDescending { it.first }
    }
}
