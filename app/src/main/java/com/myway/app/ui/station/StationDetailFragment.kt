package com.myway.app.ui.station

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.myway.app.R
import com.myway.app.data.local.LocalStore
import com.myway.app.data.repository.DayType
import com.myway.app.data.repository.RepositoryProvider
import com.myway.app.data.repository.Resource
import com.myway.app.data.repository.StationDetail
import com.myway.app.data.repository.StationDetailRepository
import com.myway.app.databinding.FragmentStationDetailBinding
import com.myway.app.domain.model.Station
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.Motion
import com.myway.app.ui.common.NoticeDialog
import com.myway.app.ui.common.blockedOffline
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import com.myway.app.ui.search.StationGroupItem
import com.myway.app.ui.common.toSaved
import com.myway.app.ui.route.RouteSelectionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import com.myway.app.ui.common.displayName
import com.myway.app.ui.common.StationNames

class StationDetailViewModel(
    private val repo: StationDetailRepository = RepositoryProvider.stationDetailRepository()
) : ViewModel() {
    sealed interface UiState {
        data object Loading : UiState
        data class Success(val detail: StationDetail) : UiState
        data object Failed : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    var lineIndex = 0
    var day: DayType = todayType()

    fun load(station: Station) {
        job?.cancel()                                   // 노선 탭을 빨리 바꿔도 늦은 응답이 덮어쓰지 않게
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            _state.value = when (val r = repo.getDetail(station)) {
                is Resource.Success -> UiState.Success(r.data)
                else -> UiState.Failed
            }
        }
    }

    private fun todayType() = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.SATURDAY -> DayType.SATURDAY
        Calendar.SUNDAY -> DayType.HOLIDAY
        else -> DayType.WEEKDAY
    }
}

/** Figma 5-2 역 상세 (노선도·검색의 역 시트 → '역 정보') */
class StationDetailFragment : Fragment(R.layout.fragment_station_detail) {

    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private val vm: StationDetailViewModel by viewModels()
    private var _b: FragmentStationDetailBinding? = null
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentStationDetailBinding.bind(view).also { _b = it }
        val item = routeVm.focused.value ?: run { findNavController().popBackStack(); return }
        val stations = item.stations.filter { LineStyle.isSupported(it.line) }
        if (stations.isEmpty()) { findNavController().popBackStack(); return }
        if (savedInstanceState == null) vm.lineIndex = arguments?.getInt(ARG_LINE, 0)?.coerceIn(0, stations.lastIndex) ?: 0

        b.title.text = item.name.displayName()
        b.backButton.setOnClickListener { findNavController().popBackStack() }
        b.favoriteButton.setOnClickListener { LocalStore.toggleFavoriteStation(item.toSaved()) }
        b.departButton.setOnClickListener { pick(Slot.ORIGIN, item) }
        b.arriveButton.setOnClickListener { pick(Slot.DESTINATION, item) }

