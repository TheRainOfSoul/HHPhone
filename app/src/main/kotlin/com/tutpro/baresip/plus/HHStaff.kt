package com.tutpro.baresip.plus

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Режим сотрудника: блокировка из QR и сессия админа до ухода приложения в фон. */
object HHStaff {
    private lateinit var prefs: SharedPreferences
    private val _restricted = MutableStateFlow(false)
    private val _adminSession = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    val adminSession: StateFlow<Boolean> = _adminSession.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("hh_staff", Context.MODE_PRIVATE)
        update()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = setAdminSession(false)
        })
    }

    fun state() = HHLockState(
        locked = prefs.getBoolean("locked", false),
        pinHash = prefs.getString("pinHash", "")!!,
        salt = prefs.getString("salt", "")!!,
        fails = prefs.getInt("fails", 0),
        lockoutUntil = prefs.getLong("lockoutUntil", 0),
    )

    fun save(s: HHLockState) {
        prefs.edit(commit = true) {
            putBoolean("locked", s.locked); putString("pinHash", s.pinHash); putString("salt", s.salt)
            putInt("fails", s.fails); putLong("lockoutUntil", s.lockoutUntil)
        }
        update()
    }

    fun setAdminSession(on: Boolean) {
        _adminSession.value = on
        update()
    }

    private fun update() {
        _restricted.value = prefs.getBoolean("locked", false) && !_adminSession.value
    }

    /** Для точек входа upstream: в режиме сотрудника показать подсказку и запретить действие. */
    fun blockedAndToast(ctx: Context): Boolean {
        if (!_restricted.value) return false
        Toast.makeText(ctx, ctx.getString(R.string.hh_admin_only), Toast.LENGTH_SHORT).show()
        return true
    }
}
