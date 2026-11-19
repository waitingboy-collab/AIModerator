package com.rado.aimoderator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Държи приложението живо, докато си в TikTok и водиш лайва,
 * за да не спре вграденият браузър да чете чата. Показва постоянно известие.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Следене на лайва", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val user = ModRepository.settings.username.removePrefix("@")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AI Модератор следи лайва")
            .setContentText(if (user.isNotEmpty()) "@$user" else "Чете чата")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val ID = 4242
        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34) */
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
