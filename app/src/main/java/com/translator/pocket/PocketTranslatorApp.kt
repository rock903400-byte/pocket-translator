package com.translator.pocket

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PocketTranslatorApp : Application() {

    companion object {
        const val CHANNEL_ID = "pocket_translation_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.service_notification_channel)
            val descriptionText = getString(R.string.service_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW // 靜音常駐通知，不發出嗶嗶聲打擾
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
