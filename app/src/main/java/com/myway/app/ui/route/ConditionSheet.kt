package com.myway.app.ui.route

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.R
import com.myway.app.databinding.SheetConditionsBinding
import com.myway.app.domain.model.Constraints
import com.myway.app.domain.model.Criterion
import com.myway.app.domain.model.Preference
import com.myway.app.domain.model.PreferenceMode
import com.myway.app.ui.route.RouteSelectionViewModel.Conditions
import java.text.NumberFormat

/**
 * UC-05 경로 조건 설정 (화면 22~34 공통).
 * 숫자 입력 대신 줄마다 4~5개 선택지 → 고르기 빠르고(Hick) 잘못 입력할 일이 없다.
 * 지금 쓰는 값이 목록에 없으면(예: AI가 40분) 그 값도 선택지에 넣어 그대로 보여 준다.
 */
class ConditionSheet : BottomSheetDialogFragment() {

    private val routeVm: RouteSelectionViewModel by activityViewModels()
    private var _b: SheetConditionsBinding? = null

    private lateinit var initial: Conditions
    private var time: Int? = null
    private var transfers: Int? = null
    private var fare: Int? = null
    private var pref: Criterion? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initial = routeVm.effectiveConditions()
        val c = initial.constraints
        time = c.maxDurationMinutes
        transfers = c.maxTransfers
        fare = c.maxFareKrw
        pref = initial.preference?.priority?.firstOrNull()
        savedInstanceState?.let { s ->           // 화면 회전 후에도 고르던 값 유지
            time = s.getInt(K_TIME, NONE).takeIf { it != NONE }
            transfers = s.getInt(K_TRANSFER, NONE).takeIf { it != NONE }
            fare = s.getInt(K_FARE, NONE).takeIf { it != NONE }
            pref = s.getString(K_PREF)?.let { Criterion.valueOf(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetConditionsBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        b.closeButton.setOnClickListener { dismiss() }
        b.resetButton.setOnClickListener {
            time = null; transfers = null; fare = null; pref = null; renderAll()
        }
        b.applyButton.setOnClickListener { apply() }
        renderAll()
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_TIME, time ?: NONE)
        outState.putInt(K_TRANSFER, transfers ?: NONE)
        outState.putInt(K_FARE, fare ?: NONE)
        outState.putString(K_PREF, pref?.name)
    }

    private fun renderAll() {
        val b = _b ?: return
        val won = NumberFormat.getInstance()
        row(b.timeRow, withCurrent(TIME_OPTIONS, time), time,
            { it?.let { m -> getString(R.string.cond_minutes, m) } ?: getString(R.string.cond_none) }) { time = it }
        row(b.transferRow, withCurrent(TRANSFER_OPTIONS, transfers), transfers, {
            when (it) {
                null -> getString(R.string.cond_none)
                0 -> getString(R.string.cond_transfer_zero)
                else -> getString(R.string.cond_transfer_n, it)
            }
        }) { transfers = it }
        row(b.fareRow, withCurrent(FARE_OPTIONS, fare), fare,
            { it?.let { f -> getString(R.string.cond_fare_n, won.format(f)) } ?: getString(R.string.cond_none) }) { fare = it }
        row(b.prefRow, PREF_OPTIONS, pref, {
            getString(when (it) {
                null -> R.string.cond_pref_default
                Criterion.DURATION -> R.string.cond_pref_duration
                Criterion.TRANSFERS -> R.string.cond_pref_transfers
                Criterion.FARE -> R.string.cond_pref_fare
            })
        }) { pref = it }
    }

    /** 한 줄의 선택지를 다시 그린다. 같은 너비로 나눠 누르기 쉽게(최소 44dp 높이) */
    private fun <T> row(container: LinearLayout, options: List<T?>, current: T?, label: (T?) -> String, pick: (T?) -> Unit) {
        val ctx = requireContext()
        val gap = resources.getDimensionPixelSize(R.dimen.space_xs)
        val h = (44 * resources.displayMetrics.density).toInt()
        container.removeAllViews()
        options.forEachIndexed { i, opt ->
            container.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply { if (i > 0) marginStart = gap }
                gravity = Gravity.CENTER
                maxLines = 1
                setTextAppearance(R.style.Text_MyWay_Label14Bold)
                setTextColor(ctx.getColorStateList(R.color.text_option))
                setBackgroundResource(R.drawable.bg_option)
                text = label(opt)
                isSelected = opt == current
                contentDescription = text
                setOnClickListener { pick(opt); renderAll() }
            })
        }
    }

    private fun build(): Conditions {
        val constraints = Constraints(time, transfers, fare)
        // 기준을 안 바꿨으면 AI 가 정한 선호(가중치 등)를 그대로 둔다
        val preference = if (pref == initial.preference?.priority?.firstOrNull()) initial.preference
        else pref?.let { first ->
            Preference(PreferenceMode.LEXICOGRAPHIC, listOf(first) + Criterion.entries.filter { it != first }, null)
        }
        return Conditions(constraints, preference)
    }

    private fun apply() {
        val c = build()
        if (c != routeVm.effectiveConditions()) routeVm.setConditions(c)
        dismiss()
    }

    private fun withCurrent(base: List<Int?>, current: Int?): List<Int?> =
        if (current == null || current in base) base
        else (base.filterNotNull() + current).sorted().let { listOf<Int?>(null) + it }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ConditionSheet"
        private const val NONE = Int.MIN_VALUE
        private const val K_TIME = "time"
        private const val K_TRANSFER = "transfer"
        private const val K_FARE = "fare"
        private const val K_PREF = "pref"
        private val TIME_OPTIONS = listOf(null, 30, 45, 60, 90)
        private val TRANSFER_OPTIONS = listOf(null, 0, 1, 2)
        private val FARE_OPTIONS = listOf(null, 1500, 1800, 2000)
        private val PREF_OPTIONS = listOf(null, Criterion.DURATION, Criterion.TRANSFERS, Criterion.FARE)
    }
}
