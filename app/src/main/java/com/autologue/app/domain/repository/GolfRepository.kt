package com.autologue.app.domain.repository

import com.autologue.app.domain.model.GolfRound
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface GolfRepository {
    fun getAllGolfRoundsFlow(): Flow<List<GolfRound>>
    suspend fun getGolfRoundById(id: Long): GolfRound?
    suspend fun insertGolfRound(round: GolfRound): Long
    suspend fun updateGolfRound(round: GolfRound)
    suspend fun deleteGolfRound(id: Long)
    suspend fun getGolfRoundByDate(date: LocalDate): GolfRound?
}
