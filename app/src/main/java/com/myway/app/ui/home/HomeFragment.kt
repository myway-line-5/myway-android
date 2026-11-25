package com.myway.app.ui.home

import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.myway.app.R
import com.myway.app.databinding.FragmentHomeBinding
import com.myway.app.data.repository.RepositoryProvider
import com.myway.app.data.repository.Resource
import com.myway.app.data.local.LocalStore
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.NoticeDialog
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.search.SearchFragment
import com.myway.app.ui.search.StationActionSheet
import com.myway.app.ui.search.StationGroupItem
import com.myway.app.ui.onboarding.PermissionFlow
import com.myway.app.ui.onboarding.Tutorial
import com.myway.app.ui.onboarding.TutorialOverlay
import com.myway.app.ui.onboarding.TutorialSession
import com.myway.app.data.remote.NetworkMonitor
import com.myway.app.ui.common.Motion
import com.myway.app.ui.common.blockedOffline
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.myway.app.ui.common.displayName

/** Figma 1-2~1-5 홈 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val vm: HomeViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentHomeBinding? = null
    private var tutorial: TutorialSession? = null
    /** 지금 시트를 띄운 역 (남색 핀) */
    private var focused: MapStations.S? = null

    /** 최초 실행: 권한 안내 → 튜토리얼 (필드에서 만들어야 함) */
    private val permissions = PermissionFlow(this) { if (!Tutorial.done) startTutorial(0) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view).also { _b = it }

        // 노선도 카드 모서리(12dp) 밖으로 지도가 삐져나오지 않게
        b.mapCard.outlineProvider = ViewOutlineProvider.BACKGROUND
        b.mapCard.clipToOutline = true
        b.mapView.placeholderText = getString(R.string.home_map_missing)

        b.searchBar.setOnClickListener {
            routeVm.startFresh()                  // 지난 경로 카드 대신 빈 검색창부터
            // 2·5·9호선 노선도를 보고 있으면 그 호선 역만 검색
            findNavController().navigate(R.id.action_home_to_search, searchArgs())
        }
        b.linePicker.setOnClickListener {
            LinePickerSheet().show(childFragmentManager, LinePickerSheet.TAG)
        }
        b.favoritesButton.setOnClickListener { findNavController().navigate(R.id.to_favorites) }
        b.settingsButton.setOnClickListener { findNavController().navigate(R.id.to_settings) }
        b.aiButton.setOnClickListener { findNavController().navigate(R.id.to_ai_mode) }
        b.helpButton.setOnClickListener { Tutorial.fromAi = false; startTutorial(0) }

        b.mapView.onFirstGesture = { vm.onHintSeen() }
        b.mapView.onPinTap = { pin -> removePin(pin.kind) }
        b.mapView.onMapTap = { x, y ->
            if (isDebuggable()) Log.d(TAG_COORD, "${vm.map.value.name} x=${x.toInt()} y=${y.toInt()}")
            // 1-12: 노선도의 역을 누르면 출발·도착·역 정보 시트 (손가락 크기 28dp 안의 가장 가까운 역)
            // 지금 노선도의 역만 누를 수 있음 (2호선 노선도면 2호선 역만)
            val tolerance = 28 * resources.displayMetrics.density / b.mapView.currentScale()
            MapStations.nearestIn(stationsOf(vm.map.value), x, y, tolerance)?.let { s ->
                focused = s
                renderPins()
                b.mapView.focusOn(s.x.toFloat(), s.y.toFloat())        // 아래 시트에 가리지 않게 위로
                b.mapView.announceForAccessibility(getString(R.string.map_pin_selected, s.name.displayName()))
                onMapStation(s.name, vm.map.value.line)
            }
        }

        renderServiceBadges()
        // 시트를 그냥 닫으면 '고른 역' 핀도 거둠 (출발·도착 핀은 남음)
        childFragmentManager.setFragmentResultListener(StationActionSheet.RESULT_CLOSED, viewLifecycleOwner) { _, _ ->
            focused = null; renderPins()
        }

        permissions.attach()
        view.post {
            if (_b == null) return@post
            // AI 모드에서 '이전'으로 돌아온 튜토리얼 → 최초 실행 권한 안내 → 튜토리얼
            val resume = Tutorial.consume(Tutorial.HOME)
            when {
                resume != null -> startTutorial(resume)
                !permissions.asked && childFragmentManager.findFragmentByTag(com.myway.app.ui.onboarding.PermissionSheet.TAG) == null -> permissions.start()
                permissions.asked && !Tutorial.done -> startTutorial(0)
                permissions.asked -> ScopeNotice.maybeShow(this@HomeFragment)   // 1-1: 튜토리얼 뒤, 실행마다 한 번
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.map.collect { renderMap(it); renderPins() } }
                launch { routeVm.points.collect { renderPins() } }
                launch {
                    // 오프라인 배너가 핀치 힌트보다 우선 (같은 자리 경쟁 방지)
                    vm.hintVisible.combine(NetworkMonitor.online) { hint, online -> hint to online }.collect { (hint, online) ->
                        if (b.offlineBanner.isVisible == !online && b.pinchHint.isVisible == (hint && online)) return@collect
                        Motion.change(b.mapCard)
                        b.offlineBanner.isVisible = !online
                        b.pinchHint.isVisible = hint && online
                    }
                }
            }
        }
    }

    /** 역 이름 → 노선별 역 묶음. 서버 역 목록에 없으면 노선도 기준으로 채움 (Fake 단계) */
    /**
     * 노선도 시트에서 출발·도착을 골랐을 때 (시트가 호출).
     * 둘 다 정해지면 바로 결과, 하나면 노선도에 머물며 다음 역을 누르라고 안내. 검색은 [검색] 버튼으로 선택.
     */
    fun onMapPicked() {
        val b = _b ?: return
        val p = routeVm.points.value
        if (p.isComplete) { if (!blockedOffline()) findNavController().navigate(R.id.to_result); return }
        val picked = p.origin ?: p.destination ?: return
        val msg = getString(if (p.origin != null) R.string.map_next_destination else R.string.map_next_origin, picked.name.displayName())
        com.google.android.material.snackbar.Snackbar.make(b.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAnchorView(b.bottomBar)
            .setAction(R.string.map_next_search) {
                findNavController().navigate(R.id.action_home_to_search, searchArgs())
            }
            .show()
    }

    /** 노선도별 역 좌표표 */
    private fun stationsOf(type: MapType): List<MapStations.S> =
        type.line?.let { LineMapStations.byLine[it] } ?: MapStations.all

    /** 검색으로 넘길 값: 노선별 노선도면 그 호선 */
    fun searchArgs() = androidx.core.os.bundleOf(SearchFragment.ARG_LINE to vm.map.value.line)

    /** onlyLine: 노선별 노선도에서 누르면 그 호선 역으로만 (환승역이라도) */
    private fun onMapStation(name: String, onlyLine: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val known = (RepositoryProvider.stationRepository().searchStations(name) as? Resource.Success)
                ?.data.orEmpty().filter { it.name == name && LineStyle.isSupported(it.line) }
                .filter { onlyLine == null || LineStyle.key(it.line) == onlyLine }
            val extra = (onlyLine?.let { listOf(it) } ?: MapStations.linesOf(name))
                .filter { line -> known.none { LineStyle.key(it.line) == line } }
                .map { line -> Station("map:$line:$name", name, "${line}호선", true) }
            val item = StationGroupItem(name, (known + extra).sortedBy { LineStyle.key(it.line) },
                isFavorite = LocalStore.isFavoriteStation(name))
            if (!item.isActive) {                       // 1-17 비활성 역
                focused = null; renderPins()
                NoticeDialog.show(this@HomeFragment, R.string.inactive_dialog_title, R.string.inactive_dialog_body)
                return@launch
            }
            if (childFragmentManager.findFragmentByTag(StationActionSheet.TAG) != null) return@launch
            routeVm.focus(item)
            StationActionSheet().show(childFragmentManager, StationActionSheet.TAG)
        }
    }

    /** 출발·도착 이름표(✕)를 누르면 지우기. 실수 대비 '되돌리기' */
    private fun removePin(kind: ZoomableMapView.PinKind) {
        val b = _b ?: return
        val slot = when (kind) {
            ZoomableMapView.PinKind.ORIGIN -> RouteSelectionViewModel.Slot.ORIGIN
            ZoomableMapView.PinKind.DESTINATION -> RouteSelectionViewModel.Slot.DESTINATION
            ZoomableMapView.PinKind.FOCUS -> return
        }
        val p = routeVm.points.value
        val item = (if (slot == RouteSelectionViewModel.Slot.ORIGIN) p.origin else p.destination) ?: return
        routeVm.clear(slot)
        val msg = getString(if (slot == RouteSelectionViewModel.Slot.ORIGIN) R.string.map_pin_removed_origin
            else R.string.map_pin_removed_destination, item.name.displayName())
        com.google.android.material.snackbar.Snackbar.make(b.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAnchorView(b.bottomBar)
            .setAction(R.string.map_pin_undo) { routeVm.set(slot, item) }
            .show()
    }

    /** 노선도 핀: 출발(초록)·도착(빨강)은 계속, 지금 누른 역(남색)은 시트가 떠 있는 동안 */
    private fun renderPins() {
        val b = _b ?: return
        // 지금 노선도에 있는 역만 핀 (2호선 노선도에서 9호선 역 핀은 안 보임)
        val here = stationsOf(vm.map.value)
        fun at(name: String) = here.firstOrNull { it.name == name }
        val p = routeVm.points.value
        val pins = mutableListOf<ZoomableMapView.Pin>()
        p.origin?.let { o -> at(o.name)?.let {
            pins += ZoomableMapView.Pin(it.x.toFloat(), it.y.toFloat(), ZoomableMapView.PinKind.ORIGIN, getString(R.string.map_pin_origin, o.name.displayName())) } }
        p.destination?.let { d -> at(d.name)?.let {
            pins += ZoomableMapView.Pin(it.x.toFloat(), it.y.toFloat(), ZoomableMapView.PinKind.DESTINATION, getString(R.string.map_pin_destination, d.name.displayName())) } }
        focused?.takeIf { f -> f.name != p.origin?.name && f.name != p.destination?.name }?.let {
            pins += ZoomableMapView.Pin(it.x.toFloat(), it.y.toFloat(), ZoomableMapView.PinKind.FOCUS, it.name.displayName())
        }
        b.mapView.pins = pins
    }

    private fun renderMap(type: MapType) {
        val b = _b ?: return
        val ctx = requireContext()
        val color = if (type.line == null) R.color.brand_navy else LineStyle.colorRes(type.line)
        b.searchHint.text = type.line?.let { getString(R.string.search_hint_line, it) } ?: getString(R.string.search_hint)
        if (focused != null && stationsOf(type).none { it == focused }) focused = null
        b.lineDot.backgroundTintList = ColorStateList.valueOf(ctx.getColor(color))
        b.linePickerLabel.setText(type.labelRes)

        b.mapView.contentDescription = getString(R.string.home_map_desc, getString(type.titleRes))
        val resId = resources.getIdentifier(type.drawableName, "drawable", ctx.packageName)
        if (resId == 0) { showMap(null); return }
        MapImage.cached(resId)?.let { showMap(it); return }           // 두 번째부터는 즉시
        b.mapView.placeholderText = ""                                // 읽는 동안 '이미지 없음' 깜빡임 방지
        b.mapView.drawable = null
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = MapImage.load(ctx, resId)
            if (vm.map.value == type) showMap(bmp)                    // 그새 노선을 바꿨으면 버림
        }
    }

    private var shownMap: MapType? = null

    private fun showMap(bmp: android.graphics.Bitmap?) {
        val b = _b ?: return
        // 노선을 바꿨을 때만 새 노선도가 스르륵 나타남 (처음 열 때·돌아올 때는 그대로)
        val type = vm.map.value
        if (shownMap != null && shownMap != type) {
            b.mapView.alpha = 0f
            b.mapView.animate().alpha(1f).setDuration(220).setInterpolator(com.myway.app.ui.common.Motion.ease).start()
        }
        shownMap = type
        b.mapView.placeholderText = getString(R.string.home_map_missing)
        b.mapView.drawable = bmp?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        renderPins()
    }

    private fun renderServiceBadges() {
        val b = _b ?: return
        val gap = resources.getDimensionPixelSize(R.dimen.space_s)
        b.serviceBadges.removeAllViews()
        listOf("2", "5", "9").forEach { line ->
            b.serviceBadges.addView(
                LineStyle.badge(requireContext(), line, gap, R.dimen.service_badge)
            )
        }
    }

    /** T-1~T-4: 노선 선택 → 노선도 → 검색·즐겨찾기 → AI 전환. 5단계부터는 AI 모드에서 이어짐 */
    private fun startTutorial(step: Int) {
        val b = _b ?: return
        tutorial?.dismiss()
        val session = TutorialSession(
            fragment = this,
            range = Tutorial.HOME,
            target = { i ->
                when (i) {
                    0 -> TutorialOverlay.Target(b.linePicker, 12f)
                    1 -> TutorialOverlay.Target(b.mapCard, 12f)
                    2 -> TutorialOverlay.Target(b.searchBar, 10f)
                    else -> TutorialOverlay.Target(b.aiButton, -1f)
                }
            },
            toNextScreen = {
                Tutorial.pending = Tutorial.AI.first
                findNavController().navigate(R.id.to_ai_mode)
            },
            toPrevScreen = null,
            onFinished = { ScopeNotice.maybeShow(this) }
        ).also { tutorial = it }
        session.start(step)
    }

    private fun isDebuggable() =
        (requireContext().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onDestroyView() {
        tutorial?.dismiss(); tutorial = null
        _b = null
        super.onDestroyView()
    }

    companion object { private const val TAG_COORD = "MapCoord" }
}
