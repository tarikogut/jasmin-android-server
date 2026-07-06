package com.smsapi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

data class IncomingSms(
    val id: Long,
    val phone: String,
    val message: String,
    val timestamp: Long,
    val contactName: String? = null,
    val forwardedToSmpp: Boolean = false
)

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group messages by originating address (same sender = concatenated SMS)
        val grouped = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "unknown" }

        for ((phone, smsMessages) in grouped) {
            // Sort by message body to reassemble concatenated SMS
            val sortedMessages = smsMessages.sortedBy { it.messageBody }
            val fullMessage = sortedMessages.joinToString("") { it.messageBody ?: "" }

            if (fullMessage.isBlank()) continue

            Log.d(TAG, "Incoming SMS from $phone: ${fullMessage.take(50)}...")

            // Get contact name
            val contactName = ContactsManager.getName(phone)

            // Store in database
            val db = SmsSender.db
            val id = db?.insertIncomingSms(phone, fullMessage, contactName) ?: continue

            // Forward to SMPP sessions (RECEIVER/TRANSCEIVER bound clients)
            val smppServer = SmsSender.smppServer
            if (smppServer != null && smppServer.isRunning) {
                smppServer.forwardIncomingSms(phone, fullMessage, contactName, id)
            }

            Log.d(TAG, "Incoming SMS processed: phone=$phone, contact=$contactName, id=$id")
        }
    }
}
