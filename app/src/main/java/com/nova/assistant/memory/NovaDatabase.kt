package com.nova.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryEntity::class,
        RoutineEntity::class,
        HabitObservationEntity::class,
        RoutineExecutionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun routineDao(): RoutineDao
    abstract fun habitObservationDao(): HabitObservationDao
    abstract fun routineExecutionDao(): RoutineExecutionDao

    companion object {
        @Volatile private var INSTANCE: NovaDatabase? = null

        fun get(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova.db"
                )
                    // No real users depend on the old schema yet at this stage of the
                    // project — a clean rebuild on the next install is fine, and far
                    // simpler/safer than hand-writing a migration for an early build.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
