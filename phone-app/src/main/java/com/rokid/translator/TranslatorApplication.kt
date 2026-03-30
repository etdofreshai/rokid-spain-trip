package com.rokid.translator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.rokid.translator.common.Constants

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "Rokid Translator Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Translation service for Rokid glasses"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
