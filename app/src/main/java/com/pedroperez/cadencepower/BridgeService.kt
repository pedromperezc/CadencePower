package com.pedroperez.cadencepower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service so Android keeps Bluetooth alive while the screen is off.
 * The actual BLE work runs from MainViewModel; this service exists only to
 * promote the process to "user-visible" foreground priority.
 */
class BridgeService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "CadencePower", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CadencePower")
                .setContentText("Streaming power to Zwift")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CadencePower")
                .setContentText("Streaming power to Zwift")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
        startForeground(1, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL_ID = "cadencepower-fg" }
}
