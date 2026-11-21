package com.myway.app.ui.common

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.myway.app.R
import com.myway.app.databinding.DialogNoticeBinding

/** 검색 화면과 같은 안내 팝업(dialog_notice)을 다른 화면에서도 쓰기 위한 도우미 */
object NoticeDialog {
    fun show(f: Fragment, @StringRes title: Int, @StringRes body: Int) {
        val db = DialogNoticeBinding.inflate(f.layoutInflater)
        val dialog = Dialog(f.requireContext()).apply {
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = f.resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(f.resources.displayMetrics.widthPixels - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.dialogTitle.setText(title)
        db.dialogBody.setText(body)
        db.dialogAction.setText(R.string.inactive_dialog_action)
        db.dialogAction.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
