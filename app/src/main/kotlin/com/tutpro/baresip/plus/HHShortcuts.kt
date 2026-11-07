package com.tutpro.baresip.plus

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri

/**
 * Ярлыки быстрого звонка (Android App Shortcuts): избранные контакты в меню при зажатии иконки (их можно перетащить
 * на рабочий стол) и «Вынести на рабочий стол» для любого номера. Ярлык открывает HHPhone с ACTION_CALL — MainActivity
 * сразу звонит. Иконка — фото контакта или буква в цветном круге, как в приложении.
 */
object HHShortcuts {
    private const val TAG = "HHShortcuts"
    private const val MAX = 4   // лаунчеры всё равно показывают 4–5 ярлыков

    /** Чистая часть: кого звать и как подписать ярлык. */
    data class Target(val id: String, val label: String, val callUri: String, val contact: Contact? = null)

    /** tel:/sip: для интента: sip — как есть; иначе tel: с номером без пробелов. */
    fun callUri(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("sip:") -> s
            s.startsWith("tel:") -> "tel:" + s.removePrefix("tel:").filterNot { it.isWhitespace() }
            else -> "tel:" + s.filterNot { it.isWhitespace() }
        }
    }

    /** Стабильный id ярлыка по адресу (не зависит от пробелов и схемы tel:). */
    fun idFor(raw: String): String = "hhcall:" + callUri(raw)

    /** Цели для меню при зажатии иконки: только избранные, первый непустой номер, без повторов, до [max]. */
    fun favoriteTargets(contacts: List<Contact>, max: Int = MAX): List<Target> =
        contacts.asSequence()
            .filter { it.favorite() }
            .mapNotNull { c ->
                c.uris().firstOrNull { it.uri.isNotBlank() }
                    ?.let { Target(idFor(it.uri), c.name().ifBlank { HHUiLogic.peerKey(it.uri) }, callUri(it.uri), c) }
            }
            .distinctBy { it.callUri }
            .take(max)
            .toList()

    /** Обновить динамические ярлыки по текущему избранному. Контекст — из живого сервиса. */
    fun refresh() {
        val ctx = BaresipService.instance?.applicationContext ?: return
        try {
            // фото есть, но не прочиталось (фоновый старт) — ярлык не трогаем, иначе закреплённый получит букву вместо фото
            ShortcutManagerCompat.setDynamicShortcuts(ctx, favoriteTargets(BaresipService.contacts).mapNotNull { t ->
                val c = t.contact
                val bmp = c?.let { photo(ctx, it) }
                if (bmp == null && c != null && hasPhoto(c)) null else build(ctx, t, bmp)
            })
        } catch (e: Exception) {
            Log.w(TAG, "setDynamicShortcuts failed: $e")
        }
    }

    /** Положить ярлык конкретного номера на рабочий стол (кнопка в карточке контакта); photo — фото этой карточки. */
    fun pin(ctx: Context, name: String, peer: String, photo: Bitmap?) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            HHUi.message.value = ctx.getString(R.string.hh_pin_unsupported)
            return
        }
        val label = name.ifBlank { HHUiLogic.peerKey(peer) }
        try {
            ShortcutManagerCompat.requestPinShortcut(ctx, build(ctx, Target(idFor(peer), label, callUri(peer)), photo), null)
        } catch (e: Exception) {
            Log.w(TAG, "requestPinShortcut failed: $e")
        }
    }

    private fun build(ctx: Context, t: Target, photo: Bitmap?): ShortcutInfoCompat {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_CALL)
            .setData(t.callUri.toUri())
        return ShortcutInfoCompat.Builder(ctx, t.id)
            .setShortLabel(t.label)
            .setLongLabel(t.label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(photo ?: letter(t.label)))
            .setIntent(intent)
            .build()
    }

    /** Буква в цветном круге (палитра и буква — как у HHAvatar). */
    private fun letter(name: String): Bitmap =
        Utils.createTextAvatar(
                HHUiLogic.avatarLetter(name),
                String.format("#%06X", 0xFFFFFF and hhAvatarColors[HHUiLogic.avatarColor(name)].toArgb())
            )

    private fun hasPhoto(c: Contact) = when (c) {
        is Contact.AndroidContact -> c.thumbnailUri != null
        is Contact.BaresipContact -> c.avatarImage != null
    }

    private fun photo(ctx: Context, c: Contact): Bitmap? = when (c) {
        is Contact.AndroidContact -> c.thumbnailUri?.let { HHContactStore.decodePhoto(ctx, it) }
        is Contact.BaresipContact -> c.avatarImage
    }
}
