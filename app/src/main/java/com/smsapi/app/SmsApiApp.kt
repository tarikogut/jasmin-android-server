package com.smsapi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SmsApiApp : Application() {
    companion object {
        const val CHANNEL_ID = "sms_api_service"
        const val CHANNEL_NAME = "SMS API Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContactsManager.init(this)
        SmsSender.init(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SMS API HTTP Server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
