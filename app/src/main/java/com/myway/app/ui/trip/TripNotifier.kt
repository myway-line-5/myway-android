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

    /**
     * 알림은 하나만 쓴다 (중복 방지). 평소엔 조용한 진행 알림,
     * 내릴 때·환승·경고 같은 순간엔 같은 알림을 소리·진동(urgent)으로 바꿔 띄우고
     * 다음 상태가 오면 다시 조용한 진행 알림으로 덮어쓴다 → 지난 안내는 저절로 사라짐.
     */
    fun build(title: String, text: String, done: Int = 0, max: Int = 0, urgent: Boolean = false): android.app.Notification =
        NotificationCompat.Builder(ctx, if (urgent) CH_ALERT else CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(!urgent).setSilent(!urgent)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)       // 잠금화면에서도 보임 (3-11)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(max, done.coerceIn(0, max), false)             // max 0 = 바 숨김
            .setContentIntent(open())
            .setDeleteIntent(repost())                                   // Android 14+는 밀면 지워짐 → 바로 다시 띄움
            .build()

    /** 알림을 밀어서 지우면 서비스에 다시 띄우라고 알림 */
    private fun repost() = PendingIntent.getService(ctx, 1,
        Intent(ctx, GuideService::class.java).setAction(GuideService.ACTION_REPOST),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @android.annotation.SuppressLint("MissingPermission")
    fun show(title: String, text: String, done: Int, max: Int, urgent: Boolean = false) {
        if (urgent) vibrate()
        if (!allowed()) return
        nm.notify(ID_PROGRESS, build(title, text, done, max, urgent))
    }

    /** 도착 알림: 진행 알림이 사라진 뒤 남는 한 장. 조용히, 눌러서 지우거나 30분 뒤 자동 정리 */
    @android.annotation.SuppressLint("MissingPermission")
    fun done(title: String, text: String) {
        if (!allowed()) return
        nm.notify(ID_DONE, NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

    fun clear() { nm.cancel(ID_PROGRESS); nm.cancel(ID_ALERT) }   // ID_ALERT: 예전 버전에서 남은 알림 정리

    companion object {
        private const val CH_PROGRESS = "trip_progress"
        private const val CH_ALERT = "trip_alert"
        const val ID_PROGRESS = 3011
        private const val ID_ALERT = 3012
        private const val ID_DONE = 3013
    }
}
