package com.tutpro.baresip.plus

/** Запись журнала для нового интерфейса (из CallHistoryNew): start = null — не отвечен. */
data class HHLogEntry(val peer: String, val aor: String, val dir: String, val start: Long?, val stop: Long, val rejected: Boolean)

/** Подряд идущие звонки одного собеседника: last — самый свежий. */
data class HHLogGroup(val peer: String, val aor: String, val last: HHLogEntry, val count: Int)

/** Линия для кнопки вызова: SIM (mobile) или станция. */
data class HHLine(val aor: String, val mobile: Boolean, val registered: Boolean)

/** Чистая логика экранов HHPhone 0.5.0 (спека 2026-10-13 ui) — без Android, тестируется JUnit. */
object HHUiLogic {
    fun peerKey(uri: String): String {
        val s = uri.removePrefix("sip:").removePrefix("tel:").substringBefore(";").substringBefore("@")
        return if (s.any { it.isLetter() }) s.lowercase() else s.filter { it.isDigit() || it == '+' }
    }

    fun group(entries: List<HHLogEntry>): List<HHLogGroup> {
        val out = mutableListOf<HHLogGroup>()
        for (e in entries.sortedByDescending { it.stop }) {
            val g = out.lastOrNull()
            if (g != null && peerKey(g.peer) == peerKey(e.peer)) out[out.size - 1] = g.copy(count = g.count + 1)
            else out += HHLogGroup(e.peer, e.aor, e, 1)
        }
        return out
    }

    fun avatarLetter(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    fun avatarColor(name: String): Int = name.lowercase().sumOf { it.code } % 8

    /** (единица, значение): 0 — только что, 1 — минуты, 2 — часы, 3 — вчера, 4 — дата (значение = thenMs). */
    fun ago(nowMs: Long, thenMs: Long): Pair<Int, Long> {
        val m = (nowMs - thenMs) / 60_000
        return when {
            m < 1 -> 0 to 0L
            m < 60 -> 1 to m
            m < 24 * 60 -> 2 to m / 60
            m < 48 * 60 -> 3 to 0L
            else -> 4 to thenMs
        }
    }

    fun lines(all: List<HHLine>): List<HHLine> =
        all.distinctBy { it.aor }.sortedBy { if (it.mobile) 0 else 1 }

    fun screenFor(callStatuses: List<String>, video: Boolean): String {
        val open = callStatuses.filter { it != "closed" }
        return when {
            open.isEmpty() -> "home"
            video -> "video"
            "incoming" in open && open.none { it == "connected" } -> "incoming"
            else -> "call"
        }
    }

    private val item = Regex(""""n":"([^"]*)","name":"((?:[^"\\]|\\.)*)"""")
    private val esc = Regex("""\\(u[0-9a-fA-F]{4}|.)""")

    /** Ответ directory.php → (номер, имя); мусор — пустой список. */
    fun parseDirectory(json: String): List<Pair<String, String>> {
        if (!json.contains("\"ok\":true")) return emptyList()
        return item.findAll(json).map { m ->
            m.groupValues[1] to esc.replace(m.groupValues[2]) {
                val c = it.groupValues[1]
                if (c.length == 5) c.substring(1).toInt(16).toChar().toString()
                else when (c) { "n" -> "\n"; "t" -> "\t"; else -> c }
            }
        }.toList()
    }
}
