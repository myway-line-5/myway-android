package com.myway.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myway.app.R
import com.myway.app.data.local.SavedRoute
import com.myway.app.data.local.SavedStation
import com.myway.app.databinding.ItemRecentBinding
import com.myway.app.databinding.ItemRecentHeaderBinding
import com.myway.app.domain.model.RouteType
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.displayName

/** Figma 1-10: 최근 검색(역) · 최근 경로. 행마다 ☆ 즐겨찾기 + ✕ 지우기 */
sealed interface RecentRow {
    data class Header(val titleRes: Int, val showClearAll: Boolean) : RecentRow
    data class StationRow(val station: SavedStation, val favorite: Boolean) : RecentRow
    data class RouteRow(val route: SavedRoute, val favorite: Boolean) : RecentRow
}

class RecentAdapter(
    private val onClearAll: () -> Unit,
    private val onStation: (SavedStation) -> Unit,
    private val onStationFavorite: (SavedStation) -> Unit,
    private val onStationRemove: (SavedStation) -> Unit,
    private val onRoute: (SavedRoute) -> Unit,
    private val onRouteFavorite: (SavedRoute) -> Unit,
    private val onRouteRemove: (SavedRoute) -> Unit
) : ListAdapter<RecentRow, RecyclerView.ViewHolder>(Diff) {

    class HeaderVH(val b: ItemRecentHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class RowVH(val b: ItemRecentBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is RecentRow.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(ItemRecentHeaderBinding.inflate(inf, parent, false))
        else RowVH(ItemRecentBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = getItem(position)) {
            is RecentRow.Header -> with((holder as HeaderVH).b) {
                title.setText(row.titleRes)
                clearAll.visibility = if (row.showClearAll) android.view.View.VISIBLE else android.view.View.INVISIBLE
                clearAll.setOnClickListener { onClearAll() }
            }
            is RecentRow.StationRow -> with((holder as RowVH).b) {
                bindBadges(this, row.station)
                title.text = row.station.name.displayName()
                sub.text = row.station.stations.joinToString(" · ") {
                    ctx.getString(R.string.line_format, LineStyle.key(it.line))
                }
                bindStar(this, row.favorite, row.station.name.displayName())
                root.setOnClickListener { onStation(row.station) }
                favoriteButton.setOnClickListener { onStationFavorite(row.station) }
                removeButton.contentDescription = ctx.getString(R.string.recent_remove, row.station.name.displayName())
                removeButton.setOnClickListener { onStationRemove(row.station) }
            }
            is RecentRow.RouteRow -> with((holder as RowVH).b) {
                val r = row.route
                bindBadges(this, r.origin)
                val name = ctx.getString(R.string.recent_route_name, r.origin.name.displayName(), r.destination.name.displayName())
                title.text = name
                sub.text = listOfNotNull(
                    ctx.getString(typeLabel(r.type)),
                    r.minutes?.let { ctx.getString(R.string.result_min, it) }
                ).joinToString(" · ")
                bindStar(this, row.favorite, name)
                root.setOnClickListener { onRoute(r) }
                favoriteButton.setOnClickListener { onRouteFavorite(r) }
                removeButton.contentDescription = ctx.getString(R.string.recent_remove, name)
                removeButton.setOnClickListener { onRouteRemove(r) }
            }
        }
    }

    private fun bindBadges(b: ItemRecentBinding, s: SavedStation) {
        val ctx = b.root.context
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.space_xs)
        b.badgeContainer.removeAllViews()
        s.stations.forEachIndexed { i, st ->
            b.badgeContainer.addView(LineStyle.badge(ctx, st.line, if (i == 0) 0 else gap))
        }
    }

    private fun bindStar(b: ItemRecentBinding, fav: Boolean, name: String) {
        val ctx = b.root.context
        b.favoriteButton.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        b.favoriteButton.contentDescription =
            ctx.getString(if (fav) R.string.favorite_remove else R.string.favorite_add, name)
    }

    private object Diff : DiffUtil.ItemCallback<RecentRow>() {
        override fun areItemsTheSame(a: RecentRow, b: RecentRow) = when {
            a is RecentRow.Header && b is RecentRow.Header -> a.titleRes == b.titleRes
            a is RecentRow.StationRow && b is RecentRow.StationRow -> a.station.name == b.station.name
            a is RecentRow.RouteRow && b is RecentRow.RouteRow ->
                a.route.samePair(b.route.origin.name, b.route.destination.name)
            else -> false
        }
        override fun areContentsTheSame(a: RecentRow, b: RecentRow) = a == b
    }

    companion object {
        fun typeLabel(t: RouteType) = when (t) {
            RouteType.MIN_TRANSFERS -> R.string.result_tab_transfer
            RouteType.MIN_FARE -> R.string.result_tab_fare
            else -> R.string.result_tab_time
        }
    }
}
