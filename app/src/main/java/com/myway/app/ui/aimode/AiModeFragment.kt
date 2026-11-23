package com.myway.app.ui.aimode

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.myway.app.R
import com.myway.app.databinding.FragmentAiModeBinding
import com.myway.app.databinding.ItemAiPointBinding
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.RouteType
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.Motion
import com.myway.app.ui.onboarding.Tutorial
import com.myway.app.ui.onboarding.TutorialOverlay
import com.myway.app.ui.onboarding.TutorialSession
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import com.myway.app.ui.search.StationGroupItem
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Figma 2-1~2-10 AI 모드 */
class AiModeFragment : Fragment(R.layout.fragment_ai_mode) {

    private val vm: AiModeViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentAiModeBinding? = null
    private val dotAnims = mutableListOf<ValueAnimator>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentAiModeBinding.bind(view).also { _b = it }

        b.helpButton.setOnClickListener { Tutorial.fromAi = true; startTutorial(Tutorial.AI.first) }
        Tutorial.consume(Tutorial.AI)?.let { step -> view.post { startTutorial(step) } }
        b.settingsButton.setOnClickListener { findNavController().navigate(R.id.to_settings) }
        b.backButton.setOnClickListener { stepBack() }
        // 시스템 뒤로가기도 '한 단계 뒤로' (입력 화면에서만 홈으로 나감)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        b.normalButton.setOnClickListener { findNavController().popBackStack() }
        // 입력창 오른쪽 버튼: 비었으면 🎤 말하기, 글자가 있으면 ↑ 보내기 (Figma MicButton 424:43)
        b.sendButton.setOnClickListener { if (b.input.text.isNullOrBlank()) listen() else send() }
        b.input.doAfterTextChanged { updateSendButton() }
        updateSendButton()
        b.input.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) send() } }

        // 피그마 '캐릭터 웨이' 이미지(drawable-nodpi/ai_way.png)가 있으면 그걸, 없으면 임시 벡터
        val way = resources.getIdentifier("ai_way", "drawable", requireContext().packageName)
        if (way != 0) b.mascot.setImageResource(way)
        startFloating()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    /** 웨이+후광이 함께 둥둥 떠다니는 효과 (위아래 6dp, 2.4초 왕복) + 뒤 빛이 은은하게 숨 쉼 */
    private val floatAnims = mutableListOf<ValueAnimator>()
    private fun startFloating() {
        val b = _b ?: return
        floatAnims += ObjectAnimator.ofFloat(b.mascotBox, View.TRANSLATION_Y, -dp(6).toFloat(), dp(6).toFloat()).apply {
            duration = 1200
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            start()
        }
        val glow = b.mascotBox.getChildAt(0)
        floatAnims += ObjectAnimator.ofFloat(glow, View.ALPHA, 0.7f, 1f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stepBack()
    }

    /** 해석 중 → 취소, 그 외 → 입력 화면 (확인된 조건은 유지) */
    private fun stepBack() {
        if (vm.state.value is AiUiState.Interpreting) vm.cancel() else vm.backToInput()
    }

    /** T-5~T-8: 입력창 → 일반 모드 버튼 → 설정 → 도움말 */
    private var tutorial: TutorialSession? = null
    private fun startTutorial(step: Int) {
        val b = _b ?: return
        if (vm.state.value != AiUiState.Idle) vm.backToInput()      // 입력 화면 위에서 안내
        tutorial?.dismiss()
        val session = TutorialSession(
            fragment = this,
            range = Tutorial.AI,
            target = { i ->
                when (i) {
                    4 -> TutorialOverlay.Target(b.inputBox, 10f)
                    5 -> TutorialOverlay.Target(b.normalButton, -1f)
                    6 -> TutorialOverlay.Target(b.settingsButton, 10f)
                    else -> TutorialOverlay.Target(b.helpButton, 10f)
                }
            },
            toNextScreen = null,
            // 홈에서 이어 온 경우만 '이전'으로 홈 4단계에 돌아감
            toPrevScreen = if (Tutorial.fromAi) null else ({
                Tutorial.pending = Tutorial.HOME.last
                findNavController().popBackStack(); Unit
            }),
            // 최초 실행·홈에서 시작했으면 끝난 뒤 홈으로 (Figma: 건너뛰기/시작하기 = 홈)
            onFinished = { if (!Tutorial.fromAi) findNavController().popBackStack() }
        ).also { tutorial = it }
        session.start(step)
    }

    /** 안드로이드 기본 음성 인식 화면 → 받아쓴 문장을 바로 해석 (STT 고도화는 Phase 2) */
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            _b?.input?.setText(said)
            send()
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ai_listen_prompt))
        try { speech.launch(intent) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.ai_listen_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private var micShown: Boolean? = null
    private fun updateSendButton() {
        val b = _b ?: return
        val empty = b.input.text.isNullOrBlank()
        if (micShown == empty) return
        val first = micShown == null
        micShown = empty
        val apply = {
            b.sendButton.setImageResource(if (empty) R.drawable.ic_perm_mic else R.drawable.ic_send)
            b.sendButton.contentDescription = getString(if (empty) R.string.ai_listen else R.string.ai_send)
        }
        if (first) apply() else Motion.swap(b.sendButton, apply)      // 🎤 ↔ ↑ 바뀌는 순간을 보이게
    }

    private fun send() {
        val b = _b ?: return
        vm.submit(b.input.text.toString())
        hideKeyboard()
    }

    // ---------- 그리기 ----------

    private var lastKind: Any? = null
    private fun render(s: AiUiState) {
        val b = _b ?: return
        // 단계가 바뀔 때만: 웨이·제목 자리 이동 + 카드 나타남을 한 번에 부드럽게
        val kind = s::class
        if (lastKind != null && lastKind != kind) Motion.change(b.root as android.view.ViewGroup, 260)
        lastKind = kind
        b.busyBlock.isVisible = s is AiUiState.Interpreting
        b.confirmCard.isVisible = s is AiUiState.Confirm
        b.confirmHelp.isVisible = s is AiUiState.Confirm && s.draft.condition.preference != null
        b.options.isVisible = s is AiUiState.Candidates || s is AiUiState.Unsupported || s is AiUiState.NotUnderstood
        b.optionsLabel.isVisible = s is AiUiState.Unsupported || s is AiUiState.NotUnderstood
        b.errorBox.isVisible = s is AiUiState.Failed
        b.inputBar.isVisible = s is AiUiState.Idle
        b.status.isVisible = s is AiUiState.Idle || s is AiUiState.Interpreting
        setDots(s is AiUiState.Interpreting)
        val inner = s !is AiUiState.Idle
        b.backButton.isVisible = inner          // 같은 자리(좌상단)에서 ? ↔ ← 교체
        b.helpButton.isVisible = !inner
        backCallback.isEnabled = inner

        when (s) {
            AiUiState.Idle -> {
                layout(titleFirst = true, mascotDp = 160)
                b.title.setText(R.string.ai_idle_title)
                b.subtitle.setText(R.string.ai_idle_sub)
                b.status.setText(R.string.ai_idle_status)
                buttons(null, null)
                vm.consumePrefill()?.let { t ->
                    b.input.setText(t); b.input.setSelection(t.length)
                    b.input.requestFocus()
                    b.input.post { requireContext().getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(b.input, InputMethodManager.SHOW_IMPLICIT) }
                }
            }
            is AiUiState.Interpreting -> {
                layout(titleFirst = true, mascotDp = 120)
                b.title.setText(R.string.ai_idle_title)
                b.subtitle.setText(R.string.ai_idle_sub)
                b.status.setText(R.string.ai_busy_status)
                b.quote.text = getString(R.string.ai_quote, s.said)
                buttons(null, R.string.ai_cancel to { vm.cancel() })
            }
            is AiUiState.Confirm -> {
                layout(titleFirst = false, mascotDp = 80)
                b.title.setText(R.string.ai_confirm_title)
                b.subtitle.setText(R.string.ai_confirm_sub)
                bindConfirm(s.draft)
                val goAction: (() -> Unit)? = if (s.draft.ready) ({ goToResult(s) }) else null
                buttons(R.string.ai_go to goAction, R.string.ai_edit_manually to { editManually(s.draft) })
            }
            is AiUiState.Candidates -> {
                layout(titleFirst = false, mascotDp = 80)
                b.title.setText(R.string.ai_pick_title)
                b.subtitle.text = getString(R.string.ai_pick_sub, s.query)
                bindCandidates(s.options)
                buttons(null, R.string.ai_pick_search to { editManually(s.draft, s.slot) })
            }
            is AiUiState.Unsupported -> {
                layout(titleFirst = false, mascotDp = 80)
                b.title.setText(R.string.ai_unsupported_title)
                b.subtitle.text = getString(R.string.ai_quote, s.said)
                b.optionsLabel.setText(R.string.ai_unsupported_ask)
                bindAlternatives()
                buttons(null, R.string.ai_say_again to { vm.backToInput() })
            }
            is AiUiState.NotUnderstood -> {
                // 미지원(2-9)과 다름: 사용자 잘못이 아니라 '말하는 법'을 보여 주고 바로 고쳐 쓰게
                layout(titleFirst = false, mascotDp = 80)
                b.title.setText(R.string.ai_unknown_title)
                b.subtitle.text = getString(R.string.ai_quote, s.said) + "\n" + getString(R.string.ai_unknown_tip)
                b.optionsLabel.setText(R.string.ai_unknown_examples)
                bindExamples()
                buttons(R.string.ai_edit_again to { vm.editAgain() },
                    R.string.ai_pick_search to { routeVm.startFresh(); findNavController().navigate(R.id.to_search) })
            }
            is AiUiState.Failed -> {
                layout(titleFirst = true, mascotDp = 120)
                b.title.setText(R.string.ai_failed_title)
                b.subtitle.setText(R.string.ai_failed_sub)
                // 자동 전환 없음: 사용자가 고른다 (설계서 4.3.4)
                buttons(R.string.ai_pick_manually to { routeVm.startFresh(); findNavController().navigate(R.id.to_search) },
                    R.string.ai_retry to { vm.retry() })
            }
        }
    }

    /** 제목과 마스코트 순서·크기 */
    private fun layout(titleFirst: Boolean, mascotDp: Int) {
        val b = _b ?: return
        val col = b.column
        col.removeView(b.titleBox); col.removeView(b.mascotBox)
        if (titleFirst) { col.addView(b.titleBox, 0); col.addView(b.mascotBox, 1) }
        else { col.addView(b.mascotBox, 0); col.addView(b.titleBox, 1) }
        val box = dp(mascotDp * 3 / 2)
        b.mascotBox.updateLayoutParams<ViewGroup.LayoutParams> { width = box; height = box }
        b.mascot.updateLayoutParams<ViewGroup.LayoutParams> { width = dp(mascotDp); height = dp(mascotDp) }
    }

    private fun buttons(primary: Pair<Int, (() -> Unit)?>?, secondary: Pair<Int, () -> Unit>?) {
        val b = _b ?: return
        b.primaryButton.isVisible = primary != null
        primary?.let { (text, action) ->
            b.primaryButton.setText(text)
            b.primaryButton.isEnabled = action != null
            b.primaryButton.alpha = if (action != null) 1f else 0.4f
            b.primaryButton.setOnClickListener { action?.invoke() }
        }
        b.secondaryButton.isVisible = secondary != null
        secondary?.let { (text, action) ->
            b.secondaryButton.setText(text)
            b.secondaryButton.setOnClickListener { action() }
        }
    }

    // ---------- 2-5 해석 확인 ----------

    private fun bindConfirm(d: AiDraft) {
        val b = _b ?: return
        bindPoint(b.originRow, Slot.ORIGIN, d.origin, d.condition.origin?.rawName, d)
        bindPoint(b.destinationRow, Slot.DESTINATION, d.destination, d.condition.destination?.rawName, d)

        val c = d.condition.constraints
        val limits = listOfNotNull(
            c.maxDurationMinutes?.let { getString(R.string.ai_limit_time, it) },
            c.maxTransfers?.let { if (it == 0) getString(R.string.ai_limit_transfer_none) else getString(R.string.ai_limit_transfer, it) },
            c.maxFareKrw?.let { getString(R.string.ai_limit_fare, NumberFormat.getNumberInstance(Locale.KOREA).format(it)) }
        )
        b.limitsLabel.isVisible = limits.isNotEmpty()
        b.limits.isVisible = limits.isNotEmpty()
        b.limits.removeAllViews()
        limits.forEachIndexed { i, t -> b.limits.addView(chip(t, selected = false, marginStart = if (i == 0) 0 else 8)) }

        val pr = d.condition.preference?.priority.orEmpty()
        b.priorityLabel.isVisible = pr.isNotEmpty()
        b.priorities.isVisible = pr.isNotEmpty()
        b.priorities.removeAllViews()
        pr.forEachIndexed { i, crit ->
            if (i > 0) b.priorities.addView(TextView(requireContext()).apply {
                text = "→"; setTextColor(requireContext().getColor(R.color.ai_text_secondary))
                setPadding(dp(6), 0, dp(6), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            b.priorities.addView(chip("${i + 1}  ${label(crit)}", selected = i == 0, marginStart = 0))
        }
    }

    private fun bindPoint(row: ItemAiPointBinding, slot: Slot, item: StationGroupItem?, raw: String?, d: AiDraft) {
        val isOrigin = slot == Slot.ORIGIN
        val label = getString(if (isOrigin) R.string.point_origin else R.string.point_destination)
        row.dot.setBackgroundResource(if (isOrigin) R.drawable.dot_origin else R.drawable.dot_destination)
        row.label.text = label
        row.name.text = item?.name ?: raw?.let { "$it · ${getString(R.string.ai_not_found)}" }
            ?: getString(if (isOrigin) R.string.ai_missing_origin else R.string.ai_missing_destination)
        row.name.setTextColor(requireContext().getColor(if (item != null) android.R.color.white else R.color.ai_error_text))
        row.root.contentDescription = getString(R.string.ai_edit_desc, "$label ${row.name.text}")
        row.root.setOnClickListener { editManually(d, slot) }
    }

    private fun chip(text: String, selected: Boolean, marginStart: Int) = TextView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { this.marginStart = dp(marginStart) }
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        this.text = text
        setTextAppearance(R.style.Text_MyWay_Label14Bold)
        setTextColor(requireContext().getColor(android.R.color.white))
        setBackgroundResource(if (selected) R.drawable.bg_ai_chip_selected else R.drawable.bg_ai_chip)
    }

    private fun label(c: Criterion) = getString(when (c) {
        Criterion.DURATION -> R.string.ai_crit_duration
        Criterion.TRANSFERS -> R.string.ai_crit_transfers
        Criterion.FARE -> R.string.ai_crit_fare
    })

    // ---------- 2-8 후보 / 2-9 대안 ----------

    private fun bindCandidates(items: List<StationGroupItem>) {
        val b = _b ?: return
        b.options.removeAllViews()
        items.forEach { item ->
            val lines = item.stations.joinToString(" · ") { getString(R.string.line_format, LineStyle.key(it.line)) }
            b.options.addView(option(item.name, lines) { vm.choose(item) })
        }
    }

    private fun bindExamples() {
        val b = _b ?: return
        b.options.removeAllViews()
        listOf(R.string.ai_ex_1, R.string.ai_ex_2, R.string.ai_ex_3).forEach { res ->
            val ex = getString(res)
            b.options.addView(option(ex, getString(R.string.ai_example_tap)) { vm.submit(ex) })
        }
    }

    private fun bindAlternatives() {
        val b = _b ?: return
        b.options.removeAllViews()
        listOf(
            RouteType.SHORTEST_TIME to R.string.result_tab_time,
            RouteType.MIN_TRANSFERS to R.string.result_tab_transfer,
            RouteType.MIN_FARE to R.string.result_tab_fare
        ).forEach { (type, text) ->
            b.options.addView(option(getString(text), null, centered = true) {
                // 지원하는 기준으로: 역은 일반 검색에서 고르고 결과는 이 탭부터
                routeVm.startFresh()
                routeVm.setPreferredType(type)
                findNavController().navigate(R.id.to_search)
            })
        }
    }

    private fun option(title: String, sub: String?, centered: Boolean = false, onClick: () -> Unit) =
        LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or (if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START)
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            setBackgroundResource(R.drawable.bg_ai_option)
            contentDescription = listOfNotNull(title, sub).joinToString(", ")
            setOnClickListener { onClick() }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (centered) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(R.style.Text_MyWay_Body16Bold)
                    setTextColor(context.getColor(android.R.color.white))
                })
                sub?.let { s ->
                    addView(TextView(context).apply {
                        text = s
                        setTextAppearance(R.style.Text_MyWay_Caption12Medium)
                        setTextColor(context.getColor(R.color.ai_text_secondary))
                    })
                }
            })
            if (!centered) addView(android.widget.ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ai_text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

    // ---------- 이동 ----------

    /** 2-5 '이대로 길찾기' → 결과(2-6). 확인한 조건만 탐색에 쓴다 */
    private fun goToResult(s: AiUiState.Confirm) {
        val o = s.draft.origin ?: return
        val d = s.draft.destination ?: return
        val c = s.draft.condition
        routeVm.setAiRoute(o, d, RouteSelectionViewModel.AiExtras(
            said = s.said,
            constraints = c.constraints,
            preference = c.preference,
            preferredType = AiModeViewModel.preferredType(c)
        ))
        findNavController().navigate(R.id.to_result)
    }

    /** 직접 수정: 정해진 역은 그대로 두고 일반 검색으로 */
    private fun editManually(d: AiDraft, slot: Slot? = null) {
        val o = d.origin; val dest = d.destination
        when {
            o != null && dest != null && slot == null -> routeVm.setRoute(o, dest)
            else -> {
                routeVm.clear(Slot.ORIGIN); routeVm.clear(Slot.DESTINATION)
                if (slot != Slot.ORIGIN && o != null) routeVm.set(Slot.ORIGIN, o)
                if (slot != Slot.DESTINATION && dest != null) routeVm.set(Slot.DESTINATION, dest)
            }
        }
        if (slot != null) routeVm.clear(slot)
        findNavController().navigate(R.id.to_search)
    }

    // ---------- 기타 ----------

    /** 2-4 ● ● ● 차례로 깜빡임 */
    private fun setDots(on: Boolean) {
        val b = _b ?: return
        dotAnims.forEach { it.cancel() }; dotAnims.clear()
        if (!on) return
        b.dots.children.forEachIndexed { i, v ->
            dotAnims += ObjectAnimator.ofFloat(v, View.ALPHA, 0.3f, 1f).apply {
                duration = 450; startDelay = i * 150L
                repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun hideKeyboard() {
        val b = _b ?: return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.input.windowToken, 0)
        b.input.clearFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        floatAnims.forEach { if (it.isPaused) it.resume() }
        dotAnims.forEach { if (it.isPaused) it.resume() }
    }

    override fun onStop() {
        floatAnims.forEach { it.pause() }
        dotAnims.forEach { it.pause() }
        super.onStop()
    }

    override fun onDestroyView() {
        micShown = null; lastKind = null
        tutorial?.dismiss(); tutorial = null
        dotAnims.forEach { it.cancel() }; dotAnims.clear()
        floatAnims.forEach { it.cancel() }; floatAnims.clear()
        _b = null
        super.onDestroyView()
    }
}
