package com.autologue.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autologue.app.data.local.db.dao.*
import com.autologue.app.data.local.entity.*

@Database(
    entities = [
        DiaryEntryEntity::class,
        TransactionEntity::class,
        VehicleLogEntity::class,
        GolfRoundEntity::class,
        TransactionRuleEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun vehicleLogDao(): VehicleLogDao
    abstract fun golfRoundDao(): GolfRoundDao
    abstract fun transactionRuleDao(): TransactionRuleDao
}
