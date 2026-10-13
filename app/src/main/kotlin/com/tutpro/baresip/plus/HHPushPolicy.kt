package com.tutpro.baresip.plus

/** Держать ли связь со станциями; иначе HHPhone выходит, следующий звонок разбудит push (спека 2026-10-13, раздел 3). */
object HHPushPolicy {
    const val GRACE_MS = 60_000L
    fun keep(foreign: Boolean, visible: Boolean, inCall: Boolean, idleMs: Long): Boolean =
        foreign || visible || inCall || idleMs < GRACE_MS
}
