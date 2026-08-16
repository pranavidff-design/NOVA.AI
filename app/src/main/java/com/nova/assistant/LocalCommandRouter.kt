package com.nova.assistant

import android.content.Context

/**
 * Routes simple commands to real Android actions — free, instant, no AI call needed.
 * Sensitive actions (camera) are flagged here but the actual approval dialog + execution
 * happens in the caller (Activity dialog, or a notification if backgrounded), since only
 * an Activity can show a normal dialog.
 *
 * IntentMatcher normalization means phrasing no longer has to match exactly —
 * "Open calculator.", "Can you open the calculator?", "Nova, I need the
 * calculator.", and "Launch calculator." all reduce to the same normalized
 * form before matching. This is honest keyword/synonym normalization, not a
 * real language-understanding model — it won't handle genuinely novel phrasing,
 * but it removes the "must say the exact magic words" brittleness.
 */
class LocalCommandRouter(private val context: Context) {

    private val executor = ActionExecutor(context)

    sealed class RouteResult {
        data class Executed(val message: String) : RouteResult()
        data class NeedsApproval(val actionLabel: String, val onApproved: () -> String) : RouteResult()
    }

    enum class ControlType { PAUSE, RESUME }

    companion object {
        private val pausePattern = Regex("^(nova,? )?(pause|stop listening)\\.?$", RegexOption.IGNORE_CASE)
        private val resumePattern = Regex("^(nova,? )?(resume|start listening|wake up)\\.?$", RegexOption.IGNORE_CASE)

        fun matchControl(text: String): ControlType? {
            val trimmed = text.trim()
            return when {
                pausePattern.matches(trimmed) -> ControlType.PAUSE
                resumePattern.matches(trimmed) -> ControlType.RESUME
                else -> null
            }
        }

        /** Strips politeness/filler words and normalizes near-synonyms so different
         *  phrasings of the same request converge before regex matching runs. */
        fun normalize(text: String): String {
            var t = " ${text.lowercase().trim()} "
            val fillers = listOf(
                "hey nova,", "hey nova", "nova,", "nova ",
                "can you ", "could you ", "would you ", "please ",
                "i need to ", "i need ", "i want to ", "i want ",
                "i'd like to ", "for me "
            )
            fillers.forEach { t = t.replace(" $it", " ").replace("^${Regex.escape(it)}".toRegex(), "") }
            val synonyms = mapOf(
                "launch" to "open", "start" to "open", "fire up" to "open",
                "what's" to "what is", "whats" to "what is"
            )
            synonyms.forEach { (from, to) -> t = t.replace(from, to) }
            return t.trim().trimEnd('.', '?', '!')
        }
    }

    fun tryHandle(text: String): RouteResult? {
        val lower = normalize(text)

        if (Regex("what is the time|current time").containsMatchIn(lower)) {
            val time = java.text.SimpleDateFormat("h:mm a").format(java.util.Date())
            return RouteResult.Executed("It's $time.")
        }

        if (Regex("open calculator").containsMatchIn(lower)) return RouteResult.Executed(executor.openCalculator())
        if (Regex("open maps|navigate").containsMatchIn(lower)) return RouteResult.Executed(executor.openMaps())
        if (Regex("open browser|open chrome").containsMatchIn(lower)) return RouteResult.Executed(executor.openBrowser())
        if (Regex("open (email|mail)").containsMatchIn(lower)) return RouteResult.Executed(executor.openEmail())
        if (Regex("open settings").containsMatchIn(lower)) return RouteResult.Executed(executor.openSettings())
        if (Regex("open whatsapp").containsMatchIn(lower)) return RouteResult.Executed(executor.openWhatsApp())

        if (Regex("flashlight on|torch on").containsMatchIn(lower)) return RouteResult.Executed(executor.setFlashlight(true))
        if (Regex("flashlight off|torch off").containsMatchIn(lower)) return RouteResult.Executed(executor.setFlashlight(false))

        if (Regex("volume up").containsMatchIn(lower)) return RouteResult.Executed(executor.adjustVolume(true))
        if (Regex("volume down").containsMatchIn(lower)) return RouteResult.Executed(executor.adjustVolume(false))

        if (Regex("open camera|take a photo|take a picture").containsMatchIn(lower)) {
            return RouteResult.NeedsApproval("open the Camera") { executor.openCameraApp() }
        }

        val alarmMatch = Regex("alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(lower)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toInt()
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = alarmMatch.groupValues[3].lowercase()
            if (ampm == "pm" && hour != 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return RouteResult.Executed(executor.setAlarm(hour, minute))
        }

        val calendarMatch = Regex("(?:add to calendar|create (?:an? )?event)[: ]+(.+)", RegexOption.IGNORE_CASE).find(lower)
        if (calendarMatch != null) {
            return RouteResult.Executed(executor.createCalendarEvent(calendarMatch.groupValues[1].trim()))
        }

        val searchMatch = Regex("(?:search(?: the web)? for|google) (.+)", RegexOption.IGNORE_CASE).find(lower)
        if (searchMatch != null) {
            return RouteResult.Executed(executor.webSearch(searchMatch.groupValues[1].trim()))
        }

        // Generic fallback: "open <anything else installed>" (Spotify, Instagram, etc.)
        // Placed last so the friendlier specific handlers above always win first.
        val openAnyMatch = Regex("^open (.+)$").find(lower)
        if (openAnyMatch != null) {
            val appName = openAnyMatch.groupValues[1].trim()
            return RouteResult.Executed(executor.openAppByName(appName))
        }

        return null // unmatched -> goes to the AI model (or WhatsApp-send / routines, handled in CommandProcessor)
    }

    /** Mirrors tryHandle's matching but returns a structured, replayable RoutineAction
     *  instead of executing anything — used only by HabitAnalyzer to record what
     *  happened, never to trigger a second execution of the same command. */
    fun classifyAction(text: String): RoutineAction? {
        val lower = normalize(text)
        return when {
            Regex("open calculator").containsMatchIn(lower) -> RoutineAction.OpenCalculator
            Regex("open maps|navigate").containsMatchIn(lower) -> RoutineAction.OpenMaps
            Regex("open browser|open chrome").containsMatchIn(lower) -> RoutineAction.OpenBrowser
            Regex("open (email|mail)").containsMatchIn(lower) -> RoutineAction.OpenEmail
            Regex("open settings").containsMatchIn(lower) -> RoutineAction.OpenSettings
            Regex("open whatsapp").containsMatchIn(lower) -> RoutineAction.OpenWhatsApp
            Regex("flashlight on|torch on").containsMatchIn(lower) -> RoutineAction.FlashlightOn
            Regex("flashlight off|torch off").containsMatchIn(lower) -> RoutineAction.FlashlightOff
            Regex("volume up").containsMatchIn(lower) -> RoutineAction.VolumeUp
            Regex("volume down").containsMatchIn(lower) -> RoutineAction.VolumeDown
            else -> {
                val searchMatch = Regex("(?:search(?: the web)? for|google) (.+)", RegexOption.IGNORE_CASE).find(lower)
                if (searchMatch != null) return RoutineAction.WebSearch(searchMatch.groupValues[1].trim())
                val openAnyMatch = Regex("^open (.+)$").find(lower)
                if (openAnyMatch != null) return RoutineAction.OpenAppByPackage(openAnyMatch.groupValues[1].trim())
                null
            }
        }
    }
}
