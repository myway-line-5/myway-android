package com.myway.app.ui.result

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myway.app.R
import com.myway.app.databinding.ItemTimelineLegBinding
import com.myway.app.databinding.ItemTimelineStopBinding
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.displayName

class TimelineAdapter : ListAdapter<TimelineRow, RecyclerView.ViewHolder>(Diff) {

    class StopVH(val b: ItemTimelineStopBinding) : RecyclerView.ViewHolder(b.root)
    class LegVH(val b: ItemTimelineLegBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position) is TimelineRow.Stop) STOP else LEG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == STOP) StopVH(ItemTimelineStopBinding.inflate(inf, parent, false))
        else LegVH(ItemTimelineLegBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val row = getItem(position)) {
            is TimelineRow.Stop -> with((holder as StopVH).b) {
                time.text = row.time
                badge.text = row.badge
                badge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(LineStyle.colorRes(row.line)))
                badge.setTextColor(ctx.getColor(LineStyle.textColorRes(row.line)))
                name.text = row.name.displayName()
                sub.text = row.sub
                sub.isVisible = !row.sub.isNullOrEmpty()
                root.contentDescription = listOfNotNull(row.time, row.name.displayName(), row.sub).joinToString(", ")
            }
            is TimelineRow.Leg -> with((holder as LegVH).b) {
                rail.setBackgroundColor(
                    ctx.getColor(if (row.line == null) R.color.border_default else LineStyle.colorRes(row.line))
                )
                label.text = row.label
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<TimelineRow>() {
        override fun areItemsTheSame(a: TimelineRow, b: TimelineRow) = a == b
        override fun areContentsTheSame(a: TimelineRow, b: TimelineRow) = a == b
    }

    companion object { private const val STOP = 0; private const val LEG = 1 }
}
