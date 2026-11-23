package com.myway.app.ui.favorites

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.myway.app.R
import com.myway.app.data.local.SavedRoute
import com.myway.app.data.local.SavedStation
import com.myway.app.databinding.ItemFavoriteBinding
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.search.RecentAdapter

sealed interface FavoriteItem {
    data class Route(val route: SavedRoute) : FavoriteItem
    data class Station(val station: SavedStation) : FavoriteItem
}

/** 편집 모드에서 순서 바꾸기를 위해 목록을 직접 들고 있는 단순 어댑터 */
class FavoritesAdapter(
    private val onClick: (FavoriteItem) -> Unit,
    private val onRemove: (FavoriteItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    class VH(val b: ItemFavoriteBinding) : RecyclerView.ViewHolder(b.root)

    private val items = mutableListOf<FavoriteItem>()
    var editing = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) { field = value; notifyDataSetChanged() }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<FavoriteItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from)); notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.b
        val ctx = b.root.context
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.space_xs)

        b.leadingBadges.removeAllViews()
        b.smallBadges.removeAllViews()

        val name: String
        when (item) {
            is FavoriteItem.Route -> {
                val r = item.route
                name = ctx.getString(R.string.recent_route_name, r.origin.name, r.destination.name)
                b.leadingBadges.isVisible = false
                b.labelRow.isVisible = true
                b.label.isVisible = r.label != null
                b.label.text = r.label
                // 출발역 노선 (작은 배지 18dp)
                r.origin.stations.forEachIndexed { i, s ->
                    b.smallBadges.addView(LineStyle.badge(ctx, s.line, if (i == 0) 0 else gap, R.dimen.small_badge))
                }
                b.title.text = name
                b.sub.isVisible = false
                b.typeChip.isVisible = !editing
                b.typeChip.setText(RecentAdapter.typeLabel(r.type))
                b.trailing.isVisible = !editing
                b.trailing.setImageResource(R.drawable.ic_chevron_right)
                b.starButton.isVisible = false
            }
            is FavoriteItem.Station -> {
                val s = item.station
                name = s.name
                b.leadingBadges.isVisible = true
                s.stations.forEachIndexed { i, st ->
                    b.leadingBadges.addView(LineStyle.badge(ctx, st.line, if (i == 0) 0 else gap))
                }
                b.labelRow.isVisible = false
                b.title.text = s.name
                b.sub.isVisible = true
                b.sub.text = s.stations.joinToString(" · ") {
                    ctx.getString(R.string.line_format, LineStyle.key(it.line))
                }
                b.typeChip.isVisible = false
                b.trailing.isVisible = false
                b.starButton.isVisible = !editing
                b.starButton.contentDescription = ctx.getString(R.string.favorite_remove, s.name)
                b.starButton.setOnClickListener { onRemove(item) }
            }
        }

        b.removeButton.isVisible = editing
        b.removeButton.contentDescription = ctx.getString(R.string.fav_remove_desc, name)
        b.removeButton.setOnClickListener { onRemove(item) }

        b.dragHandle.isVisible = editing
        b.dragHandle.contentDescription = ctx.getString(R.string.fav_drag_desc, name)
        b.dragHandle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        b.root.isClickable = !editing
        b.root.setOnClickListener { if (!editing) onClick(item) }
        b.root.contentDescription = name
    }
}
