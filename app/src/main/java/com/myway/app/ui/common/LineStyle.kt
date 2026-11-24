package com.myway.app.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import com.myway.app.R

/** 노선 값(Int 2 또는 String "2호선" 모두 OK) → 색·배지 */
object LineStyle {
    fun key(line: Any): String = line.toString().filter { it.isDigit() }

    /** 앱 지원 범위: 2·5·9호선 */
    fun isSupported(line: Any): Boolean = key(line) in setOf("2", "5", "9")

    @ColorRes
    fun colorRes(line: Any): Int = when (key(line)) {
        "2" -> R.color.line2
        "5" -> R.color.line5
        "9" -> R.color.line9
        else -> R.color.line_unknown
    }

    /** 9호선 금색은 흰 글자 대비가 낮아 navy 글자 (Figma 동일) */
    @ColorRes
    fun textColorRes(line: Any): Int =
        if (key(line) == "9") R.color.on_line9 else R.color.text_on_brand

    /** 24dp 원형 배지 하나 생성 */
    fun badge(
        context: Context,
        line: Any,
        marginStartPx: Int = 0,
        sizeRes: Int = R.dimen.row_badge,
        textStyle: Int = R.style.Text_MyWay_Caption12Bold
    ): TextView {
        val size = context.resources.getDimensionPixelSize(sizeRes)
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = marginStartPx }
            gravity = Gravity.CENTER
            text = key(line)
            setTextAppearance(textStyle)
            setTextColor(context.getColor(textColorRes(line)))
            setBackgroundResource(R.drawable.bg_line_badge)
            backgroundTintList = ColorStateList.valueOf(context.getColor(colorRes(line)))
        }
    }
}
