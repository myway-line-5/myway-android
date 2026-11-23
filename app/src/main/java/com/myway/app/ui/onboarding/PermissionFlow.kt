package com.myway.app.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.myway.app.R
import com.myway.app.data.local.SettingsStore

/** 6-1~6-4 권한 안내 단계 (설계서 UC-01: 이유를 먼저 안내한 뒤 요청) */
enum class PermissionStep(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int) {
    MIC(R.drawable.ic_perm_mic, R.string.perm_mic_title, R.string.perm_mic_body),
    NOTIFY(R.drawable.ic_perm_bell, R.string.perm_notify_title, R.string.perm_notify_body),
    BATTERY(R.drawable.ic_perm_battery, R.string.perm_battery_title, R.string.perm_battery_body),
    NEARBY(R.drawable.ic_perm_headset, R.string.perm_nearby_title, R.string.perm_nearby_body)
}

/**
 * 최초 실행 권한 안내. 이미 허용됐거나 이 기기 버전에 없는 권한은 건너뜀.
 * '나중에'를 눌러도 다음 단계로 (앱은 권한 없이도 동작해야 함).
 * 반드시 Fragment 생성 시점(필드)에서 만들어야 함: registerForActivityResult 규칙.
 */
class PermissionFlow(private val fragment: Fragment, private val onDone: () -> Unit) {

    private val queue = ArrayDeque<PermissionStep>()
    private val ask = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    private val askSettings = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { next() }

    val asked get() = SettingsStore.bool(SettingsStore.Key.PERMISSIONS_ASKED)

    /** onViewCreated 에서 호출. 회전 등으로 시트가 남아 있으면 이어서 진행 */
    fun attach() {
        val fm = fragment.childFragmentManager
        fm.setFragmentResultListener(PermissionSheet.RESULT, fragment.viewLifecycleOwner) { _, b ->
            val step = PermissionStep.valueOf(b.getString(PermissionSheet.ARG_STEP)!!)
            if (b.getBoolean(PermissionSheet.ARG_ALLOW)) request(step) else next()
        }
        val showing = (fm.findFragmentByTag(PermissionSheet.TAG) as? PermissionSheet)?.step
        if (showing != null) {
            queue.clear(); queue.addAll(needed().dropWhile { it != showing }.drop(1))
        }
    }

    fun start() {
        queue.clear(); queue.addAll(needed())
        next()
    }

    private fun next() {
        if (!fragment.isAdded) return
        val step = queue.removeFirstOrNull()
        if (step == null) {
            SettingsStore.set(SettingsStore.Key.PERMISSIONS_ASKED, true)
            onDone()
            return
        }
        PermissionSheet.newInstance(step).show(fragment.childFragmentManager, PermissionSheet.TAG)
    }

    @SuppressLint("BatteryLife")
    private fun request(step: PermissionStep) {
        val ctx = fragment.requireContext()
        when (step) {
            PermissionStep.MIC -> ask.launch(Manifest.permission.RECORD_AUDIO)
            PermissionStep.NOTIFY -> if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else next()
            PermissionStep.NEARBY -> if (Build.VERSION.SDK_INT >= 31) ask.launch(Manifest.permission.BLUETOOTH_CONNECT) else next()
            PermissionStep.BATTERY -> {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { askSettings.launch(direct) }
                    .recoverCatching { askSettings.launch(list) }
                    .onFailure { next() }
            }
        }
    }

    private fun needed(): List<PermissionStep> {
        val ctx = fragment.requireContext()
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val pm = ctx.getSystemService(PowerManager::class.java)
        return PermissionStep.entries.filter {
            when (it) {
                PermissionStep.MIC -> !granted(Manifest.permission.RECORD_AUDIO)
                PermissionStep.NOTIFY -> Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
                PermissionStep.BATTERY -> pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false
                PermissionStep.NEARBY -> Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}
