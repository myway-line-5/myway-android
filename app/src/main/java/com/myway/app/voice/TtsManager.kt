package com.myway.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** locale: 읽어 줄 언어 (앱 화면 언어를 따름. 기본 한국어) */
class TtsManager(context: Context, private val locale: Locale = Locale.KOREAN) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts?.setLanguage(locale)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (ready) {
            pending?.let { speak(it) }
            pending = null
        }
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myway-tts")
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}