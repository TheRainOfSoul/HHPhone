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
    }
}
