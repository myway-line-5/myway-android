package com.myway.app.ui.trip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myway.app.R
import com.myway.app.data.local.SettingsStore
import com.myway.app.ui.common.StationNames
import com.myway.app.ui.common.displayName
import com.myway.app.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 기능 28 · Figma 3-11: 화면이 꺼지거나 다른 앱을 써도 이동 중 안내를 이어 가는 포그라운드 서비스.
 * - 상시 알림: 다음 하차역 · 남은 역 (잠금화면에서도 보임)
 * - 하차·환승·경고 음성 + 진동 + 알림 (화면이 없어도)
 * - 핸즈프리로 시작했으면 내림 → 환승 안내 → 다음 열차까지 자동으로 넘김
 * 안내가 끝나면(도착·종료) 스스로 멈춤.
 */
class GuideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notifier: TripNotifier
    private var tts: TtsManager? = null
    private var lastKey: Any? = null
    private var lastProgress: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = TripNotifier(this)
        val p = TripEngine.plan
        val title = p?.let { getString(R.string.trip_route, it.origin.displayName(), it.destination.displayName()) }
            ?: getString(R.string.trip_ch_progress)
        ServiceCompat.startForeground(this, TripNotifier.ID_PROGRESS,
            notifier.buildProgress(title, getString(R.string.trip_ch_progress)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        tts = TtsManager(this, if (StationNames.english) java.util.Locale.ENGLISH else java.util.Locale.KOREAN)
        scope.launch {
            combine(TripEngine.state, TripEngine.muted) { s, m -> s to m }.collect { (s, m) -> onState(s, m) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun onState(s: TripState, muted: Boolean) {
        val p = TripEngine.plan ?: return stopSelf()
        val title = getString(R.string.trip_route, p.origin.displayName(), p.destination.displayName())
        val first = lastKey != s
        val prev = lastKey
        lastKey = s
        when (s) {
            is TripState.Waiting -> {
                val leg = p.legs[s.leg]
                progress(title, getString(R.string.trip_wait_sub, leg.line, leg.toward.displayName()))
                // 못 탔어요 → 다음 열차 기다림 안내
                if (first && (prev is TripState.BoardCheck || prev is TripState.Riding))
                    say(getString(R.string.trip_say_missed, (s.seconds + 59) / 60), muted)
            }
            is TripState.BoardCheck -> {
                if (!first) return
                progress(title, getString(R.string.trip_board_check_title))
                notifier.alert(getString(R.string.trip_board_check_title), getString(R.string.trip_board_check_sub))
                // 말로 시작했으면 화면(Fragment)이 묻고 듣는다. 아니면 여기서 말로만 알림
                if (!TripEngine.handsFree) say(getString(R.string.trip_say_board_check), muted)
            }
            is TripState.Riding -> {
                val leg = p.legs[s.leg]
                val left = leg.stops.lastIndex - s.at
                progress(title, getString(R.string.trip_noti_progress, leg.alight.name.displayName(), left))
                if (!first) return
                if (s.at == 0) say(getString(R.string.trip_say_board), muted)
                if (left == 2) {
                    notifier.alert(getString(R.string.trip_noti_soon, left), getString(R.string.trip_noti_soon_body, leg.alight.name.displayName()))
                    say(getString(R.string.trip_say_soon, left, leg.alight.name.displayName()), muted)
                }
            }
            is TripState.AlightNow -> {
                if (!first) return
                val leg = p.legs[s.leg]; val next = p.legs[s.leg + 1]
                progress(title, getString(R.string.trip_noti_now))
                notifier.alert(getString(R.string.trip_noti_now), leg.alight.name.displayName())
                say(getString(R.string.trip_say_now_transfer, leg.alight.name.displayName(), next.line), muted) {
                    if (TripEngine.handsFree && TripEngine.state.value == s) TripEngine.showTransfer()
                }
            }
            is TripState.Transfer -> {
                if (!first) return
                val next = p.legs[s.leg + 1]
                val walk = ((p.walks[s.leg] + 59) / 60).coerceAtLeast(1)
                progress(title, getString(R.string.trip_transfer_to, next.line))
                say(getString(R.string.trip_say_transfer, next.line, next.toward.displayName(), walk), muted) {
                    // 핸즈프리: 걷는 동안 기다렸다가 다음 열차로 (시연 6초)
                    if (TripEngine.handsFree) main.postDelayed({
                        if (TripEngine.state.value == s) TripEngine.boardNext(TripEngine.FIRST_TRAIN)
                    }, WALK_DEMO_MS)
                }
            }
            is TripState.Passed, is TripState.WrongWay -> {
                if (!first) return
                val label = getString(if (s is TripState.Passed) R.string.trip_passed_label else R.string.trip_wrong_label)
                notifier.alert(label, getString(R.string.trip_reroute_1))
                say(label, muted)
            }
            is TripState.Arrived -> {
                if (!first) return
                say(getString(R.string.trip_say_arrived, p.destination.displayName()), muted) { stopSelf() }
            }
        }
    }

    private fun progress(title: String, text: String) {
        if (text == lastProgress) return
        lastProgress = text
        notifier.progress(title, text)
    }

    /** 말하기. 소리 끔·읽어주기 끔이면 바로 다음으로 */
    private fun say(text: String, muted: Boolean, then: (() -> Unit)? = null) {
        val t = tts
        if (!muted && SettingsStore.bool(SettingsStore.Key.TTS) && t != null) t.speak(text, then)
        else if (then != null) main.postDelayed({ then() }, 2_000)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        tts?.shutdown(); tts = null
        notifier.clear()
        super.onDestroy()
    }

    companion object {
        private const val WALK_DEMO_MS = 6_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuideService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GuideService::class.java))
        }
    }
}
