package com.nova.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig

/**
 * CloudBrain — Nova's PRIMARY brain when online. Uses Firebase AI Logic to call
 * Google's Gemini API DIRECTLY from the app, with NO custom backend to write,
 * host, or pay to keep running.
 *
 * How the API key stays safe with no backend of our own: Firebase AI Logic is
 * Google's own client SDK for exactly this. Every call from this SDK is proxied
 * through Firebase's servers — the real Gemini API key lives on Google's side,
 * never inside this app or this repo. `app/google-services.json` (downloaded
 * from the Firebase console) is a config pointer, not a secret — it's meant to
 * ship inside apps. See README "Cloud brain setup" for the exact console steps.
 *
 * Why Gemini (evaluated against OpenAI and others, Aug 2026):
 * - Genuinely free tier, no credit card: Flash/Flash-Lite give roughly
 *   10-15 requests/minute and ~1000-1500 requests/day — comfortably enough
 *   for one person's voice assistant, and it never expires (unlike a trial credit).
 * - Flash is built for low-latency chat, not deep reasoning — matters here
 *   because every extra second is felt while the user is waiting to hear a reply.
 * - Firebase AI Logic (GA as of I/O 2026) is the only path we found that needs
 *   ZERO server hosting from a phone-only user — the earlier "write your own
 *   backend" plan (e.g. a Cloudflare Worker) is no longer necessary.
 * Tradeoff: needs internet, and free-tier terms allow Google to use free-tier
 * prompts to improve their models (documented in the README, not hidden).
 *
 * Not configured (Firebase not set up / google-services.json missing) or the
 * call fails for any reason -> ask() returns null so NovaBrain falls back to
 * LocalBrain, never a hard crash.
 */
class CloudBrain(private val context: Context) {

    companion object {
        private const val TAG = "CloudBrain"
        private const val MODEL_NAME = "gemini-2.5-flash"
    }

    var lastError: String? = null
        private set

    /** True once google-services.json was present at build time and Firebase
     *  actually initialized — this is how we know "is the cloud brain set up at all". */
    val isConfigured: Boolean
        get() = try {
            FirebaseApp.getInstance()
            true
        } catch (e: IllegalStateException) {
            false
        }

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 200 // short spoken replies only
            }
        )
    }

    fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Returns the reply text, or null if not configured / offline / errored
     *  (check lastError for the real reason) — caller falls back to LocalBrain on null. */
    suspend fun ask(systemPrompt: String, userText: String, contextBlock: String): String? {
        if (!isConfigured) {
            lastError = "Cloud brain not set up — app/google-services.json is missing or invalid. See README \"Cloud brain setup\"."
            return null
        }
        if (!hasInternet()) {
            lastError = "No internet connection right now."
            return null
        }
        return try {
            val fullPrompt = buildString {
                append(systemPrompt)
                if (contextBlock.isNotBlank()) { append("\n\n"); append(contextBlock) }
                append("\n\nUser: $userText")
            }
            val response = model.generateContent(content { text(fullPrompt) })
            val reply = response.text?.trim().orEmpty()
            if (reply.isBlank()) {
                lastError = "Gemini returned an empty reply."
                return null
            }
            lastError = null
            reply
        } catch (e: Exception) {
            lastError = "Cloud ask() failed: ${e.javaClass.simpleName} — ${e.message ?: "no further detail"}"
            Log.e(TAG, "ask() failed", e)
            null
        }
    }
}
