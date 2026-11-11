package com.myway.app.ui.search

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myway.app.R
import com.myway.app.databinding.DialogNoticeBinding
import com.myway.app.databinding.FragmentSearchBinding
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {

    private val vm: SearchViewModel by viewModels()
    private var _b: FragmentSearchBinding? = null
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

        // 검색 레이어는 열리자마자 키보드
        b.searchInput.requestFocus()
        b.searchInput.post {
            requireContext().getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(state: SearchUiState) {
        val b = _b ?: return
        val hasResult = state is SearchUiState.Success
        b.progress.isVisible = state is SearchUiState.Loading
        b.resultLabel.isVisible = hasResult
        b.stationList.isVisible = hasResult
        b.emptyView.isVisible = state is SearchUiState.Empty || state is SearchUiState.Error

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

    private fun onStationClick(item: StationGroupItem) {
        if (!item.isSupported) {               // 2·5·9호선 외
            showNotice(R.string.unsupported_dialog_title, R.string.unsupported_dialog_body); return
        }
        if (!item.isActive) {                  // 1-17
            showNotice(R.string.inactive_dialog_title, R.string.inactive_dialog_body); return
        }
        // 이름이 아니라 노선별 역 id 목록으로 넘긴다 (동명 역 구분)
        val ids = ArrayList(item.activeStations.map { it.stationId })
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_STATION_IDS to ids))
        // TODO: 1-12 역 선택 팝업(출발/도착 지정) 연결
    }

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

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "station_selected"
        const val RESULT_STATION_IDS = "stationIds"
    }
}
