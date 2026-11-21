package com.myway.app.ui.settings

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.materialswitch.MaterialSwitch
import com.myway.app.R
import com.myway.app.data.local.SettingsStore
import com.myway.app.data.local.SettingsStore.Key
import com.myway.app.databinding.DialogNoticeBinding
import com.myway.app.databinding.FragmentSettingsBinding
import com.myway.app.domain.model.RouteType

/**
 * Figma 4-1~4-7 설정. 홈 위에 겹쳐 뜨는 패널.
 * 1단계: 뒤 화면 흐림 + 오른쪽 메뉴 슬라이드 인
 * 2단계: 메뉴를 누르면 왼쪽 상세 슬라이드 인 (뒤로: 상세 → 메뉴 → 닫기 순서)
 */
class SettingsFragment : DialogFragment() {

    private enum class Section(@StringRes val title: Int, @DrawableRes val icon: Int) {
        VOICE(R.string.set_voice, R.drawable.ic_set_voice),
        ALERT(R.string.set_alert, R.drawable.ic_set_alert),
        ROUTE(R.string.set_route, R.drawable.ic_set_route),
        SCREEN(R.string.set_screen, R.drawable.ic_set_screen),
        LANGUAGE(R.string.set_language, R.drawable.ic_set_language),
        INFO(R.string.set_info, R.drawable.ic_set_info)
    }