        renderTabs(stations)
        if (vm.state.value !is StationDetailViewModel.UiState.Success) vm.load(stations[vm.lineIndex])

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LocalStore.favoriteStations.collect { favs ->
                        val on = favs.any { it.name == item.name }
                        b.favoriteButton.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_outline)
                        b.favoriteButton.contentDescription =
                            getString(if (on) R.string.favorite_remove else R.string.favorite_add, item.name)
                    }
                }
                launch { vm.state.collect(::render) }
            }
        }
    }

    /** 검색에서 왔으면 검색으로 돌아가 이어서, 그 외(노선도·즐겨찾기)는 새 검색: 둘 다 차면 결과, 아니면 검색 */
    private fun pick(slot: Slot, item: StationGroupItem) {
        val nav = findNavController()
        val prev = nav.previousBackStackEntry?.destination?.id
        val fromSearch = prev == R.id.searchFragment
        if (!fromSearch) routeVm.startFresh()
        if (!routeVm.set(slot, item)) {
            NoticeDialog.show(this, R.string.same_station_title, R.string.same_station_body); return
        }
        // 둘 다 정해져 결과로 갈 차례인데 오프라인이면 이 화면에 머물며 안내 (역은 지정된 상태 유지)
        if (!fromSearch && routeVm.points.value.isComplete && blockedOffline()) return
        nav.popBackStack()
        when {
            fromSearch -> Unit                                              // 검색이 칩·카드로 이어받음
            routeVm.points.value.isComplete -> nav.navigate(R.id.to_result)
            prev == R.id.homeFragment -> Unit                               // 노선도로 돌아가 핀으로 확인 (검색은 선택)
            else -> nav.navigate(R.id.to_search)
        }
    }

    /** 노선 탭: 선택 = 진하게 + 남색 밑줄 (검색 역 시트와 같은 모양) */
    private fun renderTabs(stations: List<Station>) {
        val b = _b ?: return
        val ctx = requireContext()
        b.lineTabs.removeAllViews()
        stations.forEachIndexed { i, s ->
            val on = i == vm.lineIndex
            b.lineTabs.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(14) }
                contentDescription = getString(R.string.line_format, LineStyle.key(s.line))
                isSelected = on
                isClickable = stations.size > 1
                setOnClickListener {
                    if (vm.lineIndex == i) return@setOnClickListener
                    vm.lineIndex = i; renderTabs(stations); vm.load(stations[i])
                }
                addView(LineStyle.badge(ctx, s.line, 0, R.dimen.tab_badge, R.style.Text_MyWay_Label14Bold)
                    .apply { alpha = if (on) 1f else 0.5f })
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(3)).apply { topMargin = dp(6) }
                    setBackgroundColor(if (on) ctx.getColor(R.color.brand_navy) else Color.TRANSPARENT)
                })
            })
        }
    }

    private fun render(s: StationDetailViewModel.UiState) {
        val b = _b ?: return
        b.loading.isVisible = s is StationDetailViewModel.UiState.Loading
        b.errorText.isVisible = s is StationDetailViewModel.UiState.Failed
        val ok = s as? StationDetailViewModel.UiState.Success
        listOf(b.firstLastCard, b.timetableCard, b.congestionCard, b.facilityCard).forEach { it.isVisible = ok != null }
        ok ?: return
        bindFirstLast(ok.detail)
        bindTimetable(ok.detail)
        bindCongestion(ok.detail)
        bindFacilities(ok.detail)
        Motion.stagger(listOf(b.firstLastCard, b.timetableCard, b.congestionCard, b.facilityCard))
    }

    // ---------- 첫차 · 막차 ----------
    private fun bindFirstLast(dt: StationDetail) {
        val b = _b ?: return
        b.firstLastRows.removeAllViews()
        dt.firstLast.forEach { fl ->
            b.firstLastRows.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                contentDescription = "${StationNames.direction(fl.direction)}, ${getString(R.string.detail_first)} ${fl.first}, ${getString(R.string.detail_last)} ${fl.last}"
                addView(text(StationNames.direction(fl.direction), R.style.Text_MyWay_Body16Bold, R.color.text_primary),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(fl.first, R.style.Text_MyWay_Body16Bold, R.color.text_primary).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(text(fl.last, R.style.Text_MyWay_Body16Bold, R.color.text_primary).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    // ---------- 시간표: 요일 세그먼트 + 방면별 '지금부터' 4대 (첫 칸 = 다음 열차 강조) ----------
    private fun bindTimetable(dt: StationDetail) {
        val b = _b ?: return
        val ctx = requireContext()
        b.dayTabs.removeAllViews()
        listOf(DayType.WEEKDAY to R.string.detail_weekday, DayType.SATURDAY to R.string.detail_saturday,
            DayType.HOLIDAY to R.string.detail_holiday).forEach { (day, label) ->
            val on = day == vm.day
            b.dayTabs.addView(TextView(ctx).apply {
                setText(label)
                gravity = Gravity.CENTER
                setTextAppearance(R.style.Text_MyWay_Label14Bold)
                setTextColor(ctx.getColor(if (on) R.color.text_on_brand else R.color.text_secondary))
                setBackgroundResource(if (on) R.drawable.bg_detail_segment_on else 0)
                isSelected = on
                setOnClickListener {
                    if (vm.day == day) return@setOnClickListener
                    Motion.change(b.timetableCard, 200)
                    vm.day = day; bindTimetable(dt)
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }

        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
            .let { if (it < 3 * 60) it + 24 * 60 else it }        // 새벽 0~2시는 '어제 운행'의 24:xx
        b.timetableRows.removeAllViews()
        dt.timetable[vm.day].orEmpty().forEach { (dir, times) ->
            b.timetableRows.addView(text(getString(R.string.detail_next_trains, dir), R.style.Text_MyWay_Caption12Bold, R.color.text_secondary)
                .apply { setPadding(0, dp(14), 0, dp(8)) })
            val next = times.filter { minutes(it) >= now }.take(4)
            if (next.isEmpty()) {
                // 막차가 끝난 것 ≠ 비활성 역 (설계서 기능 14)
                b.timetableRows.addView(text(getString(R.string.detail_service_over, times.firstOrNull().orEmpty()),
                    R.style.Text_MyWay_Label14Medium, R.color.text_secondary))
                return@forEach
            }
            b.timetableRows.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                next.forEachIndexed { i, t ->
                    addView(text(display(t), R.style.Text_MyWay_Body16Bold, if (i == 0) R.color.text_on_brand else R.color.text_primary).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(14), 0, dp(14), 0)
                        setBackgroundResource(if (i == 0) R.drawable.bg_detail_time_next else R.drawable.bg_detail_time)
                        if (i == 0) contentDescription = getString(R.string.detail_time_next, display(t))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { if (i > 0) marginStart = dp(8) })
                }
            })
        }
    }

    private fun minutes(hm: String) = hm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    private fun display(hm: String) = hm.split(":").let { "%02d:%s".format(it[0].toInt() % 24, it[1]) }

    // ---------- 혼잡도: 06시부터 24칸, 지금 시각만 남색 ----------
    private fun bindCongestion(dt: StationDetail) {
        val b = _b ?: return
        val ctx = requireContext()
        val hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val order = (6..23) + (0..5)
        val max = (dt.congestion.maxOrNull() ?: 1).coerceAtLeast(1)
        b.bars.removeAllViews()
        order.forEachIndexed { i, h ->
            val v = dt.congestion.getOrElse(h) { 0 }
            b.bars.addView(View(ctx).apply {
                setBackgroundResource(R.drawable.bg_detail_bar)
                backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (h == hourNow) R.color.brand_navy else R.color.border_default))
                contentDescription = getString(R.string.detail_bar_desc, h, getString(level(v)))
            }, LinearLayout.LayoutParams(0, (dp(80) * v / max).coerceAtLeast(dp(4)), 1f).apply { if (i > 0) marginStart = dp(4) })
        }
        b.barLabels.removeAllViews()
        listOf(6, 9, 12, 15, 18, 21).forEach { h ->
            b.barLabels.addView(text(getString(R.string.detail_hour, h), R.style.Text_MyWay_Caption12Medium, R.color.text_secondary),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val ampm = getString(if (hourNow < 12) R.string.detail_am else R.string.detail_pm)
        val h12 = (hourNow % 12).let { if (it == 0) 12 else it }
        b.nowText.text = getString(R.string.detail_now, getString(R.string.detail_hour_12, ampm, h12))
        b.nowLevel.setText(level(dt.congestion.getOrElse(hourNow) { 0 }))
    }

    private fun level(v: Int) = when {
        v < 35 -> R.string.detail_level_low
        v < 70 -> R.string.detail_level_mid
        else -> R.string.detail_level_high
    }

    // ---------- 편의시설 ----------
    private fun bindFacilities(dt: StationDetail) {
        val b = _b ?: return
        val ctx = requireContext()
        b.facilityCard.isVisible = dt.facilities.isNotEmpty()
        b.facilityRows.removeAllViews()
        dt.facilities.forEach { f ->
            b.facilityRows.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(0, dp(10), 0, 0)
                contentDescription = "${facilityName(f)}, ${f.where}"
                addView(text(f.code, R.style.Text_MyWay_Caption12Bold, R.color.text_primary).apply {
                    gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_detail_code)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(facilityName(f), R.style.Text_MyWay_Body16Bold, R.color.text_primary))
                    addView(text(f.where, R.style.Text_MyWay_Label14Medium, R.color.text_secondary))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
        }
    }

    private fun text(s: String, style: Int, color: Int) = TextView(requireContext()).apply {
        text = s
        setTextAppearance(style)
        setTextColor(requireContext().getColor(color))
    }

    /** 편의시설 이름: 코드로 번역 (모르는 코드는 받은 이름 그대로) */
    private fun facilityName(f: com.myway.app.data.repository.Facility) = when (f.code) {
        "WC" -> getString(R.string.detail_fac_wc)
        "EV" -> getString(R.string.detail_fac_ev)
        else -> f.name
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_LINE = "lineIndex"
    }
}
