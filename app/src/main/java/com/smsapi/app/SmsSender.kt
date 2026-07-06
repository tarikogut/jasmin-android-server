package com.smsapi.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SmsRequest(
    val phone: String,
    val message: String,
    val callbackUrl: String? = null
)

data class BulkSmsRequest(
    val phones: List<String>,
    val message: String,
    val delay: Long = 1000,
    val callbackUrl: String? = null
)

data class SmsResponse(
    val success: Boolean,
    val message: String,
    val reportId: String? = null,
    val pendingCount: Int = 0
)

data class BulkSmsResponse(
    val success: Boolean,
    val message: String,
    val totalSent: Int,
    val totalFailed: Int,
    val reportIds: List<String>
)

data class StatusResponse(
    val running: Boolean,
    val port: Int,
    val pendingCount: Int,
    val sentCount: Int,
    val failedCount: Int,
    val uptime: Long
)

data class ReportEntry(
    val reportId: String,
    val phone: String,
    val contactName: String? = null,
    val message: String,
    val status: String,
    val errorCode: Int? = null,
    val timestamp: Long,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val smppMessageId: String? = null,
    val smppSessionId: String? = null
)

object SmsSender {
    private const val TAG = "SmsSender"

    private val pendingSms = ConcurrentHashMap<Int, SmsRequest>()
    private val reportById = ConcurrentHashMap<String, ReportEntry>()
    private val reportByPhoneSentId = ConcurrentHashMap<Int, String>()

    // SMPP mapping: reportId -> (messageId, sessionId)
    private val smppMapping = ConcurrentHashMap<String, Pair<String, String>>()

    var sentCount = 0
        private set
    var failedCount = 0
        private set

    // Reference to SMPP server for DELIVER_SM callbacks
    var smppServer: SmppServer? = null

    fun sendSms(context: Context, request: SmsRequest): SmsResponse {
        val reportId = "RPT-${UUID.randomUUID().toString().take(8).uppercase()}"

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SmsResponse(false, "SEND_SMS permission not granted")
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val sentIntent = Intent("com.smsapi.app.SMS_SENT").apply {
                putExtra("report_id", reportId)
                setPackage(context.packageName)
            }
            val sentPI = PendingIntent.getBroadcast(
                context,
                reportId.hashCode(),
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val deliveryIntent = Intent("com.smsapi.app.SMS_DELIVERED").apply {
                putExtra("report_id", reportId)
                setPackage(context.packageName)
            }
            val deliveryPI = PendingIntent.getBroadcast(
                context,
                reportId.hashCode() + 1,
                deliveryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val contactName = ContactsManager.getName(request.phone)

            reportById[reportId] = ReportEntry(
                reportId = reportId,
                phone = request.phone,
                contactName = contactName,
                message = request.message,
                status = "PENDING",
                timestamp = System.currentTimeMillis()
            )

            val parts = smsManager.divideMessage(request.message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                val deliveryIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    sentIntents.add(sentPI)
                    deliveryIntents.add(deliveryPI)
                }
                smsManager.sendMultipartTextMessage(
                    request.phone, null, parts, sentIntents, deliveryIntents
                )
            } else {
                smsManager.sendTextMessage(
                    request.phone, null, request.message, sentPI, deliveryPI
                )
            }

            pendingSms[reportId.hashCode()] = request
            reportByPhoneSentId[reportId.hashCode()] = reportId
            Log.d(TAG, "SMS queued: reportId=$reportId, phone=${request.phone}")
            SmsResponse(true, "SMS queued for sending", reportId, pendingSms.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            val entry = reportById[reportId]
            if (entry != null) {
                reportById[reportId] = entry.copy(status = "FAILED", errorCode = -1)
            }
            failedCount++
            SmsResponse(false, "Failed: ${e.message}")
        }
    }

    fun getReport(reportId: String): ReportEntry? = reportById[reportId]

    fun getAllReports(limit: Int = 100): List<ReportEntry> {
        return reportById.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    fun setSmppMessageId(reportId: String, messageId: String, sessionId: String) {
        smppMapping[reportId] = Pair(messageId, sessionId)
        // Update report with SMPP info
        val entry = reportById[reportId]
        if (entry != null) {
            reportById[reportId] = entry.copy(
                smppMessageId = messageId,
                smppSessionId = sessionId
            )
        }
    }

    fun getSmppMapping(reportId: String): Pair<String, String>? = smppMapping[reportId]

    fun onSmsSent(reportId: String, resultCode: Int) {
        val entry = reportById[reportId] ?: return
        if (resultCode == -1) {
            sentCount++
            reportById[reportId] = entry.copy(
                status = "SENT",
                sentAt = System.currentTimeMillis()
            )
            Log.d(TAG, "SMS sent: reportId=$reportId, phone=${entry.phone}")

            // Notify SMPP if applicable
            smppServer?.sendDeliveryReport(reportId, entry.phone, entry.message, "SENT")
        } else {
            failedCount++
            reportById[reportId] = entry.copy(
                status = "FAILED",
                errorCode = resultCode
            )
            Log.e(TAG, "SMS failed: reportId=$reportId, resultCode=$resultCode")

            // Notify SMPP of failure
            smppServer?.sendDeliveryReport(reportId, entry.phone, entry.message, "FAILED")
        }
        pendingSms.remove(reportId.hashCode())
        reportByPhoneSentId.remove(reportId.hashCode())
    }

    fun onSmsDelivered(reportId: String) {
        val entry = reportById[reportId] ?: return
        reportById[reportId] = entry.copy(
            status = "DELIVERED",
            deliveredAt = System.currentTimeMillis()
        )
        Log.d(TAG, "SMS delivered: reportId=$reportId")

        // Notify SMPP of delivery
        smppServer?.sendDeliveryReport(reportId, entry.phone, entry.message, "DELIVERED")
    }

    fun getPendingCount(): Int = pendingSms.size
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reportId = intent.getStringExtra("report_id") ?: return
        SmsSender.onSmsSent(reportId, resultCode)
    }
}

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reportId = intent.getStringExtra("report_id") ?: return
        SmsSender.onSmsDelivered(reportId)
    }
}
