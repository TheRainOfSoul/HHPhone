package com.tutpro.baresip.plus

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Push от HHPBX (спека 2026-10-13): будит HHPhone к входящему звонку, держит FCM-токен станций актуальным. */
class HHPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {   // уже в фоновом потоке
        HHPush.token = token
        if (BaresipService.isNativeReady) HHPush.sync()   // иначе — при старте сервиса (hhPushStart)
    }
    override fun onMessageReceived(message: RemoteMessage) {}
}
