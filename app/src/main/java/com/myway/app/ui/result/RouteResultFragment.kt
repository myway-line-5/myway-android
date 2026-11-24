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
import com.myway.app.R
import com.myway.app.data.local.LocalStore
import com.myway.app.databinding.FragmentRouteResultBinding
import com.myway.app.databinding.ItemRouteTabBinding
import com.myway.app.domain.model.AppError
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.RouteType
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import com.myway.app.voice.TtsManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Figma 1-18 최단시간 · 1-20 최소환승 · 1-21 최소비용 */
class RouteResultFragment : Fragment(R.layout.fragment_route_result) {

    private val vm: RouteResultViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentRouteResultBinding? = null
    private val adapter = TimelineAdapter()
    private var tts: TtsManager? = null   // ★ 추가

    private fun tabs(): Map<RouteType, ItemRouteTabBinding> {
        val b = _b ?: return emptyMap()
        return mapOf(
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
                    RouteType.SHORTEST_TIME -> R.string.result_tab_time
                    RouteType.MIN_TRANSFERS -> R.string.result_tab_transfer
                    else -> R.string.result_tab_fare
                }
            )
            tab.root.setOnClickListener { vm.select(type) }
        }
        b.closeButton.setOnClickListener { findNavController().popBackStack() }
        b.retryButton.setOnClickListener { vm.retry() }
        b.favoriteButton.setOnClickListener { vm.toggleFavorite(routeVm.points.value) }

        tts = TtsManager(requireContext())                 // ★ 추가
        b.speakButton.setOnClickListener { speakRoute() }  // ★ 추가

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
                    combine(routeVm.points, routeVm.aiExtras) { p, x -> p to x }.collect { (p, x) ->
                        if (!p.isComplete) return@collect
                        b.routeCard.originName.text = p.origin?.name
                        b.routeCard.destinationName.text = p.destination?.name
                        renderAiBanner(x)
                        vm.load(p, x)
                    }
                }
                launch { combine(vm.state, vm.selected) { s, t -> s to t }.collect { (s, t) -> render(s, t) } }
                launch {
                    combine(LocalStore.favoriteRoutes, routeVm.points) { _, p ->
                        val o = p.origin?.name; val d = p.destination?.name
                        o != null && d != null && LocalStore.isFavoriteRoute(o, d)
                    }.collect { fav ->
                        b.favoriteButton.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
                        b.favoriteButton.contentDescription =
                            getString(if (fav) R.string.result_favorite_remove else R.string.result_favorite)
                    }
                }
            }
        }
    }

    /** 선택된 기준의 경로 요약을 소리로 읽어준다 */   // ★ 추가
    private fun speakRoute() {
        val s = vm.state.value as? RouteResultUiState.Success ?: return
        val route = s.routes[vm.selected.value] ?: s.routes.values.first()
        val dest = routeVm.points.value.destination?.name ?: return
        val min = Timeline.minutes(route.totalSeconds)
        val transferText = if (route.transferCount == 0) "환승 없이 갈 수 있어요"
        else "환승은 ${route.transferCount}회예요"
        tts?.speak("${dest}까지 약 ${min}분 걸리고, $transferText")
    }

    /** 2-6: AI 조건을 반영했다는 표시 */
    private fun renderAiBanner(x: RouteSelectionViewModel.AiExtras?) {
        val b = _b ?: return
        b.aiBanner.isVisible = x != null
        x ?: return
        val pr = x.preference?.priority.orEmpty()
        fun label(c: Criterion) = getString(when (c) {
            Criterion.DURATION -> R.string.ai_crit_duration
            Criterion.TRANSFERS -> R.string.ai_crit_transfers
            Criterion.FARE -> R.string.ai_crit_fare
        })
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
        b.progress.isVisible = state is RouteResultUiState.Loading
        b.content.isVisible = state is RouteResultUiState.Success
        b.errorView.isVisible = state is RouteResultUiState.Error

        when (state) {
            is RouteResultUiState.Success -> {
                tabs().forEach { (type, tab) ->
                    val r = state.routes[type]
                    tab.root.isVisible = r != null
                    r?.let {
                        tab.tabSub.text = getString(R.string.result_tab_sub,
                            Timeline.duration(ctx, it.totalSeconds), it.transferCount)
                    }
                    styleTab(tab, type == selected)
                }
                val route = state.routes[selected] ?: state.routes.values.first()
                b.totalTime.text = Timeline.duration(ctx, route.totalSeconds)
                b.transfers.text = getString(R.string.result_transfers, route.transferCount)
                b.fare.text = Timeline.fare(ctx, route.totalFareKrw)
                b.departTime.text = Timeline.clock(state.departAt)
                b.arriveTime.text = Timeline.clock(state.departAt.plusSeconds(route.totalSeconds.toLong()))
                adapter.submitList(Timeline.build(ctx, route, state.departAt))
            }
            is RouteResultUiState.Error -> {
                val notFound = state.error is AppError.RouteNotFound
                val network = state.error is AppError.Network
                b.errorTitle.setText(when {
                    notFound -> R.string.result_error_not_found
                    network -> R.string.result_error_network
                    else -> R.string.result_error_generic
                })
                b.errorBody.setText(if (notFound) R.string.result_error_not_found_body else R.string.result_error_generic_body)
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
        tts?.shutdown()   // ★ 추가
        tts = null        // ★ 추가
        _b = null
        super.onDestroyView()
    }
}