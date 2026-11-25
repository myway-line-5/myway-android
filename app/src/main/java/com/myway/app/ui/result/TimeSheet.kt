package com.myway.app.ui.result

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.R
import com.myway.app.databinding.SheetTimeBinding
import java.text.DateFormatSymbols
import java.time.LocalTime

/**
 * Figma 1-19 · 1-22 시간 설정 시트
 * 출발 기준이면 도착 예상, 도착 기준이면 출발 예상을 바로 보여 줌.
 * 결과: [KEY] → arrive(Boolean), time(초) 또는 now=true
 */
class TimeSheet : BottomSheetDialogFragment() {

    private var _b: SheetTimeBinding? = null
    private var arrive = false
    private val total: Long get() = requireArguments().getLong(ARG_TOTAL)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetTimeBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        val args = requireArguments()
        arrive = args.getBoolean(ARG_ARRIVE)
        val start = LocalTime.ofSecondOfDay(args.getInt(ARG_TIME).toLong())

        val amPm = DateFormatSymbols.getInstance(resources.configuration.locales[0]).amPmStrings
        b.ampm.setup(0, 1, amPm)
        b.hour.setup(1, 12, null)
        b.minute.setup(0, 59, Array(60) { "%02d".format(it) })
        set(start)
        listOf(b.ampm, b.hour, b.minute).forEach { it.setOnValueChangedListener { _, _, _ -> renderEstimate() } }

        b.modeDepart.setOnClickListener { setMode(false) }
        b.modeArrive.setOnClickListener { setMode(true) }
        setMode(arrive)

        b.closeButton.setOnClickListener { dismiss() }
        b.nowButton.setOnClickListener {
            setFragmentResult(KEY, bundleOf(RES_NOW to true))
            dismiss()
        }
        b.okButton.setOnClickListener {
            setFragmentResult(KEY, bundleOf(RES_ARRIVE to arrive, RES_TIME to picked().toSecondOfDay()))
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED   // 휠이 잘리지 않게
    }

    private fun setMode(a: Boolean) {
        val b = _b ?: return
        arrive = a
        b.modeDepart.isSelected = !a
        b.modeArrive.isSelected = a
        setFragmentResult(KEY_MODE, bundleOf(RES_ARRIVE to a))        // 뒤 화면 칩도 같이 강조
        renderEstimate()
    }

    private fun picked(): LocalTime {
        val b = _b ?: return LocalTime.now()
        val h12 = b.hour.value % 12
        return LocalTime.of(h12 + if (b.ampm.value == 1) 12 else 0, b.minute.value)
    }

    private fun set(t: LocalTime) {
        val b = _b ?: return
        b.ampm.value = if (t.hour >= 12) 1 else 0
        b.hour.value = (t.hour % 12).let { if (it == 0) 12 else it }
        b.minute.value = t.minute
    }

    /** 반대쪽 시각: 출발 기준 → 도착 예상 / 도착 기준 → 출발 예상 */
    private fun renderEstimate() {
        val b = _b ?: return
        val t = picked()
        b.estimateLabel.setText(if (arrive) R.string.time_estimate_depart else R.string.time_estimate_arrive)
        b.estimateTime.text = Timeline.clock(if (arrive) t.minusSeconds(total) else t.plusSeconds(total))
    }

    /** 휠 한 열: 가운데 글자 크게·진하게(Android 10+), 위아래로 끝없이 돌게 */
    private fun NumberPicker.setup(min: Int, max: Int, labels: Array<String>?) {
        minValue = min; maxValue = max
        displayedValues = labels
        wrapSelectorWheel = labels?.size != 2
        if (Build.VERSION.SDK_INT >= 29) {
            textColor = requireContext().getColor(R.color.text_primary)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
            selectionDividerHeight = 0                      // 구분선 대신 뒤의 회색 줄
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (isAdded) setFragmentResult(KEY_CLOSED, Bundle())                // 닫히면 칩 강조를 확정값으로 되돌림
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "TimeSheet"
        const val KEY = "time_sheet"
        const val KEY_MODE = "time_sheet_mode"
        const val KEY_CLOSED = "time_sheet_closed"
        const val RES_NOW = "now"
        const val RES_ARRIVE = "arrive"
        const val RES_TIME = "time"
        private const val ARG_ARRIVE = "arrive"
        private const val ARG_TIME = "time"
        private const val ARG_TOTAL = "total"

        fun newInstance(arrive: Boolean, time: LocalTime, totalSeconds: Long) = TimeSheet().apply {
            arguments = bundleOf(ARG_ARRIVE to arrive, ARG_TIME to time.toSecondOfDay(), ARG_TOTAL to totalSeconds)
        }
    }
}
