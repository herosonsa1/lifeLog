package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.GolfRoundDao
import com.autologue.app.data.local.entity.GolfRoundEntity
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.repository.GolfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class GolfRepositoryImpl @Inject constructor(
    private val golfRoundDao: GolfRoundDao
) : GolfRepository {

    override fun getAllGolfRoundsFlow(): Flow<List<GolfRound>> {
        return golfRoundDao.getAllGolfRounds().map { it.map { e -> e.toDomain() } }
    }

    override suspend fun getGolfRoundById(id: Long): GolfRound? = withContext(Dispatchers.IO) {
        golfRoundDao.getGolfRoundById(id)?.toDomain()
    }

    override suspend fun insertGolfRound(round: GolfRound): Long = withContext(Dispatchers.IO) {
        golfRoundDao.insertGolfRound(round.toEntity())
    }

    override suspend fun updateGolfRound(round: GolfRound) = withContext(Dispatchers.IO) {
        golfRoundDao.updateGolfRound(round.toEntity())
    }

    override suspend fun deleteGolfRound(id: Long) = withContext(Dispatchers.IO) {
        golfRoundDao.deleteGolfRoundById(id)
    }

    override suspend fun getGolfRoundByDate(date: LocalDate): GolfRound? = withContext(Dispatchers.IO) {
        val startMilli = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        golfRoundDao.getGolfRoundByDateRange(startMilli, endMilli)?.toDomain()
    }

    private fun GolfRoundEntity.toDomain() = GolfRound(
        id = id, clubName = clubName, roundDate = roundDate, golfType = golfType,
        latitude = latitude, longitude = longitude, totalScore = totalScore,
        totalPutts = totalPutts, holeScores = holeScores, scorecardPhotoUri = scorecardPhotoUri,
        matchingPhotoUris = matchingPhotoUris, greenFeeExpense = greenFeeExpense, memo = memo,
        startTime = startTime, endTime = endTime, companions = companions,
        penaltyCount = penaltyCount, girPercentage = girPercentage,
        averageDriveDistance = averageDriveDistance, adjustedDriveDistance = adjustedDriveDistance,
        averageTempo = averageTempo,
        steps = steps, driveDistances = driveDistances, tempos = tempos
    )

    private fun GolfRound.toEntity() = GolfRoundEntity(
        id = id, clubName = clubName, roundDate = roundDate, golfType = golfType,
        latitude = latitude, longitude = longitude, totalScore = totalScore,
        totalPutts = totalPutts, holeScores = holeScores, scorecardPhotoUri = scorecardPhotoUri,
        matchingPhotoUris = matchingPhotoUris, greenFeeExpense = greenFeeExpense, memo = memo,
        startTime = startTime, endTime = endTime, companions = companions,
        penaltyCount = penaltyCount, girPercentage = girPercentage,
        averageDriveDistance = averageDriveDistance, adjustedDriveDistance = adjustedDriveDistance,
        averageTempo = averageTempo,
        steps = steps, driveDistances = driveDistances, tempos = tempos
    )
}
