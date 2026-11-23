package com.myway.app.ui.onboarding

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myway.app.databinding.SheetPermissionBinding

/** Figma 6-1~6-4 권한 안내 시트: 이유 설명 → [나중에] [권한 허용] */
class PermissionSheet : BottomSheetDialogFragment() {

    val step get() = PermissionStep.valueOf(requireArguments().getString(ARG_STEP)!!)
    private var answered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetPermissionBinding.inflate(inflater, container, false)
        val s = step
        b.icon.setImageResource(s.icon)
        b.title.setText(s.title)
        b.body.setText(s.body)
        b.laterButton.setOnClickListener { answer(false) }
        b.allowButton.setOnClickListener { answer(true) }
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun answer(allow: Boolean) {
        if (answered) return
        answered = true
        setFragmentResult(RESULT, bundleOf(ARG_STEP to step.name, ARG_ALLOW to allow))
        dismiss()
    }

    /** 바깥 탭·아래로 내리기·뒤로가기 = 나중에 */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        answer(false)
    }

    companion object {
        const val TAG = "PermissionSheet"
        const val RESULT = "permission_result"
        const val ARG_STEP = "step"
        const val ARG_ALLOW = "allow"
        fun newInstance(step: PermissionStep) = PermissionSheet().apply { arguments = bundleOf(ARG_STEP to step.name) }
    }
}
