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
        if (message.data["t"] != "call") return
        HHPush.touch()
        if (BaresipService.isServiceRunning) return   // уже на связи
        ContextCompat.startForegroundService(this, Intent(this, BaresipService::class.java).setAction("Start"))
    }
}
