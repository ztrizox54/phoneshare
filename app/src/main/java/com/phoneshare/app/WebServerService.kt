package com.phoneshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WebServerService : Service() {

    private var server: PhoneShareServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        if (isRunning) {
            startForeground(NOTIF_ID, buildNotification())
            broadcastStatus()
            return
        }
        try {
            val srv = PhoneShareServer(applicationContext, port)
            srv.start()
            server = srv
            instance = srv
            isRunning = true
            ServerLog.ok("Server started on port $port")
            startForeground(NOTIF_ID, buildNotification())
            broadcastStatus()
        } catch (e: Exception) {
            ServerLog.err("Failed to start: ${e.message}")
            isRunning = false
            broadcastStatus()
            stopSelf()
        }
    }

    private fun stopEverything() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        instance = null
        if (isRunning) ServerLog.warn("Server stopped")
        isRunning = false
        broadcastStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PhoneShare server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), piFlags)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, WebServerService::class.java).setAction(ACTION_STOP), piFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneShare is running")
            .setContentText("Listening on port $port")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    companion object {
        const val ACTION_START  = "com.phoneshare.app.START"
        const val ACTION_STOP   = "com.phoneshare.app.STOP"
        const val ACTION_STATUS = "com.phoneshare.app.STATUS"
        const val CHANNEL_ID = "phoneshare-server"
        const val NOTIF_ID = 1
        const val port = 8080

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var instance: PhoneShareServer? = null
            private set
    }
}
