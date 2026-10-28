package com.autologue.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.autologue.app.data.local.db.dao.DiaryDao
import com.autologue.app.data.local.db.dao.TransactionDao
import com.autologue.app.data.local.db.dao.TransactionRuleDao
import com.autologue.app.data.local.db.dao.VehicleLogDao
import com.autologue.app.data.local.entity.DiaryEntryEntity
import com.autologue.app.data.local.entity.TransactionEntity
import com.autologue.app.data.local.entity.TransactionRuleEntity
import com.autologue.app.data.local.entity.VehicleLogEntity

@Database(
    entities = [
        DiaryEntryEntity::class,
        TransactionEntity::class,
        VehicleLogEntity::class,
        TransactionRuleEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun vehicleLogDao(): VehicleLogDao
    abstract fun transactionRuleDao(): TransactionRuleDao
}
