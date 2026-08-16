package com.nova.assistant

import android.content.Context
import android.util.Log

/**
 * NovaBrain — the hybrid orchestrator. Decides per-question whether to use the
 * cloud brain (smarter, needs internet, configured by the user) or fall back to
 * the local brain (dumber but always available). Neither CommandProcessor nor
 * anything else in the app needs to know which one actually answered.
 *
 * Order per call:
 *   1. If CloudBrain is configured (a backend URL is saved) AND internet looks
 *      available -> try cloud first.
 *   2. If that fails for ANY reason (not configured, offline, timeout, backend
 *      error) -> fall back to LocalBrain, if its model is loaded.
 *   3. If neither worked -> one honest, specific error message (never a fake
 *      "I don't know" that hides which of the two paths actually failed).
 */
class NovaBrain(private val context: Context) {

    companion object { private const val TAG = "NovaBrain" }

    private val local = LocalBrain(context)
    private val cloud = CloudBrain(context)

    val isReady: Boolean get() = local.isReady // "ready" = local fallback ready; cloud is checked live per-call
    val isCloudConfigured: Boolean get() = cloud.isConfigured

    var lastError: String? = null
        private set
    /** Which brain answered the most recent question — surfaced in the UI log for transparency. */
    var lastAnsweredBy: String? = null
        private set

    private val systemPrompt = """
        You are Nova, a warm, capable personal voice assistant for an Indian user.
        Your DEFAULT language is natural Hinglish (Hindi + English mixed, written in
        Roman script) — this is the normal, expected way to reply, not a special mode.
        Example: user says "Nova, kal mujhe kitne baje uthna chahiye?" and you reply
        naturally like "Haan, agar tumhe subah 7 baje uthna hai toh around 10:30-11
        baje tak so jaana better rahega." Only switch fully to plain English or
        formal Hindi if the user clearly does so first and keeps doing it.
        Keep replies short (1-3 sentences) since they are spoken aloud, not read.
        Never claim to have done something you weren't actually told was executed.
    """.trimIndent()

    // Topics neither brain can answer accurately without live data access (Gemini via
    // this simple text API has no browsing/search grounding wired up here, and the
    // local model is fully offline). Rather than guess, Nova says so honestly.
    private val needsInternetKeywords = listOf(
        "weather", "today's news", "latest news", "current price", "score today",
        "kal ka mausam", "aaj ka mausam"
    )

    fun copyPickedModelFile(uri: android.net.Uri, onDone: (Boolean) -> Unit) = local.copyPickedModelFile(uri, onDone)

    /** Exposed so other components (e.g. RuleTeacher, for parsing taught rules)
     *  can ask Gemini something directly without going through the full
     *  local-fallback ask() flow meant for spoken conversation. Null if the
     *  cloud brain isn't configured/online — caller must have its own fallback. */
    suspend fun askCloudDirect(systemPrompt: String, userText: String): String? =
        cloud.ask(systemPrompt, userText, "")

    fun initialize(onReady: (Boolean) -> Unit) {
        // Local model load attempt always runs (even if cloud is configured) so
        // there's a working offline fallback the moment internet drops.
        local.initialize { ready ->
            lastError = local.lastError
            onReady(ready)
        }
    }

    suspend fun ask(userText: String, contextBlock: String): String {
        val lower = userText.lowercase()
        if (needsInternetKeywords.any { lower.contains(it) } && !cloud.isConfigured) {
            return "I can't check that live — I'm running offline-only right now. Set up the cloud brain (see README) or connect a weather/news source later."
        }

        val prompt = buildString {
            append(systemPrompt)
            if (contextBlock.isNotBlank()) { append("\n\n"); append(contextBlock) }
            append("\n\nUser: $userText\nNova:")
        }

        if (cloud.isConfigured) {
            val cloudReply = cloud.ask(systemPrompt, userText, contextBlock)
            if (cloudReply != null) {
                lastAnsweredBy = "cloud"
                lastError = null
                return cloudReply
            }
            Log.w(TAG, "Cloud brain failed (${cloud.lastError}), falling back to local.")
        }

        val localReply = local.ask(prompt)
        if (localReply != null) {
            lastAnsweredBy = "local"
            lastError = null
            return localReply
        }

        lastAnsweredBy = null
        lastError = when {
            cloud.isConfigured -> "Both cloud (${cloud.lastError}) and local (${local.lastError}) brains failed."
            else -> local.lastError ?: "Local model isn't loaded — tap 'Load AI Model', or set up the cloud brain in Settings."
        }
        Log.e(TAG, lastError!!)
        return "I couldn't think that through right now — ${if (cloud.isConfigured) "both my cloud and local brains failed" else "my local model isn't ready"}. Check the log for the real reason."
    }

    fun shutdown() = local.shutdown()
}
