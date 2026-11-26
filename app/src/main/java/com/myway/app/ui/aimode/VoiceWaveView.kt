package com.myway.app.ui.aimode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.myway.app.R
import kotlin.math.sin

/** 2-3 음성 파형: 말소리 크기(level 0~1)에 따라 막대들이 출렁임. 듣는 동안만 움직임 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 마이크 소리 크기 0~1 (SpeechRecognizer.onRmsChanged 에서 넣음) */
    var level = 0f

    var running = false
        set(value) { field = value; if (value) postInvalidateOnAnimation() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ai_purple_light)
        strokeCap = Paint.Cap.ROUND
    }
    private val shape = floatArrayOf(.35f, .55f, .8f, .6f, .9f, 1f, .9f, .6f, .8f, .55f, .35f)
    private var smooth = 0f
    private var phase = 0f

    override fun onDraw(canvas: Canvas) {
        smooth += (level - smooth) * 0.25f          // 갑자기 튀지 않게 따라감
        phase += 0.12f
        val n = shape.size
        val gap = width / (n * 2f)
        paint.strokeWidth = gap
        val cy = height / 2f
        for (i in 0 until n) {
            val amp = 0.2f + 0.8f * smooth
            val wob = 0.65f + 0.35f * sin(phase + i * 0.8f)
            val h = (height * shape[i] * amp * wob).coerceAtLeast(gap)
            val x = gap / 2f + i * 2 * gap + gap / 2f
            canvas.drawLine(x, cy - h / 2f + gap / 2f, x, cy + h / 2f - gap / 2f, paint)
        }
        if (running) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
}
