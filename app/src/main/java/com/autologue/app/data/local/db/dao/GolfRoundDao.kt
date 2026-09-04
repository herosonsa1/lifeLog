package com.autologue.app.data.local.db.dao

import androidx.room.*
import com.autologue.app.data.local.entity.GolfRoundEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GolfRoundDao {
    @Query("SELECT * FROM golf_rounds ORDER BY roundDate DESC")
    fun getAllGolfRounds(): Flow<List<GolfRoundEntity>>

    @Query("SELECT * FROM golf_rounds WHERE id = :id")
    suspend fun getGolfRoundById(id: Long): GolfRoundEntity?

    @Query("SELECT * FROM golf_rounds WHERE roundDate BETWEEN :start AND :end LIMIT 1")
    suspend fun getGolfRoundByDateRange(start: Long, end: Long): GolfRoundEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGolfRound(round: GolfRoundEntity): Long

    @Update
    suspend fun updateGolfRound(round: GolfRoundEntity)

    @Query("DELETE FROM golf_rounds WHERE id = :id")
    suspend fun deleteGolfRoundById(id: Long)
}
