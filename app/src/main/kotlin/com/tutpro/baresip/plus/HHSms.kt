package com.tutpro.baresip.plus

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import android.telephony.SmsManager

/** Системная база SMS — единая для HHPhone (спека 2026-10-14). */
object HHSms {
    const val MOBILE = "sip:mobile@pstn"
    fun canRead(ctx: Context) = Utils.checkPermissions(ctx, arrayOf(Manifest.permission.READ_SMS))
    /** Роль SMS (RoleManager): getDefaultSmsPackage даёт null, если у приложения нет READ_CELL_BROADCASTS. */
    fun isDefault(ctx: Context) = Utils.isDefaultSmsApp(ctx)

    /** Входящее SMS → Inbox (обязанность SMS-приложения по умолчанию). */
    fun storeIncoming(ctx: Context, address: String, body: String, sentAt: Long, subId: Int) {
        if (!isDefault(ctx)) return
        try {
            ctx.contentResolver.insert(Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Sms.ADDRESS, address); put(Sms.BODY, body); put(Sms.DATE, System.currentTimeMillis())
                put(Sms.DATE_SENT, sentAt); put(Sms.READ, 0); put(Sms.SEEN, 0)
                if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
            })
        } catch (e: Exception) { Log.e(TAG, "SMS store failed: $e") }
    }

    /** Отправка с делением длинного текста; SMS-приложение по умолчанию само пишет в базу (Outbox → Sent/Failed). */
    fun send(ctx: Context, address: String, text: String): Boolean = try {
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val row = if (isDefault(ctx)) ctx.contentResolver.insert(Sms.Outbox.CONTENT_URI, ContentValues().apply {
            put(Sms.ADDRESS, address); put(Sms.BODY, text); put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1); put(Sms.SEEN, 1)
        }) else null
        val parts = sm.divideMessage(text)
        val base = ((row?.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis()) % 1_000_000).toInt() * 64   // строка базы + часть
        val sent = ArrayList(parts.indices.map { i ->
            PendingIntent.getBroadcast(ctx, base + i, Intent(ctx, HHSmsSentReceiver::class.java).setData(row),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        })
        sm.sendMultipartTextMessage(address, null, parts, sent, null)
        true
    } catch (e: Exception) {
        Log.e(TAG, "SMS send failed: $e")
        false
    }

    private val cols = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.READ)

    private fun toMessage(c: Cursor) = Message(MOBILE, "tel:" + (c.getString(2) ?: ""), c.getString(3) ?: "", c.getLong(4),
        when (c.getInt(5)) {
            Sms.MESSAGE_TYPE_INBOX -> MESSAGE_DOWN
            Sms.MESSAGE_TYPE_FAILED -> MESSAGE_UP_FAIL
            Sms.MESSAGE_TYPE_OUTBOX, Sms.MESSAGE_TYPE_QUEUED -> MESSAGE_UP_WAIT
            else -> MESSAGE_UP
        }, 0, "", c.getInt(6) == 0 && c.getInt(5) == Sms.MESSAGE_TYPE_INBOX, smsId = c.getLong(0))

    // ---- MMS (спека 0.8.0, п. 2) ----
    /** Участники групповых бесед: thread_id → адреса (без своего номера). */
    private val groups = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()
    fun isGroup(peer: String) = peer.startsWith("mms:")
    fun groupAddresses(peer: String): List<String> = peer.removePrefix("mms:").toLongOrNull()?.let { groups[it] } ?: emptyList()

    private fun mmsAddr(ctx: Context, id: Long): Pair<String?, List<String>> {
        val rows = mutableListOf<Pair<String, Int>>()
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) rows += (c.getString(0) ?: "") to c.getInt(1)
        }
        return HHUiLogic.mmsAddresses(rows, BaresipService.mobileNumber)
    }

    /** Текст и картинки MMS: text/plain — текст, image/… — content://mms/part/<id>. */
    private fun mmsParts(ctx: Context, id: Long): Pair<String, List<String>> {
        val text = StringBuilder(); val images = mutableListOf<String>()
        ctx.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getString(1) ?: ""
                when {
                    ct == "text/plain" -> c.getString(2)?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
                    ct.startsWith("image/") -> images += "content://mms/part/" + c.getLong(0)
                }
            }
        }
        return text.toString() to images
    }

    /** MMS → сообщение чата; peer — собеседник или "mms:<thread>" для групповой беседы. */
    private fun mmsMessage(ctx: Context, id: Long, thread: Long, dateSec: Long, box: Int, read: Int): Message {
        val (from, to) = mmsAddr(ctx, id)
        val people = (listOfNotNull(from) + to).distinctBy { HHUiLogic.peerKey(it) }
        if (people.size > 1) groups[thread] = people
        val peer = if (people.size > 1) "mms:$thread" else "tel:" + (people.firstOrNull() ?: "")
        val (text, images) = mmsParts(ctx, id)
        val dir = when (box) { 1 -> MESSAGE_DOWN; 4 -> MESSAGE_UP_WAIT; 5 -> MESSAGE_UP_FAIL; else -> MESSAGE_UP }
        // во входящем групповом — подпись отправителя
        val body = if (people.size > 1 && box == 1 && from != null) "${hhName("tel:$from")}: $text" else text
        return Message(MOBILE, peer, body.ifEmpty { if (images.isEmpty()) "[MMS]" else "" }, dateSec * 1000, dir, 0, "",
            box == 1 && read == 0, images, smsId = -id)   // smsId < 0 — MMS (удаление — content://mms/<id>)
    }

    private fun mmsQuery(ctx: Context, sel: String?, args: Array<String>?): List<Message> = try {
        ctx.contentResolver.query(Uri.parse("content://mms"), arrayOf("_id", "thread_id", "date", "msg_box", "read"),
            sel, args, "date DESC")?.use { c ->
            buildList { while (c.moveToNext()) add(mmsMessage(ctx, c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4)) to c.getLong(1)) }
                .map { it.first.also { m -> m.threadId = it.second } }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "MMS read failed: $e")
        emptyList()
    }

    /** Последнее сообщение каждой беседы (по THREAD_ID); new — в беседе есть непрочитанные. */
    fun threads(ctx: Context): List<Message> {
        if (!canRead(ctx)) return emptyList()
        val seen = LinkedHashMap<Long, Message>()
        try {
            ctx.contentResolver.query(Sms.CONTENT_URI, cols, null, null, "${Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(1)
                    val m = toMessage(c)
                    val have = seen[t]
                    if (have == null) seen[t] = m else if (m.new) have.new = true
                }
            }
        } catch (e: Exception) { Log.e(TAG, "SMS threads failed: $e") }
        // MMS: последняя в беседе, если новее SMS; групповые беседы — свой peer
        val lastMms = LinkedHashMap<Long, Message>()
        for (m in mmsQuery(ctx, null, null)) {
            val have = lastMms[m.threadId]
            if (have == null) lastMms[m.threadId] = m else if (m.new) have.new = true
        }
        for ((t, m) in lastMms) {
            val s = seen[t]
            if (s == null || m.timeStamp > s.timeStamp || isGroup(m.peerUri)) {
                if (s?.new == true) m.new = true
                seen[t] = if (isGroup(m.peerUri) || s == null) m else m.copyPeer(s.peerUri)
            } else if (m.new) s.new = true
        }
        // +374… и 0… — разные THREAD_ID в системе, но одна беседа в HHPhone
        val out = mutableListOf<Message>()
        for (m in seen.values.sortedByDescending { it.timeStamp }) {
            val same = if (isGroup(m.peerUri)) null else out.firstOrNull { HHUiLogic.samePhone(it.peerUri, m.peerUri) }
            if (same == null) out += m else if (m.new) same.new = true
        }
        return out
    }

    private fun threadIds(ctx: Context, peerUri: String): List<Long> {
        val ids = mutableSetOf<Long>()
        // сужаем в SQL (последние 8 цифр или буквенный адрес), точное сравнение — samePhone
        val key = HHUiLogic.peerKey(peerUri)
        val digits = key.filter { it.isDigit() }
        val (sel, arg) = if (key.any { it.isLetter() } || digits.isEmpty()) "${Sms.ADDRESS} = ? COLLATE NOCASE" to key
            else "${Sms.ADDRESS} LIKE ?" to "%" + digits.takeLast(8).toList().joinToString("%")
        ctx.contentResolver.query(Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.ADDRESS), sel, arrayOf(arg), null)?.use { c ->
            while (c.moveToNext()) if (HHUiLogic.samePhone(c.getString(1) ?: "", peerUri)) ids += c.getLong(0)
        }
        // беседы с этим номером только из MMS (не групповые)
        try {
            ctx.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
                val addrIds = buildList { while (c.moveToNext()) if (HHUiLogic.samePhone(c.getString(1) ?: "", peerUri)) add(c.getLong(0).toString()) }
                if (addrIds.isNotEmpty())
                    ctx.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"),
                        null, null, null)?.use { t ->
                        while (t.moveToNext()) if ((t.getString(1) ?: "").trim().split(" ").let { r -> r.size == 1 && r[0] in addrIds })
                            ids += t.getLong(0)
                    }
            }
        } catch (e: Exception) { Log.e(TAG, "MMS threads failed: $e") }
        return ids.toList()
    }

    /** Сообщения беседы (новые сверху); +374… и 0… — одна беседа. */
    fun thread(ctx: Context, peerUri: String): List<Message> {
        if (!canRead(ctx)) return emptyList()
        return try {
            val ids = if (isGroup(peerUri)) listOfNotNull(peerUri.removePrefix("mms:").toLongOrNull()) else threadIds(ctx, peerUri)
            if (ids.isEmpty()) return emptyList()
            val inIds = ids.joinToString(",")
            val sms = ctx.contentResolver.query(Sms.CONTENT_URI, cols, "${Sms.THREAD_ID} IN ($inIds)", null,
                "${Sms.DATE} DESC")?.use { c -> buildList { while (c.moveToNext()) add(toMessage(c).copyPeer(peerUri)) } }
                ?: emptyList()
            (sms + mmsQuery(ctx, "thread_id IN ($inIds)", null).map { it.copyPeer(peerUri) }).sortedByDescending { it.timeStamp }
        } catch (e: Exception) {
            Log.e(TAG, "SMS thread failed: $e")
            emptyList()
        }
    }

    fun markRead(ctx: Context, peerUri: String) {
        if (!isDefault(ctx)) return
        try {
            val ids = if (isGroup(peerUri)) listOfNotNull(peerUri.removePrefix("mms:").toLongOrNull()) else threadIds(ctx, peerUri)
            if (ids.isEmpty()) return
            ctx.contentResolver.update(Sms.CONTENT_URI, ContentValues().apply { put(Sms.READ, 1); put(Sms.SEEN, 1) },
                "${Sms.THREAD_ID} IN (${ids.joinToString(",")}) AND ${Sms.READ} = 0", null)
            ctx.contentResolver.update(Uri.parse("content://mms"), ContentValues().apply { put("read", 1); put("seen", 1) },
                "thread_id IN (${ids.joinToString(",")}) AND read = 0", null)
        } catch (e: Exception) { Log.e(TAG, "SMS mark read failed: $e") }
    }

    fun delete(ctx: Context, id: Long) {
        try {   // id < 0 — MMS
            if (id < 0) ctx.contentResolver.delete(Uri.parse("content://mms/${-id}"), null, null)
            else ctx.contentResolver.delete(ContentUris.withAppendedId(Sms.CONTENT_URI, id), null, null)
        }
        catch (e: Exception) { Log.e(TAG, "SMS delete failed: $e") }
    }

    /** Один раз: SIM-сообщения HHPhone, которых нет в системе, переносятся туда. */
    @Synchronized fun migrate(ctx: Context) {   // вкладка зовёт на каждый bump — второй вызов ждёт и видит флаг
        val p = ctx.getSharedPreferences("hh_ui", Context.MODE_PRIVATE)
        if (p.getBoolean("sms_imported", false) || !HHHistory.restored || !isDefault(ctx) || !canRead(ctx)) return
        val mine = synchronized(BaresipService.messagesLock) { BaresipService.messages.filter { it.aor == MOBILE } }
        val sysByPeer = HashMap<String, List<Message>>()
        for (m in mine) {
            if (m.message.isEmpty() || m.images.isNotEmpty()) continue   // MMS остаются в HHPhone
            if (m.direction == MESSAGE_UP_FAIL || m.direction == MESSAGE_UP_WAIT) continue   // не ушедшие — не «отправленные»
            val sys = sysByPeer.getOrPut(m.peerUri) { thread(ctx, m.peerUri) }
            // у входящих HHPhone хранит время SMSC, система — время приёма: окно ±5 мин, как на экране чата
            if (sys.any { it.message == m.message && kotlin.math.abs(it.timeStamp - m.timeStamp) < 300_000 }) continue
            try {
                ctx.contentResolver.insert(if (m.direction == MESSAGE_DOWN) Sms.Inbox.CONTENT_URI else Sms.Sent.CONTENT_URI,
                    ContentValues().apply {
                        put(Sms.ADDRESS, HHUiLogic.peerKey(m.peerUri)); put(Sms.BODY, m.message); put(Sms.DATE, m.timeStamp)
                        put(Sms.READ, if (m.new) 0 else 1); put(Sms.SEEN, 1)
                    })
            } catch (e: Exception) { Log.e(TAG, "SMS migrate failed: $e") }
        }
        p.edit().putBoolean("sms_imported", true).apply()
    }

    /** Подписка на изменения базы SMS; возвращает отписку. */
    fun observe(ctx: Context, onChange: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) = onChange() }
        return try {
            ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, o)   // SMS и MMS
            val off: () -> Unit = { ctx.contentResolver.unregisterContentObserver(o) }
            off
        } catch (_: SecurityException) { {} }
    }

    private const val TAG = "HHSms"
}

/** Итог отправки части SMS: SENT или FAILED в системной базе. */
class HHSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val row = intent.data ?: return
        val type = if (resultCode == Activity.RESULT_OK) Sms.MESSAGE_TYPE_SENT else Sms.MESSAGE_TYPE_FAILED
        try {   // неудача одной части не перетирается успехом следующей
            ctx.contentResolver.update(row, ContentValues().apply { put(Sms.TYPE, type) },
                "${Sms.TYPE} != ${Sms.MESSAGE_TYPE_FAILED}", null)
        } catch (_: Exception) {}
    }
}
