package com.tutpro.baresip.plus

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff

/** Действия со звонками для экранов HHPhone 0.5.0 — поверх функций baresip (MainScreen.kt, Call.kt). */
object HHCalls {
    /** Позвонить через учётку aor (SIM — sip:mobile@pstn). Ошибки baresip показывает своим окном (showAlert). */
    fun dial(ctx: Context, viewModel: ViewModel, aor: String, number: String, onHoldCallp: Long = 0L) {
        if (number.isBlank()) return
        viewModel.updateSelectedAor(aor)
        viewModel.dialerState.showCallConferenceButton.value = false
        makeCall(ctx, viewModel, number.trim(), viewModel.dialerState, false, onHoldCallp)
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

    /** Слепой перевод (REFER); SIM — недоступен. */
    fun transfer(ctx: Context, viewModel: ViewModel, call: Call, number: String) {
        if (!canTransfer(call) || number.isBlank()) return
        transfer(ctx, viewModel, call.ua, number.trim(), false)
    }

    /** Второй звонок: текущий — на удержание, новый — через ту же учётку. */
    fun add(ctx: Context, viewModel: ViewModel, call: Call, number: String) {
        if (number.isBlank()) return
        call.hold()
        dial(ctx, viewModel, call.ua.account.aor, number, call.callp)
    }

    /** Переключиться на звонок на удержании. */
    fun swap(call: Call) = toggleHold(call)
}
