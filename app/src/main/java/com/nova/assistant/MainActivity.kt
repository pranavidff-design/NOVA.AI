package com.nova.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.memory.MemoryManager
import kotlinx.coroutines.launch

/**
 * MainActivity — tap-to-talk (foreground) path. "Hey Nova" wake word runs in
 * NovaWakeService (a real foreground service) so it keeps working when this
 * Activity isn't visible; MainActivity just starts/stops it and mirrors its
 * activity via NovaEventBus while it happens to be on screen.
 *
 * LIFECYCLE FIX (this rebuild): the previous version created its SpeechRecognizer
 * once in onCreate() and never touched it again. A SpeechRecognizer's connection
 * to the underlying recognition service can go stale after the app is backgrounded
 * for a while — even without the Activity itself being destroyed — which is why
 * voice worked on a fresh launch but silently died after Home -> Recents -> reopen.
 * onPause() now cancels any in-flight session cleanly, and onResume() always
 * recreates a fresh SpeechRecognizer instance before use.
 */
class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusLabel: TextView
    private lateinit var logText: TextView
    private lateinit var orb: NovaOrbView
    private lateinit var voiceEngine: NovaVoiceEngine
    private lateinit var brain: NovaBrain
    private lateinit var memory: MemoryManager
    private lateinit var processor: CommandProcessor
    private var wakeWordOn = false
    private var recognitionAvailable = true

    private enum class PendingAction { NONE, TAP_TO_TALK, WAKE_WORD, CONTACTS_COMMAND }
    private var pendingAction = PendingAction.NONE
    private var pendingContactsCommandText: String? = null

    /** Drives the orb's SPEAKING state off the REAL TTS start/stop callbacks
     *  (see NovaVoiceEngine) instead of guessing — so the orb accurately shows
     *  Nova talking for exactly as long as she's actually talking. */
    private val speakingListener: (Boolean) -> Unit = { isSpeaking ->
        if (isSpeaking) {
            statusLabel.text = getString(R.string.status_speaking)
            orb.setState(OrbState.SPEAKING)
        } else {
            statusLabel.text = if (memory.paused) "Paused — tap Resume to continue" else getString(R.string.status_idle)
            orb.setState(if (memory.paused) OrbState.PAUSED else if (wakeWordOn) OrbState.WAKE_ACTIVE else OrbState.IDLE)
        }
    }

    private val modelPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            statusLabel.text = "Copying model file… this can take a minute for a large file."
            brain.copyPickedModelFile(uri) { copiedOk ->
                if (copiedOk) {
                    brain.initialize { ready ->
                        runOnUiThread {
                            if (ready) {
                                statusLabel.text = getString(R.string.status_idle)
                                appendLog("Nova", "Local AI model loaded successfully.")
                            } else {
                                appendLog("System", "Model load failed: ${brain.lastError ?: "unknown reason"}")
                                statusLabel.text = "Model copied but failed to load."
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        appendLog("System", "Model copy failed: ${brain.lastError ?: "make sure it's the correct .task file"}")
                        statusLabel.text = "Couldn't copy that file."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusLabel = findViewById(R.id.statusLabel)
        logText = findViewById(R.id.logText)
        orb = findViewById(R.id.orbContainer)
        val app = application as NovaApp
        voiceEngine = app.voiceEngine
        voiceEngine.onInitFailed = { reason -> runOnUiThread { appendLog("System", reason) } }
        voiceEngine.addSpeakingStateListener(speakingListener)
        memory = app.memory
        brain = app.brain

        processor = CommandProcessor(
            context = this,
            memory = memory,
            brain = brain,
            voiceEngine = voiceEngine,
            scope = lifecycleScope,
            onLog = { who, text -> runOnUiThread { appendLog(who, text) } },
            onStatus = { status -> runOnUiThread {
                statusLabel.text = statusToLabel(status)
                orb.setState(statusToOrbState(status))
            } },
            requestApproval = { actionLabel, onDecision -> PermissionGate.request(this, actionLabel, onDecision) },
            onNeedsContactsPermission = { originalText ->
                pendingContactsCommandText = originalText
                pendingAction = PendingAction.CONTACTS_COMMAND
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_CODE)
            }
        )

        statusLabel.text = "Loading Nova's local brain…"
        app.ensureBrainInitialized { success ->
            runOnUiThread {
                if (success) {
                    statusLabel.text = getString(R.string.status_idle)
                } else {
                    appendLog("System", "Startup local model load failed: ${brain.lastError ?: "model file not found"}")
                    statusLabel.text = if (brain.isCloudConfigured) "Local model not loaded, but cloud brain is set up." else "AI model not loaded — tap LOAD AI MODEL or set up the cloud brain."
                }
            }
        }

        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        if (!recognitionAvailable) {
            Log.e(TAG, "SpeechRecognizer.isRecognitionAvailable() == false on this device")
            appendLog("System", "No speech recognition service found on this device (needs the Google app, or another STT provider, installed). Voice input won't work here.")
            statusLabel.text = "No speech recognition available on this device."
        }

        orb.setOnClickListener { onOrbTapped() }

        findViewById<android.widget.Button?>(R.id.memoryButton)?.setOnClickListener {
            startActivity(android.content.Intent(this, MemoryActivity::class.java))
        }

        findViewById<android.widget.Button?>(R.id.routinesButton)?.setOnClickListener {
            startActivity(android.content.Intent(this, RoutinesActivity::class.java))
        }

        findViewById<android.widget.Button?>(R.id.cloudSetupButton)?.setOnClickListener {
            showCloudSetupDialog()
        }

        val pauseButton = findViewById<android.widget.Button?>(R.id.pauseButton)
        pauseButton?.text = if (memory.paused) "Resume Nova" else "Pause Nova"
        orb.setState(if (memory.paused) OrbState.PAUSED else OrbState.IDLE)
        pauseButton?.setOnClickListener {
            memory.paused = !memory.paused
            pauseButton.text = if (memory.paused) "Resume Nova" else "Pause Nova"
            statusLabel.text = if (memory.paused) "Paused — tap Resume to continue" else getString(R.string.status_idle)
            orb.setState(if (memory.paused) OrbState.PAUSED else OrbState.IDLE)
            if (memory.paused && wakeWordOn) {
                NovaWakeService.stop(this)
                wakeWordOn = false
                findViewById<android.widget.Button?>(R.id.wakeWordButton)?.text = "Wake word: OFF"
            }
        }

        val wakeWordButton = findViewById<android.widget.Button?>(R.id.wakeWordButton)
        wakeWordButton?.setOnClickListener {
            if (memory.paused) { statusLabel.text = "Nova is paused — tap Resume first"; return@setOnClickListener }
            if (!recognitionAvailable) { statusLabel.text = "No speech recognition available on this device."; return@setOnClickListener }
            val missing = missingWakeWordPermissions()
            if (missing.isNotEmpty()) {
                pendingAction = PendingAction.WAKE_WORD
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
                return@setOnClickListener
            }
            toggleWakeWord()
        }

        findViewById<android.widget.Button?>(R.id.loadModelButton)?.setOnClickListener {
            appendLog("System", "Opening file picker — select the downloaded .task model file.")
            modelPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    /** Cloud brain setup now happens at build time (app/google-services.json from
     *  the Firebase console), not by typing a URL into the app — there's no backend
     *  URL to manage anymore. This just shows the current status honestly. */
    private fun showCloudSetupDialog() {
        val message = if (brain.isCloudConfigured) {
            "Cloud brain (Gemini via Firebase) is set up and will be used whenever you're online, falling back to the local model automatically if it's ever unreachable."
        } else {
            "Cloud brain isn't set up yet. This is configured once, at build time, by adding app/google-services.json from your Firebase project — see README \"Cloud brain setup\" for the exact steps. Nova works fine on the local model alone until then."
        }
        AlertDialog.Builder(this)
            .setTitle("Cloud AI status")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        wakeWordOn = NovaWakeService.isRunning
        findViewById<android.widget.Button?>(R.id.wakeWordButton)?.text =
            if (wakeWordOn) "Wake word: ON" else "Wake word: OFF"
        orb.setState(if (memory.paused) OrbState.PAUSED else if (wakeWordOn) OrbState.WAKE_ACTIVE else OrbState.IDLE)

        NovaEventBus.setListeners(
            onLog = { who, text -> runOnUiThread { appendLog(who, text) } },
            onStatus = { status -> runOnUiThread {
                statusLabel.text = statusToLabel(status)
                orb.setState(statusToOrbState(status))
            } }
        )
    }

    override fun onStop() {
        super.onStop()
        NovaEventBus.setListeners(null, null)
    }

    override fun onResume() {
        super.onResume()
        // Recreate fresh every time this Activity becomes visible again — this is
        // the actual fix for "works on fresh launch, dead after reopening from
        // Recents": a stale SpeechRecognizer service connection is invisible (no
        // exception, no error callback) until you try to use it and nothing happens.
        if (recognitionAvailable) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(this)
        }
    }

    override fun onPause() {
        super.onPause()
        // Cancel (not destroy — destroy happens fresh in onResume) any in-flight
        // tap-to-talk session so it can't get stuck "Listening…" forever after
        // the user leaves mid-command.
        try { speechRecognizer?.cancel() } catch (e: Exception) { Log.w(TAG, "cancel() on pause threw", e) }
    }

    private fun statusToLabel(status: String): String = when (status) {
        "listening" -> getString(R.string.status_listening)
        "thinking" -> getString(R.string.status_thinking)
        "ready" -> getString(R.string.status_idle)
        "paused" -> "Paused — tap Resume to continue"
        "waiting for approval" -> "Waiting for your approval…"
        else -> status
    }

    private fun statusToOrbState(status: String): OrbState = when (status) {
        "listening" -> OrbState.LISTENING
        "thinking", "waiting for approval" -> OrbState.THINKING
        "paused" -> OrbState.PAUSED
        "ready" -> if (wakeWordOn) OrbState.WAKE_ACTIVE else OrbState.IDLE
        else -> OrbState.IDLE
    }

    private fun missingWakeWordPermissions(): List<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleWakeWord() {
        wakeWordOn = !wakeWordOn
        val wakeWordButton = findViewById<android.widget.Button?>(R.id.wakeWordButton)
        wakeWordButton?.text = if (wakeWordOn) "Wake word: ON" else "Wake word: OFF"
        if (wakeWordOn) {
            NovaWakeService.start(this)
            statusLabel.text = "Wake word active in background — say \"Hey Nova\". See the notification to stop it."
            orb.setState(OrbState.WAKE_ACTIVE)
        } else {
            NovaWakeService.stop(this)
            statusLabel.text = getString(R.string.status_idle)
            orb.setState(OrbState.IDLE)
        }
    }

    private fun onOrbTapped() {
        if (memory.paused) {
            statusLabel.text = "Nova is paused — tap Resume first"
            return
        }
        if (!recognitionAvailable) {
            statusLabel.text = "No speech recognition available on this device."
            appendLog("System", "SpeechRecognizer.isRecognitionAvailable() returned false — install/enable the Google app's speech services and try again.")
            return
        }
        if (wakeWordOn) {
            statusLabel.text = "Wake word is already listening — just say \"Hey Nova\"."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = PendingAction.TAP_TO_TALK
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val action = pendingAction
        pendingAction = PendingAction.NONE

        val recordAudioIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val micGranted = recordAudioIdx == -1 || (grantResults.getOrNull(recordAudioIdx) == PackageManager.PERMISSION_GRANTED)

        if (!micGranted) {
            statusLabel.text = "Microphone permission denied — Nova can't listen without it."
            appendLog("System", "RECORD_AUDIO permission was denied. Tap the orb again to re-request, or enable it from Android Settings > Apps > Nova > Permissions.")
            return
        }

        when (action) {
            PendingAction.TAP_TO_TALK -> startListening()
            PendingAction.WAKE_WORD -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notifIdx = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
                    val notifGranted = notifIdx == -1 || grantResults.getOrNull(notifIdx) == PackageManager.PERMISSION_GRANTED
                    if (!notifGranted) {
                        appendLog("System", "Notification permission denied — the wake-word service needs it to show its required status notification on Android 13+.")
                    }
                }
                toggleWakeWord()
            }
            PendingAction.CONTACTS_COMMAND -> {
                val contactsIdx = permissions.indexOf(Manifest.permission.READ_CONTACTS)
                val contactsGranted = contactsIdx != -1 && grantResults.getOrNull(contactsIdx) == PackageManager.PERMISSION_GRANTED
                val text = pendingContactsCommandText
                pendingContactsCommandText = null
                if (contactsGranted && text != null) {
                    processor.handle(text)
                } else {
                    appendLog("System", "Contacts permission was denied — I can't look up a contact for WhatsApp without it.")
                    statusLabel.text = getString(R.string.status_idle)
                }
            }
            PendingAction.NONE -> { /* nothing pending */ }
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            statusLabel.text = "Speech recognizer not available."
            return
        }
        statusLabel.text = getString(R.string.status_listening)
        orb.setState(OrbState.LISTENING)
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening() threw", e)
            statusLabel.text = getString(R.string.status_idle)
            appendLog("System", "Couldn't start listening: ${e.javaClass.simpleName} — ${e.message ?: "no further detail"}")
        }
    }

    override fun onResults(results: Bundle) {
        val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (heard.isNullOrBlank()) {
            statusLabel.text = getString(R.string.status_idle)
            appendLog("System", "Didn't catch any recognizable speech — try again, closer to the mic.")
            return
        }
        appendLog("You", heard)
        processor.handle(heard)
    }

    private fun appendLog(who: String, text: String) {
        logText.append("\n\n$who: $text")
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error — check the microphone."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client error — try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout."
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy — try again in a moment."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again."
            else -> "Speech recognition error (code $error)."
        }
        Log.w(TAG, "SpeechRecognizer error: $message (code=$error)")
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            appendLog("System", message)
            orb.setState(OrbState.ERROR)
        }
        statusLabel.text = getString(R.string.status_idle)
        orb.postDelayed({ orb.setState(if (wakeWordOn) OrbState.WAKE_ACTIVE else OrbState.IDLE) }, 900)
    }
    override fun onReadyForSpeech(params: Bundle?) {
        statusLabel.text = getString(R.string.status_listening)
        orb.setState(OrbState.LISTENING)
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {
        orb.setAudioLevel(rmsdB)
    }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        statusLabel.text = getString(R.string.status_thinking)
        orb.setState(OrbState.THINKING)
        orb.setAudioLevel(0f)
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        speechRecognizer?.destroy()
        voiceEngine.removeSpeakingStateListener(speakingListener)
        super.onDestroy()
    }
}
