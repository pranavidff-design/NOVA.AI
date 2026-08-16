package com.nova.assistant

import android.app.Application
import com.nova.assistant.memory.MemoryManager

/**
 * MainActivity (foreground) and NovaWakeService (background wake word) both need
 * NovaBrain and NovaVoiceEngine. Without this, each one used to create its OWN
 * separate instance — meaning the multi-GB local AI model could end up loaded
 * into memory TWICE at once (once per instance) if both were active together,
 * and two independent TextToSpeech engines could fight over audio focus.
 *
 * Application-scoped singletons fix both: the model loads once, one TTS engine
 * speaks for whichever path (tap or wake word) triggered the reply.
 */
class NovaApp : Application() {

    val memory: MemoryManager by lazy { MemoryManager(this) }
    val brain: NovaBrain by lazy { NovaBrain(this) }
    val voiceEngine: NovaVoiceEngine by lazy { NovaVoiceEngine(this) }

    private var modelInitStarted = false

    /** Safe to call from both MainActivity and NovaWakeService — only actually
     *  kicks off model loading once, no matter how many times/places call it. */
    fun ensureBrainInitialized(onReady: (Boolean) -> Unit) {
        if (modelInitStarted) {
            // Already loading/loaded elsewhere — just report current state.
            onReady(brain.isReady)
            return
        }
        modelInitStarted = true
        brain.initialize(onReady)
    }
}
