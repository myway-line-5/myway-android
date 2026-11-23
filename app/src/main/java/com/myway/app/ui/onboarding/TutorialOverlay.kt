package com.myway.app.ui.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.myway.app.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Figma T-1~T-8: 화면을 어둡게 덮고 대상 버튼만 뚫어서(스포트라이트) 흰 테두리로 강조.
 * 말풍선은 대상 아래(위쪽 대상) 또는 위(아래쪽 대상)에, 공간이 없으면 대상 안 가운데.
 * 탭·왼쪽으로 밀기 = 다음, 오른쪽으로 밀기 = 이전.
 */
@SuppressLint("ViewConstructor")
class TutorialOverlay(context: Context) : FrameLayout(context) {

    /** cornerDp < 0 이면 원형 */
    class Target(val view: View, val cornerDp: Float)

    var onNext: () -> Unit = {}
    var onPrev: () -> Unit = {}
    var onSkip: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bubble = LayoutInflater.from(context).inflate(R.layout.view_tutorial_bubble, this, false)
    private val stepText: TextView = bubble.findViewById(R.id.tutStep)
    private val titleText: TextView = bubble.findViewById(R.id.tutTitle)
    private val bodyText: TextView = bubble.findViewById(R.id.tutBody)
    private val dots: LinearLayout = bubble.findViewById(R.id.tutDots)
    private val action: TextView = bubble.findViewById(R.id.tutAction)
    private val hint = TextView(context).apply {
        setTextAppearance(R.style.Text_MyWay_Label14Medium)
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.bg_tutorial_hint)
        setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var target: Target? = null
    private var last = false
    private val hole = RectF()            // 지금 그리는 구멍 (움직이는 중)
    private var holeRadius = 0f
    private val holeTo = RectF()          // 목표 구멍 (말풍선·화살표 배치 기준)
    private var radiusTo = 0f
    private val holeFrom = RectF()
    private var radiusFrom = 0f
    private var holeT = 1f                // 0→1: 이전 대상에서 새 대상으로 스포트라이트가 미끄러짐
    private var holeAnim: android.animation.ValueAnimator? = null
    private val dimPaint = Paint().apply { color = context.getColor(R.color.tutorial_dim) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dim = Path()
    private val arrow = Path()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent) = true.also { performClick() }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) || abs(vx) < dp(300f)) return false
            if (vx < 0) onNext() else onPrev()
            return true
        }
    })

    init {
        setWillNotDraw(false)
        isFocusable = true
        setOnClickListener { onNext() }
        addView(bubble, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(24f).toInt(); marginEnd = dp(24f).toInt()
        })
        addView(hint, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        action.setOnClickListener { if (last) onNext() else onSkip() }
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
    }

    fun show(t: Target, number: Int, total: Int, title: String, body: String, hintText: String?, last: Boolean) {
        val moving = target != null && target?.view != t.view && !hole.isEmpty
        target = t
        this.last = last
        if (moving) {
            holeFrom.set(hole); radiusFrom = holeRadius
            holeAnim?.cancel()
            holeT = 0f
            holeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = com.myway.app.ui.common.Motion.ease
                addUpdateListener { holeT = it.animatedValue as Float; lerpHole(); invalidate() }
                start()
            }
        }
        stepText.text = "$number / $total"
        titleText.text = title
        bodyText.text = body
        bindDots(number - 1, total)
        action.setText(if (last) R.string.tut_start else R.string.tut_skip)
        action.setBackgroundResource(if (last) R.drawable.bg_tutorial_start else 0)
        action.setTextColor(context.getColor(if (last) R.color.text_on_brand else R.color.text_secondary))
        hint.text = hintText
        hint.isVisible = hintText != null
        contentDescription = context.getString(R.string.tut_a11y, number, total, title, body)
        announceForAccessibility(contentDescription)

        bubble.alpha = 0f
        bubble.translationY = dp(8f)
        bubble.animate().alpha(1f).translationY(0f).setDuration(220).start()
        requestLayout()
        invalidate()
    }

    private fun bindDots(current: Int, total: Int) {
        dots.removeAllViews()
        repeat(total) { i ->
            dots.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(if (i == current) 16f else 6f).toInt(), dp(6f).toInt())
                    .apply { if (i > 0) marginStart = dp(5f).toInt() }
                setBackgroundResource(R.drawable.bg_tutorial_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(if (i == current) R.color.brand_navy else R.color.icon_default)
                )
            })
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        measureHole()

        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight
        val hw = hint.measuredWidth
        val hh = if (hint.isVisible) hint.measuredHeight else 0
        val left = dp(24f).toInt()
        val gap = dp(64f)            // 화살표가 들어갈 자리
        val hintGap = dp(12f)
        val edge = dp(24f)

        val bubbleTop: Float
        val hintTop: Float
        val belowTop = holeTo.bottom + gap
        val aboveTop = holeTo.top - gap - bh
        when {
            holeTo.centerY() < h / 2f && belowTop + bh + hintGap + hh < h - edge -> {
                bubbleTop = belowTop; hintTop = bubbleTop + bh + hintGap
            }
            aboveTop - hintGap - hh > edge -> {
                bubbleTop = aboveTop; hintTop = bubbleTop - hintGap - hh
            }
            else -> {   // 대상이 너무 큼(노선도): 대상 안 가운데
                bubbleTop = holeTo.centerY() - bh / 2f; hintTop = bubbleTop + bh + hintGap
            }
        }
        bubble.layout(left, bubbleTop.toInt(), left + bw, bubbleTop.toInt() + bh)
        hint.layout((w - hw) / 2, hintTop.toInt(), (w + hw) / 2, hintTop.toInt() + hh)
        buildArrow(bubbleTop, bubbleTop + bh, left.toFloat(), (left + bw).toFloat())
    }

    private fun measureHole() {
        val t = target ?: return
        val v = t.view
        val a = IntArray(2); val o = IntArray(2)
        v.getLocationInWindow(a); getLocationInWindow(o)
        val pad = dp(4f)
        val x = (a[0] - o[0]).toFloat()
        val y = (a[1] - o[1]).toFloat()
        holeTo.set(x - pad, y - pad, x + v.width + pad, y + v.height + pad)
        radiusTo = if (t.cornerDp < 0) holeTo.height() / 2f else dp(t.cornerDp) + pad
        lerpHole()
    }

    private fun lerpHole() {
        val f = holeT
        if (f >= 1f || holeFrom.isEmpty) { hole.set(holeTo); holeRadius = radiusTo; return }
        hole.set(
            holeFrom.left + (holeTo.left - holeFrom.left) * f, holeFrom.top + (holeTo.top - holeFrom.top) * f,
            holeFrom.right + (holeTo.right - holeFrom.right) * f, holeFrom.bottom + (holeTo.bottom - holeFrom.bottom) * f
        )
        holeRadius = radiusFrom + (radiusTo - radiusFrom) * f
    }

    /** 말풍선 → 대상으로 휘어진 화살표 */
    private fun buildArrow(bTop: Float, bBottom: Float, bLeft: Float, bRight: Float) {
        arrow.reset()
        val inset = dp(48f)
        val endX = holeTo.centerX().coerceIn(bLeft + inset, bRight - inset)
        val startX = (endX + dp(16f)).coerceIn(bLeft + inset, bRight - inset)
        val (startY, endY) = when {
            bTop >= holeTo.bottom -> bTop - dp(8f) to holeTo.bottom + dp(8f)
            bBottom <= holeTo.top -> bBottom + dp(8f) to holeTo.top - dp(8f)
            else -> return      // 말풍선이 대상 안에 있으면 화살표 없음
        }
        val cx = startX + dp(22f)
        val cy = (startY + endY) / 2f
        arrow.moveTo(startX, startY)
        arrow.quadTo(cx, cy, endX, endY)
        val ang = atan2(endY - cy, endX - cx)
        val len = dp(10f)
        for (s in floatArrayOf(-1f, 1f)) {
            val a = ang + Math.PI.toFloat() + s * 0.5f
            arrow.moveTo(endX, endY)
            arrow.lineTo(endX + len * cos(a), endY + len * sin(a))
        }
    }

    override fun onDraw(canvas: Canvas) {
        dim.reset()
        dim.fillType = Path.FillType.EVEN_ODD
        dim.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dim.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW)
        canvas.drawPath(dim, dimPaint)
        canvas.drawRoundRect(hole, holeRadius, holeRadius, ringPaint)
        arrowPaint.alpha = (255 * holeT).toInt()          // 스포트라이트가 도착한 뒤 화살표
        canvas.drawPath(arrow, arrowPaint)
    }

    /** 대상이 움직이면(키보드·애니메이션) 구멍도 따라감 */
    private val follow = ViewTreeObserver.OnGlobalLayoutListener {
        val old = RectF(holeTo)
        measureHole()
        if (old != holeTo) requestLayout()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnGlobalLayoutListener(follow) }
    override fun onDetachedFromWindow() {
        holeAnim?.cancel()
        viewTreeObserver.removeOnGlobalLayoutListener(follow)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true     // 아래 화면은 누를 수 없게
    }
}
