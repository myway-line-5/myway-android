package com.myway.app.ui.common

import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * 앱 공통 움직임 값. 짧고(≤300ms) 같은 곡선을 써서 화면마다 느낌이 같게.
 * 시스템 '애니메이션 끄기' 설정을 따름 (Transition·ViewPropertyAnimator 기본 동작).
 */
object Motion {
    /** Material 'emphasized' 감속 곡선 */
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 다음 레이아웃 변화(보이기·숨기기·자리 이동)를 부드럽게: 사라짐·이동·나타남을 동시에 */
    fun change(root: ViewGroup, duration: Long = 240) {
        TransitionManager.beginDelayedTransition(root, TransitionSet()
            .addTransition(Fade(Fade.OUT))
            .addTransition(ChangeBounds())
            .addTransition(Fade(Fade.IN))
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .setDuration(duration)
            .setInterpolator(ease))
    }

    /** 카드들이 아래에서 차례로 떠오름 */
    fun stagger(views: List<View>, step: Long = 45) {
        val d = views.firstOrNull()?.resources?.displayMetrics?.density ?: return
        views.filter { it.visibility == View.VISIBLE }.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 12 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * step).setDuration(260).setInterpolator(ease).start()
        }
    }

    /** 아이콘 바꿔 끼우기: 작아졌다가 새 아이콘으로 커짐 */
    fun swap(v: View, change: () -> Unit) {
        v.animate().cancel()
        v.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.4f).setDuration(80).withEndAction {
            change()
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }.start()
    }
}
