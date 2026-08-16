package com.nova.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One long-term memory fact Nova was explicitly told to remember.
 * Nothing is stored here automatically from casual conversation —
 * only things the user directly asked her to remember, per the spec
 * ("Nova should not secretly store everything").
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val createdAt: Long = System.currentTimeMillis()
)
