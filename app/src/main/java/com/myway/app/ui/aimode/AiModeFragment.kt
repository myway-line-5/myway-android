package com.myway.app.ui.aimode

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.myway.app.data.local.SettingsStore
import com.myway.app.ui.common.VoiceAsk
import com.myway.app.ui.common.VoiceCommand
import com.myway.app.ui.common.VoiceSession
import com.myway.app.voice.TtsManager
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
import com.myway.app.data.remote.NetworkMonitor
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
import com.myway.app.ui.common.displayName
import com.myway.app.ui.common.bilingualKeyboard

/** Figma 2-1~2-10 AI 모드 */
class AiModeFragment : Fragment(R.layout.fragment_ai_mode) {

    private val vm: AiModeViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentAiModeBinding? = null
    private val dotAnims = mutableListOf<ValueAnimator>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentAiModeBinding.bind(view).also { _b = it }
        tts = TtsManager(requireContext(), if (com.myway.app.ui.common.StationNames.english) java.util.Locale.ENGLISH else java.util.Locale.KOREAN)
        voice = VoiceAsk(requireActivity(), tts)

        b.helpButton.setOnClickListener { Tutorial.fromAi = true; startTutorial(Tutorial.AI.first) }
        Tutorial.consume(Tutorial.AI)?.let { step -> view.post { startTutorial(step) } }
        b.settingsButton.setOnClickListener { findNavController().navigate(R.id.to_settings) }
        b.backButton.setOnClickListener { stepBack() }
        // 시스템 뒤로가기도 '한 단계 뒤로' (입력 화면에서만 홈으로 나감)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        b.normalButton.setOnClickListener { findNavController().popBackStack() }
        // 입력창 오른쪽 버튼: 비었으면 🎤 말하기, 글자가 있으면 ↑ 보내기 (Figma MicButton 424:43)
        b.sendButton.setOnClickListener { if (b.input.text.isNullOrBlank()) listen() else { VoiceSession.active = false; send() } }
        // 2-3 듣는 중 버튼들: ■ = 지금까지 들은 말로 바로 해석, 취소, 글자로 입력
        b.stopButton.setOnClickListener { recognizer?.stopListening() }
        b.listenCancel.setOnClickListener { cancelListening() }
        b.listenType.setOnClickListener { typeInstead() }
        // 자동 배치 애니메이션 끔: 단계 전환은 swapIn() 하나로만 연출 (겹치면 잔상·웨이 날아다님)
        b.column.layoutTransition = null
        // 웨이가 이전 자리에서 새 자리로 옮겨 가는 동안 상자 밖으로 나가도 잘리지 않게
        b.mascotBox.clipChildren = false; b.mascotBox.clipToPadding = false
        b.column.clipChildren = false; b.column.clipToPadding = false
        // 키보드가 뜨고 내려갈 때 진행 중인 화면 전환 애니메이션을 끝냄 → 입력창이 키보드 위에 딱 붙음
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (ime != imeShown) {
                imeShown = ime
                android.transition.TransitionManager.endTransitions(v as android.view.ViewGroup)
                v.requestLayout()
                v.post { _b?.bottomArea?.requestLayout(); v.requestLayout() }
            }
            insets
        }
        b.input.bilingualKeyboard()                // 화면 언어와 상관없이 한·영 문장 입력
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

    /** 해석 중 → 취소, 듣는 중 → 듣기 취소, 그 외 → 입력 화면 (확인된 조건은 유지) */
    private fun stepBack() {
        when (vm.state.value) {
            is AiUiState.Interpreting -> vm.cancel()
            is AiUiState.Listening -> cancelListening()
            else -> vm.backToInput()
        }
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

    // ---------- 음성 입력: 구글 팝업 대신 앱 화면 안에서 듣기 (Figma 2-3 듣는 중 · 2-7 실패) ----------

    private var recognizer: SpeechRecognizer? = null

    /** 마이크 권한이 없으면 먼저 묻고, 허용하면 바로 듣기 */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        when {
            ok -> startListening()
            // '다시 묻지 않음' 상태: 시스템 팝업이 더는 안 뜸 → 설정으로 가는 길을 알려 줌
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> micBlocked()
            else -> Unit                                           // 방금 거절: 그대로 둠 (입력창 사용)
        }
    }

    /** 한 번 거절한 뒤 다시 누름: 왜 필요한지 먼저 말하고 시스템 팝업 */
    private fun micWhy() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_mic_why_title)
            .setMessage(R.string.ai_mic_why_body)
            .setPositiveButton(R.string.ai_mic_allow) { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            .setNegativeButton(R.string.ai_type_instead) { _, _ -> typeInstead() }
            .show()
    }

    /** 권한이 완전히 막힘: 앱 정보 화면으로 바로 보냄 */
    private fun micBlocked() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_mic_off_title)
            .setMessage(R.string.ai_mic_off_body)
            .setPositiveButton(R.string.ai_mic_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)))
            }
            .setNegativeButton(R.string.ai_type_instead) { _, _ -> typeInstead() }
            .show()
    }

    private fun listen() {
        val ctx = requireContext()
        if (!SettingsStore.bool(SettingsStore.Key.STT)) return     // 설정 > 음성 > 음성 입력 꺼짐
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, R.string.ai_listen_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) micWhy()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        hideKeyboard()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(requireContext()).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _b?.wave?.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _b?.wave?.level = 0f }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { vm.heard(it) }
            }
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?: (vm.state.value as? AiUiState.Listening)?.heard
                if (said.isNullOrBlank()) vm.noSpeech() else finishListening(said)
            }
            override fun onError(error: Int) {
                // 이미 취소했으면 무시. 조금이라도 들렸으면 그 말로 진행, 아니면 2-7
                val s = vm.state.value as? AiUiState.Listening ?: return
                if (s.heard.isNotBlank()) finishListening(s.heard) else vm.noSpeech()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        vm.startListening()
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (com.myway.app.ui.common.StationNames.english) "en-US" else "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().packageName))
    }

    /** 들은 문장을 입력창에 넣고 바로 해석. 마이크로 시작했으니 이후 단계도 말로 이어 감 (핸즈프리) */
    private fun finishListening(said: String) {
        _b?.input?.setText(said)
        VoiceSession.active = true
        send()
    }

    // ---- 핸즈프리: 확인·후보·다시 묻기를 말로 ----
    private var tts: TtsManager? = null
    private var voice: VoiceAsk? = null
    private var voiceAskedFor: Any? = null

    private fun voiceTurn(s: AiUiState) {
        val v = voice ?: return
        if (!VoiceSession.active || voiceAskedFor === s) return
        voiceAskedFor = s
        when (s) {
            is AiUiState.Confirm -> {
                val o = s.draft.origin?.name?.displayName(); val d = s.draft.destination?.name?.displayName()
                if (!s.draft.ready || o == null || d == null) {
                    v.ask(getString(R.string.voice_need_stations), getString(R.string.voice_hint_say)) { heard ->
                        if (heard == null) endVoice() else vm.submit(heard)
                    }
                    return
                }
                val pref = when (AiModeViewModel.preferredType(s.draft.condition)) {
                    RouteType.SHORTEST_TIME -> getString(R.string.voice_pref_time)
                    RouteType.MIN_TRANSFERS -> getString(R.string.voice_pref_transfers)
                    RouteType.MIN_FARE -> getString(R.string.voice_pref_fare)
                    else -> ""
                }
                v.ask(getString(R.string.voice_confirm, o, d, pref), getString(R.string.voice_hint_yes_no)) { heard ->
                    when (VoiceCommand.parse(heard)) {
                        VoiceCommand.Cmd.YES -> (vm.state.value as? AiUiState.Confirm)?.let { goToResult(it) }
                        VoiceCommand.Cmd.AGAIN -> { voiceAskedFor = null; voiceTurn(s) }
                        VoiceCommand.Cmd.NO -> endVoice()
                        else -> when {
                            heard == null -> endVoice()
                            heard.replace(" ", "").length <= 2 -> { voiceAskedFor = null; voiceTurn(s) }   // 짧고 애매한 대답 → 다시 물음
                            else -> vm.submit(heard)     // "아니, 출발은 종로3가" → 고쳐서 다시 확인
                        }
                    }
                }
            }
            is AiUiState.Candidates -> {
                val names = s.options.map { it.name.displayName() }
                v.ask(getString(R.string.voice_candidates, names.joinToString(", ")), getString(R.string.voice_hint_say)) { heard ->
                    val h = heard?.replace(" ", "")
                    val pick = s.options.firstOrNull { h != null && (h.contains(it.name.replace(" ", "")) || h.contains(it.name.displayName().replace(" ", ""), ignoreCase = true)) }
                    if (pick != null) vm.choose(pick) else endVoice()
                }
            }
            is AiUiState.NotUnderstood, is AiUiState.Unsupported -> {
                v.ask(getString(R.string.voice_retry), getString(R.string.voice_hint_say)) { heard ->
                    if (heard == null) endVoice() else { _b?.input?.setText(heard); vm.submit(heard) }
                }
            }
            is AiUiState.Failed -> { v.say(getString(R.string.ai_failed_title)); endVoice(quiet = true) }
            else -> {}
        }
    }

    /** 말 대화 끝: 화면 조작으로 이어 감 */
    private fun endVoice(quiet: Boolean = false) {
        VoiceSession.active = false
        if (!quiet) voice?.say(getString(R.string.voice_bye))
    }

    private fun cancelListening() {
        recognizer?.cancel()
        vm.backToInput()
    }

    /** 말 대신 글자로: 듣기를 멈추고 입력창에 키보드 */
    private var imeShown = false

    /** 화면 전환(260ms)이 끝난 뒤에 키보드를 올림 — 전환 도중 올라오면 배치가 옛 높이로 굳어 입력창 아래 빈 공간이 남음 */
    private fun showKeyboardAfterSwap() {
        val b = _b ?: return
        b.input.postDelayed({
            val bb = _b ?: return@postDelayed
            if (!bb.input.hasFocus()) bb.input.requestFocus()
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(bb.input, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun typeInstead() {
        recognizer?.cancel()
        vm.backToInput()
        val b = _b ?: return
        b.input.requestFocus()
        showKeyboardAfterSwap()
    }

    private var micShown: Boolean? = null
    private fun updateSendButton() {
        val b = _b ?: return
        val empty = b.input.text.isNullOrBlank()
        val stt = SettingsStore.bool(SettingsStore.Key.STT)
        b.sendButton.alpha = if (empty && !stt) 0.4f else 1f     // 음성 입력 끔: 빈칸이면 보내기 비활성
        val mic = empty && stt
        if (micShown == mic) return
        val first = micShown == null
        micShown = mic
        val apply = {
            b.sendButton.setImageResource(if (mic) R.drawable.ic_perm_mic else R.drawable.ic_send)
            b.sendButton.contentDescription = getString(if (mic) R.string.ai_listen else R.string.ai_send)
        }
        if (first) apply() else Motion.swap(b.sendButton, apply)      // 🎤 ↔ ↑ 바뀌는 순간을 보이게
    }

    private fun send() {
        val b = _b ?: return
        if (!NetworkMonitor.isOnline) {   // 오프라인: AI 해석 불가, 입력은 유지
            Toast.makeText(requireContext(), R.string.ai_offline, Toast.LENGTH_SHORT).show()
            return
        }
        vm.submit(b.input.text.toString())
        hideKeyboard()
    }

    // ---------- 그리기 ----------

    private var lastKind: Any? = null
    /**
     * 단계 전환 연출 — 한 가지 방식만 씀 (여러 애니메이션이 겹치면 잔상·날아다님이 생김)
     * 웨이: 이전 자리·크기에서 새 자리·크기로 이어서 움직임 (같은 캐릭터가 계속 있는 느낌)
     * 나머지: 바로 바뀐 뒤, 웨이가 거의 자리 잡을 즈음 살짝 떠오르며 나타남
     */
    private fun render(s: AiUiState) {
        val b = _b ?: return
        val kind = s::class
        val swap = lastKind != null && lastKind != kind
        val oldW = b.mascotBox.width
        val oldCy = IntArray(2).also { b.mascotBox.getLocationOnScreen(it) }[1] - b.mascotBox.translationY + oldW / 2f
        renderState(s)
        if (swap && oldW > 0) swapIn(oldW, oldCy)
        voiceTurn(s)
    }

    private fun swapIn(oldW: Int, oldCy: Float) {
        val b = _b ?: return
        val others = b.column.children.filter { it !== b.mascotBox && it.isVisible }.toList() + b.bottomArea
        others.forEach { v ->
            v.animate().cancel()
            v.alpha = 0f; v.translationY = dp(8).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(200).setInterpolator(Motion.ease).start()
        }
        val box = b.mascotBox
        box.doOnNextLayout {
            if (_b == null) return@doOnNextLayout
            val newW = box.width.takeIf { it > 0 } ?: return@doOnNextLayout
            val loc = IntArray(2).also { box.getLocationOnScreen(it) }
            val newCy = loc[1] - box.translationY + newW / 2f
            val sc = oldW.toFloat() / newW
            box.animate().cancel()
            box.scaleX = sc; box.scaleY = sc
            box.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(Motion.ease).start()
            val dy = (oldCy - newCy) / sc
            box.children.forEach { c ->
                c.animate().cancel()
                c.translationY = dy
                c.animate().translationY(0f).setDuration(280).setInterpolator(Motion.ease).start()
            }
        }
    }

    private fun renderState(s: AiUiState) {
        val b = _b ?: return
        lastKind = s::class
        b.subtitle.setTextColor(requireContext().getColor(R.color.ai_text_secondary))   // 경고색은 같은 역일 때만
        b.busyBlock.isVisible = s is AiUiState.Interpreting
        b.confirmCard.isVisible = s is AiUiState.Confirm
        b.confirmHelp.isVisible = s is AiUiState.Confirm && s.draft.condition.preference != null
        b.options.isVisible = s is AiUiState.Candidates || s is AiUiState.Unsupported || s is AiUiState.NotUnderstood
        b.optionsLabel.isVisible = s is AiUiState.Unsupported || s is AiUiState.NotUnderstood
        b.errorBox.isVisible = s is AiUiState.Failed
        b.inputBar.isVisible = s is AiUiState.Idle
        b.listenBar.isVisible = s is AiUiState.Listening
        b.wave.running = s is AiUiState.Listening
        b.heardText.isVisible = s is AiUiState.Listening
        b.noSpeechCard.isVisible = s is AiUiState.NoSpeech
        b.status.isVisible = s is AiUiState.Interpreting || s is AiUiState.Listening   // 처음 화면엔 안 띄움 (듣지 않는데 "듣고 있어요"는 거짓)
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
                    showKeyboardAfterSwap()
                }
            }
            is AiUiState.Listening -> {                       // 2-3
                layout(titleFirst = true, mascotDp = 160)
                b.title.setText(R.string.ai_idle_title)
                b.subtitle.setText(R.string.ai_idle_sub)
                b.status.setText(R.string.ai_idle_status)
                b.heardText.text = s.heard.ifBlank { getString(R.string.ai_heard_hint) }
                b.heardText.alpha = if (s.heard.isBlank()) 0.5f else 1f
                buttons(null, null)
            }
            AiUiState.NoSpeech -> {                                // 2-7
                layout(titleFirst = true, mascotDp = 120)
                b.title.setText(R.string.ai_nospeech_title)
                b.subtitle.setText(R.string.ai_nospeech_sub)
                buttons(R.string.ai_say_again to { listen() }, R.string.ai_type_instead to { typeInstead() })
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
                // 같은 역이면 이유를 바로 알려 주고 [길 찾기]는 막음 → [직접 수정]으로 고치게
                if (s.draft.sameStation) b.subtitle.text = getString(R.string.ai_same_station, s.draft.origin?.name?.displayName().orEmpty())
                else b.subtitle.setText(R.string.ai_confirm_sub)
                if (s.draft.sameStation) b.subtitle.setTextColor(requireContext().getColor(R.color.ai_error_text))
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
    /**
     * 단계별 웨이 크기·제목 순서 (Figma 2-1~2-10).
     * 뷰를 빼고 다시 넣으면 기본 애니메이션이 (0,0)에서 출발해 웨이가 옆에서 날아옴 → 그 애니메이션은 끄고,
     * 웨이는 제자리(가운데)에서 커지거나 작아지며 위아래로만 부드럽게 옮겨 감. 제목은 살짝 나타남.
     */
    private fun layout(titleFirst: Boolean, mascotDp: Int) {
        val b = _b ?: return
        val col = b.column
        val box = b.mascotBox
        val target = dp(mascotDp)
        val ordered = (col.indexOfChild(b.titleBox) < col.indexOfChild(box)) == titleFirst
        if (ordered && b.mascot.layoutParams.width == target) return

        val lt = col.layoutTransition
        col.layoutTransition = null
        if (!ordered) {
            col.removeView(b.titleBox); col.removeView(box)
            if (titleFirst) { col.addView(b.titleBox, 0); col.addView(box, 1) }
            else { col.addView(box, 0); col.addView(b.titleBox, 1) }
        }
        mascotSize(target)
        col.layoutTransition = lt
    }

    private fun mascotSize(px: Int) {
        val b = _b ?: return
        b.mascotBox.updateLayoutParams<ViewGroup.LayoutParams> { width = px * 3 / 2; height = px * 3 / 2 }
        b.mascot.updateLayoutParams<ViewGroup.LayoutParams> { width = px; height = px }
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
        row.name.text = item?.name?.displayName() ?: raw?.let { "$it · ${getString(R.string.ai_not_found)}" }
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
            b.options.addView(option(item.name.displayName(), lines) { vm.choose(item) })
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
        recognizer?.destroy(); recognizer = null
        voice?.close(); voice = null
        tts?.shutdown(); tts = null
        // voiceAskedFor 는 남겨 둠: 결과·지도에서 돌아왔을 때 같은 확인을 다시 묻지 않게
        if (vm.state.value is AiUiState.Listening) vm.backToInput()   // 화면을 떠나면 듣기 종료
        _b = null
        super.onDestroyView()
    }
}
