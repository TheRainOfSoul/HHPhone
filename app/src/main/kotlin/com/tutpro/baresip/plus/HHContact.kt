package com.tutpro.baresip.plus

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.RingtoneManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Тип строки «SIP-адрес» среди номеров карточки (у Phone.TYPE таких значений нет). */
private const val SIP_TYPE = -2

/** Карточка: key — a<id> (контакт телефона), b<id> (baresip), n<номер> (новый). */
data class HHCard(val key: String, val name: String, val nums: List<HHNum>, val photo: Uri? = null, val bitmap: Bitmap? = null,
                  val favorite: Boolean = false, val rawId: Long = 0, val nameRowId: Long = 0, val contactId: Long = 0,
                  val groups: Map<Long, List<Long>> = emptyMap(),   // номер на экране → все его _ID в разных аккаунтах
                  val readOnly: Set<Long> = emptySet(),             // _ID строк аккаунтов только для чтения (мессенджеры, Exchange)
                  val editable: Boolean = true)

/** Чтение и запись контакта: Android — по _ID строк Data одной транзакцией; baresip — через Contact. */
object HHContactStore {

    fun load(ctx: Context, key: String): HHCard? = try {
        when (key.firstOrNull()) {
            'a' -> loadAndroid(ctx, key.drop(1).toLong())
            'b' -> Contact.baresipContact(key.drop(1).toLong())?.let { c ->
                HHCard(key, c.name, c.uris.mapIndexed { i, u ->
                    if (u.uri.startsWith("sip:")) HHNum(i + 1L, u.uri, SIP_TYPE)
                    else HHNum(i + 1L, u.uri.removePrefix("tel:"), Contact.stringToType(u.label), u.label)
                }, bitmap = c.avatarImage, favorite = c.favorite)
            }
            'n' -> key.drop(1).let { v -> HHCard(key, "", listOf(if (v.startsWith("sip:")) HHNum(-1, v, SIP_TYPE) else HHNum(-1, v, Phone.TYPE_MOBILE))) }
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "load $key failed: $e")
        null
    }

