package com.myway.app.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 노선도 이미지(4000×4000) 한 번만 읽어서 앱이 켜져 있는 동안 재사용.
 * - 백그라운드에서 읽음: 홈에 돌아올 때마다 화면이 0.3~0.5초 멈추던 것 제거
 * - RGB_565: 투명도 없는 지도라 색 정밀도를 조금 낮춰 메모리 64MB → 32MB
 */
object MapImage {
    private val cache = HashMap<Int, Bitmap>()

    fun cached(resId: Int): Bitmap? = cache[resId]

    suspend fun load(context: Context, resId: Int): Bitmap? {
        cache[resId]?.let { return it }
        val res = context.applicationContext.resources
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeResource(res, resId, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inScaled = false
                })
            }.getOrNull()
        } ?: return null
        bmp.setHasMipMap(true)           // 크게 줄여 볼 때 글자 깨짐 방지
        cache[resId] = bmp
        return bmp
    }
}
