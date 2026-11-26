package com.myway.app.ui.trip

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.R
import com.myway.app.databinding.SheetTrainPickBinding
import com.myway.app.ui.common.LineStyle
import com.myway.app.ui.common.displayName

/**
 * Figma 3-1 열차 선택 팝업 (껍데기)
 * 실시간 도착 API 전이라 곧 올 열차 3대를 가짜로 보여 준다. 고르면 [KEY] 결과를 보냄.
 */
class TrainPickSheet : BottomSheetDialogFragment() {

    private var _b: SheetTrainPickBinding? = null
    private var picked = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        SheetTrainPickBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        val plan = TripSession.plan ?: return dismiss()
        val legIdx = requireArguments().getInt(ARG_LEG)
        val leg = plan.legs.getOrNull(legIdx) ?: return dismiss()
        b.pickSub.text = getString(R.string.trip_pick_sub, leg.board.name.displayName(), leg.line, leg.toward.displayName())
        b.closeButton.setOnClickListener { dismiss() }

        // 가짜 도착 예정 열차 (초): 가장 빠른 열차가 기본 선택. 1초마다 줄어듦
        val trains = TRAINS.copyOf()
        // 열차마다 목적지 도착 시각 = 지금 + 이 열차 기다림 + 타고 난 뒤 남은 시간 → 늦은 열차는 도착도 늦음을 고르기 전에 보여 줌
        val now = java.time.LocalTime.now()
        val rest = plan.restSeconds(legIdx).toLong()
        val rows = trains.mapIndexed { i, sec ->
            row(leg, i, sec, getString(R.string.trip_train_arrive, plan.destination.displayName(),
                com.myway.app.ui.result.Timeline.clock(now.plusSeconds(sec + rest))))
        }
        countdown = object : Runnable {
            override fun run() {
                val bb = _b ?: return
                trains.forEachIndexed { i, s ->
                    trains[i] = (s - 1).coerceAtLeast(0)
                    setEta(rows[i].findViewWithTag(TAG_ETA), trains[i])
                }
                bb.root.postDelayed(this, 1000)
            }
        }
        b.root.postDelayed(countdown!!, 1000)
        rows.forEach { b.trainList.addView(it) }
        fun select(i: Int) {
            picked = i
            rows.forEachIndexed { j, r -> styleRow(r, leg.line, j == i) }
        }
        rows.forEachIndexed { i, r -> r.setOnClickListener { select(i) } }
        select(0)

        b.confirmButton.setOnClickListener {
            setFragmentResult(KEY, bundleOf(RES_LEG to requireArguments().getInt(ARG_LEG), RES_WAIT to trains[picked]))
            dismiss()
        }
    }

    private fun row(leg: TripLeg, i: Int, sec: Int, arrive: String): LinearLayout {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * d).toInt()
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) topMargin = (8 * d).toInt() }
            isClickable = true; isFocusable = true
            addView(LineStyle.badge(ctx, leg.line))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = (12 * d).toInt() }
                addView(TextView(ctx).apply {
                    setTextAppearance(R.style.Text_MyWay_Body16Bold)
                    text = getString(R.string.trip_train_name, leg.toward.displayName())
                })
                addView(TextView(ctx).apply {
                    setTextAppearance(R.style.Text_MyWay_Label14Medium)
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    fontFeatureSettings = "tnum"
                    text = arrive                                       // "교대 16:45 도착"
                })
            })
            // 오른쪽: "도착까지" + 남은 시간 (전광판처럼 읽히게). 폭 고정 + 오른쪽 정렬 → 숫자가 바뀌어도 글자 위치 그대로
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((100 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(ctx).apply {
                    setTextAppearance(R.style.Text_MyWay_Caption12Medium)
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    setText(R.string.trip_train_until)
                })
                addView(TextView(ctx).apply {
                    tag = TAG_ETA
                    setTextAppearance(R.style.Text_MyWay_Body16Bold)
                    fontFeatureSettings = "tnum"            // 숫자 폭 같게 (초가 바뀔 때 흔들림 방지)
                    setEta(this, sec)
                })
            })
        }
    }

    /** 1분 안 = '곧 도착'(빨강), 0 = '도착' */
    private fun setEta(v: TextView?, sec: Int) {
        v ?: return
        val ctx = v.context
        v.text = when {
            sec <= 0 -> getString(R.string.trip_train_arriving_now)
            sec < 60 -> getString(R.string.trip_train_soon, sec)
            else -> getString(R.string.trip_train_min_sec, sec / 60, sec % 60)     // 2분 00초 → 1분 59초 (형식 고정)
        }
        v.setTextColor(ctx.getColor(if (sec < 60) R.color.status_error else R.color.text_primary))
    }

    private var countdown: Runnable? = null

    /** 고른 열차: 노선색 테두리 1.5dp + 옅은 바탕 (노선도 선택 시트와 같은 규칙) */
    private fun styleRow(v: View, line: String, on: Boolean) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val c = ctx.getColor(LineStyle.colorRes(line))
        v.background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.radius_input)
            if (on) { setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(c, 0x1F)); setStroke((1.5f * d).toInt(), c) }
            else { setColor(ctx.getColor(R.color.bg_surface)); setStroke((1 * d).toInt(), ctx.getColor(R.color.border_strong)) }
        }
        v.isSelected = on
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onDestroyView() {
        countdown?.let { _b?.root?.removeCallbacks(it) }; countdown = null
        _b = null; super.onDestroyView()
    }

    companion object {
        const val TAG = "TrainPickSheet"
        const val KEY = "train_pick"
        const val RES_LEG = "leg"
        const val RES_WAIT = "wait"
        private const val TAG_ETA = "eta"
        /** 곧 올 열차 3대 (초). 첫 열차 = 결과 화면 출발 시각·말로 시작할 때 타는 열차 */
        val TRAINS = intArrayOf(TripEngine.FIRST_TRAIN, 524, 720)
        private const val ARG_LEG = "leg"
        fun newInstance(leg: Int) = TrainPickSheet().apply { arguments = bundleOf(ARG_LEG to leg) }
    }
}
