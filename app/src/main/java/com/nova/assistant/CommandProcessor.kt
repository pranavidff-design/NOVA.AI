package com.nova.assistant

import android.content.Context
import android.util.Log
import com.nova.assistant.memory.MemoryManager
import com.nova.assistant.memory.RoutineEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns a recognized utterance into an action + spoken reply. Used identically
 * by MainActivity (tap-to-talk / foreground) and NovaWakeService (background
 * "Hey Nova" wake word), so a command behaves the same no matter which path
 * triggered it.
 *
 * Routing order per turn: pause/resume control -> pending routine-suggestion
 * yes/no -> memory commands -> WhatsApp send -> approved routine trigger ->
 * local device commands -> AI model fallback. Habit pattern-tracking (see
 * HabitAnalyzer) runs alongside the last two steps, never on its own.
 */
class CommandProcessor(
    private val context: Context,
    private val memory: MemoryManager,
    private val brain: NovaBrain,
    private val voiceEngine: NovaVoiceEngine,
    private val scope: CoroutineScope,
    private val onLog: (who: String, text: String) -> Unit = { _, _ -> },
    private val onStatus: (String) -> Unit = {},
    /** Shows a real approval prompt for a sensitive action (camera, WhatsApp send,
     *  a confirmation-required routine). From an Activity this is an AlertDialog
     *  (see PermissionGate); from the background service it's a notification with
     *  Approve/Deny actions (see SensitiveActionReceiver) — either way nothing
     *  sensitive runs without an explicit user tap. */
    private val requestApproval: (actionLabel: String, onDecision: (Boolean) -> Unit) -> Unit,
    /** Only an Activity can trigger a real runtime permission request — a background
     *  Service cannot. Left null from the background service, which falls back to a
     *  spoken/logged explanation instead. */
    private val onNeedsContactsPermission: ((originalText: String) -> Unit)? = null
) {
    companion object { private const val TAG = "CommandProcessor" }

    private val localRouter = LocalCommandRouter(context)
    private val contactResolver = ContactResolver(context)
    private val routineDao = memory.routineDao()
    private val habitAnalyzer = HabitAnalyzer(memory.routineDao(), memory.habitObservationDao())
    private val routineEngine = RoutineEngine(context, memory.routineDao(), memory.routineExecutionDao(), scope)

    /** Set right after Nova announces a routine suggestion — the NEXT utterance is
     *  checked as a yes/no answer to THIS specific suggestion before anything else. */
    private var pendingSuggestion: RoutineEntity? = null

    private val whatsappSendPattern =
        Regex("send (.+) to ([a-zA-Z][a-zA-Z '\\-]{0,40}?) on whatsapp", RegexOption.IGNORE_CASE)

    fun handle(text: String) {
        if (text.isBlank()) return

        val ctrl = LocalCommandRouter.matchControl(text)
        if (ctrl == LocalCommandRouter.ControlType.RESUME && memory.paused) {
            memory.paused = false
            onLog("Nova", "I'm back.")
            voiceEngine.speak("I'm back.")
            onStatus("ready")
            return
        }
        if (memory.paused) return
        if (ctrl == LocalCommandRouter.ControlType.PAUSE) {
            memory.paused = true
            onLog("Nova", "Okay, pausing.")
            voiceEngine.speak("Okay, pausing.")
            onStatus("paused")
            return
        }

        // Answering a pending routine suggestion takes priority — but only if this
        // utterance actually looks like a yes/no, so a genuinely new command right
        // after a suggestion never gets trapped as a misread answer.
        val pending = pendingSuggestion
        if (pending != null) {
            val lower = text.trim().lowercase()
            val isYes = listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please do", "go ahead").any { lower == it || lower.startsWith("$it ") }
            val isNo = listOf("no", "nope", "don't", "do not", "never mind", "cancel").any { lower == it || lower.startsWith("$it ") }
            pendingSuggestion = null
            if (isYes) {
                scope.launch {
                    routineDao.update(pending.copy(isApproved = true))
                    reply("Great — I'll run \"${pending.triggerPhrase}\" the same way next time you say that. You can rename, disable, or delete it anytime from Routines.", text)
                }
                return
            }
            if (isNo) {
                scope.launch {
                    routineDao.delete(pending)
                    reply("Okay, I won't save that.", text)
                }
                return
            }
            // Not a yes/no -> keep going, process `text` as an unrelated new command below.
        }

        // Step 1: memory-specific commands — never need the AI model for these.
        val rememberFact = memory.extractRememberCommand(text)
        if (rememberFact != null) {
            scope.launch {
                memory.remember(rememberFact)
                val replyText = if (memory.memoryEnabled) "Got it, I'll remember that."
                    else "Memory is currently turned off, so I won't save that."
                reply(replyText, text)
            }
            return
        }
        if (memory.isRecallQuery(text)) {
            scope.launch {
                val facts = memory.recallAll()
                val replyText = if (facts.isEmpty()) "I don't have anything saved yet."
                    else "Here's what I remember: " + facts.joinToString(". ") { it.fact }
                reply(replyText, text)
            }
            return
        }
        if (memory.isForgetAllCommand(text)) {
            scope.launch {
                memory.clearAll()
                reply("Done — I've cleared everything I had saved.", text)
            }
            return
        }

        // Step 2: WhatsApp send — needs a resolved contact + explicit approval before
        // WhatsApp even opens. Never auto-sends — see ActionExecutor.sendWhatsAppMessage.
        val waMatch = whatsappSendPattern.find(text)
        if (waMatch != null) {
            val message = waMatch.groupValues[1].trim()
            val contactName = waMatch.groupValues[2].trim()
            if (!contactResolver.hasPermission()) {
                if (onNeedsContactsPermission != null) {
                    onNeedsContactsPermission.invoke(text)
                } else {
                    reply("I need Contacts permission to find $contactName — grant it from Nova's app settings and try again.", text)
                }
                return
            }
            val phone = contactResolver.findPhoneNumber(contactName)
            if (phone == null) {
                reply("I couldn't find a contact named $contactName.", text)
                return
            }
            onStatus("waiting for approval")
            requestApproval("send \"$message\" to $contactName on WhatsApp") { approved ->
                val replyText = if (approved) {
                    ActionExecutor(context).sendWhatsAppMessage(phone, message)
                } else {
                    "Okay, I won't send that."
                }
                reply(replyText, text)
            }
            return
        }

        // Step 3+: approved-routine trigger, then local device commands, then the AI
        // model — run together in one coroutine since routine matching needs the DB.
        // Habit-pattern tracking rides along on the local-command and AI-fallback
        // paths, so a genuinely repeated sequence can eventually turn into a
        // suggestion (never silently — see HabitAnalyzer).
        onStatus("thinking")
        scope.launch {
            val actionSpec = localRouter.classifyAction(text)

            val routineReply = routineEngine.tryHandle(text, requestApproval)
            if (routineReply != null) {
                reply(routineReply, text)
                return@launch
            }

            when (val routed = localRouter.tryHandle(text)) {
                is LocalCommandRouter.RouteResult.Executed -> {
                    val suggestion = habitAnalyzer.observe(text, actionSpec)
                    reply(routed.message, text)
                    announceSuggestionIfAny(suggestion)
                    return@launch
                }
                is LocalCommandRouter.RouteResult.NeedsApproval -> {
                    onStatus("waiting for approval")
                    requestApproval(routed.actionLabel) { approved ->
                        val replyText = if (approved) routed.onApproved() else "Understood — I won't do that."
                        reply(replyText, text)
                    }
                    return@launch
                }
                null -> { /* fall through to the AI model below */ }
            }

            val suggestion = habitAnalyzer.observe(text, null)
            val contextBlock = listOf(memory.longTermContext(), memory.shortTermContext())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            // brain.ask() is a suspend fun that internally hops off the Main thread for
            // the actual inference/network call — it never freezes the UI while "thinking".
            val aiReply = brain.ask(text, contextBlock)
            reply(aiReply, text)
            announceSuggestionIfAny(suggestion)
            brain.lastError?.let { Log.w(TAG, "ask() completed with a logged issue: $it") }
        }
    }

    private fun announceSuggestionIfAny(suggestion: RoutineEntity?) {
        if (suggestion == null) return
        pendingSuggestion = suggestion
        val actionsDesc = RoutineAction.parseList(suggestion.actions).joinToString(", ") { it.describe() }
        val announcement = "By the way — I've noticed that when you say \"${suggestion.triggerPhrase}\", " +
            "you usually $actionsDesc. Want me to save this as a routine? Say yes or no."
        onLog("Nova", announcement)
        voiceEngine.speak(announcement)
    }

    private fun reply(text: String, forUserText: String) {
        onLog("Nova", text)
        voiceEngine.speak(text)
        scope.launch { memory.addTurn(forUserText, text) }
        onStatus("ready")
    }
}
