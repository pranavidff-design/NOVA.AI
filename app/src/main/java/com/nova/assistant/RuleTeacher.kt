package com.nova.assistant

import android.util.Log
import org.json.JSONObject

/**
 * Turns a free-text rule the user types (e.g. "Whenever I say 'ghar aa gaya hoon',
 * turn mobile data off and Wi-Fi on") into a structured trigger phrase + action list
 * that RoutineEngine can already execute — this is the ONLY new piece; everything
 * downstream (matching, cooldown, execution, transparency log, enable/disable/delete)
 * reuses the existing RoutineEntity/RoutineEngine/RoutinesActivity machinery as-is.
 *
 * Two parse paths, tried in order:
 *  1. Cloud (Gemini via NovaBrain.askCloudDirect) — handles natural, messy,
 *     Hinglish phrasing well. Used when online.
 *  2. Local heuristic fallback — simple keyword/quote matching, works offline,
 *     covers the common cases (flashlight, Wi-Fi panel, mobile data settings,
 *     volume, opening apps, searching) but won't understand very free-form phrasing.
 * Never invents an action outside RoutineAction's real vocabulary — see that file
 * for exactly what Nova can and cannot actually do (e.g. mobile data can only be
 * opened to a settings screen, never silently toggled — Android doesn't allow that
 * for a normal app).
 */
class RuleTeacher(private val brain: NovaBrain) {

    data class ParsedRule(val triggerPhrase: String, val actions: List<RoutineAction>) {
        fun describe(): String = actions.joinToString(", ") { it.describe() }
    }

    private val actionVocab = """
        OPEN_CALCULATOR, OPEN_MAPS, OPEN_BROWSER, OPEN_EMAIL, OPEN_SETTINGS, OPEN_WHATSAPP,
        FLASHLIGHT_ON, FLASHLIGHT_OFF, VOLUME_UP, VOLUME_DOWN, OPEN_WIFI_PANEL,
        OPEN_MOBILE_DATA_SETTINGS, WEB_SEARCH:<query>, OPEN_APP_PKG:<app name>
    """.trimIndent()

    /** Returns null if nothing usable could be extracted from ruleText — caller
     *  should tell the user plainly rather than saving a broken/empty rule. */
    suspend fun parse(ruleText: String): ParsedRule? {
        tryCloudParse(ruleText)?.let { return it }
        return tryLocalParse(ruleText)
    }

    private suspend fun tryCloudParse(ruleText: String): ParsedRule? {
        val systemPrompt = """
            You convert a user's automation rule into strict JSON. The rule describes:
            WHEN the user says some trigger phrase, THEN do one or more actions.
            The ONLY action types that exist (never invent others) are:
            $actionVocab
            Respond with ONLY this JSON, nothing else, no markdown fences:
            {"trigger": "<short trigger phrase the user would actually SAY, lowercase, no quotes>", "actions": ["TYPE1", "TYPE2:param"]}
            If a requested action has no real match in the list above, leave it out of
            "actions" rather than guessing — do not invent a new type.
            If NOTHING in the rule maps to a real action, respond {"trigger": "", "actions": []}.
        """.trimIndent()

        val raw = try {
            brain.askCloudDirect(systemPrompt, "Rule: $ruleText")
        } catch (e: Exception) {
            Log.w("RuleTeacher", "Cloud parse failed: ${e.message}")
            null
        } ?: return null

        return try {
            val jsonText = raw.substringAfter("{", "").let { "{" + it.substringBeforeLast("}", "") + "}" }
            val json = JSONObject(jsonText)
            val trigger = json.optString("trigger").trim()
            val actionsArray = json.optJSONArray("actions")
            if (trigger.isBlank() || actionsArray == null || actionsArray.length() == 0) return null
            val actions = (0 until actionsArray.length()).mapNotNull { RoutineAction.parseOne(actionsArray.getString(it)) }
            if (actions.isEmpty()) return null
            ParsedRule(LocalCommandRouter.normalize(trigger), actions)
        } catch (e: Exception) {
            Log.w("RuleTeacher", "Couldn't parse Gemini's JSON: ${e.message} — raw: $raw")
            null
        }
    }

    /** Offline fallback: pulls a quoted trigger phrase (or the text before "turn on"
     *  style action keywords) and keyword-matches known actions. Deliberately
     *  conservative — only claims an action when a clear keyword is present. */
    private fun tryLocalParse(ruleText: String): ParsedRule? {
        val quoteMatch = Regex("['\"]([^'\"]+)['\"]").find(ruleText)
        val trigger = quoteMatch?.groupValues?.get(1)?.trim()
            ?: ruleText.substringAfter("say ", "").substringBefore(",").trim().ifBlank { null }
            ?: return null
        if (trigger.isBlank()) return null

        val lower = ruleText.lowercase()
        val actions = mutableListOf<RoutineAction>()
        if (Regex("flashlight (on|off)|torch (on|off)").containsMatchIn(lower)) {
            actions.add(if (lower.contains("flashlight off") || lower.contains("torch off")) RoutineAction.FlashlightOff else RoutineAction.FlashlightOn)
        }
        if (lower.contains("wifi") || lower.contains("wi-fi")) actions.add(RoutineAction.OpenWifiPanel)
        if (lower.contains("mobile data") || lower.contains("data off") || lower.contains("data on")) actions.add(RoutineAction.OpenMobileDataSettings)
        if (lower.contains("volume up")) actions.add(RoutineAction.VolumeUp)
        if (lower.contains("volume down")) actions.add(RoutineAction.VolumeDown)
        if (lower.contains("whatsapp")) actions.add(RoutineAction.OpenWhatsApp)
        if (lower.contains("calculator")) actions.add(RoutineAction.OpenCalculator)
        if (lower.contains("maps")) actions.add(RoutineAction.OpenMaps)
        if (lower.contains("browser")) actions.add(RoutineAction.OpenBrowser)
        if (lower.contains("settings")) actions.add(RoutineAction.OpenSettings)

        if (actions.isEmpty()) return null
        return ParsedRule(LocalCommandRouter.normalize(trigger), actions)
    }
}