    /** Аккаунты, куда приложению можно писать: память телефона или синхронизация с выгрузкой (Google). */
    private fun writableTypes(): Set<String> = android.content.ContentResolver.getSyncAdapterTypes()
        .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }.map { it.accountType }.toSet()
    private val localTypes = setOf("vnd.sec.contact.phone", "com.android.localphone", "com.android.local")

    private fun loadAndroid(ctx: Context, id: Long): HHCard? {
        val cr = ctx.contentResolver
        var name = ""; var star = false; var photo: Uri? = null; var nameRaw = 0L
        cr.query(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.NAME_RAW_CONTACT_ID), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            name = c.getString(0) ?: ""; star = c.getInt(1) == 1; photo = c.getString(2)?.let { Uri.parse(it) }; nameRaw = c.getLong(3)
        } ?: return null
        val upload = writableTypes()
        val writable = LinkedHashMap<Long, Boolean>()
        cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val t = c.getString(1)
                writable[c.getLong(0)] = t.isNullOrEmpty() || t in localTypes || t in upload
            }
        }
        // основной сырой контакт — тот, чьё имя показывается, если в него можно писать, иначе первый записываемый
        val rawId = nameRaw.takeIf { writable[it] == true } ?: writable.entries.firstOrNull { it.value }?.key ?: 0L
        val rows = mutableListOf<Pair<HHNum, Long>>()   // номер и его сырой контакт
        var nameRow = 0L
        cr.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getLong(1)
                when (c.getString(2)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (raw == rawId) nameRow = c.getLong(0)
                    Phone.CONTENT_ITEM_TYPE -> rows += HHNum(c.getLong(0), c.getString(3) ?: "", c.getInt(4), c.getString(5) ?: "") to raw
                    SipAddress.CONTENT_ITEM_TYPE -> rows += HHNum(c.getLong(0), "sip:" + (c.getString(3) ?: ""), SIP_TYPE) to raw
                }
            }
        }
        val rawOf = rows.associate { it.first.id to it.second }
        val merged = HHUiLogic.mergeSame(rows.map { it.first }) { rawOf[it.id] == rawId }
        return HHCard("a$id", name, merged.map { it.first }, photo, null, star, rawId, nameRow, id,
            groups = merged.associate { it.first.id to it.second },
            readOnly = rows.filter { writable[it.second] != true }.map { it.first.id }.toSet(),
            editable = rawId != 0L)
    }

    /** Сохранение; возвращает ключ сохранённой карточки или null (ошибка). photo — новое фото, removePhoto — удалить. */
    fun save(ctx: Context, before: HHCard, after: HHCard, photo: Bitmap?, removePhoto: Boolean): String? = try {
        if (before.key.startsWith("b") || (before.key.startsWith("n") && BaresipService.contactsMode == "baresip"))
            saveBaresip(ctx, before, after, photo, removePhoto)
        else saveAndroid(ctx, before, after, photo, removePhoto)
    } catch (e: Exception) {
        Log.e(TAG, "save failed: $e")
        null
    }

    private fun saveAndroid(ctx: Context, before: HHCard, after: HHCard, photo: Bitmap?, removePhoto: Boolean): String? {
        val cr = ctx.contentResolver
        val new = before.key.startsWith("n")
        if (!new && !before.editable) return null   // только аккаунты «для чтения» — правка откатилась бы синхронизацией
        val ops = ArrayList<ContentProviderOperation>()
        fun data() = ContentProviderOperation.newInsert(Data.CONTENT_URI).let {
            if (new) it.withValueBackReference(Data.RAW_CONTACT_ID, 0) else it.withValue(Data.RAW_CONTACT_ID, before.rawId)
        }
        if (new) ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build()
        if (new || (before.nameRowId == 0L && after.name != before.name))
            ops += data().withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, after.name).build()
        else if (after.name != before.name)   // части имени очищаем — провайдер разберёт DISPLAY_NAME сам
            ops += ContentProviderOperation.newUpdate(Data.CONTENT_URI).withSelection("${Data._ID} = ?", arrayOf(before.nameRowId.toString()))
                .withValue(StructuredName.DISPLAY_NAME, after.name).withValue(StructuredName.GIVEN_NAME, null)
                .withValue(StructuredName.FAMILY_NAME, null).withValue(StructuredName.MIDDLE_NAME, null)
                .withValue(StructuredName.PREFIX, null).withValue(StructuredName.SUFFIX, null).build()
        // номер на экране = все его копии в записываемых аккаунтах
        fun ids(id: Long) = (before.groups[id] ?: listOf(id)).filter { it !in before.readOnly }
        for (op in HHUiLogic.numberOps(if (new) emptyList() else before.nums, after.nums)) when (op) {
            is HHNumOp.Delete -> ids(op.id).forEach { ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data._ID} = ?", arrayOf(it.toString())).build() }
            is HHNumOp.Update -> ids(op.id).forEach { ops += ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("${Data._ID} = ?", arrayOf(it.toString())).withValues(numValues(op.num, false)).build() }
            is HHNumOp.Insert -> ops += data().withValues(numValues(op.num, true)).build()
        }
        val res = if (ops.isEmpty()) emptyArray() else cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = if (new) ContentUris.parseId(res[0].uri!!) else before.rawId
        try {   // фото — после записи контакта; его сбой не делает контакт «несохранённым» (иначе повторное «Сохранить» дублирует)
            if (photo != null) writePhoto(ctx, rawId, photo)
            else if (removePhoto && !new)
                cr.delete(Data.CONTENT_URI, "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                    arrayOf(before.contactId.toString(), Photo.CONTENT_ITEM_TYPE))
        } catch (e: Exception) { Log.e(TAG, "photo failed: $e") }
        // правка могла пересобрать агрегат — id контакта берём заново
        val contactId = cr.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            arrayOf(RawContacts.CONTACT_ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            ?.takeIf { it != 0L } ?: before.contactId
        Contact.loadAndroidContacts(ctx)
        Contact.contactsUpdate()
        return "a$contactId"
    }

    private fun numValues(n: HHNum, insert: Boolean) = ContentValues().apply {
        if (n.type == SIP_TYPE) {
            if (insert) put(Data.MIMETYPE, SipAddress.CONTENT_ITEM_TYPE)
            put(SipAddress.SIP_ADDRESS, n.number.trim().removePrefix("sip:"))
        } else {
            if (insert) put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            put(Phone.NUMBER, n.number.trim()); put(Phone.TYPE, n.type)
            put(Phone.LABEL, if (n.type == Phone.TYPE_CUSTOM) n.label else null)
        }
    }

    /** Полноразмерное фото через DisplayPhoto — провайдер сам сделает миниатюру. */
    private fun writePhoto(ctx: Context, rawId: Long, bmp: Bitmap) {
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        ctx.contentResolver.openAssetFileDescriptor(uri, "rw")?.use { fd ->
            fd.createOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        // своё фото — главное в агрегате (иначе может показываться фото из WhatsApp/Google)
        ctx.contentResolver.update(Data.CONTENT_URI, ContentValues().apply { put(Data.IS_PRIMARY, 1); put(Data.IS_SUPER_PRIMARY, 1) },
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE))
    }

    private fun saveBaresip(ctx: Context, before: HHCard, after: HHCard, photo: Bitmap?, removePhoto: Boolean): String? {
        val old = if (before.key.startsWith("b")) Contact.baresipContact(before.key.drop(1).toLong()) else null
        val id = old?.id ?: System.currentTimeMillis()
        val uris = ArrayList(after.nums.filter { it.number.isNotBlank() }.map { n ->
            val digits = n.number.filter { it.isDigit() || it in "+*#" }
            if (n.type == SIP_TYPE || n.number.startsWith("sip:") || digits.none { it.isDigit() })
                Contact.ContactUri(n.number.trim().let { if (it.startsWith("sip:")) it else "sip:$it" })
            else Contact.ContactUri("tel:$digits", Contact.typeLabel(n.type, n.label))
        })
        val c = Contact.BaresipContact(after.name, uris, old?.email ?: "", old?.color ?: Utils.randomColor(), id, old?.favorite ?: false)
        val file = File(BaresipService.filesPath, "$id.png")
        when {
            photo != null -> {   // кружок не больше 140 dp: 256 px хватает, 720 px держали бы память на каждый контакт
                val small = if (photo.width > 256) Bitmap.createScaledBitmap(photo, 256, 256 * photo.height / photo.width, true) else photo
                file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }; c.avatarImage = small
            }
            removePhoto -> file.delete()
            else -> c.avatarImage = old?.avatarImage
        }
        if (old == null) Contact.addBaresipContact(c) else Contact.updateBaresipContact(id, c)
        return "b$id"
    }

    /** Удалить контакт: телефона — весь агрегат (синхронизация разнесёт по аккаунтам), baresip — запись и фото. */
    fun delete(ctx: Context, card: HHCard): Boolean = try {
        when {
            card.key.startsWith("a") -> {
                val n = ctx.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, card.contactId), null, null)
                Contact.loadAndroidContacts(ctx)
                Contact.contactsUpdate()
                n > 0
            }
            card.key.startsWith("b") -> Contact.baresipContact(card.key.drop(1).toLong())?.let {
                File(BaresipService.filesPath, "${it.id}.png").delete()
                Contact.removeBaresipContact(it)
                true
            } ?: false
            else -> false
        }
    } catch (e: Exception) {
        Log.e(TAG, "delete failed: $e")
        false
    }

    private fun rawIds(ctx: Context, contactId: Long): List<Long> =
        ctx.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()), null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        } ?: emptyList()

    /**
     * Объединить с другим контактом телефона: исключения агрегации KEEP_TOGETHER (данные аккаунтов не меняются —
     * поэтому и «только для чтения» можно). Возвращает ключ объединённой карточки.
     */
    fun merge(ctx: Context, card: HHCard, otherContactId: Long): String? = try {
        val mine = rawIds(ctx, card.contactId)
        val other = rawIds(ctx, otherContactId)
        if (mine.isEmpty() || other.isEmpty()) null else {
            val ops = ArrayList(other.map { r ->
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, mine[0])
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, r).build()
            })
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val id = ctx.contentResolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, mine[0]),
                arrayOf(RawContacts.CONTACT_ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
            Contact.loadAndroidContacts(ctx)
            Contact.contactsUpdate()
            if (id != 0L) "a$id" else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "merge failed: $e")
        null
    }

    fun setFavorite(ctx: Context, card: HHCard, on: Boolean) {
        if (card.key.startsWith("a")) {
            ctx.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, card.contactId),
                ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) }, null, null)
            Contact.loadAndroidContacts(ctx)
            Contact.contactsUpdate()
        } else if (card.key.startsWith("b"))
            Contact.baresipContact(card.key.drop(1).toLong())?.let { if (it.favorite != on) Contact.toggleFavorite(ctx, it) }
    }

    /** Фото из галереи: не больше 720 px, по центру квадратом. */
    fun decodePhoto(ctx: Context, uri: Uri): Bitmap? = try {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val k = maxOf(info.size.width, info.size.height) / 720f
            if (k > 1) d.setTargetSize((info.size.width / k).toInt(), (info.size.height / k).toInt())
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val s = minOf(bmp.width, bmp.height)
        Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
    } catch (e: Exception) {
        Log.e(TAG, "photo decode failed: $e")
        null
    }

    private const val TAG = "HHContact"
}

