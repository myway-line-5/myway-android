package com.myway.app.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.myway.app.data.local.SettingsStore
import java.util.Locale

/**
 * locale: 읽어 줄 언어 (앱 화면 언어를 따름. 기본 한국어)
 * 설정 > 음성의 목소리(여성/남성)·음량을 따른다.
 */
class TtsManager(context: Context, private val locale: Locale = Locale.KOREAN) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null
    private var pendingDone: (() -> Unit)? = null
    /** 다 읽은 뒤 할 일 (음성 대화: 읽고 → 바로 듣기) */
    private val doneCallbacks = mutableMapOf<String, () -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts?.setLanguage(locale)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finish(id) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { finish(id) }
            override fun onError(id: String?, errorCode: Int) { finish(id) }
        })
        if (ready) {
            pending?.let { speak(it, pendingDone) }
            pending = null; pendingDone = null
        }
    }

    /**
     * 안드로이드는 목소리의 성별을 알려 주지 않아서 이름으로 고른다.
     * 이름에 male/female이 있으면 그걸, 구글 TTS면 알려진 남성 목소리 코드를 쓴다.
     * 맞는 남성 목소리가 폰에 없으면 기본 목소리를 낮은 톤으로 읽는다.
     */
    private fun applyVoice() {
        val t = tts ?: return
        val male = SettingsStore.string(SettingsStore.Key.VOICE_GENDER) == "male"
        val voices = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
        fun isMale(v: Voice): Boolean {
            val n = v.name.lowercase()
            if ("female" in n) return false
            if ("male" in n) return true
            return MALE_CODES.any { n.contains("-x-$it") }
        }
        val pick = voices.filter { isMale(it) == male }.minByOrNull { it.latency }
        if (pick != null) { t.voice = pick; t.setPitch(1f) }
        else { t.setLanguage(locale); t.setPitch(if (male) 0.75f else 1f) }
    }

    private fun finish(id: String?) {
        val cb = synchronized(doneCallbacks) { doneCallbacks.remove(id) } ?: return
        main.post { cb() }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) { pending = text; pendingDone = onDone; return }
        applyVoice()                                    // 설정을 방금 바꿨어도 바로 반영
        val vol = SettingsStore.int(SettingsStore.Key.VOICE_VOLUME).coerceIn(0, 100) / 100f
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol) }
        val id = "myway-${System.nanoTime()}"
        if (onDone != null) synchronized(doneCallbacks) { doneCallbacks.clear(); doneCallbacks[id] = onDone }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun stop() { synchronized(doneCallbacks) { doneCallbacks.clear() }; tts?.stop() }

    fun shutdown() {
        synchronized(doneCallbacks) { doneCallbacks.clear() }
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        /** 구글 TTS 남성 목소리 코드 (ko-kr-x-jun/koc, en-us-x-iom/iol/tpd 등) */
        val MALE_CODES = listOf("jun", "koc", "iom", "iol", "tpd", "rjs", "gbd", "gbg", "ene")
    }
}
