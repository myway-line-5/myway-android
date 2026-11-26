package com.myway.app.ui.trip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

/** 실시간 정보 상태 (3-2 실시간 / 3-8 지연·추정 / 3-9 오프라인) */
enum class Feed { LIVE, DELAYED, OFFLINE }

sealed interface TripState {
    /** 3-1 다음: 고른 열차가 오기를 기다림 (도착하면 자동으로 Riding) */
    data class Waiting(val leg: Int, val seconds: Int) : TripState
    /** 열차 도착: "타셨어요?" — 탔어요 / 못 탔어요 / 대답 없으면 탄 것으로 보고 시작 (1분간 되돌리기) */
    data class BoardCheck(val leg: Int) : TripState
    /** 3-2·3-3 타고 가는 중. at = 지금 역 (leg.stops 안 순번) */
    data class Riding(val leg: Int, val at: Int) : TripState
    /** 3-4 지금 내리세요 (환승역) */
    data class AlightNow(val leg: Int) : TripState
    /** 3-5 환승 안내 (다음 구간 = leg + 1) */
    data class Transfer(val leg: Int) : TripState
    /** 3-6 내릴 역을 지나침 */
    data class Passed(val leg: Int) : TripState
    /** 3-7 반대 방향 탑승 */
    data class WrongWay(val leg: Int) : TripState
    /** 3-10 도착 */
    data class Arrived(val planned: LocalTime, val actual: LocalTime) : TripState
}

/**
 * 이동 중 안내 (Phase 2 · 껍데기)
 * 실시간 열차 위치 API가 나오기 전이라, 몇 초마다 한 역씩 가는 가짜 열차로 흐름을 보여 준다.
 * 서버가 생기면 tick() 대신 위치 응답으로 at 을 갱신하면 된다.
 */
/**
 * 이동 중 안내의 '진짜 상태'. 화면(Fragment/ViewModel)과 따로 살아 있어서
 * 화면이 꺼지거나 지도를 보는 동안에도 GuideService(포그라운드)가 이 상태로 안내를 이어 간다.
 * 실시간 API 전이라 가짜 열차로 움직임 (서버 오면 run() 의 tick 을 위치 응답으로 교체).
 */
object TripEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var plan: TripPlan? = null; private set
    /** 말로 시작한 안내: 환승까지 손대지 않고 이어 감 */
    var handsFree = false; private set
    /** 음성으로 묻는 동안 가짜 열차를 멈춤 */
    @Volatile var hold = false
    var running = false; private set
    private var startedAt: LocalTime = LocalTime.now()
    private var firstWait = 0

    /** TripSession(결과 화면에서 넘긴 값)으로 새 안내 시작 */
    fun start() {
        stop()
        plan = TripSession.plan ?: return
        handsFree = TripSession.voiceStart
        startedAt = LocalTime.now()
        hold = false
        val wait = TripSession.firstWaitSeconds
        firstWait = wait
        _waitLeft.value = wait
        _pos.value = 0f
        _feed.value = Feed.LIVE
        _canUndo.value = false
        _muted.value = false
        _state.value = if (wait > 0) TripState.Waiting(0, wait) else TripState.Riding(0, 0)
        running = true
        run()
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        running = false
    }

    private val _state = MutableStateFlow<TripState>(TripState.Riding(0, 0))

    /** 기다리는 열차 도착까지 남은 초 (화면 카운트다운) */
    private val _waitLeft = MutableStateFlow(0)
    val waitLeft: StateFlow<Int> = _waitLeft.asStateFlow()
    val state: StateFlow<TripState> = _state.asStateFlow()

    private val _feed = MutableStateFlow(Feed.LIVE)
    val feed: StateFlow<Feed> = _feed.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private var ticker: Job? = null


    /** 대답 없이 '탄 것으로' 시작한 뒤 1분 동안 [못 탔어요]로 되돌릴 수 있음 */
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private var undoUntil = 0L

    /** 구간 안 열차 위치 = 지금 역 순번 + 다음 역까지 온 비율 (예: 2.4). 역이 바뀌어도 값이 끊기지 않아 막대가 되감기지 않음 */
    private val _pos = MutableStateFlow(0f)
    val pos: StateFlow<Float> = _pos.asStateFlow()

    /**
     * 역 사이 실제 소요시간을 [SPEEDUP]배 빠르게 재생: 긴 구간은 천천히, 짧은 구간은 빨리 (4~15초).
     * 막대와 열차 이동이 같은 시계를 쓰므로 막대가 끝까지 찬 뒤에 다음 역으로 넘어감.
     */
    private fun hopMs(s: TripState.Riding): Long {
        val sec = plan?.legs?.getOrNull(s.leg)?.hops?.getOrNull(s.at) ?: 120
        return (sec * 1000L / SPEEDUP).coerceIn(4_000L, 15_000L)
    }

    private fun run() {
        ticker?.cancel()
        ticker = scope.launch {
            var current: TripState.Riding? = null
            var elapsed = 0L
            var waiting: TripState.Waiting? = null
            var waited = 0L
            var checking: TripState.BoardCheck? = null
            var checked = 0L
            while (true) {
                // 움직이는 단계(기다림·탑승 확인·이동)만 30fps로, 나머지(내림·환승·경고·도착)는 쉬엄쉬엄 → 배터리
                val moving = _state.value.let { it is TripState.Waiting || it is TripState.BoardCheck || it is TripState.Riding }
                delay(if (moving) FRAME_MS else IDLE_MS)
                if (!moving) continue
                // 열차 기다리는 중: 시연 배속으로 줄어들다 0이 되면 탑승 → 안내 시작
                (_state.value as? TripState.Waiting)?.let { w ->
                    if (w != waiting) { waiting = w; waited = 0 }
                    if (!hold) waited += FRAME_MS
                    val left = w.seconds - (waited * SPEEDUP / 1000).toInt()
                    _waitLeft.value = left.coerceAtLeast(0)
                    if (left <= 0) _state.value = TripState.BoardCheck(w.leg)
                    continue
                }
                // 타셨어요? 대답을 기다림 (음성으로 묻는 중엔 멈춤). 대답 없으면 탄 것으로 보고 시작
                (_state.value as? TripState.BoardCheck)?.let { c ->
                    if (c != checking) { checking = c; checked = 0 }
                    if (!hold) checked += FRAME_MS
                    if (checked >= BOARD_ASK_MS) boarded(assumed = true)
                    continue
                }
                checking = null
                if (_canUndo.value && System.currentTimeMillis() > undoUntil) _canUndo.value = false
                waiting = null
                val s = _state.value as? TripState.Riding
                if (s == null) { current = null; elapsed = 0; continue }
                if (s != current) { current = s; elapsed = 0; _pos.value = s.at.toFloat() }
                if (hold) continue                                   // 음성으로 묻는 동안 멈춤
                elapsed += FRAME_MS
                val d = hopMs(s)
                _pos.value = s.at + (elapsed.toFloat() / d).coerceAtMost(1f)
                if (elapsed >= d + ARRIVE_PAUSE_MS) tick()             // 막대가 다 찬 걸 잠깐 보여 준 뒤 다음 역
            }
        }
    }

    /** 가짜 열차 한 역 이동 */
    private fun tick() {
        val p = plan ?: return
        val s = _state.value as? TripState.Riding ?: return
        val leg = p.legs[s.leg]
        val next = s.at + 1
        _state.value = when {
            next < leg.stops.lastIndex -> s.copy(at = next)
            s.leg == p.legs.lastIndex -> arrived(p)
            else -> TripState.AlightNow(s.leg)
        }
    }

    private fun arrived(p: TripPlan): TripState.Arrived {
        // 예정 = 안내 시작 + 첫 열차 기다림 + 이동 시간. 실제 도착은 열차 위치 API가 붙기 전까지 예정과 같게(정시)
        val planned = startedAt.plusSeconds((firstWait + p.totalSeconds).toLong())
        return TripState.Arrived(planned, planned)
    }

    fun showTransfer() { (_state.value as? TripState.AlightNow)?.let { _state.value = TripState.Transfer(it.leg) } }

    /** 3-1에서 다음 구간 열차를 고름 → 탑승 */
    /** 다음 구간 열차를 고름 → 그 열차를 기다림 */
    fun boardNext(waitSeconds: Int) {
        val t = _state.value as? TripState.Transfer ?: return
        _waitLeft.value = waitSeconds
        _state.value = TripState.Waiting(t.leg + 1, waitSeconds)
    }

    /** 3-6·3-7 '수정 경로 안내': 하차역 한 역 전부터 다시 안내 */
    fun acceptReroute() {
        val p = plan ?: return
        val leg = when (val s = _state.value) { is TripState.Passed -> s.leg; is TripState.WrongWay -> s.leg; else -> return }
        _state.value = TripState.Riding(leg, (p.legs[leg].stops.lastIndex - 1).coerceAtLeast(0))
    }

    /** 3-6·3-7 '그대로 가기': 경고만 닫고 원래 안내 */
    fun keepGoing() {
        val leg = when (val s = _state.value) { is TripState.Passed -> s.leg; is TripState.WrongWay -> s.leg; else -> return }
        _state.value = TripState.Riding(leg, 0)
    }

    fun toggleMute() { _muted.value = !_muted.value }

    /** [탔어요] 또는 대답 없음(assumed) → 안내 시작 */
    fun boarded(assumed: Boolean = false) {
        val c = _state.value as? TripState.BoardCheck ?: return
        _canUndo.value = assumed
        undoUntil = System.currentTimeMillis() + UNDO_MS
        _state.value = TripState.Riding(c.leg, 0)
    }

    /** [못 탔어요]: 도착 확인 중이거나, 탄 것으로 본 뒤 1분 안이면 다음 열차를 기다림 */
    fun missed() {
        val leg = when (val s = _state.value) {
            is TripState.BoardCheck -> s.leg
            is TripState.Riding -> if (_canUndo.value && s.at == 0) s.leg else return
            else -> return
        }
        _canUndo.value = false
        _waitLeft.value = NEXT_TRAIN_GAP
        _state.value = TripState.Waiting(leg, NEXT_TRAIN_GAP)
    }

    /** '다음 열차': 다른 열차를 탔음 → 이 구간 처음부터 다시 (가짜 열차 기준) */
    fun restartLeg() {
        val leg = when (val s = _state.value) { is TripState.Riding -> s.leg; is TripState.Waiting -> s.leg; is TripState.BoardCheck -> s.leg; else -> return }
        _waitLeft.value = NEXT_TRAIN_GAP
        _state.value = TripState.Waiting(leg, NEXT_TRAIN_GAP)
    }

    // ---- 시연용 (상단 칩 길게 누르기) : 실시간 API가 없어 예외 화면을 직접 띄워 봄 ----
    fun demoPassed() { legNow()?.let { _state.value = TripState.Passed(it) } }
    fun demoWrongWay() { legNow()?.let { _state.value = TripState.WrongWay(it) } }
    fun demoFeed(f: Feed) { _feed.value = f }

    private fun legNow(): Int? = when (val s = _state.value) {
        is TripState.Riding -> s.leg; is TripState.Waiting -> s.leg; is TripState.BoardCheck -> s.leg
        is TripState.AlightNow -> s.leg; is TripState.Transfer -> s.leg
        is TripState.Passed -> s.leg; is TripState.WrongWay -> s.leg; is TripState.Arrived -> null
    }

    // ---- 시연 값 ----
        const val BOARD_ASK_MS = 10_000L       // '타셨어요?' 대답 기다림
        const val UNDO_MS = 60_000L            // 대답 없이 시작한 뒤 [못 탔어요] 가능 시간
        const val NEXT_TRAIN_GAP = 331          // '다음 열차' = 약 5분 뒤 (가짜)
        const val FIRST_TRAIN = 193             // 말로 시작할 때 가장 먼저 오는 열차 (가짜)
        const val SPEEDUP = 10            // 시연: 실제보다 10배 빠르게 (2분 구간 → 12초)
        private const val FRAME_MS = 33L
        private const val IDLE_MS = 250L
        private const val ARRIVE_PAUSE_MS = 400L
}
