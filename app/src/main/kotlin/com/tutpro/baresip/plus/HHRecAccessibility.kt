package com.tutpro.baresip.plus

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Пустая служба специальных возможностей: Android 10+ глушит микрофон обычных приложений во время SIM-звонка,
 * а приложению с включённой службой оставляет (так пишут Cube ACR, ACR Phone). Ничего не читает и не делает.
 */
class HHRecAccessibility : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, HHRecAccessibility::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, true) }
        }

        /** WRITE_SECURE_SETTINGS выдан (adb shell pm grant) — служба включается только на время SIM-звонка, банки её не видят. */
        fun canToggle(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        fun set(ctx: Context, on: Boolean) {
            if (!canToggle(ctx) || enabled(ctx) == on) return
            val me = ComponentName(ctx, HHRecAccessibility::class.java).flattenToString()
            val cr = ctx.contentResolver
            val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && !it.equals(me, true) }
            try {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (if (on) others + me else others).joinToString(":"))
                if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (e: Exception) { Log.w("HHRecA11y", "toggle failed: $e") }
        }
    }
}
