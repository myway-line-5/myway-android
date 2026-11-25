package com.myway.app.ui.trip

import androidx.lifecycle.ViewModel

/**
 * 안내 화면용 창구. 실제 상태·가짜 열차는 TripEngine 이 들고 있음
 * (화면이 꺼지거나 지도를 봐도 GuideService 가 같은 상태로 안내를 이어 가게).
 */
class TripViewModel : ViewModel() {

    init {
        // 결과 화면에서 새 경로로 들어왔으면 새로 시작, 같은 안내면 이어서
        if (!TripEngine.running || TripEngine.plan !== TripSession.plan) TripEngine.start()
    }

    val plan: TripPlan? get() = TripEngine.plan
    val handsFree: Boolean get() = TripEngine.handsFree
    var hold: Boolean
        get() = TripEngine.hold
        set(v) { TripEngine.hold = v }

    val state = TripEngine.state
    val feed = TripEngine.feed
    val muted = TripEngine.muted
    val pos = TripEngine.pos
    val waitLeft = TripEngine.waitLeft
    val canUndo = TripEngine.canUndo

    fun showTransfer() = TripEngine.showTransfer()
    fun boardNext(waitSeconds: Int) = TripEngine.boardNext(waitSeconds)
    fun acceptReroute() = TripEngine.acceptReroute()
    fun keepGoing() = TripEngine.keepGoing()
    fun toggleMute() = TripEngine.toggleMute()
    fun restartLeg() = TripEngine.restartLeg()
    fun boarded() = TripEngine.boarded()
    fun missed() = TripEngine.missed()
    fun demoPassed() = TripEngine.demoPassed()
    fun demoWrongWay() = TripEngine.demoWrongWay()
    fun demoFeed(f: Feed) = TripEngine.demoFeed(f)

    companion object {
        const val FIRST_TRAIN = TripEngine.FIRST_TRAIN
        const val SPEEDUP = TripEngine.SPEEDUP
    }
}
