package com.myway.app.ui.search

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myway.app.R
import com.myway.app.databinding.DialogNoticeBinding
import com.myway.app.databinding.FragmentSearchBinding
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.route.RouteSelectionViewModel.Points
import com.myway.app.ui.route.RouteSelectionViewModel.Slot
import kotlinx.coroutines.launch

/**
 * 검색 레이어
 * - 1-11 역 검색 → 역 누르면 1-12 역 선택 시트(출발/도착)
 * - 1-16 하나만 정해지면: 칩 표시 + 남은 칸 검색, 역 누르면 바로 지정
 * - 1-13 둘 다 정해지면: 출발·도착 카드 + 경로 찾기
 */
class SearchFragment : Fragment(R.layout.fragment_search) {

    private val vm: SearchViewModel by viewModels()
    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentSearchBinding? = null
    private var lastState: SearchUiState = SearchUiState.Idle
    private var lastPoints: Points? = null

    private val adapter = StationAdapter(
        onClick = ::onStationClick,
        onFavoriteClick = { vm.onToggleFavorite(it) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSearchBinding.bind(view).also { _b = it }

        b.stationList.adapter = adapter
        b.backButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        b.favoritesButton.setOnClickListener { /* TODO: 즐겨찾기 화면(61~65)으로 이동 */ }
        b.searchInput.doAfterTextChanged { vm.onQueryChange(it?.toString().orEmpty()) }

        // 칩: 누르면 그 칸을 비우고 다시 고르기
        b.pointChip.root.setOnClickListener {
            val p = routeVm.points.value
            routeVm.clear(if (p.origin != null) Slot.ORIGIN else Slot.DESTINATION)
        }
        // 카드
        with(b.routeCard) {
            swapButton.setOnClickListener { routeVm.swap() }
            originRow.setOnClickListener { routeVm.clear(Slot.ORIGIN) }
            originClear.setOnClickListener { routeVm.clear(Slot.ORIGIN) }
            destinationRow.setOnClickListener { routeVm.clear(Slot.DESTINATION) }
            destinationClear.setOnClickListener { routeVm.clear(Slot.DESTINATION) }
        }
        b.findRouteButton.setOnClickListener {
            // TODO: RouteRepository Fake 생기면 경로 결과(1-18)로 이동
            Toast.makeText(requireContext(), R.string.route_find_pending, Toast.LENGTH_SHORT).show()
        }

        showKeyboard()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { lastState = it; render() } }
                launch { routeVm.points.collect(::renderPoints) }
            }
        }
    }

    // ---------- 그리기 ----------

    private fun renderPoints(p: Points) {
        val b = _b ?: return
        val changed = lastPoints != null && lastPoints != p
        lastPoints = p

        // 1-13 카드
        b.routeCard.root.isVisible = p.isComplete
        b.findRouteButton.isVisible = p.isComplete
        b.backButton.isVisible = !p.isComplete
        b.searchField.isVisible = !p.isComplete
        p.origin?.let { b.routeCard.originName.text = it.name }
        p.destination?.let { b.routeCard.destinationName.text = it.name }
        b.routeCard.originClear.contentDescription = getString(R.string.point_clear_desc, getString(R.string.point_origin))
        b.routeCard.destinationClear.contentDescription = getString(R.string.point_clear_desc, getString(R.string.point_destination))

        // 1-16 칩 + 검색창 안내 문구
        val chipItem = p.origin ?: p.destination
        b.pointChip.root.isVisible = p.missing != null
        if (p.missing != null && chipItem != null) {
            val isOrigin = p.origin != null
            val label = getString(if (isOrigin) R.string.point_origin else R.string.point_destination)
            b.pointChip.chipDot.setBackgroundResource(if (isOrigin) R.drawable.dot_origin else R.drawable.dot_destination)
            b.pointChip.chipLabel.text = label
            b.pointChip.chipName.text = chipItem.name
            b.pointChip.root.contentDescription = getString(R.string.point_chip_desc, label, chipItem.name)
        }
        b.searchInput.setHint(
            when (p.missing) {
                Slot.DESTINATION -> R.string.hint_destination
                Slot.ORIGIN -> R.string.hint_origin
                null -> R.string.search_hint
            }
        )

        if (changed) {
            b.searchInput.text?.clear()
            if (p.isComplete) hideKeyboard() else showKeyboard()
        }
        render()
    }

    private fun render() {
        val b = _b ?: return
        val state = lastState
        val searching = routeVm.points.value.isComplete.not()
        val hasResult = searching && state is SearchUiState.Success
        b.progress.isVisible = searching && state is SearchUiState.Loading
        b.resultLabel.isVisible = hasResult
        b.stationList.isVisible = hasResult
        b.emptyView.isVisible = searching && (state is SearchUiState.Empty || state is SearchUiState.Error)

        when (state) {
            is SearchUiState.Success -> adapter.submitList(state.items)
            is SearchUiState.Empty -> {
                b.emptyTitle.text = getString(R.string.search_empty_title, state.query)
                b.emptyBody.setText(R.string.search_empty_body)
            }
            is SearchUiState.Error -> {          // TODO: ErrorPresenter 로 교체
                b.emptyTitle.setText(R.string.search_error_title)
                b.emptyBody.setText(R.string.search_error_body)
            }
            else -> adapter.submitList(emptyList())
        }
    }

    // ---------- 동작 ----------

    private fun onStationClick(item: StationGroupItem) {
        if (!item.isSupported) {               // 2·5·9호선 외
            showNotice(R.string.unsupported_dialog_title, R.string.unsupported_dialog_body); return
        }
        if (!item.isActive) {                  // 1-17
            showNotice(R.string.inactive_dialog_title, R.string.inactive_dialog_body); return
        }
        val missing = routeVm.points.value.missing
        if (missing != null) {                 // 1-16: 남은 칸을 바로 지정 (한 번 덜 누르게)
            if (!routeVm.set(missing, item)) showSameStationNotice()
            return
        }
        routeVm.focus(item)                    // 1-12: 출발/도착 고르기
        hideKeyboard()
        StationActionSheet().show(childFragmentManager, StationActionSheet.TAG)
    }

    fun showSameStationNotice() = showNotice(R.string.same_station_title, R.string.same_station_body)

    private fun showNotice(titleRes: Int, bodyRes: Int) {
        val db = DialogNoticeBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(resources.displayMetrics.widthPixels - margin * 2,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.dialogTitle.setText(titleRes)
        db.dialogBody.setText(bodyRes)
        db.dialogAction.setText(R.string.inactive_dialog_action)
        db.dialogAction.setOnClickListener {
            dialog.dismiss()
            _b?.searchInput?.selectAll()      // 바로 다른 역 입력
        }
        dialog.show()
    }

    private fun showKeyboard() {
        val input = _b?.searchInput ?: return
        input.requestFocus()
        input.post {
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val input = _b?.searchInput ?: return
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    override fun onDestroyView() {
        _b = null
        lastPoints = null
        super.onDestroyView()
    }
}
