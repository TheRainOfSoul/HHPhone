package com.tutpro.baresip.plus

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class HHLockState(
    val locked: Boolean = false,
    val pinHash: String = "",
    val salt: String = "",
    val fails: Int = 0,
    val lockoutUntil: Long = 0,
)

sealed interface HHPinCheck {
    data object Ok : HHPinCheck
    data object Wrong : HHPinCheck
    data class LockedOut(val minutesLeft: Int) : HHPinCheck
}

object HHPin {
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 5 * 60_000L

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 100_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun withPin(state: HHLockState, pin: String): HHLockState {
        val salt = newSalt()
        return state.copy(pinHash = hash(pin, salt), salt = salt, fails = 0, lockoutUntil = 0)
    }

    fun check(state: HHLockState, pin: String, now: Long): Pair<HHPinCheck, HHLockState> {
        if (now < state.lockoutUntil)
            return HHPinCheck.LockedOut(minutesLeft(state.lockoutUntil - now)) to state
        if (hash(pin, state.salt) == state.pinHash)
            return HHPinCheck.Ok to state.copy(fails = 0, lockoutUntil = 0)
        val fails = state.fails + 1
        return if (fails >= MAX_FAILS)
            HHPinCheck.LockedOut(minutesLeft(LOCKOUT_MS)) to state.copy(fails = 0, lockoutUntil = now + LOCKOUT_MS)
        else
            HHPinCheck.Wrong to state.copy(fails = fails)
    }

    private fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).toInt()
}

/** Нужно ли спрашивать текущий PIN перед добавлением учётки из QR (спека, раздел 5). */
fun hhQrNeedsPin(state: HHLockState, adminSession: Boolean, qr: HHAccountQr): Boolean {
    if (!state.locked || adminSession) return false
    return qr.pin.isEmpty() || HHPin.hash(qr.pin, state.salt) != state.pinHash
}

/** После авторизации блокировка и PIN берутся из нового QR. */
fun hhApplyQrLock(state: HHLockState, qr: HHAccountQr): HHLockState =
    if (qr.lock) HHPin.withPin(state.copy(locked = true), qr.pin) else HHLockState()
