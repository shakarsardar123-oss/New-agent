package com.aura.aura_assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/// Minimal foreground [Service] for the screen-capture subsystem.
///
/// On Android 14+ (API 34), a foreground service with type
/// `mediaProjection` must be running *before* calling
/// [MediaProjectionManager.getMediaProjection].
/// This service exists solely to satisfy that requirement —
/// it does NOT perform any screen-capture work itself.
///
/// The actual capture logic lives in [ScreenCapturePlugin].
/// This service is started by [ScreenCapturePlugin.startForegroundService]
/// and stopped by [ScreenCapturePlugin.stopForegroundService].
class ScreenCaptureService : Service() {

    companion object {
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "aura_screen_capture"
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("NewApi")
    private fun buildNotification(): Notification {
        // Ensure the notification channel exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AURA Screen Capture",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps screen capture active"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("${packageName}.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AURA Screen Capture")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AURA Screen Capture")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
