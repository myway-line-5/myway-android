package com.myway.app.ui.trip

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.myway.app.R
import com.myway.app.databinding.DialogConfirmBinding
import com.myway.app.databinding.FragmentTripGuideBinding
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.Motion
import com.myway.app.ui.common.StationNames
import com.myway.app.ui.common.displayName
import com.myway.app.ui.result.Timeline
import com.myway.app.voice.TtsManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Figma 3-2 ~ 3-12 이동 중 안내 (Phase 2 · 실시간 API 전 껍데기) */
class TripGuideFragment : Fragment(R.layout.fragment_trip_guide) {


    private val vm: TripViewModel by viewModels()
    private var _b: FragmentTripGuideBinding? = null
    private var tts: TtsManager? = null
    private var lastKind: Any? = null

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = confirmEnd()           // 뒤로가기 = 안내 종료 확인 (3-12)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentTripGuideBinding.bind(view).also { _b = it }
        val plan = vm.plan ?: run { findNavController().popBackStack(); return }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        tts = TtsManager(requireContext(), if (StationNames.english) java.util.Locale.ENGLISH else java.util.Locale.KOREAN)
        // 기능 28: 화면이 꺼져도 안내가 이어지게 포그라운드 서비스 시작 (음성·진동·알림은 서비스가 담당)
        if (vm.state.value !is TripState.Arrived) GuideService.start(requireContext())   // 도착 뒤 화면 재생성 시 안내 중복 방지

        b.routePill.text = getString(R.string.trip_route, plan.origin.displayName(), plan.destination.displayName())
        b.endButton.setOnClickListener { confirmEnd() }
        b.feedChip.setOnLongClickListener { demoMenu(it); true }        // 시연: 실시간 API 없이 예외 화면 보기

        childFragmentManager.setFragmentResultListener(TrainPickSheet.KEY, viewLifecycleOwner) { _, r -> vm.boardNext(r.getInt(TrainPickSheet.RES_WAIT)) }

        // 핸즈프리로 시작: 어떤 열차로 안내하는지 말하고, 다른 열차면 '다음 열차'
        voice = com.myway.app.ui.common.VoiceAsk(requireActivity(), tts)
        if (TripSession.voiceStart && savedInstanceState == null) {
            TripSession.voiceStart = false
            val first = plan.legs.first()
            askTrain(first)
        }

