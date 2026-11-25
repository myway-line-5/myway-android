package com.myway.app.ui.trip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myway.app.MainActivity
import com.myway.app.R
import com.myway.app.data.local.SettingsStore

/**
 * Figma 3-11 잠금화면 알림 (껍데기)
 * 채널 둘: 진행(조용히·계속 떠 있음) / 하차·환승(소리·진동). 설계서 4.x 알림 채널 규칙.
 * 화면이 꺼져도 계속 갱신하려면 다음 단계에서 포그라운드 서비스로 옮긴다.
 */
class TripNotifier(private val ctx: Context) {

    private val nm = NotificationManagerCompat.from(ctx)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = ctx.getSystemService(NotificationManager::class.java)
            sys.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.trip_ch_progress),
                NotificationManager.IMPORTANCE_LOW))
            sys.createNotificationChannel(NotificationChannel(CH_ALERT, ctx.getString(R.string.trip_ch_alert),
                NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun allowed() = SettingsStore.bool(SettingsStore.Key.NOTIFY) &&
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun open() = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 진행 중 알림 (포그라운드 서비스의 상시 알림과 같은 것): 다음 하차역 · 남은 역 */
    fun buildProgress(title: String, text: String): android.app.Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)       // 잠금화면에서도 보임 (3-11)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open())
            .build()

    @android.annotation.SuppressLint("MissingPermission")
    fun progress(title: String, text: String) {
        if (!allowed()) return
        nm.notify(ID_PROGRESS, buildProgress(title, text))
    }

    /** 하차·환승 알림 + 진동 */
    @android.annotation.SuppressLint("MissingPermission")
    fun alert(title: String, text: String) {
        vibrate()
        if (!allowed()) return
        nm.notify(ID_ALERT, NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open())
            .build())
    }

    private fun vibrate() {
        if (!SettingsStore.bool(SettingsStore.Key.VIBRATE)) return
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        val amp = (SettingsStore.int(SettingsStore.Key.VIBRATE_STRENGTH) * 255 / 100).coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), intArrayOf(0, amp, 0, amp), -1))
        else @Suppress("DEPRECATION") v.vibrate(600)
    }

    fun clear() { nm.cancel(ID_PROGRESS); nm.cancel(ID_ALERT) }

    companion object {
        private const val CH_PROGRESS = "trip_progress"
        private const val CH_ALERT = "trip_alert"
        const val ID_PROGRESS = 3011
        private const val ID_ALERT = 3012
    }
}
