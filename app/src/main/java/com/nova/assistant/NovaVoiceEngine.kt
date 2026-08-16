package com.nova.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Wraps text-to-speech so the rest of the app never talks to a TTS provider directly.
 *
 * Voice choice: tries a Hindi (hi-IN) voice first, then Indian-English (en-IN), then
 * whatever default is available. HONEST LIMIT: Android's built-in TTS engines are not
 * true "Hinglish" voices — there is no free on-device engine that natively mixes
 * Hindi+English pronunciation rules. What this does is pick the voice that reads
 * Romanized Hindi words the LEAST awkwardly (hi-IN reads Devanagari-style words much
 * better than en-US would, but will still mispronounce pure English words with an
 * Indian-Hindi accent, and vice versa for en-IN). A genuinely native-sounding Hinglish
 * voice would need a paid cloud TTS engine (e.g. Google Cloud TTS Chirp voices) — out
 * of scope for a free, on-device default, but swappable later since only this file
 * would need to change.
 *
 * onSpeakingStateChanged(true/false) is critical, not cosmetic: the wake-word listener
 * must NOT be listening while Nova is talking, or it can hear her own voice through the
 * speaker and mistake it for a new wake word / command (a real feedback-loop bug). This
 * callback is what lets NovaWakeService know exactly when it's safe to resume listening.
 */
class NovaVoiceEngine(context: Context) {

    companion object { private const val TAG = "NovaVoiceEngine" }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    var onInitFailed: ((String) -> Unit)? = null

    // Multiple listeners, not one: BOTH MainActivity (drives the orb's SPEAKING state)
    // and NovaWakeService (must stop listening while Nova talks, or she can hear her
    // own voice through the speaker and mistake it for a new wake word) need to know
    // when speech starts/stops — whichever path actually triggered voiceEngine.speak().
    private val speakingListeners = mutableListOf<(Boolean) -> Unit>()
    fun addSpeakingStateListener(listener: (Boolean) -> Unit) { speakingListeners.add(listener) }
    fun removeSpeakingStateListener(listener: (Boolean) -> Unit) { speakingListeners.remove(listener) }
    private fun notifySpeaking(isSpeaking: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            speakingListeners.forEach { it.invoke(isSpeaking) }
        }
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                pickBestVoice()
                tts?.setPitch(0.96f)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        notifySpeaking(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        notifySpeaking(false)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        notifySpeaking(false)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "TTS utterance error code=$errorCode")
                        notifySpeaking(false)
                    }
                })
                ready = true
                pendingText?.let { text -> pendingText = null; speak(text) }
            } else {
                Log.e(TAG, "TextToSpeech init failed with status=$status")
                onInitFailed?.invoke("Text-to-speech engine failed to initialize (status=$status). Nova can still understand you, but can't speak replies aloud.")
            }
        }
    }

    /** Tries hi-IN first (best for Hinglish/Hindi words), then en-IN, then leaves the
     *  engine's own default untouched if neither is installed on this phone. */
    private fun pickBestVoice() {
        val engine = tts ?: return
        val hindiResult = engine.setLanguage(Locale("hi", "IN"))
        if (hindiResult == TextToSpeech.LANG_MISSING_DATA || hindiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val indianEnglishResult = engine.setLanguage(Locale("en", "IN"))
            if (indianEnglishResult == TextToSpeech.LANG_MISSING_DATA || indianEnglishResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Neither hi-IN nor en-IN voice data found on this device — using engine default.")
            }
        }
        val voices = engine.voices ?: return
        val preferredFemale = voices.firstOrNull {
            it.name.contains("female", ignoreCase = true) &&
                (it.locale.language == "hi" || (it.locale.language == "en" && it.locale.country == "IN"))
        }
        preferredFemale?.let { engine.voice = it }
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "speak() called before TTS was ready — queuing: \"$text\"")
            pendingText = text
            return
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova_utterance_${System.currentTimeMillis()}")
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR for text: \"$text\"")
            notifySpeaking(false)
        }
    }

    /** Stops mid-sentence — backs the "interruptible speech" requirement from the spec. */
    fun stopSpeaking() {
        tts?.stop()
        notifySpeaking(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
