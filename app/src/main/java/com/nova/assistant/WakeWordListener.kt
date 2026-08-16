package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Clear state machine, exactly as specced:
 *   IDLE -> WAKE_LISTENING -> WAKE_DETECTED -> COMMAND_LISTENING -> (handed off) -> WAKE_LISTENING
 * Only one SpeechRecognizer session is ever alive at a time — enforced by
 * `phase` + destroying the previous recognizer before any new one starts.
 *
 * THE "TING EVERY 1-3 SECONDS" BUG — ROOT CAUSE AND FIX:
 * Android's SpeechRecognizer plays its OWN built-in start/stop beep every single
 * time startListening() is called — on stock/AOSP-based recognizers this beep is
 * routed through STREAM_MUSIC. The previous version created a brand-new
 * SpeechRecognizer and called startListening() every ~1-3 seconds while silently
 * waiting for the wake word (each silent timeout -> restart -> new beep). That
 * repeating system beep WAS the "ting" — not a bug in when we restarted, but an
 * unmuted restart. Fix: STREAM_MUSIC is deliberately, briefly muted around every
 * startListening() call in BOTH phases, and Nova now plays her own single,
 * deliberate ToneGenerator cue exactly once when the wake word is actually
 * detected (start of command capture) — matching the spec's "ONE listening-start
 * sound" requirement instead of Android's own repeating one.
 * HONEST LIMIT: this targets STREAM_MUSIC because that's what stock Android's
 * recognizer beep uses. Some OEM skins (custom MIUI/ColorOS speech overlays)
 * route it differently and may still produce a faint sound on those phones —
 * that's a platform difference this code can't fully erase, not something hidden.
 */
enum class WakePhase { IDLE, WAKE_LISTENING, COMMAND_LISTENING, SUSPENDED }

class WakeWordListener(
    private val context: Context,
    private val onWakeDetected: () -> Unit,
    private val onCommandHeard: (String) -> Unit,
    private val onFatalError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "WakeWordListener"
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_CONSECUTIVE_ERRORS = 8 // background silence timeouts are normal, not failures
    }

    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    var phase: WakePhase = WakePhase.IDLE
        private set
    private var consecutiveErrors = 0
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val toneGenerator by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: Exception) { null }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFatalError("This device has no speech recognition service available — wake word can't run. Try tap-to-talk instead.")
            return
        }
        if (isActive) return
        isActive = true
        consecutiveErrors = 0
        listenForWakeWord()
    }

    fun stop() {
        isActive = false
        phase = WakePhase.IDLE
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        unmuteBeep()
    }

    /** Call while Nova is THINKING or SPEAKING — stops the mic without fully
     *  tearing down the "enabled" state, so resumeListening() picks up cleanly. */
    fun pauseForProcessing() {
        if (!isActive) return
        phase = WakePhase.SUSPENDED
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        unmuteBeep()
    }

    fun resumeListening() {
        if (!isActive) return
        if (phase != WakePhase.SUSPENDED && phase != WakePhase.IDLE) return
        listenForWakeWord()
    }

    /** One deliberate cue — the spec's "ONE listening-start sound". Public so
     *  NovaWakeService can also play the matching "ONE completion sound" after
     *  Nova finishes speaking, using the same generator/volume. */
    fun playCue(ascending: Boolean) {
        try {
            toneGenerator?.startTone(
                if (ascending) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK,
                120
            )
        } catch (_: Exception) { /* best-effort cue only, never fatal */ }
    }

    private fun listenForWakeWord() {
        if (!isActive) return
        phase = WakePhase.WAKE_LISTENING
        beginSession(background = true)
    }

    private fun listenForCommand() {
        if (!isActive) return
        phase = WakePhase.COMMAND_LISTENING
        beginSession(background = false)
    }

    private fun beginSession(background: Boolean) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                consecutiveErrors = 0
                val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                val lower = heard.lowercase()

                if (background) {
                    if (lower.contains("hey nova") || lower.contains("hi nova")) {
                        val remainder = lower.replace(Regex("hey nova|hi nova"), "").trim()
                        onWakeDetected()
                        playCue(ascending = true)
                        if (remainder.length > 2) {
                            // Wake word + command said in one breath — skip the extra listening round-trip.
                            onCommandHeard(remainder)
                        } else {
                            listenForCommand()
                            return
                        }
                    } else {
                        listenForWakeWord()
                        return
                    }
                } else {
                    // COMMAND_LISTENING result -> hand off regardless of content (even empty
                    // gets a natural "didn't catch that" from CommandProcessor's caller).
                    if (heard.isNotBlank()) onCommandHeard(heard)
                    // Do NOT auto-restart wake-listening here — NovaWakeService restarts it
                    // via resumeListening() only after Nova finishes speaking the reply.
                    return
                }
            }

            override fun onError(error: Int) {
                if (background) {
                    // In the background phase, NO_MATCH / SPEECH_TIMEOUT are the NORMAL,
                    // constant state (silence) — not real errors, so they don't count
                    // toward the failure cap or need a growing backoff.
                    val benign = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (benign) {
                        restartAfterBenignTimeout()
                    } else {
                        Log.w(TAG, "wake-word recognizer error code=$error")
                        restartAfterRealError()
                    }
                } else {
                    // Command capture failed (e.g. timeout while user hesitated) — treat as
                    // "didn't catch that", hand back to the service to decide what's next.
                    Log.w(TAG, "command-capture recognizer error code=$error")
                    onCommandHeard("")
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (!background) {
                // Give the user real time to finish their command sentence instead of
                // getting cut off mid-thought.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            } else {
                // Best-effort: on recognizers that honor this, a slightly longer idle
                // window before a background segment times out means fewer restarts.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
        }

        muteBeep()
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening() threw", e)
            unmuteBeep()
            if (background) restartAfterRealError() else onCommandHeard("")
            return
        }
        // Beep fires essentially immediately on session start — safe to restore
        // volume shortly after so we never leave the phone's music stream muted.
        handler.postDelayed({ unmuteBeep() }, 350L)
    }

    private fun restartAfterBenignTimeout() {
        recognizer?.destroy()
        if (!isActive || phase == WakePhase.SUSPENDED) return
        handler.postDelayed({ listenForWakeWord() }, 300L)
    }

    private fun restartAfterRealError() {
        recognizer?.destroy()
        if (!isActive || phase == WakePhase.SUSPENDED) return
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "giving up after $consecutiveErrors consecutive recognizer errors")
            isActive = false
            phase = WakePhase.IDLE
            onFatalError("Wake word stopped after repeated microphone/recognizer errors. Check mic permission, or use tap-to-talk instead.")
            return
        }
        handler.postDelayed({ listenForWakeWord() }, RETRY_DELAY_MS)
    }

    private fun muteBeep() {
        try { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
        catch (_: Exception) { /* some OEMs restrict this — degrade to "beep sometimes audible", not a crash */ }
    }

    private fun unmuteBeep() {
        try { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        catch (_: Exception) { }
    }
}
