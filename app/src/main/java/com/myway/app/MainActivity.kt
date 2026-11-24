package com.myway.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.myway.app.data.local.LocalStore
import com.myway.app.data.local.SettingsStore
import com.myway.app.data.remote.NetworkMonitor
import com.myway.app.ui.home.MapImage
import com.myway.app.ui.common.ScreenSwap
import com.myway.app.ui.common.StationNames
import com.myway.app.ui.settings.SettingsFragment

/** 화면 전환은 res/navigation/nav_graph.xml 이 담당 (시작: 홈) */
class MainActivity : AppCompatActivity() {

    /**
     * 폰 '글자 크기'를 최대 130%까지만 반영 (그 이상은 버튼·카드가 깨짐).
     * 130%까지는 그대로 커지므로 큰 글자가 필요한 사용자도 읽을 수 있음.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = newBase.resources.configuration
        if (config.fontScale <= MAX_FONT_SCALE) { super.attachBaseContext(newBase); return }
        val capped = Configuration(config).apply { fontScale = MAX_FONT_SCALE }
        super.attachBaseContext(newBase.createConfigurationContext(capped))
    }

    private companion object { const val MAX_FONT_SCALE = 1.3f }
    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsStore.init(this)
        // 설정 > 화면 (라이트·다크·시스템). 바뀌지 않았으면 아무 일 없음
        AppCompatDelegate.setDefaultNightMode(SettingsFragment.nightMode(SettingsStore.string(SettingsStore.Key.THEME)))
        super.onCreate(savedInstanceState)
        LocalStore.init(this)
        // 설정 > 언어 표시값: 앱에서 고른 언어 → 없으면 폰 언어 (영어면 en, 그 외 ko)
        val lang = resources.configuration.locales[0].language        // 실제로 보이는 언어
        SettingsStore.set(SettingsStore.Key.LANGUAGE, if (lang == "en") "en" else "ko")
        StationNames.english = lang == "en"

        NetworkMonitor.init(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ScreenSwap.reveal(ScreenSwap.ACTIVITY, this)        // 테마·언어 바꾼 직후면 이전 화면에서 부드럽게 넘어감
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /** 백그라운드로 가면 큰 노선도 비트맵(장당 32MB)을 놓아 줌 → 다른 앱 쓰다 돌아와도 강제 종료 덜 됨 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MapImage.trim(level)
    }
}
