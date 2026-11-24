package com.myway.app.ui.result

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.myway.app.R
import com.myway.app.data.local.LocalStore
import com.myway.app.data.remote.NetworkMonitor
import com.myway.app.databinding.FragmentRouteResultBinding
import com.myway.app.databinding.ItemRouteTabBinding
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.RouteType
import com.myway.app.ui.common.Motion
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.route.RouteSelectionViewModel.Conditions
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import com.myway.app.ui.common.StationNames
import com.myway.app.voice.TtsManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.myway.app.ui.common.displayName

/** Figma 1-18 최단시간 · 1-20 최소환승 · 1-21 최소비용 (+ 선호를 정하면 '맞춤') */
class RouteResultFragment : Fragment(R.layout.fragment_route_result) {

    private val vm: RouteResultViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentRouteResultBinding? = null
    private val adapter = TimelineAdapter()
    private var tts: TtsManager? = null
    private var cond = Conditions()
    private var lastKind: kotlin.reflect.KClass<*>? = null
    private var lastSelected: RouteType? = null
    private var lastFav: Boolean? = null

    private fun tabs(): Map<RouteType, ItemRouteTabBinding> {
        val b = _b ?: return emptyMap()
        return mapOf(
            RouteType.PREFERENCE to b.tabPref,
            RouteType.SHORTEST_TIME to b.tabTime,
            RouteType.MIN_TRANSFERS to b.tabTransfer,
            RouteType.MIN_FARE to b.tabFare
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentRouteResultBinding.bind(view).also { _b = it }
        b.timeline.adapter = adapter
        if (savedInstanceState == null) {
            (routeVm.aiExtras.value?.preferredType ?: routeVm.consumePreferredType())?.let { vm.select(it) }
        }

        tabs().forEach { (type, tab) ->
            tab.tabTitle.setText(
                when (type) {
                    RouteType.PREFERENCE -> R.string.result_tab_pref
                    RouteType.SHORTEST_TIME -> R.string.result_tab_time
                    RouteType.MIN_TRANSFERS -> R.string.result_tab_transfer
                    RouteType.MIN_FARE -> R.string.result_tab_fare
                }
            )
            tab.root.setOnClickListener { vm.select(type) }
        }
        b.closeButton.setOnClickListener { findNavController().popBackStack() }
        b.favoriteButton.setOnClickListener { vm.toggleFavorite(routeVm.points.value) }
        b.mapButton.setOnClickListener { openMap() }

        // 경로 읽어주기 (TTS): 화면 언어로 읽음
        tts = TtsManager(requireContext(), if (StationNames.english) java.util.Locale.ENGLISH else java.util.Locale.KOREAN)
        b.speakButton.setOnClickListener { speakRoute() }

        // 31·34: 시각 선택은 시간표 탐색 확정 전까지 비활성 → 눌러도 이유를 알려 준다
        listOf(b.departChip, b.arriveChip).forEach { chip ->
            chip.setOnClickListener {
                Snackbar.make(b.root, R.string.result_time_pending, Snackbar.LENGTH_SHORT).show()
            }
        }

        // 결과 화면의 출발·도착 카드: ✕ 대신, 줄을 누르면 검색으로 돌아가 다시 고르기
        with(b.routeCard) {
            root.elevation = 0f
            root.setBackgroundResource(R.drawable.bg_card_outline)
            originClear.isVisible = false
            destinationClear.isVisible = false
            swapButton.setOnClickListener { routeVm.swap() }
            originRow.setOnClickListener { repick(Slot.ORIGIN) }
            destinationRow.setOnClickListener { repick(Slot.DESTINATION) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(routeVm.points, routeVm.aiExtras, routeVm.conditions) { p, x, c -> Triple(p, x, c) }
                        .collect { (p, x, c) ->
                            if (!p.isComplete) return@collect
                            b.routeCard.originName.text = p.origin?.name?.displayName()
                            b.routeCard.destinationName.text = p.destination?.name?.displayName()
                            renderAiBanner(x)
                            cond = routeVm.effectiveConditions(x, c)
                            vm.load(p, cond)
                        }
                }
                launch { combine(vm.state, vm.selected) { s, t -> s to t }.collect { (s, t) -> render(s, t) } }
                launch {
                    combine(LocalStore.favoriteRoutes, routeVm.points) { _, p ->
                        val o = p.origin?.name; val d = p.destination?.name
                        o != null && d != null && LocalStore.isFavoriteRoute(o, d)
                    }.collect { fav ->
                        val apply = {
                            b.favoriteButton.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
                            b.favoriteButton.contentDescription =
                                getString(if (fav) R.string.result_favorite_remove else R.string.result_favorite)
                        }
                        // 별을 누른 순간이 보이게 (처음 그릴 때는 그대로)
                        if (lastFav != null && lastFav != fav) Motion.swap(b.favoriteButton, apply) else apply()
                        lastFav = fav
                    }
                }
                // 다시 연결되면: 저장본·연결 오류를 보고 있던 경우 새로 찾는다
                launch {
                    NetworkMonitor.online.collect { online ->
                        val s = vm.state.value
                        val stale = (s is RouteResultUiState.Success && s.savedAt != null) ||
                            (s is RouteResultUiState.Error && s.error is AppError.Network)
                        if (online && stale) vm.retry()
                    }
                }
            }
        }
    }

