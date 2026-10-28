package com.autologue.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.autologue.app.data.local.db.AppDatabase
import com.autologue.app.data.local.db.dao.DiaryDao
import com.autologue.app.data.local.db.dao.TransactionDao
import com.autologue.app.data.local.db.dao.TransactionRuleDao
import com.autologue.app.data.local.db.dao.VehicleLogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Room v6 -> v7 무손실 마이그레이션:
     * 기존 가계부 거래·다이어리·차계부·분류 규칙 데이터를 100% 보존하면서 제거된 `golf_rounds` 테이블만 삭제합니다.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `golf_rounds`")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "autologue.db"
        )
            .addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideDiaryDao(db: AppDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideVehicleLogDao(db: AppDatabase): VehicleLogDao = db.vehicleLogDao()

    @Provides
    fun provideTransactionRuleDao(db: AppDatabase): TransactionRuleDao = db.transactionRuleDao()
}