/** Системная блокировка номеров (BlockedNumberContract) — работает, пока HHPhone звонилка или SMS-приложение. */
object HHBlock {
    /** canCurrentUserBlockNumbers не проверяет роль — без роли звонилки/SMS запись упадёт SecurityException. */
    fun can(ctx: Context) = (Utils.isDefaultDialer(ctx) || Utils.isDefaultSmsApp(ctx)) &&
        try { BlockedNumberContract.canCurrentUserBlockNumbers(ctx) } catch (_: Exception) { false }

    fun isBlocked(ctx: Context, number: String) =
        number.isNotBlank() && try { BlockedNumberContract.isBlocked(ctx, number) } catch (_: Exception) { false }

    /** false — хотя бы один номер не удалось (раз)блокировать. */
    fun set(ctx: Context, numbers: List<String>, block: Boolean): Boolean {
        var ok = true
        for (n in numbers.filter { it.isNotBlank() }) try {
            if (block) ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, n) })
            else BlockedNumberContract.unblock(ctx, n)
        } catch (e: Exception) { Log.e("HHBlock", "block $n failed: $e"); ok = false }
        return ok
    }
}

fun NavGraphBuilder.hhContactRoute(navController: NavController, viewModel: ViewModel) {
    composable("hh_contact/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { b ->
        HHContactScreen(navController, viewModel, b.arguments?.getString("key") ?: "")
    }
}