    /** 선택된 기준의 경로 요약을 소리로 읽어준다 */
    private fun speakRoute() {
        val route = vm.currentRoute() ?: return
        val dest = routeVm.points.value.destination?.name?.displayName() ?: return
        val transfer = if (route.transferCount == 0) getString(R.string.result_speak_no_transfer)
        else getString(R.string.result_speak_transfers, route.transferCount)
        tts?.speak(getString(R.string.result_speak_text, dest, Timeline.minutes(route.totalSeconds), transfer))
    }

    private fun openMap() {
        val route = vm.currentRoute() ?: return
        val p = routeVm.points.value
        findNavController().navigate(R.id.to_route_map, RouteMapFragment.args(
            route, p.origin?.name.orEmpty().displayName(), p.destination?.name.orEmpty().displayName(),
            getString(R.string.route_map_summary, Timeline.duration(requireContext(), route.totalSeconds),
                route.transferCount, Timeline.fare(requireContext(), route.totalFareKrw))
        ))
    }

    private fun label(c: Criterion) = getString(when (c) {
        Criterion.DURATION -> R.string.ai_crit_duration
        Criterion.TRANSFERS -> R.string.ai_crit_transfers
        Criterion.FARE -> R.string.ai_crit_fare
    })

    /** 2-6: AI 조건을 반영했다는 표시 */
    private fun renderAiBanner(x: RouteSelectionViewModel.AiExtras?) {
        val b = _b ?: return
        b.aiBanner.isVisible = x != null
        x ?: return
        val pr = x.preference?.priority.orEmpty()
        b.aiBanner.text = if (pr.isNotEmpty())
            getString(R.string.ai_banner, label(pr.first()), pr.joinToString(" → ") { label(it) })
        else getString(R.string.ai_banner_simple, x.said.take(24))
    }

    /** 줄을 누르면 그 칸만 비우고 검색으로. 홈(노선도)·AI·즐겨찾기에서 왔어도 검색 화면이 열리게 */
    private fun repick(slot: Slot) {
        routeVm.clear(slot)
        val nav = findNavController()
        val fromSearch = nav.previousBackStackEntry?.destination?.id == R.id.searchFragment
        nav.popBackStack()
        if (!fromSearch) nav.navigate(R.id.to_search)
    }

