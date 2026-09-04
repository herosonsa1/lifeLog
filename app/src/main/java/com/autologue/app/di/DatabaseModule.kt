package com.autologue.app.di

import android.content.Context
import androidx.room.Room
import com.autologue.app.data.local.db.AppDatabase
import com.autologue.app.data.local.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "autologue.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideDiaryDao(db: AppDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideVehicleLogDao(db: AppDatabase): VehicleLogDao = db.vehicleLogDao()

    @Provides
    fun provideGolfRoundDao(db: AppDatabase): GolfRoundDao = db.golfRoundDao()

    @Provides
    fun provideTransactionRuleDao(db: AppDatabase): TransactionRuleDao = db.transactionRuleDao()
}
