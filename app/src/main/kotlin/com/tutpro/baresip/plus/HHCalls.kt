package com.tutpro.baresip.plus

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff

/** Действия со звонками для экранов HHPhone 0.5.0 — поверх функций baresip (MainScreen.kt, Call.kt). */
object HHCalls {
    /**
     * Позвонить через учётку aor (SIM — sip:mobile@pstn). guard — не звонить второй раз, пока предыдущий вызов не начался
     * (двойное нажатие). Ошибки baresip показывает своим окном (showAlert). false — не звонили.
     */
    fun dial(ctx: Context, viewModel: ViewModel, aor: String, number: String, onHoldCallp: Long = 0L, guard: Boolean = true): Boolean {
        if (number.isBlank() || (guard && !viewModel.dialerState.callButtonsEnabled.value)) return false
        viewModel.updateSelectedAor(aor)
        viewModel.dialerState.showCallConferenceButton.value = false
        makeCall(ctx, viewModel, number.trim(), viewModel.dialerState, false, onHoldCallp)
        return true
    }

    fun answer(ctx: Context, call: Call) = answer(ctx, call, false)

    fun reject(call: Call) = call.reject()

    fun hangup(call: Call) {
        call.terminated.value = true
        call.hangup(487, "Request Terminated")
    }

    /** hold/resume Call сами ведут onhold; resume ставит остальные звонки на удержание. */
    fun toggleHold(call: Call) {
        if (if (call.onhold) call.resume() else call.hold()) call.callOnHold.value = call.onhold
    }

    fun toggleMute(viewModel: ViewModel) {
        val mute = !BaresipService.isMicMuted
        BaresipService.setMicMute(mute)
        viewModel.updateMicIcon(if (mute) Icons.Filled.MicOff else Icons.Filled.Mic)
    }

    fun toggleSpeaker() = BaresipService.instance?.toggleSpeakerphone()

    fun dtmf(call: Call, c: Char) { call.sendDigit(c) }

    fun canTransfer(call: Call) = call !is Call.ExternalCall

    /** Слепой перевод (REFER) именно показанного звонка; SIM — недоступен. */
    fun transfer(ctx: Context, call: Call, number: String) {
        if (!canTransfer(call) || number.isBlank()) return
        val acc = call.ua.account
        val n = number.trim()
        val uri = when {
            n.startsWith("sip:") -> n
            Utils.isTelNumber(n) -> Utils.telToSip("tel:$n", acc)
            else -> Utils.uriComplete(n, acc.aor)
        }
        call.hold()
        if (!Utils.checkUri(uri) || !call.transfer(uri))
            Toast.makeText(ctx, R.string.transfer_failed, Toast.LENGTH_LONG).show()
    }

    /** Второй звонок: текущий — на удержание, новый — через ту же учётку. */
    fun add(ctx: Context, viewModel: ViewModel, call: Call, number: String) {
        if (number.isBlank()) return
        call.hold()
        dial(ctx, viewModel, call.ua.account.aor, number, call.callp, guard = false)
    }

    /** Переключиться на звонок на удержании. */
    fun swap(call: Call) = toggleHold(call)
}
