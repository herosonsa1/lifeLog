package com.autologue.app.di

import com.autologue.app.data.repository.DiaryRepositoryImpl
import com.autologue.app.data.repository.TransactionRepositoryImpl
import com.autologue.app.data.repository.TransactionRuleRepositoryImpl
import com.autologue.app.data.repository.VehicleRepositoryImpl
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.TransactionRuleRepository
import com.autologue.app.domain.repository.VehicleRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindDiaryRepository(impl: DiaryRepositoryImpl): DiaryRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindVehicleRepository(impl: VehicleRepositoryImpl): VehicleRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRuleRepository(impl: TransactionRuleRepositoryImpl): TransactionRuleRepository
}
