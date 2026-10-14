package com.tutpro.baresip.plus

/** Запись журнала для нового интерфейса (из CallHistoryNew): start = null — не отвечен. */
data class HHLogEntry(val peer: String, val aor: String, val dir: String, val start: Long?, val stop: Long, val rejected: Boolean,
                      val id: Long = 0)   // id — _ID строки системного журнала (0 — запись CallHistoryNew)

/** Подряд идущие звонки одного собеседника: last — самый свежий. */
data class HHLogGroup(val peer: String, val aor: String, val last: HHLogEntry, val count: Int,
                      val entries: List<HHLogEntry> = listOf(last))

/** Номер контакта: id — _ID строки Data (0 — новый), type — Phone.TYPE. */
data class HHNum(val id: Long, val number: String, val type: Int, val label: String = "")

sealed class HHNumOp {
    data class Update(val id: Long, val num: HHNum) : HHNumOp()
    data class Delete(val id: Long) : HHNumOp()
    data class Insert(val num: HHNum) : HHNumOp()
}

/** Линия для кнопки вызова: SIM (mobile) или станция. */
data class HHLine(val aor: String, val mobile: Boolean, val registered: Boolean)

/** Чистая логика экранов HHPhone 0.5.0 (спека 2026-10-13 ui) — без Android, тестируется JUnit. */
object HHUiLogic {
    fun peerKey(uri: String): String {
        val s = uri.removePrefix("sip:").removePrefix("tel:").substringBefore(";").substringBefore("@")
        return if (s.any { it.isLetter() }) s else s.filter { it.isDigit() || it in "+*#" }
    }

    fun group(entries: List<HHLogEntry>): List<HHLogGroup> {
        val out = mutableListOf<HHLogGroup>()
        for (e in entries.sortedByDescending { it.stop }) {
            val g = out.lastOrNull()
            if (g != null && (peerKey(g.peer) == peerKey(e.peer) || samePhone(g.peer, e.peer)))
                out[out.size - 1] = g.copy(count = g.count + 1, entries = g.entries + e)
            else out += HHLogGroup(e.peer, e.aor, e, 1)
        }
        return out
    }

