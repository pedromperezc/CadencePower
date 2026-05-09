package com.pedroperez.cadencepower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the BLE work. As long as the notification is
 * visible, Android won't kill our advertising even if the user navigates away
 * from the app or turns the screen off.
 */
class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var publishJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "CadencePower", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        startForeground(NOTIF_ID, buildNotification("Starting\u2026"))

        when (intent?.action) {
            ACTION_STOP -> {
                stopBle(app)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startBle(app)
        }
        return START_STICKY
    }

    private fun startBle(app: App) {
        if (app.running.value) return
        Log.i(TAG, "Starting BLE bridge")
        app.cadenceScanner.start()
        app.powerAdvertiser.start()
        app.running.value = true

        publishJob?.cancel()
        publishJob = scope.launch {
            while (app.running.value) {
                val now = System.currentTimeMillis()
                val lastActivity = app.cadenceScanner.lastCrankActivityMillis
                if (lastActivity == 0L || now - lastActivity > 2000) {
                    app.cadenceScanner.forceZeroCadence()
                }
                val rpm = app.cadenceScanner.cadenceRpm.value
                val watts = app.estimator.powerFromCadence(rpm)
                app.power.value = watts
                app.speedKmh.value = app.estimator.speedKmhFromCadence(rpm)
                app.powerAdvertiser.publishPower(watts)

                if ((now / 1000) % 3 == 0L) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(
                        NOTIF_ID,
                        buildNotification("$watts W \u2022 ${rpm.toInt()} rpm \u2022 ${app.subscribers.value} client(s)")
                    )
                }
                delay(1000)
            }
        }
    }

    private fun stopBle(app: App) {
        Log.i(TAG, "Stopping BLE bridge")
        app.running.value = false
        publishJob?.cancel()
        publishJob = null
        app.cadenceScanner.stop()
        app.powerAdvertiser.stop()
        app.power.value = 0
        app.speedKmh.value = 0.0
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CadencePower")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CadencePower")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "cadencepower-fg"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.pedroperez.cadencepower.STOP"
    }
}
