package com.autologue.app.data.local.db.dao

import androidx.room.*
import com.autologue.app.data.local.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY date DESC")
    fun getAllEntries(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    fun getEntriesByDateRange(start: Long, end: Long): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :start AND :end ORDER BY date DESC LIMIT 1")
    suspend fun getEntryByDateRangeSingle(start: java.time.LocalDateTime, end: java.time.LocalDateTime): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun getEntryById(id: Long): DiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DiaryEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: DiaryEntryEntity)

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Long)

    @Query("SELECT * FROM diary_entries ORDER BY id ASC")
    suspend fun getAllEntriesSync(): List<DiaryEntryEntity>
}
