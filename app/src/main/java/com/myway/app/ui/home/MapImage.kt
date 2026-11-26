package com.myway.app.ui.home

import android.app.ActivityManager
import android.content.ComponentCallbacks2
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
    // 노선도가 4장(전체·2·5·9)이라 다 들고 있으면 100MB 넘음 → 최근 2장만 (나머지는 다시 읽음)
    // 메모리가 적은 폰(저사양·앱 한도 192MB 미만)은 1장만 → 노선 바꿀 때 다시 읽지만 앱이 죽지 않음
    private var maxEntries = 2
    private var sized = false
    private val cache = object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > maxEntries
    }

    /** 앱이 뒤로 가거나 시스템 메모리가 부족하면 노선도 사본을 내려놓음 (화면에 보이는 것은 그대로) */
    fun trim(level: Int) {
        // 앱이 백그라운드 목록에 들어갔을 때만 (테마·언어 바꿀 때 화면 재생성으로는 비우지 않음 → 다시 읽느라 버벅이지 않게)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.clear()
    }

    fun cached(resId: Int): Bitmap? = cache[resId]

    suspend fun load(context: Context, resId: Int): Bitmap? {
        cache[resId]?.let { return it }
        if (!sized) {
            sized = true
            val am = context.getSystemService(ActivityManager::class.java)
            if (am != null && (am.isLowRamDevice || am.memoryClass < 192)) maxEntries = 1
        }
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