private fun typeName(ctx: Context, n: HHNum): String =
    if (n.type == SIP_TYPE) "SIP" else Phone.getTypeLabel(ctx.resources, n.type, n.label).toString()

private val editTypes = listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_OTHER)

/** Карточка контакта: просмотр (номера с вызовом и SMS, избранное, блокировка) и правка (имя, номера, фото). */
@Composable
private fun HHContactScreen(navController: NavController, viewModel: ViewModel, key: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cardKey by remember { mutableStateOf(key) }
    var card by remember { mutableStateOf<HHCard?>(null) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(key.startsWith("n")) }
    var name by remember { mutableStateOf("") }
    val nums = remember { mutableStateListOf<HHNum>() }
    var newPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var canBlock by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tmpId by remember { mutableStateOf(-2L) }   // id новых строк правки: уникальные, ≤ 0 — «новый номер»
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var pickMerge by remember { mutableStateOf(false) }
    val deleteFailed = stringResource(R.string.hh_delete_failed)
    val blockFailed = stringResource(R.string.hh_block_failed)
    val readOnlyText = stringResource(R.string.hh_contact_readonly)
    val lines = hhLines()
    val saveFailed = stringResource(R.string.hh_save_failed)
    val nameRequired = stringResource(R.string.hh_name_required)
    fun startEdit(c: HHCard) {
        name = c.name; nums.clear(); nums.addAll(c.nums); if (nums.isEmpty()) nums += HHNum(tmpId--, "", Phone.TYPE_MOBILE)
        newPhoto = null; removePhoto = false
    }
    LaunchedEffect(cardKey, reload) {
        val c = withContext(Dispatchers.IO) { HHContactStore.load(ctx, cardKey) }
        card = c
        if (c == null) { navController.navigateUp(); return@LaunchedEffect }
        blocked = withContext(Dispatchers.IO) { c.nums.any { it.type != SIP_TYPE && HHBlock.isBlocked(ctx, HHUiLogic.peerKey(it.number)) } }
        canBlock = HHBlock.can(ctx) && c.nums.any { it.type != SIP_TYPE }
        if (editing) startEdit(c)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { HHContactStore.decodePhoto(ctx, uri) }?.let { newPhoto = it; removePhoto = false }
        }
    }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->   // A6
        if (res.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult   // отмена — не трогаем мелодию
        @Suppress("DEPRECATION")
        val uri = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
        val id = card?.contactId ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.update(ContactsContract.Contacts.CONTENT_URI,
                    ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, uri?.toString()) },
                    "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()))
            } catch (e: Exception) { Log.w("HHContact", "ringtone set failed: $e") }
        }
    }
    HHLightStatusBar()
    val c = card ?: return
    val onBar = MaterialTheme.colorScheme.onPrimary
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().background(hhHeader).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                IconButton(onClick = { if (c.key.startsWith("n")) navController.navigateUp() else editing = false }) {
                    Icon(Icons.Default.Close, stringResource(R.string.hh_cancel), tint = onBar)
                }
                Spacer(Modifier.weight(1f))
                IconButton(enabled = !saving, onClick = {
                    val after = c.copy(name = name.trim().ifEmpty { nums.firstOrNull { it.number.isNotBlank() }?.number?.trim() ?: "" },
                        nums = nums.toList())
                    if (after.name.isEmpty()) { Toast.makeText(ctx, nameRequired, Toast.LENGTH_SHORT).show(); return@IconButton }
                    saving = true
                    scope.launch {
                        val k = withContext(Dispatchers.IO) { HHContactStore.save(ctx, c, after, newPhoto, removePhoto) }
                        saving = false
                        if (k == null) Toast.makeText(ctx, saveFailed, Toast.LENGTH_LONG).show()
                        else { editing = false; cardKey = k; reload++ }
                    }
                }) { Icon(Icons.Default.Check, stringResource(R.string.hh_save), tint = onBar) }
            } else {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hh_back), tint = onBar)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { HHContactStore.setFavorite(ctx, c, !c.favorite) }; reload++ } }) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, stringResource(R.string.hh_favorite), tint = onBar)
                }
                IconButton(onClick = {
                    if (c.editable) { startEdit(c); editing = true } else Toast.makeText(ctx, readOnlyText, Toast.LENGTH_LONG).show()
                }) { Icon(Icons.Outlined.Edit, stringResource(R.string.hh_edit), tint = onBar) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.hh_menu), tint = onBar) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (c.key.startsWith("a"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.hh_merge_with)) }, onClick = { menu = false; pickMerge = true })
                        if (c.editable)   // аккаунт «только для чтения» синхронизация вернёт — не предлагаем
                            DropdownMenuItem(text = { Text(stringResource(R.string.hh_delete_contact), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; askDelete = true })
                    }
                }
            }
        }
        if (askDelete) AlertDialog(onDismissRequest = { askDelete = false },
            text = { Text(stringResource(R.string.hh_delete_contact_q, c.name)) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    scope.launch {
                        if (withContext(Dispatchers.IO) { HHContactStore.delete(ctx, c) }) navController.navigateUp()
                        else Toast.makeText(ctx, deleteFailed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.hh_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.hh_cancel)) } })
        if (pickMerge) HHMergePicker(c.contactId, onDismiss = { pickMerge = false }) { otherId ->
            pickMerge = false
            scope.launch {
                val k = withContext(Dispatchers.IO) { HHContactStore.merge(ctx, c, otherId) }
                if (k == null) Toast.makeText(ctx, saveFailed, Toast.LENGTH_LONG).show() else { cardKey = k; reload++ }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(120.dp).clip(CircleShape).clickable(enabled = editing) { picker.launch("image/*") }, contentAlignment = Alignment.Center) {
                val shownName = if (editing) name.ifBlank { "?" } else c.name
                when {
                    newPhoto != null -> Image(newPhoto!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    !removePhoto && c.bitmap != null -> Image(c.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    !removePhoto && c.photo != null -> AsyncImage(c.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> HHAvatar(shownName, null, 120.dp, null)
                }
                if (editing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CameraAlt, stringResource(R.string.hh_photo_pick), tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            if (editing && (newPhoto != null || (!removePhoto && (c.photo != null || c.bitmap != null))))
                TextButton(onClick = { newPhoto = null; removePhoto = true }) { Text(stringResource(R.string.hh_photo_remove)) }
            Spacer(Modifier.height(12.dp))
            if (editing) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true,
                    label = { Text(stringResource(R.string.hh_name)) })
                Spacer(Modifier.height(8.dp))
                nums.toList().forEach { n ->   // ключ — id строки: состояние строки не «переезжает» после удаления соседней
                    androidx.compose.runtime.key(n.id) {
                        HHNumberEdit(n, onChange = { v -> nums.indexOfFirst { it.id == n.id }.let { if (it >= 0) nums[it] = v } },
                            onDelete = { nums.removeAll { it.id == n.id } })   // пропавший id = удаление
                    }
                }
                TextButton(onClick = { nums += HHNum(tmpId--, "", Phone.TYPE_MOBILE) }, Modifier.padding(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hh_add_number))
                }
            } else {
                Text(c.name, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp))
                if (blocked) Text(stringResource(R.string.hh_is_blocked), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                c.nums.filter { it.number.isNotBlank() }.forEach { n -> HHNumberRow(ctx, n, c.name, lines, navController, viewModel) }
                val notes = HHNotes.forPeers(ctx, c.nums.filter { it.number.isNotBlank() }
                    .map { if (it.type == SIP_TYPE) it.number else "tel:" + it.number })
                if (notes.isNotEmpty()) {
                    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
                    Text(stringResource(R.string.hh_call_notes), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    notes.forEach { (stop, text) ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Text(fmt.format(java.util.Date(stop)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (canBlock) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {   // SIP-адреса не блокируем: «100» с домена станции ≠ SIM-номер 100
                                HHBlock.set(ctx, c.nums.filter { it.type != SIP_TYPE }.map { HHUiLogic.peerKey(it.number) }, !blocked)
                            }
                            if (!ok) Toast.makeText(ctx, blockFailed, Toast.LENGTH_LONG).show()
                            reload++
                        }
                    }) {
                        Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (blocked) R.string.hh_unblock else R.string.hh_block), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (c.key.startsWith("a") && c.editable && c.contactId > 0) {   // A6: своя мелодия (контакт телефона)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val current = try {
                            ctx.contentResolver.query(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.contactId),
                                arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0)?.let(Uri::parse) else null
                            }
                        } catch (_: Exception) { null }
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                        }
                        try { ringtonePicker.launch(intent) } catch (_: Exception) {}
                    }) {
                        Text(stringResource(R.string.hh_contact_ringtone))
                    }
                }
            }
        }
    }
}

