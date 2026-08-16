package com.nova.assistant

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.memory.MemoryManager
import com.nova.assistant.memory.RoutineEntity
import kotlinx.coroutines.launch

/**
 * Lets the user see every routine Nova knows about — pending suggestions from
 * HabitAnalyzer, and taught/approved ones — and Accept, Reject, Rename,
 * Disable/Enable, or Delete each one. Nothing here runs an action; this is
 * purely management. Actual execution happens through RoutineEngine when the
 * trigger phrase is spoken.
 */
class RoutinesActivity : AppCompatActivity() {

    private lateinit var memory: MemoryManager
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routines)

        memory = MemoryManager(this)
        listContainer = findViewById(R.id.routineListContainer)
        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            val all = memory.routineDao().getAll()
            listContainer.removeAllViews()
            if (all.isEmpty()) {
                val empty = TextView(this@RoutinesActivity).apply {
                    text = "No routines yet. Teach Nova one, or wait for a suggestion after a repeated pattern."
                    setTextColor(getColor(R.color.nova_text_dim))
                }
                listContainer.addView(empty)
                return@launch
            }
            for (routine in all) addRoutineRow(routine)
        }
    }

    private fun addRoutineRow(routine: RoutineEntity) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_routine, listContainer, false)

        val statusWord = when {
            !routine.isApproved -> "Suggested (not yet approved)"
            !routine.isActive -> "Disabled"
            else -> "Active"
        }
        row.findViewById<TextView>(R.id.routineTitleText).text = "\"${routine.triggerPhrase}\""
        row.findViewById<TextView>(R.id.routineActionsText).text =
            RoutineAction.parseList(routine.actions).joinToString(", ") { it.describe() }
        row.findViewById<TextView>(R.id.routineStatusText).text =
            "$statusWord · observed ${routine.timesObserved}x · ${if (routine.isUserTaught) "taught by you" else "learned from a pattern"}"

        val approveButton = row.findViewById<Button>(R.id.routineApproveButton)
        val toggleButton = row.findViewById<Button>(R.id.routineToggleButton)
        val renameButton = row.findViewById<Button>(R.id.routineRenameButton)
        val deleteButton = row.findViewById<Button>(R.id.routineDeleteButton)

        if (routine.isApproved) {
            approveButton.visibility = android.view.View.GONE
        } else {
            approveButton.text = "Approve"
            approveButton.setOnClickListener {
                lifecycleScope.launch {
                    memory.routineDao().update(routine.copy(isApproved = true))
                    refreshList()
                }
            }
        }

        toggleButton.text = if (routine.isActive) "Disable" else "Enable"
        toggleButton.setOnClickListener {
            lifecycleScope.launch {
                memory.routineDao().update(routine.copy(isActive = !routine.isActive))
                refreshList()
            }
        }

        renameButton.setOnClickListener { showRenameDialog(routine) }

        deleteButton.setOnClickListener {
            lifecycleScope.launch {
                memory.routineDao().delete(routine)
                refreshList()
            }
        }

        listContainer.addView(row)
    }

    private fun showRenameDialog(routine: RoutineEntity) {
        val input = EditText(this).apply { setText(routine.triggerPhrase) }
        AlertDialog.Builder(this)
            .setTitle("Rename trigger phrase")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTrigger = input.text.toString().trim()
                if (newTrigger.isNotBlank()) {
                    lifecycleScope.launch {
                        memory.routineDao().update(routine.copy(triggerPhrase = LocalCommandRouter.normalize(newTrigger)))
                        refreshList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
