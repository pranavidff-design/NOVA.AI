package com.nova.assistant

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.memory.MemoryEntity
import com.nova.assistant.memory.MemoryManager
import com.nova.assistant.memory.RoutineEntity
import kotlinx.coroutines.launch

/**
 * "Nova's Memory & Habits" screen — remembered facts, plus teaching Nova new
 * automation rules in plain language ("Whenever I say X, do Y"). Parsing is
 * done by RuleTeacher; once parsed, a taught rule is just a normal approved
 * RoutineEntity — RoutineEngine (already wired into CommandProcessor) picks it
 * up immediately, no separate execution path to maintain.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var memory: MemoryManager
    private lateinit var brain: NovaBrain
    private lateinit var ruleTeacher: RuleTeacher
    private lateinit var listContainer: LinearLayout
    private lateinit var enabledSwitch: Switch
    private lateinit var ruleInput: EditText
    private lateinit var ruleStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        memory = MemoryManager(this)
        brain = (application as NovaApp).brain
        ruleTeacher = RuleTeacher(brain)

        listContainer = findViewById(R.id.memoryListContainer)
        enabledSwitch = findViewById(R.id.memoryEnabledSwitch)
        ruleInput = findViewById(R.id.ruleInputText)
        ruleStatus = findViewById(R.id.ruleTeachStatusText)

        enabledSwitch.isChecked = memory.memoryEnabled
        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            memory.memoryEnabled = isChecked
        }

        findViewById<Button>(R.id.clearAllButton).setOnClickListener {
            lifecycleScope.launch {
                memory.clearAll()
                refreshList()
            }
        }

        findViewById<Button>(R.id.teachRuleButton).setOnClickListener { teachRule() }

        findViewById<Button>(R.id.manageRulesButton).setOnClickListener {
            startActivity(Intent(this, RoutinesActivity::class.java))
        }

        refreshList()
    }

    private fun teachRule() {
        val text = ruleInput.text.toString().trim()
        if (text.isBlank()) {
            ruleStatus.text = "Type a rule first — e.g. \"Whenever I say ghar aa gaya hoon, turn the flashlight off.\""
            return
        }
        ruleStatus.text = "Understanding that…"
        lifecycleScope.launch {
            val parsed = try {
                ruleTeacher.parse(text)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) {
                ruleStatus.text = "I couldn't turn that into an action I actually know how to do. Try naming the trigger phrase clearly and a concrete action (flashlight, Wi-Fi panel, open an app, search something, volume, WhatsApp, calculator, maps, browser, settings)."
                return@launch
            }
            AlertDialog.Builder(this@MemoryActivity)
                .setTitle("Confirm this rule")
                .setMessage("Whenever you say \"${parsed.triggerPhrase}\", Nova will: ${parsed.describe()}.\n\nSave this?")
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        val existing = memory.routineDao().findByTrigger(parsed.triggerPhrase)
                        if (existing != null) {
                            memory.routineDao().update(
                                existing.copy(
                                    actions = RoutineAction.serializeList(parsed.actions),
                                    isUserTaught = true,
                                    isApproved = true,
                                    isActive = true
                                )
                            )
                            ruleStatus.text = "Updated the existing rule for \"${parsed.triggerPhrase}\"."
                        } else {
                            memory.routineDao().insert(
                                RoutineEntity(
                                    triggerPhrase = parsed.triggerPhrase,
                                    actions = RoutineAction.serializeList(parsed.actions),
                                    isUserTaught = true,
                                    isApproved = true,
                                    isActive = true
                                )
                            )
                            ruleStatus.text = "Saved. Say \"${parsed.triggerPhrase}\" any time and Nova will do it."
                        }
                        ruleInput.setText("")
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> ruleStatus.text = "" }
                .show()
        }
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val facts = memory.recallAll()
            listContainer.removeAllViews()
            if (facts.isEmpty()) {
                val empty = TextView(this@MemoryActivity).apply {
                    text = "Nothing saved yet."
                    setTextColor(getColor(R.color.nova_text_dim))
                }
                listContainer.addView(empty)
                return@launch
            }
            for (fact in facts) {
                addMemoryRow(fact)
            }
        }
    }

    private fun addMemoryRow(entity: MemoryEntity) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_memory, listContainer, false)
        row.findViewById<TextView>(R.id.memoryFactText).text = entity.fact
        row.findViewById<Button>(R.id.deleteButton).setOnClickListener {
            lifecycleScope.launch {
                memory.forget(entity)
                refreshList()
            }
        }
        listContainer.addView(row)
    }
}