    private fun render(state: RouteResultUiState, selected: RouteType) {
        val b = _b ?: return
        val ctx = requireContext()
        val saved = (state as? RouteResultUiState.Success)?.savedAt
        // 로딩 → 결과/오류: 화면이 툭 바뀌지 않게. 같은 결과에서 탭만 바꾸면 내용만 옆으로 바꿔 끼움
        val kind = state::class
        val tabOrder = listOf(RouteType.PREFERENCE, RouteType.SHORTEST_TIME, RouteType.MIN_TRANSFERS, RouteType.MIN_FARE)
        when {
            lastKind != null && lastKind != kind -> {
                Motion.change(b.root as android.view.ViewGroup, 220)
                if (state is RouteResultUiState.Success) Motion.rise(b.content)
            }
            state is RouteResultUiState.Success && lastSelected != null && lastSelected != selected ->
                Motion.refresh(b.content, if (tabOrder.indexOf(selected) > tabOrder.indexOf(lastSelected)) 1 else -1)
        }
        lastKind = kind
        lastSelected = selected
        b.progress.isVisible = state is RouteResultUiState.Loading
        b.content.isVisible = state is RouteResultUiState.Success
        b.errorView.isVisible = state is RouteResultUiState.Error
        b.savedBanner.isVisible = saved != null

        when (state) {
            is RouteResultUiState.Success -> {
                saved?.let {
                    // 언어에 맞는 날짜 (11월 24일 / Nov 24)
                    val pattern = android.text.format.DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "MMMd")
                    val day = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern(pattern, resources.configuration.locales[0]))
                    b.savedBanner.text = getString(R.string.result_saved_banner, day)
                }
                tabs().forEach { (type, tab) ->
                    val r = state.routes[type]
                    tab.root.isVisible = r != null
                    r?.let {
                        tab.tabSub.text = getString(R.string.result_tab_sub,
                            Timeline.duration(ctx, it.totalSeconds), it.transferCount)
                    }
                    styleTab(tab, type == selected)
                }
                val type = selected.takeIf { it in state.routes } ?: state.routes.keys.first()
                val route = state.routes.getValue(type)
                // 30~40: 선호를 반영한 '맞춤' 결과인지, 기준별 기본 결과인지 구분
                val pr = cond.preference?.priority.orEmpty()
                b.durationLabel.text = if (type == RouteType.PREFERENCE && pr.isNotEmpty())
                    getString(R.string.result_pref_label, pr.joinToString(" → ") { label(it) })
                else getString(R.string.result_duration_label)
                b.totalTime.text = Timeline.duration(ctx, route.totalSeconds)
                b.transfers.text = getString(R.string.result_transfers, route.transferCount)
                // 서버가 요금을 아직 안 주면(0) '0원' 대신 확인 중으로
                b.fare.text = if (route.totalFareKrw > 0) Timeline.fare(ctx, route.totalFareKrw)
                else getString(R.string.result_fare_pending)
                b.departTime.text = Timeline.clock(state.departAt)
                b.arriveTime.text = Timeline.clock(state.departAt.plusSeconds(route.totalSeconds.toLong()))
                adapter.submitList(Timeline.build(ctx, route, state.departAt))
            }
            is RouteResultUiState.Error -> {
                // 조건 때문에 경로가 없으면 '다시 찾기' 대신 '조건 바꾸기'로 바로 풀 수 있게
                val byConditions = state.error is AppError.RouteNotFound && !cond.isEmpty
                val (title, body) = when (state.error) {
                    AppError.RouteNotFound -> R.string.result_error_not_found to
                        if (byConditions) R.string.result_error_not_found_cond else R.string.result_error_not_found_body
                    AppError.UnsupportedCondition -> R.string.result_error_unsupported to R.string.result_error_unsupported_body
                    AppError.Network -> R.string.result_error_network to R.string.result_error_network_body
                    AppError.Server -> R.string.result_error_server to R.string.result_error_generic_body
                    else -> R.string.result_error_generic to R.string.result_error_generic_body
                }
                b.errorTitle.setText(title)
                b.errorBody.setText(body)
                b.retryButton.setOnClickListener { vm.retry() }
            }
            RouteResultUiState.Loading -> Unit
        }
    }

    /** 선택 탭: 굵게 + 남색 밑줄 / 나머지: 보통 + 회색 */
    private fun styleTab(tab: ItemRouteTabBinding, selected: Boolean) {
        val ctx = requireContext()
        tab.tabTitle.setTextAppearance(if (selected) R.style.Text_MyWay_Body16Bold else R.style.Text_MyWay_Body16Medium)
        val color = ctx.getColor(if (selected) R.color.text_primary else R.color.text_secondary)
        tab.tabTitle.setTextColor(color)
        tab.tabSub.setTextColor(color)
        tab.tabIndicator.setBackgroundColor(if (selected) ctx.getColor(R.color.brand_navy) else 0)
        tab.root.isSelected = selected
    }

    override fun onDestroyView() {
        tts?.shutdown()
        tts = null
        _b = null
        lastKind = null; lastSelected = null; lastFav = null
        super.onDestroyView()
    }

}
