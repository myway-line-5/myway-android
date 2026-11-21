package com.myway.app.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.R
import com.myway.app.databinding.ItemLineSheetRowBinding
import com.myway.app.databinding.SheetLinePickerBinding
import com.myway.app.ui.common.LineStyle

/** Figma 1-6~1-9 노선도 선택 시트. 선택한 행: 노선색 12% 배경 + 1.5dp 테두리 + ✓ */
class LinePickerSheet : BottomSheetDialogFragment() {

    private val vm: HomeViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private var _b: SheetLinePickerBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetLinePickerBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        b.closeButton.setOnClickListener { dismiss() }

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val radius = resources.getDimension(R.dimen.radius_input)
        val selected = vm.map.value

        MapType.entries.forEach { type ->
            val row = ItemLineSheetRowBinding.inflate(layoutInflater, b.rowContainer, false)
            val lineColor = ctx.getColor(
                if (type.line == null) R.color.brand_navy else LineStyle.colorRes(type.line)
            )
            val isSelected = type == selected

            // 배지: 노선 번호 / 전체는 지도 아이콘
            row.badge.backgroundTintList = ColorStateList.valueOf(lineColor)
            row.badgeIcon.isVisible = type.line == null
            row.badgeText.isVisible = type.line != null
            type.line?.let {
                row.badgeText.text = it
                row.badgeText.setTextColor(ctx.getColor(LineStyle.textColorRes(it)))
            }

            row.rowTitle.setText(type.titleRes)
            row.rowDesc.setText(type.descRes)

            row.root.background = GradientDrawable().apply {
                cornerRadius = radius
                if (isSelected) {
                    setColor(ColorUtils.setAlphaComponent(lineColor, 0x1F))
                    setStroke((1.5f * density).toInt(), lineColor)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(density.toInt(), ctx.getColor(R.color.border_strong))
                }
            }
            row.rowTrailing.setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_chevron_right)
            row.rowTrailing.imageTintList = if (isSelected) ColorStateList.valueOf(lineColor) else null
            row.root.isSelected = isSelected
            row.root.contentDescription = "${getString(type.titleRes)}, ${getString(type.descRes)}"

            row.root.setOnClickListener {
                vm.select(type)
                dismiss()
            }
            b.rowContainer.addView(row.root)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object { const val TAG = "LinePickerSheet" }
}
