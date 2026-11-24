package com.myway.app.ui.common

import androidx.fragment.app.Fragment
import com.myway.app.R
import com.myway.app.data.remote.NetworkMonitor

/**
 * 서버가 필요한 이동(경로 찾기·역 상세) 직전에 호출.
 * 오프라인이면 안내 팝업을 띄우고 true → 호출한 쪽은 이동하지 않는다 (설계서 2장 오프라인 범위)
 */
fun Fragment.blockedOffline(): Boolean {
    if (NetworkMonitor.isOnline) return false
    NoticeDialog.show(this, R.string.offline_title, R.string.offline_body)
    return true
}
