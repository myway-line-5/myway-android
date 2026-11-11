package com.myway.app.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myway.app.R
import com.myway.app.databinding.ItemStationBinding
import com.myway.app.ui.common.LineStyle

class StationAdapter(
    private val onClick: (StationGroupItem) -> Unit,
    private val onFavoriteClick: (StationGroupItem) -> Unit
) : ListAdapter<StationGroupItem, StationAdapter.VH>(Diff) {

    class VH(val b: ItemStationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.b.root.context
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.space_xs)
        val lineLabels = item.stations.map { ctx.getString(R.string.line_format, LineStyle.key(it.line)) }

        with(holder.b) {
            badgeContainer.removeAllViews()
            item.stations.forEachIndexed { i, s ->
                badgeContainer.addView(LineStyle.badge(ctx, s.line, if (i == 0) 0 else gap))
            }
            stationName.text = item.name
            if (item.isSupported) {
                lineNames.text = lineLabels.joinToString(" · ")
                lineNames.setTextColor(ctx.getColor(R.color.text_secondary))
            } else {
                lineNames.text = ctx.getString(
                    R.string.unsupported_line_caption, LineStyle.key(item.stations.first().line)
                )
                lineNames.setTextColor(ctx.getColor(R.color.status_error))
            }

            row.alpha = if (item.isActive || !item.isSupported) 1f else 0.45f
            row.contentDescription = "${item.name}, ${lineNames.text}"
            row.setOnClickListener { onClick(item) }
            favoriteButton.visibility = if (item.isSupported) View.VISIBLE else View.INVISIBLE

            favoriteButton.setImageResource(
                if (item.isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            favoriteButton.contentDescription = ctx.getString(
                if (item.isFavorite) R.string.favorite_remove else R.string.favorite_add, item.name
            )
            favoriteButton.setOnClickListener { onFavoriteClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<StationGroupItem>() {
        override fun areItemsTheSame(a: StationGroupItem, b: StationGroupItem) =
            a.stations.map { it.stationId } == b.stations.map { it.stationId }
        override fun areContentsTheSame(a: StationGroupItem, b: StationGroupItem) = a == b
    }
}
