package com.nova.assistant

import com.nova.assistant.memory.HabitObservationDao
import com.nova.assistant.memory.HabitObservationEntity
import com.nova.assistant.memory.RoutineDao
import com.nova.assistant.memory.RoutineEntity

/**
 * Watches for repeated (trigger phrase -> sequence of actions) patterns in the
 * user's OWN spoken commands and, once the same pattern repeats enough times,
 * creates a routine SUGGESTION — never an active routine. Nova only starts
 * running it after the user explicitly says yes (see RoutinesActivity /
 * CommandProcessor's pending-suggestion flow).
 *
 * What this deliberately does NOT do, by design (per the "no secret profiling"
 * requirement):
 *  - It never looks at anything other than commands Nova already processed —
 *    no reading notifications, no tracking app usage, no location, no
 *    background sensors.
 *  - It only opens a "window" after an utterance that looks like a short,
 *    non-question statement (e.g. "I'm home") — ordinary questions and chit-chat
 *    never start a tracked window.
 *  - A pattern only becomes visible to the user as a suggestion; it's never
 *    silently promoted to an active routine.
 */
class HabitAnalyzer(
    private val routineDao: RoutineDao,
    private val habitDao: HabitObservationDao
) {
    companion object {
        private const val WINDOW_TIMEOUT_MS = 3 * 60 * 1000L
        private const val MAX_ACTIONS_PER_WINDOW = 4
        private const val OBSERVATION_THRESHOLD = 3 // same trigger+sequence repeated this many times
    }

    private data class Window(val triggerText: String, val actions: MutableList<RoutineAction>, val openedAt: Long)
    private var window: Window? = null

    /**
     * Call once per recognized utterance, after CommandProcessor already knows
     * whether it matched a structured local action (classifyAction result) or
     * fell through elsewhere (null). Returns a freshly-created suggestion the
     * FIRST time a pattern crosses the repeat threshold — null on every other
     * (i.e. almost every) turn, which the caller just ignores.
     */
    suspend fun observe(text: String, actionSpec: RoutineAction?): RoutineEntity? {
        val now = System.currentTimeMillis()
        val current = window

        if (actionSpec != null) {
            // This utterance WAS an action — if a window's open and still fresh,
            // it's part of that sequence. If not, it's just an ordinary one-off
            // command with no preceding trigger phrase — nothing to attach it to.
            if (current != null && now - current.openedAt <= WINDOW_TIMEOUT_MS &&
                current.actions.size < MAX_ACTIONS_PER_WINDOW
            ) {
                current.actions.add(actionSpec)
            }
            return null
        }

        // Not a structured action -> close out whatever window was open (recording
        // it as one observation), then possibly start a new window on THIS utterance.
        var suggestion: RoutineEntity? = null
        if (current != null) {
            suggestion = finalizeWindow(current)
        }
        window = if (looksLikeTriggerCandidate(text)) Window(text, mutableListOf(), now) else null
        return suggestion
    }

    /** Short, non-question statements only — "I'm home", "good morning", "leaving now".
     *  Questions ("what's the weather") and long sentences never open a window. */
    private fun looksLikeTriggerCandidate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val words = trimmed.split(Regex("\\s+"))
        if (words.size > 8) return false
        val lower = trimmed.lowercase()
        if (lower.contains("?")) return false
        val questionStarters = listOf(
            "what", "why", "how", "when", "who", "where",
            "is ", "are ", "can ", "could ", "does ", "do ", "will ",
            "tell me", "explain"
        )
        if (questionStarters.any { lower.startsWith(it) }) return false
        return true
    }

    private suspend fun finalizeWindow(w: Window): RoutineEntity? {
        if (w.actions.isEmpty()) return null

        val normalizedTrigger = LocalCommandRouter.normalize(w.triggerText)
        val signature = w.actions.map { it.type }.sorted().joinToString(",")
        val fullSerialized = RoutineAction.serializeList(w.actions)

        habitDao.insert(
            HabitObservationEntity(
                normalizedTrigger = normalizedTrigger,
                actionsSignature = signature,
                actionsFull = fullSerialized,
                observedAt = System.currentTimeMillis()
            )
        )

        // Already taught or already suggested for this exact trigger — just bump the
        // repeat count instead of creating a duplicate suggestion.
        val existing = routineDao.findByTrigger(normalizedTrigger)
        if (existing != null) {
            if (sameSignature(existing.actions, signature)) {
                routineDao.update(existing.copy(timesObserved = existing.timesObserved + 1))
            }
            return null
        }

        val recent = habitDao.recentForTrigger(normalizedTrigger)
        val matchingCount = recent.count { it.actionsSignature == signature }
        if (matchingCount >= OBSERVATION_THRESHOLD) {
            val newRoutine = RoutineEntity(
                triggerPhrase = normalizedTrigger,
                actions = fullSerialized,
                isUserTaught = false,
                isApproved = false,
                isActive = true,
                timesObserved = matchingCount
            )
            val id = routineDao.insert(newRoutine)
            return newRoutine.copy(id = id)
        }
        return null
    }

    private fun sameSignature(storedActions: String, signature: String): Boolean {
        val storedSig = RoutineAction.parseList(storedActions).map { it.type }.sorted().joinToString(",")
        return storedSig == signature
    }
}
