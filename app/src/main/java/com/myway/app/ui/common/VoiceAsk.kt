package com.myway.app.ui.common

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.myway.app.R
import com.myway.app.data.local.SettingsStore
import com.myway.app.voice.TtsManager

/**
 * 핸즈프리 음성 대화 한 턴: 읽어 주고(TTS) → 끝나면 짧게 듣고(STT) → 들은 말을 돌려줌.
 * 처음에 마이크로 물어본 사람(VoiceSession.active)에게만 이어서 말로 묻는다.
 * 듣기는 최대 [LISTEN_MS] 동안만 (계속 켜 두지 않음 · 배터리·사생활)
 */
class VoiceAsk(private val activity: Activity, private val tts: TtsManager?) {

    private var recognizer: SpeechRecognizer? = null
    private var chip: View? = null
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var closed = false
    /** 묻는 중(읽는 중·듣는 중)인가 */
    private var asking = false

    /** 말로 물을 수 있는 상태인가 (설정에서 읽어주기·음성 입력 둘 다 켜짐 + 마이크 권한) */
    fun usable(): Boolean = SettingsStore.bool(SettingsStore.Key.TTS) && SettingsStore.bool(SettingsStore.Key.STT) &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        SpeechRecognizer.isRecognitionAvailable(activity)

    /** prompt 를 읽고 → 듣기. onHeard(null) = 아무 말 없음·실패 */
    fun ask(prompt: String, hint: String, onHeard: (String?) -> Unit) {
        if (closed) return
        stopListening()
        if (!usable()) { tts?.speak(prompt); onHeard(null); return }
        asking = true
        val t = tts ?: return listen(hint, onHeard)
        t.speak(prompt) { if (!closed) listen(hint, onHeard) }
    }

    /** 읽기만 (대답을 기다리지 않음) */
    fun say(text: String, then: (() -> Unit)? = null) { if (!closed) tts?.speak(text, then) }

    private fun listen(hint: String, onHeard: (String?) -> Unit) {
        var delivered = false
        fun deliver(text: String?) {
            if (delivered) return
            delivered = true
            asking = false
            stopListening()
            onHeard(text?.takeIf { it.isNotBlank() })
        }
        var partial: String? = null
        val r = SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            // 후보 여러 개 중 '응·시작' 같은 명령으로 읽히는 것을 우선 (짧은 대답은 '음·흥'으로 잘못 받아쓰기 쉬움)
            override fun onResults(results: Bundle?) {
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                deliver(all.firstOrNull { VoiceCommand.parse(it) != VoiceCommand.Cmd.OTHER } ?: all.firstOrNull() ?: partial)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { partial = it }
            }
            // 짧은 말은 '못 알아들음' 오류로 끝나기도 함 → 중간에 들린 말이라도 씀
            override fun onError(error: Int) = deliver(partial)
            // 지금 말하면 된다는 신호음 (말을 너무 일찍 시작해 앞부분이 잘리는 것 방지)
            override fun onReadyForSpeech(params: Bundle?) { beep() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showChip(hint)
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (StationNames.english) "en-US" else "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName))
        timeout = Runnable { deliver(null) }.also { main.postDelayed(it, LISTEN_MS) }
    }

    private fun beep() {
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tg.release() }, 300)
        }
    }

    /** 화면 아래 '듣는 중' 알약: 지금 말하면 된다는 걸 보이게 */
    private fun showChip(hint: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        hideChip()
        val d = activity.resources.displayMetrics.density
        chip = TextView(activity).apply {
            text = activity.getString(R.string.voice_listening, hint)
            setTextAppearance(R.style.Text_MyWay_Label14Bold)
            setTextColor(activity.getColor(R.color.text_on_brand))
            setBackgroundResource(R.drawable.bg_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.brand_navy))
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            elevation = 6 * d
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (110 * d).toInt() }
        }
        root.addView(chip)
        chip?.animate()?.alpha(1f)?.setDuration(160)?.start()
    }

    private fun hideChip() { chip?.let { (it.parent as? ViewGroup)?.removeView(it) }; chip = null }

    private fun stopListening() {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        recognizer?.destroy(); recognizer = null
        hideChip()
    }

    /** 화면을 떠날 때 */
    /** 화면을 떠날 때. 묻는 도중 사용자가 직접 다른 화면으로 갔으면 말 대화를 끝냄 (돌아와도 다시 안 물음) */
    fun close() {
        if (asking) VoiceSession.active = false
        asking = false
        closed = true; stopListening(); tts?.stop()
    }

    companion object { const val LISTEN_MS = 6_000L }
}

/** 처음 질문을 마이크로 했는가 → 이후 단계도 말로 이어 감 */
object VoiceSession {
    var active = false
}

/** 짧은 대답 알아듣기 (한국어·영어) */
object VoiceCommand {
    enum class Cmd { YES, NO, AGAIN, NEXT_TRAIN, FASTEST, FEWEST_TRANSFERS, CHEAPEST, OTHER }

    private val yesWords = listOf(
        "응", "응응", "웅", "엉", "음", "어", "어어", "네", "넹", "넵", "예", "예스", "ㅇㅇ", "그래", "그래요", "그럼",
        "좋아", "좋아요", "맞아", "맞아요", "맞습니다", "시작", "시작해", "출발", "고", "가자", "찾아", "찾아줘", "해줘", "오케이",
        "ok", "okay", "yes", "yeah", "yep", "yup", "sure", "start", "go")
    private val noWords = listOf("아니", "아니요", "아뇨", "취소", "그만", "됐어", "no", "nope", "cancel", "stop")

    fun parse(raw: String?): Cmd {
        val t = raw?.lowercase()?.replace(Regex("[.,!?~]"), "")?.trim() ?: return Cmd.OTHER
        val first = t.split(" ").firstOrNull().orEmpty()
        return when {
            "다음 열차" in t || "다음열차" in t || "다음 차" in t || "next train" in t -> Cmd.NEXT_TRAIN
            noWords.any { first == it || first.startsWith("아니") } || "못 탔" in t || "못탔" in t || "놓쳤" in t || "missed" in t -> Cmd.NO
            "다시" in t || "뭐라고" in t || "again" in t || "repeat" in t -> Cmd.AGAIN
            "환승" in t && ("적" in t || "최소" in t) || "fewer transfer" in t || "fewest" in t -> Cmd.FEWEST_TRANSFERS
            "빠른" in t || "빨리" in t || "최단" in t || "fastest" in t || "quickest" in t -> Cmd.FASTEST
            "싼" in t || "저렴" in t || "최소비용" in t || "최소 비용" in t || "cheapest" in t -> Cmd.CHEAPEST
            yesWords.any { first == it || t == it } || t.endsWith("시작해") || t.endsWith("찾아줘") || t.endsWith("가자") -> Cmd.YES
            else -> Cmd.OTHER
        }
    }
}
