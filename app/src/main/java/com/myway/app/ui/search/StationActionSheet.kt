package com.myway.app.ui.search

import android.content.res.ColorStateList
import android.graphics.Color
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.myway.app.ui.common.NoticeDialog
import com.myway.app.ui.station.StationDetailFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.R
import com.myway.app.databinding.SheetStationActionBinding
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import kotlinx.coroutines.launch

/**
 * Figma 1-12 역 선택: [노선 탭 · ☆] / [노선색 띠 + 역명] / [출발 | 도착]
 * 실시간 도착(Phase 2)·이전/다음 역·상세(화면 60)는 데이터가 생기면 추가.
 */
class StationActionSheet : BottomSheetDialogFragment() {

    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private val searchVm: SearchViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private var _b: SheetStationActionBinding? = null
    private var selectedIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetStationActionBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        val item = routeVm.focused.value ?: run { dismiss(); return }
        selectedIndex = savedInstanceState?.getInt(KEY_TAB) ?: 0

        b.stationName.text = item.name
        renderTabs(item)

        b.departButton.setOnClickListener { pick(Slot.ORIGIN, item) }
        b.arriveButton.setOnClickListener { pick(Slot.DESTINATION, item) }
        b.favoriteButton.setOnClickListener { searchVm.onToggleFavorite(item) }
        b.detailButton.setOnClickListener {
            val parent = requireParentFragment()
            dismiss()
            parent.findNavController().navigate(R.id.to_station_detail,
                bundleOf(StationDetailFragment.ARG_LINE to selectedIndex))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.favoriteNames.collect { favs ->
                    val on = item.name in favs
                    b.favoriteButton.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_outline)
                    b.favoriteButton.contentDescription = getString(
                        if (on) R.string.favorite_remove else R.string.favorite_add, item.name
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 기본 흰 배경 제거 → 위쪽 둥근 모서리(20dp)가 보이게
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    /** 노선 탭: 선택된 노선은 진하게 + 아래 남색 밑줄, 띠 색도 그 노선 색 */
    private fun renderTabs(item: StationGroupItem) {
        val b = _b ?: return
        val ctx = requireContext()
        val gap = resources.getDimensionPixelSize(R.dimen.space_m)
        val underlineH = (3 * resources.displayMetrics.density).toInt()
        val tabSize = resources.getDimensionPixelSize(R.dimen.tab_badge)

        b.lineTabs.removeAllViews()
        item.stations.forEachIndexed { i, station ->
            val selected = i == selectedIndex
            val tab = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = if (i == 0) 0 else gap }
                contentDescription = getString(R.string.line_format, LineStyle.key(station.line))
                isClickable = item.stations.size > 1
                setOnClickListener { selectedIndex = i; renderTabs(item) }
            }
            tab.addView(LineStyle.badge(ctx, station.line, 0, R.dimen.tab_badge, R.style.Text_MyWay_Body16Bold)
                .apply { alpha = if (selected) 1f else 0.5f })
            tab.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(tabSize, underlineH).apply { topMargin = gap / 2 }
                setBackgroundColor(if (selected) ctx.getColor(R.color.brand_navy) else Color.TRANSPARENT)
            })
            b.lineTabs.addView(tab)
        }
        val line = item.stations.getOrNull(selectedIndex)?.line ?: ""
        b.band.backgroundTintList = ColorStateList.valueOf(ctx.getColor(LineStyle.colorRes(line)))
    }

    private fun pick(slot: Slot, item: StationGroupItem) {
        val parent = parentFragment
        if (parent !is SearchFragment) routeVm.startFresh()     // 노선도·즐겨찾기 = 새 검색
        val ok = routeVm.set(slot, item)
        dismiss()
        when {
            parent is SearchFragment -> if (!ok) parent.showSameStationNotice()
            parent == null -> Unit
            !ok -> NoticeDialog.show(parent, R.string.same_station_title, R.string.same_station_body)
            // 노선도·즐겨찾기에서 골랐을 때: 둘 다 정해졌으면 바로 결과, 아니면 남은 칸을 검색에서
            routeVm.points.value.isComplete -> parent.findNavController().navigate(R.id.to_result)
            else -> parent.findNavController().navigate(R.id.to_search)
        }
    }

    /** 닫히면 부모(홈)가 '지금 누른 역' 핀을 거두도록 알림 */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isAdded) parentFragmentManager.setFragmentResult(RESULT_CLOSED, Bundle())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedIndex)
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "StationActionSheet"
        const val RESULT_CLOSED = "station_sheet_closed"
        private const val KEY_TAB = "tab"
    }
}
