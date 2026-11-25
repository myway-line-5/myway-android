package com.myway.app.ui.home

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.myway.app.R
import com.myway.app.data.local.SettingsStore
import com.myway.app.databinding.DialogScopeNoticeBinding
import com.myway.app.ui.common.LineStyle

/**
 * Figma 1-1 서비스 범위 안내 (2·5·9호선만 지원)
 * - 튜토리얼이 끝난 뒤, 앱을 켤 때마다 한 번
 * - [확인] 이번 실행 동안만 닫음 → 다음 실행 때 다시 보임
 * - [다시 보지 않기] 영구히 끔
 */
object ScopeNotice {
    /** 이번 실행(프로세스)에서 이미 보여 줬는지 */
    private var shownThisRun = false

    fun maybeShow(f: Fragment) {
        if (shownThisRun || SettingsStore.bool(SettingsStore.Key.SCOPE_NOTICE_OFF)) return
        if (!f.isAdded || f.view == null) return
        shownThisRun = true

        val ctx = f.requireContext()
        val b = DialogScopeNoticeBinding.inflate(f.layoutInflater)
        val dialog = Dialog(ctx).apply {
            setContentView(b.root)
            setCanceledOnTouchOutside(true)              // 바깥·뒤로가기 = 확인과 같음
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = f.resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(f.resources.displayMetrics.widthPixels - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val gap = f.resources.getDimensionPixelSize(R.dimen.space_xs) * 3 / 2      // 6dp
        listOf("2", "5", "9").forEachIndexed { i, line ->
            b.scopeLines.addView(LineStyle.badge(ctx, line, if (i == 0) 0 else gap,
                sizeRes = R.dimen.scope_badge, textStyle = R.style.Text_MyWay_Label14Bold))
        }
        b.scopeOk.setOnClickListener { dialog.dismiss() }
        b.scopeNever.setOnClickListener {
            SettingsStore.set(SettingsStore.Key.SCOPE_NOTICE_OFF, true)
            dialog.dismiss()
        }
        dialog.show()
    }
}