        // 진행 막대: 열차 이동과 같은 시계(vm.pos)로 갱신 → 구간 길이만큼 빠르게·느리게, 끝까지 참. 사이 프레임은 막대가 스스로 부드럽게 보간
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.state, vm.pos, vm.waitLeft) { s, x, w -> Triple(s, x, w) }.collect { (s, x, w) ->
                    if (s is TripState.Waiting || s is TripState.BoardCheck) {   // 열차 기다리는 중: 큰 숫자 = 남은 시간
                        val left = if (s is TripState.BoardCheck) 0 else w
                        _b?.remainCount?.text = "%d:%02d".format(left / 60, left % 60)
                        _b?.progress?.fraction = 0f
                        return@collect
                    }
                    val r = s as? TripState.Riding ?: return@collect
                    val n = plan.legs[r.leg].stops.lastIndex
                    _b?.progress?.fraction = at(r, x) / n
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // canUndo 도 바뀌면 다시 그림 ([못 탔어요] 버튼이 1분 뒤 사라지게)
                combine(vm.state, vm.feed, vm.muted, vm.canUndo) { s, f, m, _ -> Triple(s, f, m) }
                    .collect { (s, f, m) -> render(plan, s, f, m) }
            }
        }
    }

    private fun render(p: TripPlan, s: TripState, feed: Feed, muted: Boolean) {
        val b = _b ?: return
        val ctx = requireContext()
        if (lastKind != null && lastKind != s::class) Motion.refresh(b.root)   // 단계 바뀔 때 살짝 전환
        lastKind = s::class

        b.rideGroup.isVisible = s is TripState.Riding || s is TripState.Waiting || s is TripState.BoardCheck
        b.alightGroup.isVisible = s is TripState.AlightNow
        b.transferGroup.isVisible = s is TripState.Transfer
        b.warnGroup.isVisible = s is TripState.Passed || s is TripState.WrongWay
        b.arrivedGroup.isVisible = s is TripState.Arrived

        // 3-4만 남색 전체 화면
        val dark = s is TripState.AlightNow
        b.root.setBackgroundColor(ctx.getColor(if (dark) R.color.brand_navy else R.color.bg_surface))
        b.routePill.setTextColor(ctx.getColor(if (dark) R.color.text_on_brand else R.color.text_primary))
        b.routePill.setBackgroundResource(if (dark) R.drawable.bg_trip_on_dark else R.drawable.bg_trip_pill)
        b.routePill.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.endButton.imageTintList = ColorStateList.valueOf(ctx.getColor(if (dark) R.color.text_on_brand else R.color.icon_default))
        b.topBar.isVisible = s !is TripState.Arrived

        renderFeed(feed, dark)
        val sound = getString(if (muted) R.string.trip_unmute else R.string.trip_mute)

        when (s) {
            is TripState.BoardCheck -> {
                val leg = p.legs[s.leg]
                b.stationNow.text = leg.board.name.displayName()
                b.stationNowSub.text = getString(R.string.trip_wait_sub, leg.line, leg.toward.displayName())
                b.untilLabel.setText(R.string.trip_wait_until)
                b.stopsUnit.isVisible = false
                b.eta.setText(R.string.trip_board_check_sub)
                bindLeg(p, s.leg)
                // 띠: 열차가 왔어요 · 타셨어요?
                b.banner.isVisible = true
                b.banner.backgroundTintList = null
                b.bannerTitle.setText(R.string.trip_board_check_title)
                b.bannerTitle.setTextColor(ctx.getColor(R.color.text_primary))
                b.bannerSub.isVisible = false
                buttons(getString(R.string.trip_missed), { vm.missed() }, getString(R.string.trip_boarded), { vm.boarded() }, primary = true)
                // 말로 시작했으면 말로 묻기 (묻는 동안 '대답 없음' 시계는 멈춤)
                if (vm.handsFree && askedCheck != s) {
                    askedCheck = s
                    val v = voice
                    if (v != null) {
                        vm.hold = true
                        v.ask(getString(R.string.trip_say_board_check), getString(R.string.voice_hint_boarded)) { heard ->
                            vm.hold = false
                            when (com.myway.app.ui.common.VoiceCommand.parse(heard)) {
                                com.myway.app.ui.common.VoiceCommand.Cmd.YES -> vm.boarded()
                                com.myway.app.ui.common.VoiceCommand.Cmd.NO -> vm.missed()
                                else -> {}                                  // 대답 없음 → 10초 뒤 탄 것으로
                            }
                        }
                    }
                }
            }
            is TripState.Waiting -> {
                val leg = p.legs[s.leg]
                // 핸즈프리 환승 뒤: 화면을 보고 있으면 탈 열차를 말로 알려 주고 '다음 열차'를 들음
                if (vm.handsFree && s.leg > 0 && askedWait != s) { askedWait = s; askTrain(leg) }
                b.stationNow.text = leg.board.name.displayName()
                b.stationNowSub.text = getString(R.string.trip_wait_sub, leg.line, leg.toward.displayName())
                b.untilLabel.setText(R.string.trip_wait_until)
                b.stopsUnit.isVisible = false
                b.remainCount.setTextColor(ctx.getColor(R.color.text_primary))
                // 고른 열차 기준 도착 예정: 지금 + 남은 기다림 + 탄 뒤 걸리는 시간
                b.eta.text = getString(R.string.trip_wait_eta, p.destination.displayName(),
                    Timeline.clock(LocalTime.now().plusSeconds((vm.waitLeft.value + p.restSeconds(s.leg)).toLong())))
                bindLeg(p, s.leg)
                banner(feed, null)
                buttons(sound, { vm.toggleMute() }, getString(R.string.trip_view_route), { openRouteMap() }, primary = false, muted = muted)
            }
            is TripState.Riding -> {
                b.untilLabel.setText(R.string.trip_until)
                b.stopsUnit.isVisible = true
                val leg = p.legs[s.leg]
                val left = leg.stops.lastIndex - s.at
                val sec = leg.hops.drop(s.at).sum()
                val eta = LocalTime.now().plusSeconds(sec.toLong())
                val estimated = feed != Feed.LIVE
                b.stationNow.text = leg.stops[s.at].name.displayName()
                b.stationNowSub.text = getString(R.string.trip_now_sub, leg.line, leg.toward.displayName(),
                    leg.stops[s.at + 1].name.displayName())
                b.remainCount.text = left.toString()
                b.remainCount.setTextColor(ctx.getColor(if (left <= 2) R.color.status_error else R.color.text_primary))  // 3-3 곧 내림
                b.eta.text = getString(if (estimated) R.string.trip_eta_estimate else R.string.trip_eta,
                    Timeline.minutes(sec), Timeline.clock(eta))
                bindLeg(p, s.leg)
                b.progress.fraction = at(s, vm.pos.value) / leg.stops.lastIndex
                banner(feed, if (left <= 2) getString(R.string.trip_soon_banner, left) else null)
                // 대답 없이 '탄 것으로' 시작했으면 1분 동안 [못 탔어요]로 되돌릴 수 있게
                if (vm.canUndo.value && s.at == 0)
                    buttons(getString(R.string.trip_missed), { vm.missed() }, getString(R.string.trip_view_route), { openRouteMap() }, primary = false)
                else
                    buttons(sound, { vm.toggleMute() }, getString(R.string.trip_view_route), { openRouteMap() }, primary = false, muted = muted)

            }
            is TripState.AlightNow -> {
                val leg = p.legs[s.leg]; val next = p.legs[s.leg + 1]
                b.alightStation.text = leg.alight.name.displayName()
                b.alightNextBadge.removeAllViews()
                b.alightNextBadge.addView(LineStyle.badge(ctx, next.line, sizeRes = R.dimen.scope_badge, textStyle = R.style.Text_MyWay_Label14Bold))
                b.alightNextTitle.text = getString(R.string.trip_transfer_to, next.line)
                b.alightNextSub.text = getString(R.string.trip_next_transfer_sub, next.toward.displayName(),
                    Timeline.minutes(p.walks[s.leg]).coerceAtLeast(1))
                banner(Feed.LIVE, null)
                single(getString(R.string.trip_transfer_guide), onDark = true) { vm.showTransfer() }
            }
            is TripState.Transfer -> {
                val here = p.legs[s.leg].alight; val next = p.legs[s.leg + 1]
                b.transferBadge.removeAllViews()
                b.transferBadge.addView(LineStyle.badge(ctx, next.line, sizeRes = R.dimen.trip_badge_large, textStyle = R.style.Text_MyWay_Body16Bold))
                b.transferTitle.text = getString(R.string.trip_transfer_title, next.toward.displayName())
                b.transferSub.text = getString(R.string.trip_transfer_sub, next.line, here.name.displayName())
                steps(b.transferSteps, listOf(
                    getString(R.string.trip_step1) to getString(R.string.trip_step1_sub, here.name.displayName()),
                    getString(R.string.trip_step2, next.line) to getString(R.string.trip_step2_sub, Timeline.minutes(p.walks[s.leg]).coerceAtLeast(1)),
                    getString(R.string.trip_step3, next.line) to getString(R.string.trip_step3_sub, next.toward.displayName())
                ), numbered = true)
                banner(feed, null)
                buttons(sound, { vm.toggleMute() }, getString(R.string.trip_pick_next, next.line), muted = muted, onRight = {
                    if (childFragmentManager.findFragmentByTag(TrainPickSheet.TAG) == null)
                        TrainPickSheet.newInstance(s.leg + 1).show(childFragmentManager, TrainPickSheet.TAG)
                }, primary = true)
            }
            is TripState.Passed, is TripState.WrongWay -> {
                val legIdx = if (s is TripState.Passed) s.leg else (s as TripState.WrongWay).leg
                val leg = p.legs[legIdx]
                if (s is TripState.Passed) {
                    b.warnLabel.setText(R.string.trip_passed_label)
                    b.warnTitle.text = leg.alight.name.displayName()
                    b.warnSub.setText(R.string.trip_passed_sub)
                } else {
                    b.warnLabel.setText(R.string.trip_wrong_label)
                    b.warnTitle.setText(R.string.trip_wrong_title)
                    b.warnSub.text = getString(R.string.trip_wrong_sub, leg.toward.displayName())
                }
                steps(b.rerouteSteps, listOf(
                    getString(R.string.trip_reroute_1) to null,
                    getString(R.string.trip_reroute_2, if (s is TripState.Passed) leg.board.name.displayName() else leg.toward.displayName()) to null,
                    getString(R.string.trip_reroute_3, leg.alight.name.displayName()) to null
                ), numbered = true)
                banner(feed, null)
                buttons(getString(R.string.trip_keep_going), { vm.keepGoing() },
                    getString(R.string.trip_accept_reroute), { vm.acceptReroute() }, primary = true)
            }
            is TripState.Arrived -> {
                b.arrivedStation.text = p.destination.displayName()
                Greeting.at(s.actual).let { g ->
                    b.greetIcon.setImageResource(g.icon)
                    b.greetTitle.setText(g.title)
                    b.greetSub.setText(g.sub)
                }
                b.arrivedSub.text = getString(R.string.trip_arrived_sub, Timeline.minutes(p.totalSeconds), p.transferCount)
                b.plannedTime.text = Timeline.clock(s.planned)
                b.actualTime.text = Timeline.clock(s.actual)
                val diff = java.time.Duration.between(s.planned, s.actual).toMinutes().toInt()
                b.diffBadge.text = getString(if (diff > 0) R.string.trip_late else R.string.trip_early, kotlin.math.abs(diff))
                b.diffBadge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (diff > 0) R.color.status_error else R.color.brand_navy))
                banner(Feed.LIVE, null)
                val saved = TripSession.isFavorite?.invoke() == true
                buttons(getString(if (saved) R.string.trip_saved else R.string.trip_save), {
                    if (TripSession.isFavorite?.invoke() != true) TripSession.saveFavorite?.invoke()
                    _b?.leftButton?.setText(R.string.trip_saved)
                }, getString(R.string.trip_done), { finish() }, primary = true)
            }
        }
    }

    /** 상단 칩: 실시간(빨강) / 추정 / 오프라인 */
    private fun renderFeed(feed: Feed, dark: Boolean) {
        val b = _b ?: return
        val ctx = requireContext()
        val (text, color) = when (feed) {
            Feed.LIVE -> R.string.trip_feed_live to R.color.status_error
            Feed.DELAYED -> R.string.trip_feed_estimate to R.color.text_secondary
            Feed.OFFLINE -> R.string.trip_feed_offline to R.color.text_secondary
        }
        val c = ctx.getColor(if (dark) R.color.text_on_brand else color)
        b.feedChip.setText(text)
        b.feedChip.setTextColor(c)
        b.feedChip.backgroundTintList = ColorStateList.valueOf(c)
    }

    /** 안내 띠: 곧 내림(빨강) > 지연·오프라인(회색) */
    private fun banner(feed: Feed, soon: String?) {
        val b = _b ?: return
        val ctx = requireContext()
        when {
            soon != null -> {
                b.bannerTitle.text = soon; b.bannerSub.isVisible = false
                b.bannerTitle.setTextColor(ctx.getColor(R.color.status_error))
                b.banner.backgroundTintList = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(ctx.getColor(R.color.status_error), 0x1F))
            }
            feed != Feed.LIVE -> {
                val off = feed == Feed.OFFLINE
                b.bannerTitle.setText(if (off) R.string.trip_offline_title else R.string.trip_delay_title)
                b.bannerSub.setText(if (off) R.string.trip_offline_sub else R.string.trip_delay_sub)
                b.bannerSub.isVisible = true
                b.bannerTitle.setTextColor(ctx.getColor(R.color.text_primary))
                b.banner.backgroundTintList = null
            }
        }
        b.banner.isVisible = soon != null || feed != Feed.LIVE
    }

    /** muted 를 주면 왼쪽은 소리 버튼: 아이콘이 지금 상태(🔊 켜짐 / 🔇 꺼짐), 꺼졌을 땐 칠한 배경으로 눈에 띄게 */
    private fun buttons(left: String, onLeft: () -> Unit, right: String, onRight: () -> Unit, primary: Boolean, muted: Boolean? = null) {
        val b = _b ?: return
        val ctx = requireContext()
        b.leftButton.isVisible = true
        b.leftButton.text = muted?.let { withIcon(left, if (it) R.drawable.ic_volume_off else R.drawable.ic_set_voice) } ?: left
        b.leftButton.setOnClickListener { onLeft() }
        b.leftButton.setBackgroundResource(if (muted == true) R.drawable.bg_button_muted else R.drawable.bg_button_secondary)
        b.leftButton.setTextColor(ctx.getColor(R.color.text_primary))
        b.rightButton.text = right; b.rightButton.setOnClickListener { onRight() }
        b.rightButton.setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        b.rightButton.setTextColor(ctx.getColor(if (primary) R.color.text_on_brand else R.color.text_primary))
    }

    /** 글자 앞에 아이콘 (아이콘과 글자가 한 덩어리로 가운데 정렬) */
    private fun withIcon(text: String, icon: Int): CharSequence {
        val d = androidx.core.content.ContextCompat.getDrawable(requireContext(), icon)?.mutate() ?: return text
        d.setBounds(0, 0, dp(20), dp(20))
        d.setTint(requireContext().getColor(R.color.text_primary))
        return android.text.SpannableStringBuilder("  ").append(text).apply {
            setSpan(android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_CENTER.takeIf { android.os.Build.VERSION.SDK_INT >= 29 }
                ?: android.text.style.ImageSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 버튼 하나만 (3-4) */
    private fun single(text: String, onDark: Boolean, onClick: () -> Unit) {
        val b = _b ?: return
        b.leftButton.isVisible = false
        b.rightButton.text = text; b.rightButton.setOnClickListener { onClick() }
        b.rightButton.setBackgroundResource(if (onDark) R.drawable.bg_trip_white_button else R.drawable.bg_button_primary)
        b.rightButton.setTextColor(requireContext().getColor(if (onDark) R.color.brand_navy else R.color.text_on_brand))
    }

    /** 번호 붙은 단계 목록 (3-5 환승 · 3-6/3-7 수정 경로) */
    private fun steps(box: LinearLayout, items: List<Pair<String, String?>>, numbered: Boolean) {
        val ctx = requireContext()
        if (box.tag == items) return            // 같은 내용이면 다시 그리지 않음 (애니메이션 반복 방지)
        box.tag = items
        box.removeAllViews()
        items.forEachIndexed { i, (title, sub) ->
            box.addView(LinearLayout(ctx).apply {
                // 1 → 2 → 3 차례로 촤르륵 (읽는 순서 = 행동 순서)
                alpha = 0f; translationY = dp(12).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(200L + i * 200L).setDuration(400)
                    .setInterpolator(Motion.ease).start()
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(16) }
                if (numbered) addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    gravity = Gravity.CENTER; includeFontPadding = false
                    text = (i + 1).toString()
                    setTextAppearance(R.style.Text_MyWay_Caption12Bold)
                    setTextColor(ctx.getColor(R.color.text_on_brand))
                    setBackgroundResource(R.drawable.bg_circle)
                    backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.brand_navy))
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
                    addView(TextView(ctx).apply { setTextAppearance(R.style.Text_MyWay_Body16Bold); text = title })
                    if (sub != null) addView(TextView(ctx).apply {
                        setTextAppearance(R.style.Text_MyWay_Label14Medium)
                        setTextColor(ctx.getColor(R.color.text_secondary)); text = sub
                    })
                })
            })
        }
    }

    /** 기다림·이동 화면 공통: 진행 막대 색·양 끝 역 이름·'다음 행동' 카드 */
    private fun bindLeg(p: TripPlan, legIdx: Int) {
        val b = _b ?: return
        val leg = p.legs[legIdx]
        b.progress.color = requireContext().getColor(LineStyle.colorRes(leg.line))
        b.boardLabel.text = getString(R.string.trip_board_label, leg.board.name.displayName())
        b.alightLabel.text = getString(R.string.trip_alight_label, leg.alight.name.displayName())
        val next = p.legs.getOrNull(legIdx + 1)
        if (next != null) {
            b.nextTitle.text = getString(R.string.trip_next_transfer, leg.alight.name.displayName(), next.line)
            b.nextSub.text = getString(R.string.trip_next_transfer_sub, next.toward.displayName(),
                Timeline.minutes(p.walks[legIdx]).coerceAtLeast(1))
        } else {
            b.nextTitle.text = getString(R.string.trip_next_alight, leg.alight.name.displayName())
            b.nextSub.text = getString(R.string.trip_next_alight_sub)
        }
    }

    /** 경로 보기: 안내는 그대로 두고 지도만 위에 띄움 (뒤로 오면 이어서 안내) */
    private fun openRouteMap() {
        val route = TripSession.route ?: return
        val p = vm.plan ?: return
        findNavController().navigate(R.id.to_route_map, com.myway.app.ui.result.RouteMapFragment.args(
            route, p.origin.displayName(), p.destination.displayName(),
            getString(R.string.route_map_summary, Timeline.duration(requireContext(), route.totalSeconds),
                route.transferCount, Timeline.fare(requireContext(), route.totalFareKrw))))
    }

    private var askedWait: Any? = null
    private var askedCheck: Any? = null


    /** 핸즈프리: 탈 열차를 말로 알려 주고 '다음 열차'를 들음. 묻는 동안 가짜 열차는 멈춤 */
    private fun askTrain(leg: TripLeg) {
        val v = voice ?: return
        vm.hold = true
        v.ask(getString(R.string.voice_trip_start, leg.toward.displayName()), getString(R.string.voice_hint_next)) { heard ->
            if (com.myway.app.ui.common.VoiceCommand.parse(heard) == com.myway.app.ui.common.VoiceCommand.Cmd.NEXT_TRAIN) {
                vm.restartLeg(); v.say(getString(R.string.voice_trip_next))
            }
            vm.hold = false
        }
    }

    /** 3-12 안내 종료 확인 */
    private fun confirmEnd() {
        if (vm.state.value is TripState.Arrived) return finish()
        val db = DialogConfirmBinding.inflate(layoutInflater)
        val dialog = android.app.Dialog(requireContext()).apply {
            setContentView(db.root)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            val m = resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(resources.displayMetrics.widthPixels - m * 2, -2)
        }
        db.dialogTitle.setText(R.string.trip_end_title)
        db.dialogBody.setText(R.string.trip_end_body)
        db.cancelButton.setText(R.string.trip_end_keep)
        db.confirmButton.setText(R.string.trip_end_confirm)
        db.cancelButton.setOnClickListener { dialog.dismiss() }
        db.confirmButton.setOnClickListener { dialog.dismiss(); finish() }
        dialog.show()
    }

    private fun finish() {
        TripEngine.stop()
        GuideService.stop(requireContext())
        findNavController().popBackStack()
    }

    private fun demoMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.trip_demo_passed).setOnMenuItemClickListener { vm.demoPassed(); true }
            menu.add(R.string.trip_demo_wrong).setOnMenuItemClickListener { vm.demoWrongWay(); true }
            menu.add(R.string.trip_demo_delay).setOnMenuItemClickListener { vm.demoFeed(Feed.DELAYED); true }
            menu.add(R.string.trip_demo_offline).setOnMenuItemClickListener { vm.demoFeed(Feed.OFFLINE); true }
            menu.add(R.string.trip_demo_live).setOnMenuItemClickListener { vm.demoFeed(Feed.LIVE); true }
        }.show()
    }

    /** 열차 위치를 지금 역 칸 안으로 (다른 구간의 지난 값이 한 프레임 섞여도 튀지 않게) */
    private fun at(r: TripState.Riding, x: Float) = if (x >= r.at && x <= r.at + 1) x else r.at.toFloat()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var voice: com.myway.app.ui.common.VoiceAsk? = null

    override fun onDestroyView() {
        voice?.close(); voice = null
        tts?.shutdown(); tts = null
        _b = null
        super.onDestroyView()
    }
}
