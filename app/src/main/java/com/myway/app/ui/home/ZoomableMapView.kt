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
        typeface = Typeface.DEFAULT_BOLD          // 아래 init 에서 Noto Sans KR Bold 로 교체
        textAlign = Paint.Align.CENTER
    }
    init {
        // 다운로드 글꼴은 비동기로 받아서 적용 (화면 멈춤 없음)
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.noto_sans_kr_bold,
                object : androidx.core.content.res.ResourcesCompat.FontCallback() {
                    override fun onFontRetrieved(typeface: Typeface) { pinText.typeface = typeface; invalidate() }
                    override fun onFontRetrievalFailed(reason: Int) = Unit
                }, null)
        }
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

    /**
     * 경로 강조(화면 59): 지도를 옅게 덮고 지나는 역을 노선색 선으로 잇는다. 그림 좌표.
     * major = 출발·환승·도착(큰 점). connector = 환승 걷는 구간(회색 점선)
     */
    data class RouteStop(val x: Float, val y: Float, val color: Int, val major: Boolean, val connector: Boolean)
    var route: List<RouteStop> = emptyList()
        set(value) {
            field = value
            // 출발 → 도착 방향으로 선이 그려지며 나타남 (경로의 흐름이 보이게)
            routeAnim?.cancel()
            routeProgress = 0f
            routeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (300L + value.size * 25L).coerceAtMost(900L)
                startDelay = 150
                interpolator = DecelerateInterpolator()
                addUpdateListener { routeProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var routeProgress = 1f
    private var routeAnim: ValueAnimator? = null
    private val dim = Paint().apply { color = 0xA6FFFFFF.toInt() }   // 알파는 그릴 때 정함
    private val routeCase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.WHITE; strokeWidth = 11 * dp
    }
    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 6 * dp
    }
    private val walkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4 * dp
        color = ContextCompat.getColor(context, R.color.line_unknown)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(2 * dp, 6 * dp), 0f)
    }
    private val stopStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ra = FloatArray(2)
    private val rb = FloatArray(2)
    private var pendingRegion: RectF? = null

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
        if (!fitted) {
            fit()
            pendingRegion?.let { if (fitted) { applyRegion(it); pendingRegion = null } }
        }
        canvas.save()
        canvas.concat(matrix)
        d.draw(canvas)
        canvas.restore()
        if (route.size >= 2) drawRoute(canvas)
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
        pinHit.getOrPut(p) { RectF() }.set(pill)       // 매 프레임 새 객체를 만들지 않게
        tip.reset()
        tip.moveTo(vx - 6 * dp, bottom - 1)
        tip.lineTo(vx + 6 * dp, bottom - 1)
        tip.lineTo(vx, bottom + 7 * dp)
        tip.close()
        canvas.drawPath(tip, pinFill)
        canvas.drawText(p.label, vx, pill.centerY() - (pinText.descent() + pinText.ascent()) / 2, pinText)
    }

    /** 선 굵기는 확대와 상관없이 일정 (화면 좌표로 그림) */
    private fun drawRoute(canvas: Canvas) {
        dim.alpha = (0xA6 * (routeProgress * 3f).coerceAtMost(1f)).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        val reach = routeProgress * (route.size - 1)          // 몇 번째 구간까지 그렸나 (소수 = 그리는 중)
        for (pass in 0..1) for (i in 1 until route.size) {
            if (i - 1 >= reach) break
            val p = route[i - 1]; val q = route[i]
            val f = (reach - (i - 1)).coerceAtMost(1f)
            ra[0] = p.x; ra[1] = p.y; rb[0] = p.x + (q.x - p.x) * f; rb[1] = p.y + (q.y - p.y) * f
            matrix.mapPoints(ra); matrix.mapPoints(rb)
            when {
                q.connector -> if (pass == 1) canvas.drawLine(ra[0], ra[1], rb[0], rb[1], walkLine)
                pass == 0 -> canvas.drawLine(ra[0], ra[1], rb[0], rb[1], routeCase)
                else -> { routeLine.color = q.color; canvas.drawLine(ra[0], ra[1], rb[0], rb[1], routeLine) }
            }
        }
        route.forEachIndexed { i, s ->
            if (i > reach + 0.01f) return@forEachIndexed
            ra[0] = s.x; ra[1] = s.y; matrix.mapPoints(ra)
            stopStroke.color = s.color
            stopStroke.strokeWidth = (if (s.major) 3f else 2f) * dp
            val r = (if (s.major) 6.5f else 3.5f) * dp
            canvas.drawCircle(ra[0], ra[1], r, ringFill.apply { alpha = 255 })
            canvas.drawCircle(ra[0], ra[1], r, stopStroke)
        }
    }

    /** 그림의 이 영역이 화면에 꽉 차게 (여백 포함). 그림이 아직 없으면 그린 뒤에 적용 */
    fun showRegion(r: RectF) {
        if (fitted && width > 0 && drawable != null) { applyRegion(r); invalidate() }
        else pendingRegion = RectF(r)
    }

    private fun applyRegion(r: RectF) {
        val pad = 56 * dp
        val w = r.width().coerceAtLeast(1f); val h = r.height().coerceAtLeast(1f)
        val s = minOf((width - 2 * pad) / w, (height - 2 * pad) / h).coerceIn(minScale, maxScale)
        matrix.setScale(s, s)
        matrix.postTranslate(width / 2f - r.centerX() * s, height / 2f - r.centerY() * s)
        clamp()
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
