package com.rado.aimoderator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast

/** Вибрация, звук и известия. Само за теб – нищо не отива в лайва. */
object Alerts {

    private const val CH_FLAGS = "flags"
    private const val CH_GREET = "greet"
    private const val GREET_ID = 9_999
    private var lastBuzz = 0L

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_FLAGS, "Сигнали от модератора", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Обиди, заплахи, спам и ботове в чата"
                enableVibration(false) // вибрира приложението само, според настройките
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GREET, "Поздрави за зрители", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Готови поздрави за копиране"
                setSound(null, null)
            }
        )
    }

    fun flag(ctx: Context, m: ChatMessage, s: AlertSettings) {
        val v = m.verdict ?: return
        val now = System.currentTimeMillis()
        val canBuzz = now - lastBuzz > 3_000
        if (canBuzz) lastBuzz = now

        if (s.vibrate && canBuzz) vibrate(ctx, if (v.severity >= 3) longArrayOf(0, 300, 120, 300) else longArrayOf(0, 180))
        if (s.sound && canBuzz) beep(v.severity)
        if (s.notify && canNotify(ctx)) {
            val n = builder(ctx, CH_FLAGS)
                .setContentTitle("⚠ ${v.label}: ${m.user}")
                .setContentText(m.text)
                .setStyle(android.app.Notification.BigTextStyle().bigText("„${m.text}“\n${m.reason}"))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setGroup("flags")
                .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                .build()
            notify(ctx, (m.id % 100_000).toInt() + 1, n)
        }
    }

    fun greetingSuggestion(ctx: Context, text: String) {
        if (!canNotify(ctx)) return
        val copy = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, CopyReceiver::class.java).putExtra(CopyReceiver.EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = builder(ctx, CH_GREET)
            .setContentTitle("👋 Поздрав за новите зрители")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(copy)
            .addAction(android.app.Notification.Action.Builder(null, "Копирай", copy).build())
            .setAutoCancel(true)
            .build()
        notify(ctx, GREET_ID, n)
    }

    fun dumpReady(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = builder(ctx, CH_GREET)
            .setContentTitle("📋 Снимката на екрана е готова")
            .setContentText("Върни се в AI Модератор → Проверка → Сподели")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, GREET_ID + 1, n)
    }

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, channel: String): android.app.Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, channel)
        else android.app.Notification.Builder(ctx).setPriority(android.app.Notification.PRIORITY_HIGH))
            .setSmallIcon(R.drawable.ic_shield)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context, pattern: LongArray) {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vib == null || !vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, -1)) else vib.vibrate(pattern, -1)
    }

    private fun beep(severity: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(if (severity >= 3) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 250)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        } catch (_: Exception) {
        }
    }
}

/** Бутонът „Копирай“ в известието с поздрава. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        copyToClipboard(context, text)
    }

    companion object {
        const val EXTRA_TEXT = "text"

        fun copyToClipboard(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Поздрав", text))
            Toast.makeText(context, "Копирано – постави го в чата", Toast.LENGTH_SHORT).show()
        }
    }
}
