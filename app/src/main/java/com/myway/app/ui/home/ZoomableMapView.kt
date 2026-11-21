package com.myway.app.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Typeface
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.myway.app.R

/**
 * 노선도 확대·축소 뷰.
 * - 두 손가락 확대·축소, 한 손가락 끌기, 두 번 탭 확대/원래대로
 * - 한 번 탭 → onMapTap(그림 기준 x, y): 역 좌표 찾기에 사용
 * - imageToView(): 그림 좌표 → 화면 좌표 (역 위 말풍선 위치 계산용)
 * - pins: 역 위에 꽂는 핀(고른 역 · 출발 · 도착). 확대해도 핀 크기는 그대로
 */
class ZoomableMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var drawable: Drawable? = null
        set(value) {
            field = value
            value?.setBounds(0, 0, value.intrinsicWidth, value.intrinsicHeight)
            // 크게 줄여 그릴 때 글자가 깨지지 않게: 밉맵(미리 줄인 사본) + 부드럽게 보간
            (value as? BitmapDrawable)?.bitmap?.setHasMipMap(true)
            value?.isFilterBitmap = true
            fitted = false
            invalidate()
        }

    /** 그림 기준 좌표로 알려 준다 */
    var onMapTap: ((x: Float, y: Float) -> Unit)? = null
    /** 처음 확대·이동했을 때 한 번 (핀치 안내 숨기기) */
    var onFirstGesture: (() -> Unit)? = null
    /** 확대·이동할 때마다 (말풍선 따라가기) */
    var onTransform: (() -> Unit)? = null

    var placeholderText: String = ""

    /** 핀 종류: 지금 누른 역(남색) · 출발(초록) · 도착(빨강) */
    enum class PinKind { FOCUS, ORIGIN, DESTINATION }
    data class Pin(val x: Float, val y: Float, val kind: PinKind, val label: String)

    var pins: List<Pin> = emptyList()
        set(value) {
            val added = value.filter { it !in field }
            val removed = field.filter { it !in value }
            field = value
            drawOrder = value.sortedBy { if (it.kind == PinKind.FOCUS) 1 else 0 }
            removed.forEach { lift(it) }
            added.forEach { drop(it) }
            pinHit.keys.retainAll(value.toSet())
            invalidate()
        }
    private val pinAppear = mutableMapOf<Pin, Float>()      // 0→1 떨어지는 애니메이션
    private val pinHit = mutableMapOf<Pin, RectF>()          // 마지막으로 그린 이름표 자리 (누르기 판정)
    /** 이름표를 누르면 (출발·도착 핀 지우기) */
    var onPinTap: ((Pin) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(4 * dp, 0f, 1.5f * dp, 0x40000000) }
    private val pinText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp }
    private val tip = Path()
    private val pill = RectF()
    private val pt = FloatArray(2)                                // 그릴 때마다 배열을 새로 만들지 않게
    private val pinColors = mapOf(
        PinKind.FOCUS to ContextCompat.getColor(context, R.color.brand_navy),
        PinKind.ORIGIN to ContextCompat.getColor(context, R.color.line2),
        PinKind.DESTINATION to ContextCompat.getColor(context, R.color.status_danger_strong)
    )
    private var drawOrder: List<Pin> = emptyList()                // 출발·도착 → 지금 누른 역(맨 위)

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val rect = RectF()
    private var fitted = false
    private var minScale = 1f      // 전체가 보이는 배율
    private var coverScale = 1f    // 처음 배율 (카드를 꽉 채움)
    private var maxScale = 1f
    private var gestured = false

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY); return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                matrix.postTranslate(-dx, -dy); changed(); return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() < coverScale * 1.5f) coverScale * 2f else coverScale
                zoomBy(target / currentScale(), e.x, e.y); return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (drawable == null) return false
                // 이름표가 역보다 우선 (위에 있는 것부터, 손가락 여유 8dp)
                drawOrder.asReversed().firstOrNull { p ->
                    pinHit[p]?.let { r -> RectF(r).apply { inset(-8 * dp, -8 * dp) }.contains(e.x, e.y) } == true
                }?.let { onPinTap?.invoke(it); return true }
                val inv = Matrix().also { matrix.invert(it) }
                val p = floatArrayOf(e.x, e.y).also { inv.mapPoints(it) }
                onMapTap?.invoke(p[0], p[1]); return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); fitted = false
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        if (d == null) {
            canvas.drawText(placeholderText, width / 2f, height / 2f, placeholderPaint); return
        }
        if (!fitted) fit()
        canvas.save()
        canvas.concat(matrix)
        d.draw(canvas)
        canvas.restore()
        // 핀: 출발·도착 먼저, 지금 누른 역이 맨 위
        for (i in leaving.indices.reversed()) drawPin(canvas, leaving[i])
        for (p in drawOrder) drawPin(canvas, p)
    }

    /** 역 자리에 흰 고리 + 그 위에 이름표(말풍선 꼬리 포함) */
    private fun drawPin(canvas: Canvas, p: Pin) {
        pt[0] = p.x; pt[1] = p.y
        matrix.mapPoints(pt)
        val vx = pt[0]; val vy = pt[1]
        val t = pinAppear[p] ?: 1f
        val color = pinColors.getValue(p.kind)
        // 역 위치 표시
        ringStroke.color = color
        val a = (255 * t.coerceIn(0f, 1f)).toInt()
        ringStroke.alpha = a; ringFill.alpha = a
        canvas.drawCircle(vx, vy, 7 * dp, ringFill)
        canvas.drawCircle(vx, vy, 7 * dp, ringStroke)

        // 이름표: 위에서 살짝 떨어지며 나타남
        val lift = (1 - t) * 14 * dp
        val textW = pinText.measureText(p.label)
        val h = 30 * dp
        val bottom = vy - 12 * dp - lift
        pill.set(vx - textW / 2 - 12 * dp, bottom - h, vx + textW / 2 + 12 * dp, bottom)
        pinFill.color = color
        pinFill.alpha = (255 * t.coerceIn(0f, 1f)).toInt()
        pinText.alpha = pinFill.alpha
        canvas.drawRoundRect(pill, h / 2, h / 2, pinFill)
        pinHit[p] = RectF(pill)
        tip.reset()
        tip.moveTo(vx - 6 * dp, bottom - 1)
        tip.lineTo(vx + 6 * dp, bottom - 1)
        tip.lineTo(vx, bottom + 7 * dp)
        tip.close()
        canvas.drawPath(tip, pinFill)
        canvas.drawText(p.label, vx, pill.centerY() - (pinText.descent() + pinText.ascent()) / 2, pinText)
    }

    /** 지워지는 핀: 위로 살짝 떠오르며 사라짐 */
    private val leaving = mutableListOf<Pin>()
    private fun lift(p: Pin) {
        leaving += p
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200
            addUpdateListener { pinAppear[p] = it.animatedValue as Float; invalidate() }
            doOnEndCompat { leaving.remove(p); if (p !in pins) pinAppear.remove(p); invalidate() }
            start()
        }
    }

    private fun drop(p: Pin) {
        pinAppear[p] = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { pinAppear[p] = it.animatedValue as Float; invalidate() }
            doOnEndCompat { pinAppear.remove(p) }
            start()
        }
    }

    private fun ValueAnimator.doOnEndCompat(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })

    /** 그림의 (x, y)가 화면의 (가로 fx, 세로 fy) 비율 위치로 오도록 부드럽게 이동. 아래 시트에 가리지 않게 */
    fun focusOn(x: Float, y: Float, fx: Float = 0.5f, fy: Float = 0.35f) {
        if (drawable == null || width == 0) return
        val v = imageToView(x, y)
        val dx = width * fx - v[0]
        val dy = height * fy - v[1]
        var last = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                matrix.postTranslate(dx * (f - last), dy * (f - last)); last = f
                clamp(); invalidate(); onTransform?.invoke()
            }
            start()
        }
    }

    /** 그림 좌표 → 화면 좌표 */
    fun imageToView(x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also { matrix.mapPoints(it) }

    fun currentScale(): Float { matrix.getValues(values); return values[Matrix.MSCALE_X] }

    /**
     * 처음 화면: 카드를 꽉 채우게(cover) 확대해서 가운데(도심·2호선 순환선)를 보여 준다.
     * 두 손가락으로 줄이면 전체(fit)까지 볼 수 있다.
     */
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val sx = width / d.intrinsicWidth.toFloat()
        val sy = height / d.intrinsicHeight.toFloat()
        minScale = minOf(sx, sy)
        coverScale = maxOf(sx, sy) * START_ZOOM
        maxScale = coverScale * MAX_ZOOM
        matrix.setScale(coverScale, coverScale)
        matrix.postTranslate(
            (width - d.intrinsicWidth * coverScale) / 2f,
            (height - d.intrinsicHeight * coverScale) / 2f
        )
        fitted = true
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val s = currentScale()
        val target = (s * factor).coerceIn(minScale, maxScale)
        matrix.postScale(target / s, target / s, fx, fy)
        changed()
    }

    private fun changed() {
        clamp()
        invalidate()
        onTransform?.invoke()
        if (!gestured) { gestured = true; onFirstGesture?.invoke() }
    }

    /** 그림이 화면 밖으로 사라지지 않게 */
    private fun clamp() {
        val d = drawable ?: return
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)
        val dx = when {
            rect.width() <= width -> (width - rect.width()) / 2f - rect.left
            rect.left > 0 -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= height -> (height - rect.height()) / 2f - rect.top
            rect.top > 0 -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }

    companion object {
        private const val START_ZOOM = 1f   // 처음에 더 크게 보이려면 1.2f 등으로
        private const val MAX_ZOOM = 5f     // 처음 배율의 5배까지 (4배 이후로는 약간 흐려질 수 있음)
    }
}
