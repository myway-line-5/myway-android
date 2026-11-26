package com.myway.app.ui.common

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import androidx.core.view.drawToBitmap

/**
 * 다크모드·언어를 바꾸면 화면이 통째로 다시 만들어진다(안드로이드 규칙).
 * 그 순간 깜빡이고 버벅여 보이지 않게: 바꾸기 직전 화면을 찍어 두고,
 * 새 화면 위에 덮었다가 부드럽게 걷어 낸다(크로스페이드).
 */
object ScreenSwap {
    private val shots = mutableMapOf<String, Bitmap>()

    /** 바꾸기 직전에: 이 창의 지금 모습을 저장 */
    fun capture(key: String, window: Window?) {
        val decor = window?.decorView ?: return
        if (decor.width == 0 || decor.height == 0) return
        runCatching { decor.drawToBitmap() }.getOrNull()?.let { shots[key] = it }
    }

    /** 새로 만들어진 창 위에 저장해 둔 모습을 덮고 걷어 냄 */
    fun reveal(key: String, window: Window?) {
        val bmp = shots.remove(key) ?: return
        val decor = window?.decorView as? ViewGroup ?: return
        val cover = ImageView(decor.context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 1000f
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cover.animate().alpha(0f).setStartDelay(80).setDuration(280).setInterpolator(Motion.ease)
            .withEndAction { decor.removeView(cover) }.start()
    }

    fun reveal(key: String, activity: Activity) = reveal(key, activity.window)

    fun clear() = shots.clear()

    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"
}
