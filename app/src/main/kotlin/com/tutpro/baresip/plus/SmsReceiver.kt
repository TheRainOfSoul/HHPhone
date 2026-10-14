package com.tutpro.baresip.plus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return

            val sender = messages[0].displayOriginatingAddress ?: return
            val body = messages.joinToString("") { it.displayMessageBody ?: "" }
            val timestamp = messages[0].timestampMillis

            Log.d(TAG, "Received SMS from $sender, starting service")
            // HHPhone: сначала в системную базу — сообщение не потеряется, даже если сервис не стартует
            HHSms.storeIncoming(context, sender, body, timestamp,
                intent.getIntExtra(android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1))

            val serviceIntent = Intent(context, BaresipService::class.java)
            serviceIntent.action = "Start"
            serviceIntent.putExtra("sender", "tel:$sender")
            serviceIntent.putExtra("body", body)
            serviceIntent.putExtra("time", timestamp)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
