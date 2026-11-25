package com.myway.app.ui.trip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.myway.app.R

/** Figma 3-2 진행 막대: 탑승역 ●━━━◉──────○ 하차역 (노선색으로 지나온 만큼 채움) */
class TripProgressView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var color: Int = ctx.getColor(R.color.brand_navy); set(v) { field = v; invalidate() }
    /** 0..1 지나온 비율 */
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ctx.getColor(R.color.border_default); strokeWidth = 8 * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 8 * d; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ctx.getColor(R.color.bg_surface) }
    private val end = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; this.color = ctx.getColor(R.color.text_primary) }

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val r = 9 * d
        val inset = r + ring.strokeWidth / 2 + 1 * d            // 테두리 두께까지 넣어야 양 끝 동그라미가 안 잘림
        val left = paddingLeft + inset; val right = width - paddingRight - inset
        val x = left + (right - left) * fraction
        c.drawLine(left, y, right, y, track)
        fill.color = color; dot.color = color; ring.color = color
        c.drawLine(left, y, x, y, fill)
        c.drawCircle(left, y, 6 * d, dot)                       // 탑승역
        c.drawCircle(right, y, r, hole); c.drawCircle(right, y, r, end)   // 하차역
        c.drawCircle(x, y, r, hole); c.drawCircle(x, y, r, ring)          // 지금 위치
    }
}
