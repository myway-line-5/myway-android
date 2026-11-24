package com.myway.app.ui.route

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.myway.app.R
import com.myway.app.domain.model.Criterion
import com.myway.app.ui.route.RouteSelectionViewModel.Conditions
import java.text.NumberFormat

/**
 * 경로 조건 칩 줄. 첫 칩 = [조건]/[조건 추가](테두리), 이어서 걸린 조건마다 남색 칩(눈에 띄게).
 * 어느 칩을 눌러도 조건 시트가 열린다.
 */
object ConditionChips {

    fun render(container: LinearLayout, c: Conditions, onClick: () -> Unit) {
        val ctx = container.context
        val res = ctx.resources
        val gap = res.getDimensionPixelSize(R.dimen.space_s)
        val h = (36 * res.displayMetrics.density).toInt()
        val padH = res.getDimensionPixelSize(R.dimen.space_m)
        val labels = labels(container, c)

        container.removeAllViews()
        fun chip(text: String, active: Boolean, icon: Boolean) = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h).apply {
                if (container.childCount > 0) marginStart = gap
            }
            gravity = Gravity.CENTER
            setPadding(padH, 0, padH, 0)
            setTextAppearance(R.style.Text_MyWay_Label14Bold)
            setTextColor(ctx.getColorStateList(R.color.text_option))
            setBackgroundResource(R.drawable.bg_option)
            isSelected = active
            this.text = text
            if (icon) {
                ContextCompat.getDrawable(ctx, R.drawable.ic_tune)?.mutate()?.let { d ->
                    d.setTintList(ColorStateList.valueOf(ctx.getColor(R.color.text_primary)))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
                    compoundDrawablePadding = res.getDimensionPixelSize(R.dimen.space_xs)
                }
            }
            setOnClickListener { onClick() }
        }

        container.addView(chip(ctx.getString(if (labels.isEmpty()) R.string.cond_add else R.string.cond_edit),
            active = false, icon = true))
        labels.forEach { container.addView(chip(it, active = true, icon = false)) }
    }

    fun labels(container: LinearLayout, c: Conditions): List<String> {
        val ctx = container.context
        val k = c.constraints
        return listOfNotNull(
            k.maxDurationMinutes?.let { ctx.getString(R.string.chip_minutes, it) },
            k.maxTransfers?.let {
                if (it == 0) ctx.getString(R.string.chip_transfer_zero) else ctx.getString(R.string.chip_transfer_n, it)
            },
            k.maxFareKrw?.let { ctx.getString(R.string.chip_fare, NumberFormat.getInstance().format(it)) },
            c.preference?.priority?.firstOrNull()?.let {
                ctx.getString(when (it) {
                    Criterion.DURATION -> R.string.chip_pref_duration
                    Criterion.TRANSFERS -> R.string.chip_pref_transfers
                    Criterion.FARE -> R.string.chip_pref_fare
                })
            }
        )
    }
}
