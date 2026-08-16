package com.nova.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LocalBrain — runs entirely ON-DEVICE via Google's MediaPipe LLM Inference API,
 * using a local Gemma 3 1B model file (~555MB .task, NOT the "-web" variant).
 * This is Nova's offline fallback: free forever, fully private, works with zero
 * signal — but noticeably less sharp than the cloud brain on complex reasoning.
 *
 * Why Gemma 3 1B specifically: Google has put this MediaPipe LLM Inference API
 * into "maintenance-only mode"; newer models (Gemma 4, Gemma 3n) ship primarily
 * as .litertlm files for a separate LiteRT-LM library. Gemma 3 1B is the
 * largest/newest model still confirmed to ship a genuine (non-web) .task file
 * compatible with this exact dependency.
 *
 * API shape: LlmInference (the "engine") is created ONCE from the model file and
 * only accepts engine-level options. Actual sampling controls (setTopK,
 * setTemperature) belong to a separate LlmInferenceSession created per turn —
 * mixing these up is a compile error, not a runtime one.
 *
 * generateResponse() is a BLOCKING call — this class never runs it on the
 * caller's thread; ask() always hops to Dispatchers.Default internally, so
 * callers (CommandProcessor) never need to remember to do that themselves.
 */
class LocalBrain(private val context: Context) {

    companion object { private const val TAG = "LocalBrain" }

    private var llmInference: LlmInference? = null
    private var modelReady = false
    val isReady: Boolean get() = modelReady

    var lastError: String? = null
        private set

    private var modelPath = "${context.getExternalFilesDir(null)}/models/gemma3-1b-it-int4.task"

    fun copyPickedModelFile(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        try {
            val destDir = File("${context.getExternalFilesDir(null)}/models")
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, "gemma3-1b-it-int4.task")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            modelPath = destFile.absolutePath
            onDone(true)
        } catch (e: Exception) {
            lastError = "Couldn't copy the picked file: ${e.javaClass.simpleName} — ${e.message ?: "no further detail"}"
            Log.e(TAG, "copyPickedModelFile() failed", e)
            onDone(false)
        }
    }

    fun initialize(onReady: (Boolean) -> Unit) {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            lastError = "Local model file not found at $modelPath. Download the .task file and load it via 'Load AI Model' — see README. (Nova can still work using the cloud brain if you've set that up.)"
            Log.w(TAG, lastError!!)
            onReady(false)
            return
        }
        try {
            val engineOptions = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(256) // short spoken replies only — keeps local inference fast
                .setMaxTopK(64)
                .build()
            llmInference = LlmInference.createFromOptions(context, engineOptions)
            modelReady = true
            lastError = null
            onReady(true)
        } catch (e: Exception) {
            modelReady = false
            lastError = "Local model failed to load: ${e.javaClass.simpleName} — ${e.message ?: "no further detail from MediaPipe"}"
            Log.e(TAG, "initialize() failed", e)
            onReady(false)
        }
    }

    /** Suspends; internally runs the blocking MediaPipe call off the caller's thread. */
    suspend fun ask(fullPrompt: String): String? = withContext(Dispatchers.Default) {
        val engine = llmInference
        if (!modelReady || engine == null) {
            lastError = "Local model isn't loaded."
            return@withContext null
        }
        var session: LlmInferenceSession? = null
        try {
            val sessionOptions = LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.7f)
                .build()
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
            session.addQueryChunk(fullPrompt)
            val result = session.generateResponse()
            lastError = null
            result.trim()
        } catch (e: Exception) {
            lastError = "Local ask() failed: ${e.javaClass.simpleName} — ${e.message ?: "no further detail"}"
            Log.e(TAG, "ask() failed", e)
            null
        } finally {
            session?.close()
        }
    }

    fun shutdown() {
        llmInference?.close()
    }
}
