package com.myway.app.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 앱 전역 연결 상태. 오프라인이면 즐겨찾기·최근·설정(로컬)만 사용 (설계서 2장) */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    val isOnline: Boolean get() = _online.value

    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = cm.activeNetwork?.let(cm::getNetworkCapabilities).hasInternet()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.hasInternet()
            }
            override fun onLost(network: Network) { _online.value = false }
        })
    }

    private fun NetworkCapabilities?.hasInternet() =
        this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
