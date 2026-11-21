package com.myway.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.myway.app.domain.model.RouteType

/**
 * 앱 설정 (설계서 4.2.4 AppSetting 키-값).
 * 임시: SharedPreferences. TODO: Room 으로 바꿔도 키·기본값은 그대로.
 */
object SettingsStore {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("myway_settings", Context.MODE_PRIVATE)
    }

    enum class Key(val default: Any) {
        // 음성 (Phase 2 기능이 사용)
        TTS(true), STT(true), VOICE_GENDER("female"), VOICE_VOLUME(70),
        // 알림
        VIBRATE(true), NOTIFY(true),
        ALERT_BEFORE(true), ALERT_ARRIVAL(true), ALERT_TRANSFER(true), ALERT_MISSED(true), ALERT_WRONG_WAY(true),
        VIBRATE_STRENGTH(60),
        // 경로·화면·언어
        DEFAULT_ROUTE(RouteType.SHORTEST_TIME.name), THEME("light"), LANGUAGE("ko"),
        // 최초 실행 (권한 안내 6-1~6-4 → 튜토리얼 T-1~T-8)
        PERMISSIONS_ASKED(false), TUTORIAL_DONE(false)
    }

    fun bool(k: Key) = prefs?.getBoolean(k.name, k.default as Boolean) ?: k.default as Boolean
    fun int(k: Key) = prefs?.getInt(k.name, k.default as Int) ?: k.default as Int
    fun string(k: Key) = prefs?.getString(k.name, k.default as String) ?: k.default as String

    fun set(k: Key, value: Boolean) { prefs?.edit()?.putBoolean(k.name, value)?.apply() }
    fun set(k: Key, value: Int) { prefs?.edit()?.putInt(k.name, value)?.apply() }
    fun set(k: Key, value: String) { prefs?.edit()?.putString(k.name, value)?.apply() }

    /** 경로 결과의 처음 탭 */
    val defaultRouteType: RouteType
        get() = runCatching { RouteType.valueOf(string(Key.DEFAULT_ROUTE)) }.getOrDefault(RouteType.SHORTEST_TIME)
}