/** Номер в просмотре: тип, номер, звонок (выбор линии, если их несколько) и SMS. */
@Composable
private fun HHNumberRow(ctx: Context, n: HHNum, name: String, lines: List<HHLine>, navController: NavController, viewModel: ViewModel) {
    val peer = if (n.type == SIP_TYPE) n.number else "tel:" + n.number
    val targets = hhLineTargets(peer, lines)
    val videoTargets = hhLineTargets(peer, lines.filter { !it.mobile })   // видео — только через станцию (SIP), не SIM
    var choose by remember { mutableStateOf(false) }
    var chooseVideo by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(n.number, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(typeName(ctx, n), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { HHShortcuts.pin(ctx, name, peer) }) {
            Icon(Icons.Outlined.AddToHomeScreen, stringResource(R.string.hh_pin_to_home), tint = MaterialTheme.colorScheme.primary)
        }
        hhChatRoute(peer, "", lines)?.let { r ->
            IconButton(onClick = { navController.navigate(r) }) {
                Icon(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.hh_sms), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (videoTargets.isNotEmpty()) {
            Box {
                IconButton(onClick = {
                    if (videoTargets.size == 1) videoTargets[0].let { (_, aor, t) -> HHCalls.dial(ctx, viewModel, aor, t, video = true) } else chooseVideo = true
                }) {
                    Icon(Icons.Outlined.Videocam, stringResource(R.string.hh_video_call), tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = chooseVideo, onDismissRequest = { chooseVideo = false }) {
                    videoTargets.forEach { (label, aor, t) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { chooseVideo = false; HHCalls.dial(ctx, viewModel, aor, t, video = true) })
                    }
                }
            }
        }
        Box {
            IconButton(enabled = targets.isNotEmpty(), onClick = {
                if (targets.size == 1) targets[0].let { (_, aor, t) -> HHCalls.dial(ctx, viewModel, aor, t) } else choose = true
            }) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colorResource(R.color.colorTrafficGreen)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Call, stringResource(R.string.hh_call), tint = Color.White)
                }
            }
            DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                targets.forEach { (label, aor, t) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { choose = false; HHCalls.dial(ctx, viewModel, aor, t) })
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Номер в правке: поле, тип (выпадающий список), удалить. Пустой номер при сохранении удаляется. */
@Composable
private fun HHNumberEdit(n: HHNum, onChange: (HHNum) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(n.number, { onChange(n.copy(number = it)) }, Modifier.weight(1f), singleLine = true,
            label = { Text(stringResource(R.string.hh_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = if (n.type == SIP_TYPE) KeyboardType.Email else KeyboardType.Phone))
        Box {
            TextButton(onClick = { if (n.type != SIP_TYPE) menu = true }, Modifier.width(116.dp)) {
                Text(typeName(ctx, n), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                editTypes.forEach { t ->
                    DropdownMenuItem(text = { Text(Phone.getTypeLabel(ctx.resources, t, "").toString()) },
                        onClick = { menu = false; onChange(n.copy(type = t, label = "")) })
                }
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.hh_delete_number)) }
    }
}

/** Выбор контакта телефона для объединения: поиск по имени и номеру. */
@Composable
private fun HHMergePicker(selfId: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { BaresipService.contacts.filterIsInstance<Contact.AndroidContact>().filter { it.id != selfId } }
    val list = all.filter { c -> q.isBlank() || c.name.contains(q, true) || c.uris.any { HHUiLogic.peerKey(it.uri).contains(q) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.hh_merge_with)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.hh_search)) })
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                    items(list.size) { i ->
                        val c = list[i]
                        Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HHAvatar(c.name, null, 36.dp, c)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name, color = MaterialTheme.colorScheme.onSurface)
                                c.uris.firstOrNull()?.let { Text(HHUiLogic.peerKey(it.uri), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hh_cancel)) } })
}
