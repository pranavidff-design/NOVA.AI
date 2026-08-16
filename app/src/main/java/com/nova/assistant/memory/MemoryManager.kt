package com.nova.assistant.memory

import android.content.Context
import android.content.SharedPreferences

/**
 * Single entry point for everything memory-related. MainActivity and NovaBrain
 * talk to this, never to the DAOs directly — keeps the "memory can be disabled"
 * requirement enforceable in one place.
 */
class MemoryManager(context: Context) {

    private val db = NovaDatabase.get(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)

    // Short-term memory: just this session's conversation, lost on app close.
    // Kept as a simple rolling list — last ~6 exchanges is enough context
    // for "what about Friday?" style follow-ups without bloating every prompt.
    private val shortTermTurns = mutableListOf<Pair<String, String>>() // (user, nova)

    var memoryEnabled: Boolean
        get() = prefs.getBoolean("memory_enabled", true)
        set(value) = prefs.edit().putBoolean("memory_enabled", value).apply()

    /** Shared pause state — stored in prefs (not just an in-memory flag) so that
     *  MainActivity (foreground) and NovaWakeService (background wake word) always
     *  agree on whether Nova is paused, regardless of which one is currently running. */
    var paused: Boolean
        get() = prefs.getBoolean("nova_paused", false)
        set(value) = prefs.edit().putBoolean("nova_paused", value).apply()

    fun addTurn(userText: String, novaReply: String) {
        shortTermTurns.add(userText to novaReply)
        if (shortTermTurns.size > 6) shortTermTurns.removeAt(0)
    }

    fun shortTermContext(): String {
        if (shortTermTurns.isEmpty()) return ""
        return shortTermTurns.joinToString("\n") { (u, n) -> "User: $u\nNova: $n" }
    }

    // --- Long-term memory ---

    /** Detects "remember that X" / "remember X" style commands. Returns the fact, or null if not that kind of command. */
    fun extractRememberCommand(text: String): String? {
        val patterns = listOf(
            Regex("remember that (.+)", RegexOption.IGNORE_CASE),
            Regex("nova,? remember (.+)", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val match = p.find(text)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    fun isRecallQuery(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("what do you remember") || lower.contains("what have i told you")
    }

    fun isForgetAllCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("forget everything") || lower.contains("clear all memories") || lower.contains("clear my memory")
    }

    suspend fun remember(fact: String) {
        if (!memoryEnabled) return
        db.memoryDao().insert(MemoryEntity(fact = fact))
    }

    suspend fun recallAll(): List<MemoryEntity> {
        if (!memoryEnabled) return emptyList()
        return db.memoryDao().getAll()
    }

    suspend fun forget(memory: MemoryEntity) {
        db.memoryDao().delete(memory)
    }

    suspend fun clearAll() {
        db.memoryDao().clearAll()
    }

    /** Long-term facts formatted for injection into the AI prompt — empty string if memory is off or empty. */
    suspend fun longTermContext(): String {
        if (!memoryEnabled) return ""
        val facts = recallAll()
        if (facts.isEmpty()) return ""
        return "Things the user has told you to remember:\n" + facts.joinToString("\n") { "- ${it.fact}" }
    }

    // --- Routines / habits (plain getters — Room DAO objects themselves don't need
    // suspend, only the calls made on them do) ---

    fun routineDao() = db.routineDao()
    fun habitObservationDao() = db.habitObservationDao()
    fun routineExecutionDao() = db.routineExecutionDao()
}
