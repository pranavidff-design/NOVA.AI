package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.memory.MemoryManager
import kotlinx.coroutines.launch

/**
 * Real Android foreground service — lets "Hey Nova" keep working when the app
 * isn't the one on screen.
 *
 * STATE MACHINE (fixes the "ting every 1-3s" + "no response after wake word" bugs):
 *   WAKE_LISTENING (background) -> WAKE_DETECTED -> COMMAND_LISTENING ->
 *   PROCESSING (AI thinking) -> SPEAKING (TTS) -> back to WAKE_LISTENING.
 * The wake-word mic is explicitly paused during PROCESSING and SPEAKING (via
 * WakeWordListener.pauseForProcessing()) and only resumed once voiceEngine
 * reports speaking has actually finished — never restarted early, and never
 * left running while Nova is talking (which used to let her hear herself).
 *
 * HONEST LIMITS (unchanged from before, still real):
 * - Foreground-while-requested, not truly unkillable — aggressive OEM battery
 *   managers (Xiaomi/MIUI, Oppo/ColorOS, Vivo) can still kill it.
 * - Requires the persistent notification — an Android platform requirement for
 *   any mic-using foreground service, not something this app can hide.
 * - Built on the same one-shot SpeechRecognizer as tap-to-talk, not a dedicated
 *   low-power wake-word chip — uses meaningfully more battery than one would.
 */
class NovaWakeService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "nova_wake_channel"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.nova.assistant.action.STOP_WAKE"

        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NovaWakeService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NovaWakeService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var memory: MemoryManager
    private lateinit var brain: NovaBrain
    private lateinit var voiceEngine: NovaVoiceEngine
    private lateinit var processor: CommandProcessor
    private var wakeWordListener: WakeWordListener? = null

    private val speakingListener: (Boolean) -> Unit = { isSpeaking ->
        if (isSpeaking) {
            wakeWordListener?.pauseForProcessing()
        } else {
            wakeWordListener?.playCue(ascending = false) // ONE completion sound
            updateNotification("Listening for \"Hey Nova\"…")
            wakeWordListener?.resumeListening()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as NovaApp
        memory = app.memory
        voiceEngine = app.voiceEngine
        brain = app.brain
        app.ensureBrainInitialized { /* if it fails, ask() will just say so — handled per-question */ }
        voiceEngine.addSpeakingStateListener(speakingListener)

        processor = CommandProcessor(
            context = this,
            memory = memory,
            brain = brain,
            voiceEngine = voiceEngine,
            scope = lifecycleScope,
            onLog = { who, text -> NovaEventBus.log(who, text) },
            onStatus = { status -> NovaEventBus.status(status) },
            requestApproval = { actionLabel, onDecision ->
                SensitiveActionReceiver.requestApproval(this, actionLabel, onDecision)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            wakeWordListener?.stop()
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        startForegroundWithNotification("Listening for \"Hey Nova\"…")
        startWakeListening()
        return START_STICKY
    }

    private fun startWakeListening() {
        if (wakeWordListener != null) return
        wakeWordListener = WakeWordListener(
            context = this,
            onWakeDetected = { updateNotification("Yes? I'm listening…") },
            onCommandHeard = { command ->
                if (command.isBlank()) {
                    NovaEventBus.log("System", "Didn't catch that — say \"Hey Nova\" again.")
                    updateNotification("Listening for \"Hey Nova\"…")
                    wakeWordListener?.resumeListening()
                    return@WakeWordListener
                }
                NovaEventBus.log("You", command)
                NovaEventBus.status("thinking")
                updateNotification("Thinking…")
                lifecycleScope.launch { processor.handle(command) }
                // Deliberately NOT resuming wake-listening here — speakingListener
                // above resumes it only once the spoken reply has actually finished,
                // so Nova never hears herself and never overlaps two sessions.
            },
            onFatalError = { reason ->
                NovaEventBus.log("System", reason)
                updateNotification(reason)
                stopSelf()
            }
        )
        wakeWordListener?.start()
    }

    private fun startForegroundWithNotification(text: String) {
        ensureChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, NovaWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nova wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        isRunning = false
        wakeWordListener?.stop()
        voiceEngine.removeSpeakingStateListener(speakingListener)
        super.onDestroy()
    }
}
