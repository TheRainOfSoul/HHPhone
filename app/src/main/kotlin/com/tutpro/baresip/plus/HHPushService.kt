package com.tutpro.baresip.plus

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Push от HHPBX (спека 2026-10-13): будит HHPhone к входящему звонку, держит FCM-токен станций актуальным. */
class HHPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {   // уже в фоновом потоке
        HHPush.token = token
        if (BaresipService.isNativeReady) HHPush.sync()   // иначе — при старте сервиса (hhPushStart)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "call" && message.data["t"] != "msg") return   // звонок или сообщение станции (HHPBX 1.12.0)
        HHPush.touch()
        if (BaresipService.isServiceRunning) {
            // уже на связи — перерегистрироваться: станция ждёт свежую регистрацию (старая могла остаться от убитого процесса)
            if (BaresipService.isNativeReady) android.os.Handler(android.os.Looper.getMainLooper()).post {
                BaresipService.uas.value.filter { HHPush.isPush(it.account.aor) }.forEach { it.reRegister() }
            }
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BaresipService::class.java).setAction("Start"))
    }
}
