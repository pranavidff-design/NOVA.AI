package com.nova.assistant.memory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * A routine is either:
 *  - taught directly ("teach a routine...") -> isUserTaught = true, isApproved = true immediately
 *  - noticed by HabitAnalyzer's pattern-tracking -> isUserTaught = false, isApproved = false
 *    until the user explicitly says yes. Nova never silently turns a pattern into an
 *    active routine — this is the whole point of the approval step.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val actions: String,              // RoutineAction.serializeList() format: "TYPE:param;TYPE:param"
    val isUserTaught: Boolean,
    val isApproved: Boolean = false,  // learned suggestions start false; taught routines start true
    val isActive: Boolean = true,     // enable/disable toggle, independent of approval
    val timesObserved: Int = 1,
    val lastExecutedAt: Long = 0,
    val cooldownMinutes: Int = 5,     // won't re-fire the same routine faster than this
    val requiresConfirmation: Boolean = false
)

@Dao
interface RoutineDao {
    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Delete
    suspend fun delete(routine: RoutineEntity)

    @Query("SELECT * FROM routines WHERE isApproved = 1 AND isActive = 1")
    suspend fun getApprovedActive(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE isApproved = 0")
    suspend fun getPendingSuggestions(): List<RoutineEntity>

    @Query("SELECT * FROM routines ORDER BY id DESC")
    suspend fun getAll(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE triggerPhrase = :phrase LIMIT 1")
    suspend fun findByTrigger(phrase: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): RoutineEntity?

    @Query("UPDATE routines SET lastExecutedAt = :timestamp WHERE id = :id")
    suspend fun markExecuted(id: Long, timestamp: Long)
}

/** Raw record of "this trigger phrase was followed by this action sequence" —
 *  purely a count of the user's OWN spoken commands, nothing else is observed.
 *  Old rows are pruned once a pattern graduates to a suggestion or goes stale. */
@Entity(tableName = "habit_observations")
data class HabitObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedTrigger: String,
    val actionsSignature: String,   // sorted action TYPEs only, used to detect "same sequence"
    val actionsFull: String,        // full serialized actions for this specific occurrence
    val observedAt: Long
)

@Dao
interface HabitObservationDao {
    @Insert
    suspend fun insert(obs: HabitObservationEntity): Long

    @Query("SELECT * FROM habit_observations WHERE normalizedTrigger = :trigger ORDER BY observedAt DESC LIMIT 5")
    suspend fun recentForTrigger(trigger: String): List<HabitObservationEntity>

    @Query("DELETE FROM habit_observations WHERE normalizedTrigger = :trigger")
    suspend fun clearForTrigger(trigger: String)

    @Query("DELETE FROM habit_observations WHERE observedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

/** One row per routine run, so the user can see exactly what Nova actually did
 *  and when — required for real transparency, not just a "trust me" toggle. */
@Entity(tableName = "routine_executions")
data class RoutineExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val executedAt: Long,
    val success: Boolean,
    val note: String
)

@Dao
interface RoutineExecutionDao {
    @Insert
    suspend fun insert(execution: RoutineExecutionEntity): Long

    @Query("SELECT * FROM routine_executions WHERE routineId = :routineId ORDER BY executedAt DESC LIMIT 20")
    suspend fun historyFor(routineId: Long): List<RoutineExecutionEntity>
}
