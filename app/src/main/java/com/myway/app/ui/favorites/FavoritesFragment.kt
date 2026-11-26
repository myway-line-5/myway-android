package com.myway.app.ui.favorites

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.myway.app.R
import com.myway.app.data.local.LocalStore
import com.myway.app.databinding.DialogConfirmBinding
import com.myway.app.databinding.FragmentFavoritesBinding
import com.myway.app.ui.common.Motion
import com.myway.app.ui.common.blockedOffline
import com.myway.app.ui.common.toItem
import com.myway.app.ui.route.RouteSelectionViewModel
import com.myway.app.ui.search.StationActionSheet
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.myway.app.ui.common.displayName

/** Figma 5-3~5-7 즐겨찾기 */
class FavoritesFragment : Fragment(R.layout.fragment_favorites) {

    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: FragmentFavoritesBinding? = null

    private var showRoutes = true
    private var editing = false

    private val adapter: FavoritesAdapter = FavoritesAdapter(
        onClick = ::onItemClick,
        onRemove = ::confirmRemove,
        onStartDrag = { touchHelper.startDrag(it) }
    )

    private var dragFrom = -1
    private val touchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        // 편집의 손잡이(≡) + 평소에도 길게 눌러 끌기 (경로·역 같은 방식)
        override fun isLongPressDragEnabled() = true

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state != ItemTouchHelper.ACTION_STATE_DRAG || vh == null) return
            // 집어 든 카드: 살짝 커지고 떠오름 + 진동
            vh.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            vh.itemView.animate().scaleX(1.03f).scaleY(1.03f)
                .translationZ(8 * resources.displayMetrics.density).setDuration(150).start()
        }
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            if (dragFrom < 0) dragFrom = from.bindingAdapterPosition
            adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).start()
            val to = vh.bindingAdapterPosition
            if (dragFrom >= 0 && to >= 0 && dragFrom != to) {
                if (showRoutes) LocalStore.moveFavoriteRoute(dragFrom, to)
                else LocalStore.moveFavoriteStation(dragFrom, to)
            }
            dragFrom = -1
        }
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentFavoritesBinding.bind(view).also { _b = it }
        showRoutes = savedInstanceState?.getBoolean(KEY_TAB, true) ?: true

        b.list.adapter = adapter
        touchHelper.attachToRecyclerView(b.list)

        b.backButton.setOnClickListener { findNavController().popBackStack() }
        b.editButton.setOnClickListener { setEditing(!editing) }
        b.tabRoutes.setOnClickListener { selectTab(true) }
        b.tabStations.setOnClickListener { selectTab(false) }
        b.emptyAction.setOnClickListener { findNavController().navigate(R.id.to_search) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(LocalStore.favoriteRoutes, LocalStore.favoriteStations) { r, s -> r to s }
                        .collect { render() }
                }
                // 역 시트에서 출발/도착을 고르면 다음 화면으로
            }
        }
    }

    private fun selectTab(routes: Boolean) {
        if (routes == showRoutes) return
        showRoutes = routes
        val b = _b
        if (b != null) Motion.refresh(b.list, if (routes) -1 else 1)   // 경로 ← | → 역 방향으로
        setEditing(false)
    }

    private fun setEditing(on: Boolean) {
        if (on != editing) _b?.let { b -> Motion.swap(b.editButton) {} }
        editing = on
        adapter.editing = on
        render()
    }

    private fun render() {
        val b = _b ?: return
        val routes = LocalStore.favoriteRoutes.value
        val stations = LocalStore.favoriteStations.value
        val allEmpty = routes.isEmpty() && stations.isEmpty()
        val items = if (showRoutes) routes.map { FavoriteItem.Route(it) } else stations.map { FavoriteItem.Station(it) }

        // 5-6: 전부 비었으면 탭 없이 안내만
        b.emptyView.isVisible = allEmpty
        b.segment.isVisible = !allEmpty
        b.list.isVisible = !allEmpty
        b.hint.isVisible = !allEmpty && items.isNotEmpty() && !editing
        b.editButton.isVisible = items.isNotEmpty()
        if (items.isEmpty() && editing) { editing = false; adapter.editing = false }

        b.editButton.setText(if (editing) R.string.fav_done else R.string.fav_edit)
        b.hint.setText(if (showRoutes) R.string.fav_hint_routes else R.string.fav_hint_stations)
        styleTab(b.tabRoutes, showRoutes)
        styleTab(b.tabStations, !showRoutes)
        adapter.submit(items)
    }

    private fun styleTab(tab: android.widget.TextView, selected: Boolean) {
        val ctx = requireContext()
        if (selected) tab.setBackgroundResource(R.drawable.bg_segment_thumb) else tab.background = null
        tab.setTextColor(ctx.getColor(if (selected) R.color.text_on_brand else R.color.text_secondary))
        tab.isSelected = selected
    }

    private fun onItemClick(item: FavoriteItem) {
        when (item) {
            is FavoriteItem.Route -> {       // 바로 길찾기 (출발·도착 쌍만 저장 → 새 탐색이라 온라인 필요)
                // 경로째 저장돼 있으면 오프라인에서도 저장본을 보여 줌 (UC-09)
                if (item.route.snapshot == null && blockedOffline()) return
                routeVm.setRoute(item.route.origin.toItem(), item.route.destination.toItem())
                findNavController().navigate(R.id.to_result)
            }
            is FavoriteItem.Station -> {     // 출발/도착/역 정보 (이동은 시트가 처리)
                routeVm.focus(item.station.toItem(isFavorite = true))
                StationActionSheet().show(childFragmentManager, StationActionSheet.TAG)
            }
        }
    }

    /** 5-5: 이름을 밝힌 삭제 확인 */
    private fun confirmRemove(item: FavoriteItem) {
        val name = when (item) {
            is FavoriteItem.Route -> {
                val path = getString(R.string.recent_route_name, item.route.origin.name.displayName(), item.route.destination.name.displayName())
                item.route.label?.let { getString(R.string.fav_route_label, it, path) } ?: path
            }
            is FavoriteItem.Station -> item.station.name.displayName()
        }
        val db = DialogConfirmBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(resources.displayMetrics.widthPixels - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.dialogTitle.text = getString(R.string.fav_delete_title, name)
        db.dialogBody.setText(R.string.fav_delete_body)
        db.confirmButton.setText(R.string.fav_delete)
        db.cancelButton.setOnClickListener { dialog.dismiss() }
        db.confirmButton.setOnClickListener {
            when (item) {
                is FavoriteItem.Route -> LocalStore.removeFavoriteRoute(item.route)
                is FavoriteItem.Station -> LocalStore.removeFavoriteStation(item.station.name)
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TAB, showRoutes)
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object { private const val KEY_TAB = "tab_routes" }
}