    private var _b: FragmentSettingsBinding? = null
    private var section: Section? = null           // null = 메뉴만 보이는 1단계
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_MyWay_SettingsPanel)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        ComponentDialog(requireContext(), theme).also { d ->
            d.onBackPressedDispatcher.addCallback(d) { handleBack() }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentSettingsBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = _b ?: return
        section = savedInstanceState?.getString(KEY_SECTION)?.let { Section.valueOf(it) }

        // 상태바·카메라 구멍 아래로 내용이 오게 (딤은 화면 전체)
        // 홈(액티비티)이 이미 알고 있는 상태바 높이를 먼저 적용 → 패널 창의 값이 오면 갱신
        ViewCompat.getRootWindowInsets(requireActivity().window.decorView)
            ?.getInsets(BARS)?.let { applyBars(it.top, it.bottom) }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(BARS)
            if (bars.top > 0) applyBars(bars.top, bars.bottom)
            insets
        }

        b.backButton.setOnClickListener { handleBack() }
        b.scrim.setOnClickListener { close() }
        bindSwitches(b)
        bindVoice(b)
        bindInfo(b)
        renderNav()
        updateHeader()
        section?.let { bindSection(it) }

        // 1단계 등장: 딤 페이드 인 + 메뉴 슬라이드 인 (복원 시에는 바로 표시)
        b.navPanel.doOnLayout { nav ->
            if (savedInstanceState == null) {
                nav.translationX = nav.width.toFloat()
                nav.isVisible = true
                b.scrim.animate().alpha(1f).setDuration(ENTER_MS).start()
                nav.animate().translationX(0f).setDuration(ENTER_MS).setInterpolator(ease).start()
            } else {
                nav.isVisible = true
                b.scrim.alpha = 1f
                b.content.isVisible = section != null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(this, false)       // 딤이 상태바까지 덮게
            attributes = attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            _b?.root?.let { ViewCompat.requestApplyInsets(it) }
            // Android 12+: 뒤 화면 블러 (지원 안 하는 기기는 딤만)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.also { it.blurBehindRadius = dp(20) }
            }
        }
    }

    private fun applyBars(top: Int, bottom: Int) {
        val b = _b ?: return
        b.navPanel.updatePadding(top = top, bottom = bottom)
        b.content.updatePadding(top = top, bottom = bottom)
    }

    // ---------- 단계 이동 ----------

    private fun handleBack() {
        if (section != null) closeDetail() else close()
    }

    /** 2단계 → 1단계: 상세가 오른쪽(메뉴 뒤)으로 들어감 */
    private fun closeDetail() {
        val c = _b?.content ?: return
        section = null
        renderNav(); updateHeader()
        c.animate().translationX(c.width.toFloat()).alpha(0f).setDuration(EXIT_MS).setInterpolator(ease)
            .withEndAction { c.isInvisible = true }.start()
    }

    /** 1단계 → 닫기: 메뉴가 오른쪽으로 나가고 딤이 걷힘 */
    private fun close() {
        val b = _b ?: return
        if (closing) return
        closing = true
        b.content.animate().alpha(0f).setDuration(EXIT_MS).start()
        b.scrim.animate().alpha(0f).setDuration(EXIT_MS).start()
        b.navPanel.animate().translationX(b.navPanel.width.toFloat()).setDuration(EXIT_MS).setInterpolator(ease)
            .withEndAction { if (isAdded) dismissAllowingStateLoss() }.start()
    }

    private fun updateHeader() {
        val b = _b ?: return
        val detail = section != null
        b.backButton.setImageResource(if (detail) R.drawable.ic_arrow_back else R.drawable.ic_close)
        b.backButton.contentDescription = getString(if (detail) R.string.set_back_menu else R.string.set_close)
    }

    // ---------- 메뉴 ----------

    /** 1단계 → 2단계: 상세가 메뉴 뒤에서 왼쪽으로 밀려 나옴. 다른 메뉴로 바꿀 땐 짧게 */
    private fun open(s: Section) {
        val b = _b ?: return
        val wasOpen = section != null
        if (s == section) { closeDetail(); return }   // 같은 메뉴 한 번 더 → 상세 닫기
        section = s
        bindSection(s); renderNav(); updateHeader()
        b.content.scrollTo(0, 0)

        val c = b.content
        c.animate().cancel()
        c.isVisible = true
        if (!wasOpen) {
            c.translationX = c.width.toFloat().coerceAtLeast(dp(120).toFloat())
            c.alpha = 0f
            c.animate().translationX(0f).alpha(1f).setDuration(ENTER_MS).setInterpolator(ease).start()
        } else {
            c.translationX = dp(24).toFloat()
            c.alpha = 0.3f
            c.animate().translationX(0f).alpha(1f).setDuration(SWITCH_MS).setInterpolator(ease).start()
        }
    }

    private fun bindSection(s: Section) {
        val b = _b ?: return
        b.sectionTitle.setText(s.title)
        b.sectionVoice.isVisible = s == Section.VOICE
        b.sectionAlert.isVisible = s == Section.ALERT
        b.sectionInfo.isVisible = s == Section.INFO
        b.sectionChoice.isVisible = s in setOf(Section.ROUTE, Section.SCREEN, Section.LANGUAGE)
        when (s) {
            Section.ROUTE -> bindChoice(R.string.set_route_label, R.string.set_route_help, Key.DEFAULT_ROUTE,
                listOf(
                    Choice(RouteType.SHORTEST_TIME.name, getString(R.string.result_tab_time)),
                    Choice(RouteType.MIN_TRANSFERS.name, getString(R.string.result_tab_transfer)),
                    Choice(RouteType.MIN_FARE.name, getString(R.string.result_tab_fare))
                ))
            // 색 팔레트가 아직 라이트뿐이라 다크·시스템은 '준비 중'으로 잠금
            Section.SCREEN -> bindChoice(R.string.set_screen_label, R.string.set_screen_help, Key.THEME,
                listOf(
                    Choice("light", getString(R.string.set_theme_light)),
                    Choice("dark", getString(R.string.set_theme_dark), enabled = false),
                    Choice("system", getString(R.string.set_theme_system), enabled = false)
                ))
            Section.LANGUAGE -> bindChoice(R.string.set_language_label, R.string.set_language_help, Key.LANGUAGE,
                listOf(
                    Choice("ko", "한국어"),
                    Choice("en", "English", enabled = false),
                    Choice("zh", "中文", enabled = false),
                    Choice("ja", "日本語", enabled = false)
                ))
            else -> Unit
        }
    }

    /** 선택된 메뉴: 남색 알약 + 흰 글자 */
    private fun renderNav() {
        val b = _b ?: return
        val ctx = requireContext()
        b.navItems.removeAllViews()
        Section.entries.forEach { s ->
            val selected = s == section
            val color = ctx.getColor(if (selected) R.color.text_on_brand else R.color.text_primary)
            val item = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                    .apply { bottomMargin = dp(4) }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                setText(s.title)
                setTextAppearance(R.style.Text_MyWay_Label14Bold)
                setTextColor(color)
                setCompoundDrawablesRelativeWithIntrinsicBounds(s.icon, 0, 0, 0)
                compoundDrawablePadding = dp(10)
                compoundDrawableTintList = ColorStateList.valueOf(color)
                if (selected) setBackgroundResource(R.drawable.bg_nav_selected)
                else {
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                }
                isSelected = selected
                setOnClickListener { open(s) }
            }
            b.navItems.addView(item)
        }
    }

    // ---------- 스위치·슬라이더 ----------

    private fun bindSwitches(b: FragmentSettingsBinding) {
        mapOf<MaterialSwitch, Key>(
            b.swTts to Key.TTS, b.swStt to Key.STT,
            b.swVibrate to Key.VIBRATE, b.swNotify to Key.NOTIFY,
            b.swBefore to Key.ALERT_BEFORE, b.swArrival to Key.ALERT_ARRIVAL,
            b.swTransfer to Key.ALERT_TRANSFER, b.swMissed to Key.ALERT_MISSED,
            b.swWrongWay to Key.ALERT_WRONG_WAY
        ).forEach { (sw, key) ->
            sw.isChecked = SettingsStore.bool(key)
            sw.setOnCheckedChangeListener { _, on -> SettingsStore.set(key, on) }
        }

        b.vibrateSlider.value = SettingsStore.int(Key.VIBRATE_STRENGTH).toFloat()
        b.vibrateValue.text = getString(R.string.set_percent, SettingsStore.int(Key.VIBRATE_STRENGTH))
        b.vibrateSlider.addOnChangeListener { _, v, _ ->
            SettingsStore.set(Key.VIBRATE_STRENGTH, v.toInt())
            b.vibrateValue.text = getString(R.string.set_percent, v.toInt())
        }
    }

    private fun bindVoice(b: FragmentSettingsBinding) {
        fun renderGender() {
            val female = SettingsStore.string(Key.VOICE_GENDER) == "female"
            styleSegment(b.voiceFemale, female)
            styleSegment(b.voiceMale, !female)
        }
        b.voiceFemale.setOnClickListener { SettingsStore.set(Key.VOICE_GENDER, "female"); renderGender() }
        b.voiceMale.setOnClickListener { SettingsStore.set(Key.VOICE_GENDER, "male"); renderGender() }
        renderGender()

        b.volumeSlider.value = SettingsStore.int(Key.VOICE_VOLUME).toFloat()
        b.volumeValue.text = getString(R.string.set_percent, SettingsStore.int(Key.VOICE_VOLUME))
        b.volumeSlider.addOnChangeListener { _, v, _ ->
            SettingsStore.set(Key.VOICE_VOLUME, v.toInt())
            b.volumeValue.text = getString(R.string.set_percent, v.toInt())
        }
    }

    private fun styleSegment(tv: TextView, selected: Boolean) {
        if (selected) tv.setBackgroundResource(R.drawable.bg_segment_thumb) else tv.background = null
        tv.setTextColor(requireContext().getColor(if (selected) R.color.text_on_brand else R.color.text_secondary))
        tv.isSelected = selected
    }

    // ---------- 한 개 고르기 목록 (경로·화면·언어) ----------

    private data class Choice(val value: String, val label: String, val enabled: Boolean = true)

    private fun bindChoice(@StringRes label: Int, @StringRes help: Int, key: Key, options: List<Choice>) {
        val b = _b ?: return
        val ctx = requireContext()
        b.choiceLabel.setText(label)
        b.choiceHelp.setText(help)
        b.choiceList.removeAllViews()
        val current = SettingsStore.string(key)

        options.forEach { opt ->
            val selected = opt.value == current
            val row = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minHeight = dp(52)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                text = if (opt.enabled) opt.label else "${opt.label}  ·  ${getString(R.string.set_pending)}"
                setTextAppearance(if (selected) R.style.Text_MyWay_Body16Bold else R.style.Text_MyWay_Body16Medium)
                setTextColor(ctx.getColor(if (opt.enabled) R.color.text_primary else R.color.icon_muted))
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (selected) R.drawable.ic_check else 0, 0)
                isEnabled = opt.enabled
                isSelected = selected
                if (opt.enabled) {
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener {
                        SettingsStore.set(key, opt.value)
                        bindChoice(label, help, key, options)
                    }
                }
            }
            b.choiceList.addView(row)
        }
    }

    // ---------- 정보 ----------

    private fun bindInfo(b: FragmentSettingsBinding) {
        val ctx = requireContext()
        b.versionText.text = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "-"
        b.mapSourceRow.setOnClickListener { notice(R.string.set_map_source, R.string.set_map_source_body) }
        b.licenseRow.setOnClickListener { notice(R.string.set_licenses, R.string.set_licenses_body) }
    }

    private fun notice(@StringRes title: Int, @StringRes body: Int) {
        val db = DialogNoticeBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = resources.getDimensionPixelSize(R.dimen.dialog_side_margin)
            window?.setLayout(resources.displayMetrics.widthPixels - margin * 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.dialogTitle.setText(title)
        db.dialogBody.setText(body)
        db.dialogAction.setText(R.string.set_ok)
        db.dialogAction.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        section?.let { outState.putString(KEY_SECTION, it.name) }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private val ease = FastOutSlowInInterpolator()

    companion object {
        private const val KEY_SECTION = "section"
        private const val ENTER_MS = 280L
        private const val EXIT_MS = 200L
        private const val SWITCH_MS = 180L
        private val BARS = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    }
}