    fun avatarLetter(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    fun avatarColor(name: String): Int = name.lowercase().sumOf { it.code } % 8

    /**
     * (единица, значение): 0 — только что, 1 — минуты, 2 — часы (тот же день), 3 — вчера (календарный), 4 — дата (thenMs).
     * tzOffsetMs — смещение часового пояса (TimeZone.getOffset), чтобы «вчера» считалось по местному дню.
     */
    fun ago(nowMs: Long, thenMs: Long, tzOffsetMs: Long): Pair<Int, Long> {
        val m = (nowMs - thenMs) / 60_000
        val days = Math.floorDiv(nowMs + tzOffsetMs, 86_400_000L) - Math.floorDiv(thenMs + tzOffsetMs, 86_400_000L)
        return when {
            m < 1 -> 0 to 0L
            m < 60 -> 1 to m
            days == 0L -> 2 to m / 60
            days == 1L -> 3 to 0L
            else -> 4 to thenMs
        }
    }

    fun lines(all: List<HHLine>): List<HHLine> =
        all.distinctBy { it.aor }.sortedBy { if (it.mobile) 0 else 1 }

    fun missed(e: HHLogEntry) = e.dir == "in" && e.start == null && !e.rejected

    /**
     * Куда и что набрать для собеседника журнала/контакта: sip: — только через станцию (лучше preferred, затем с тем же
     * доменом), полный URI; номер — preferred, иначе SIM, иначе станция. Анонимный/пустой/нет линий — null.
     */
    fun dialTarget(peer: String, lines: List<HHLine>, preferredAor: String?): Pair<String, String>? {
        val p = peer.trim()
        val user = peerKey(p)
        if (user.isEmpty() || user.equals("anonymous", true) || user.equals("unknown", true)) return null
        if (p.startsWith("sip:")) {
            val uri = p.substringBefore(";")
            val dom = uri.substringAfter("@", "")
            val st = lines.filter { !it.mobile }
            val line = st.firstOrNull { it.aor == preferredAor } ?: st.firstOrNull { it.aor.substringAfter("@") == dom } ?: st.firstOrNull()
            return line?.let { it.aor to uri }
        }
        val line = lines.firstOrNull { it.aor == preferredAor } ?: lines.firstOrNull { it.mobile } ?: lines.firstOrNull()
        return line?.let { it.aor to user }
    }

    /** Коллега — только через свою станцию (никогда не через SIM). */
    fun dialColleague(number: String, stationAor: String, lines: List<HHLine>): Pair<String, String>? =
        lines.firstOrNull { it.aor == stationAor && !it.mobile }?.let { it.aor to number }

    /** Кнопки вызова: до двух линий — по кнопке; больше — первая и кнопка выбора остальных. */
    fun callButtons(lines: List<HHLine>): List<List<HHLine>> =
        if (lines.size <= 2) lines.map { listOf(it) } else listOf(listOf(lines[0]), lines.drop(1))

    fun screenFor(callStatuses: List<String>, video: Boolean): String {
        val open = callStatuses.filter { it != "closed" }
        return when {
            open.isEmpty() -> "home"
            video -> "video"
            "incoming" in open && open.none { it == "connected" || it == "answered" } -> "incoming"
            else -> "call"
        }
    }

    const val CL_IN = 1; const val CL_OUT = 2; const val CL_MISSED = 3; const val CL_VOICEMAIL = 4
    const val CL_REJECTED = 5; const val CL_BLOCKED = 6; const val CL_EXTERNAL = 7

    /** Строка системного журнала → запись журнала HHPhone; accountId «sip:…» — звонок станции, иначе SIM. */
    fun callLogEntry(number: String, type: Int, date: Long, durationSec: Long, accountId: String?, allowed: Boolean,
                     component: String? = null, id: Long = 0): HHLogEntry {
        val station = accountId != null && accountId.startsWith("sip:")
        // не SIM и не HHPhone — звонок другого приложения: без пометки «SIM», линия выбирается как обычно
        val telephony = component.isNullOrBlank() || component.contains("com.android.phone") || component.contains("telephony", true)
        val aor = when { station -> accountId!!; telephony -> "sip:mobile@pstn"; else -> "" }
        val peer = when {
            !allowed || number.isBlank() -> "tel:anonymous"
            station && number.contains("@") -> "sip:$number"
            station -> "sip:$number@" + accountId!!.substringAfter("@")
            else -> "tel:$number"
        }
        val answered = type == CL_IN || type == CL_OUT || type == CL_EXTERNAL
        return HHLogEntry(peer, aor, if (type == CL_OUT) "out" else "in", if (answered) date else null,
            date + durationSec * 1000, type == CL_REJECTED || type == CL_BLOCKED, id)
    }

    /** NUMBER для системного журнала: номер, а для собеседника с чужого сервера — user@домен. */
    fun callLogNumber(peerUri: String, aor: String): String {
        val user = peerKey(peerUri)
        val dom = if (peerUri.startsWith("sip:")) peerUri.substringAfter("@", "").substringBefore(";") else ""
        return if (dom.isNotEmpty() && dom != aor.substringAfter("@")) "$user@$dom" else user
    }

    /** Адреса MMS (адрес, тип PduHeaders): 137 — от кого, 151/130/129 — кому; свой номер убирается. */
    fun mmsAddresses(rows: List<Pair<String, Int>>, own: String): Pair<String?, List<String>> {
        val from = rows.firstOrNull { it.second == 137 }?.first?.takeIf { it.isNotBlank() && it != "insert-address-token" }
        val to = rows.filter { it.second in setOf(151, 130, 129) }.map { it.first }
            .filter { it.isNotBlank() && it != "insert-address-token" && !(own.isNotBlank() && samePhone(it, own)) }.distinct()
        return from to to
    }

    /** Отметка исходящего SMS: TYPE (2 — отправлено, 5 — ошибка, 4/6 — в очереди), STATUS (0 — доставлено, 64+ — ошибка). */
    fun deliveryMark(type: Int, status: Int): String = when {
        type == 5 || (type == 2 && status >= 64) -> "!"
        type == 2 && status == 0 -> "✓✓"
        type == 2 -> "✓"
        type == 4 || type == 6 -> "…"
        else -> ""
    }

    /** Международный вид номера (+…); номер с 0 — через код страны SIM; короткие внутренние — null. */
    fun toE164(number: String, countryCode: String?): String? {
        val t = number.trim()
        val d = t.filter { it.isDigit() }
        val e = when {
            t.startsWith("+") -> d
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") && !countryCode.isNullOrEmpty() -> countryCode + d.drop(1)
            d.length >= 11 -> d
            else -> return null
        }
        return if (e.length in 8..15) "+$e" else null
    }

    /** Ссылки на чат в мессенджерах по номеру в международном виде. */
    fun messengerLinks(e164: String): Map<String, String> {
        val d = e164.filter { it.isDigit() }
        return mapOf("whatsapp" to "https://wa.me/$d", "viber" to "viber://chat?number=%2B$d", "telegram" to "tg://resolve?phone=$d")
    }

    /** Одинаковые номера из разных аккаунтов контакта (Google, SIM…) — одна строка: (представитель, все _ID). */
    fun mergeSame(nums: List<HHNum>, prefer: (HHNum) -> Boolean): List<Pair<HHNum, List<Long>>> {
        val groups = mutableListOf<MutableList<HHNum>>()
        for (n in nums) groups.firstOrNull { g -> samePhone(g[0].number, n.number) || g[0].number == n.number }?.add(n) ?: groups.add(mutableListOf(n))
        return groups.map { g -> (g.firstOrNull(prefer) ?: g[0]) to g.map { it.id } }
    }

    /** Правка номеров контакта: изменённые — Update, пустые и пропавшие — Delete, новые непустые — Insert. */
    fun numberOps(old: List<HHNum>, new: List<HHNum>): List<HHNumOp> {
        val out = mutableListOf<HHNumOp>()
        for (o in old) {
            val n = new.firstOrNull { it.id == o.id }
            when {
                n == null || n.number.isBlank() -> out += HHNumOp.Delete(o.id)
                n != o -> out += HHNumOp.Update(o.id, n)
            }
        }
        new.filter { it.id <= 0L && it.number.isNotBlank() }.forEach { out += HHNumOp.Insert(it) }   // ≤ 0 — новые (временные id строк правки)
        return out
    }

    /** Тип строки системного журнала для звонка станции. */
    fun callLogType(dir: String, start: Long?, rejected: Boolean): Int = when {
        dir == "out" -> CL_OUT
        start != null -> CL_IN
        rejected -> CL_REJECTED
        else -> CL_MISSED
    }

    /** Один номер: цифры совпадают, или у обоих ≥ 8 цифр и совпадают последние 8 (+374 91… = 091…). */
    fun samePhone(a: String, b: String): Boolean {
        val ka = peerKey(a)
        val kb = peerKey(b)
        if (ka.any { it.isLetter() } || kb.any { it.isLetter() })   // буквенный отправитель (Ucom, банк)
            return ka.isNotEmpty() && ka.equals(kb, ignoreCase = true)
        val x = ka.filter { it.isDigit() }
        val y = kb.filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (x.length >= 8 && y.length >= 8 && x.takeLast(8) == y.takeLast(8))
    }

    fun sameTime(a: Long, b: Long) = kotlin.math.abs(a - b) < 5000

    /** Тот же звонок в системном журнале и в CallHistoryNew: система считает от начала гудков, HHPhone — от ответа (±90 с). */
    fun sameLoggedCall(sysStart: Long, sysStop: Long, start: Long?, stop: Long) =
        kotlin.math.abs(sysStop - stop) < 90_000 || kotlin.math.abs(sysStart - (start ?: stop)) < 90_000

    private val t9 = listOf("ABC" to '2', "DEF" to '3', "GHI" to '4', "JKL" to '5', "MNO" to '6', "PQRS" to '7', "TUV" to '8', "WXYZ" to '9',
        "АБВГ" to '2', "ДЕЁЖЗ" to '3', "ИЙКЛ" to '4', "МНОП" to '5', "РСТУ" to '6', "ФХЦЧ" to '7', "ШЩЪЫ" to '8', "ЬЭЮЯ" to '9')
        .flatMap { (ls, d) -> ls.map { it to d } }.toMap()

    /** Подсказка при наборе: цифры номера (и без ведущего 0 — для +374…) или буквы начала слова имени по клавишам. */
    fun suggestMatch(query: String, name: String, number: String): Boolean {
        val q = query.filter { it.isDigit() }
        if (q.isEmpty()) return false
        val n = number.filter { it.isDigit() }
        if (n.contains(q) || (q.length > 2 && q[0] == '0' && n.contains(q.drop(1)))) return true
        return q.length > 1 && name.uppercase().split(' ', '-').any { w -> w.map { t9[it] ?: it }.joinToString("").startsWith(q) }
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
