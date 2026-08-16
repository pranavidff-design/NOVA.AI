package com.nova.assistant

import android.content.Context
import com.nova.assistant.memory.RoutineDao
import com.nova.assistant.memory.RoutineEntity
import com.nova.assistant.memory.RoutineExecutionDao
import com.nova.assistant.memory.RoutineExecutionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Matches recognized speech against approved, active routines and runs their
 * saved action sequence in order. A routine only exists here after a human
 * explicitly approved it (HabitAnalyzer suggestion + "yes", or manually taught) —
 * nothing "learns its way" into running automatically without that step.
 */
class RoutineEngine(
    context: Context,
    private val routineDao: RoutineDao,
    private val executionDao: RoutineExecutionDao,
    private val scope: CoroutineScope
) {
    private val executor = ActionExecutor(context)

    /** Returns a spoken reply if `text` matched an approved routine — null if
     *  nothing matched, meaning the caller should keep routing normally. */
    suspend fun tryHandle(
        text: String,
        requestApproval: (String, (Boolean) -> Unit) -> Unit
    ): String? {
        val normalized = LocalCommandRouter.normalize(text)
        val routines = routineDao.getApprovedActive()
        val match = routines.firstOrNull {
            normalized.contains(it.triggerPhrase) || it.triggerPhrase.contains(normalized)
        } ?: return null

        val cooldownMs = match.cooldownMinutes * 60_000L
        val sinceLast = System.currentTimeMillis() - match.lastExecutedAt
        if (match.lastExecutedAt > 0 && sinceLast < cooldownMs) {
            val waitMin = ((cooldownMs - sinceLast) / 60_000L) + 1
            return "I already ran \"${match.triggerPhrase}\" recently — give it about $waitMin more minute${if (waitMin == 1L) "" else "s"}."
        }

        if (match.requiresConfirmation) {
            val actionList = RoutineAction.parseList(match.actions).joinToString(", ") { it.describe() }
            requestApproval("run your \"${match.triggerPhrase}\" routine ($actionList)") { approved ->
                if (approved) {
                    // requestApproval resolves asynchronously (dialog or notification tap,
                    // possibly seconds/minutes later) — run the actual routine on the same
                    // scope CommandProcessor already uses, not a fire-and-forget global one.
                    scope.launch(Dispatchers.IO) { runRoutine(match) }
                }
            }
            return "Let me check with you first — routine \"${match.triggerPhrase}\" needs confirmation."
        }

        return runRoutine(match)
    }

    private suspend fun runRoutine(routine: RoutineEntity): String {
        val actions = RoutineAction.parseList(routine.actions)
        val results = mutableListOf<String>()
        var allOk = true
        for (action in actions) {
            val result = try {
                if (action is RoutineAction.WhatsAppTo) {
                    // A routine can never pre-authorize sending a message — the contact
                    // may have changed, and this always needs a LIVE approval tap, so
                    // it's deliberately not auto-run from inside a routine.
                    "skipped the WhatsApp step to ${action.param.substringBefore('|')} — say that one directly so I can get your live approval"
                } else {
                    action.execute(executor)
                }
            } catch (e: Exception) {
                allOk = false
                "couldn't ${action.describe()} (${e.message ?: "unknown error"})"
            }
            results.add(result)
        }
        routineDao.markExecuted(routine.id, System.currentTimeMillis())
        executionDao.insert(
            RoutineExecutionEntity(
                routineId = routine.id,
                executedAt = System.currentTimeMillis(),
                success = allOk,
                note = results.joinToString(" / ")
            )
        )
        return "Running your \"${routine.triggerPhrase}\" routine: " + results.joinToString(" ")
    }
}
