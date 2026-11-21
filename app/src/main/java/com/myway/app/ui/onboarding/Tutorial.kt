package com.myway.app.ui.onboarding

import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.myway.app.R
import com.myway.app.data.local.SettingsStore

/**
 * 튜토리얼 T-1~T-8 (설계서 UC-02).
 * 1~4단계는 홈, 5~8단계는 AI 모드 위에서 실제 버튼을 비추며 안내한다.
 * 최초 실행 1회 자동 + 홈/AI의 ? 버튼으로 다시 보기.
 */
object Tutorial {

    data class Step(@StringRes val title: Int, @StringRes val body: Int, @StringRes val hint: Int?)

    val steps = listOf(
        Step(R.string.tut_1_title, R.string.tut_1_body, R.string.tut_hint_next),
        Step(R.string.tut_2_title, R.string.tut_2_body, R.string.tut_hint_next),
        Step(R.string.tut_3_title, R.string.tut_3_body, R.string.tut_hint_next),
        Step(R.string.tut_4_title, R.string.tut_4_body, R.string.tut_hint_ai),
        Step(R.string.tut_5_title, R.string.tut_5_body, R.string.tut_hint_next),
        Step(R.string.tut_6_title, R.string.tut_6_body, R.string.tut_hint_next),
        Step(R.string.tut_7_title, R.string.tut_7_body, R.string.tut_hint_next),
        Step(R.string.tut_8_title, R.string.tut_8_body, null)
    )
    val HOME = 0..3
    val AI = 4..7

    /** 다른 화면으로 넘어가며 이어 보여 줄 단계 */
    var pending: Int? = null
    /** AI 모드의 ?로 시작했으면 끝나도 AI 모드에 머문다 */
    var fromAi = false

    val done get() = SettingsStore.bool(SettingsStore.Key.TUTORIAL_DONE)
    fun consume(range: IntRange): Int? = pending?.takeIf { it in range }.also { if (it != null) pending = null }
}

/**
 * 한 화면에서 튜토리얼 단계를 보여 주는 진행자.
 * 범위를 벗어나면 [toNextScreen]/[toPrevScreen]으로 다른 화면에 넘긴다.
 */
class TutorialSession(
    private val fragment: Fragment,
    private val range: IntRange,
    private val target: (Int) -> TutorialOverlay.Target?,
    private val toNextScreen: (() -> Unit)?,
    private val toPrevScreen: (() -> Unit)?,
    private val onFinished: () -> Unit
) {
    private var overlay: TutorialOverlay? = null
    private var index = range.first
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = finish()      // 뒤로가기 = 건너뛰기
    }

    val active get() = overlay != null

    fun start(i: Int) {
        if (overlay == null) {
            val act = fragment.requireActivity()
            val root = act.findViewById<ViewGroup>(android.R.id.content)
            overlay = TutorialOverlay(fragment.requireContext()).also { o ->
                o.onNext = { go(index + 1) }
                o.onPrev = { go(index - 1) }
                o.onSkip = { finish() }
                root.addView(o, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            back.isEnabled = true
            act.onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)
        }
        index = i
        render()
    }

    private fun go(i: Int) {
        when {
            i >= Tutorial.steps.size -> finish()
            i > range.last -> toNextScreen?.let { dismiss(); it() } ?: finish()
            i < range.first -> toPrevScreen?.let { dismiss(); it() }   // 없으면 첫 단계에 머묾
            else -> { index = i; render() }
        }
    }

    private fun render() {
        val o = overlay ?: return
        val t = target(index) ?: return finish()
        val s = Tutorial.steps[index]
        val ctx = fragment.requireContext()
        o.show(
            t, index + 1, Tutorial.steps.size,
            ctx.getString(s.title), ctx.getString(s.body), s.hint?.let(ctx::getString),
            last = index == Tutorial.steps.lastIndex
        )
    }

    fun finish() {
        dismiss()
        Tutorial.pending = null
        SettingsStore.set(SettingsStore.Key.TUTORIAL_DONE, true)
        onFinished()
    }

    /** 끝내지 않고 화면에서만 걷어냄 (화면 전환·onDestroyView) */
    fun dismiss() {
        back.remove()
        overlay?.let { o ->
            o.isEnabled = false
            o.animate().alpha(0f).setDuration(160).withEndAction { (o.parent as? ViewGroup)?.removeView(o) }.start()
        }
        overlay = null
    }
}
