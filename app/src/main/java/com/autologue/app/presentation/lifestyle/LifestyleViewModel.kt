package com.autologue.app.presentation.lifestyle

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.domain.model.LifeOrbitPeriod
import com.autologue.app.domain.model.LifestyleMetrics
import com.autologue.app.domain.usecase.lifestyle.CalculateLifestyleMetricsUseCase
import com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase
import com.autologue.app.domain.usecase.sync.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LifestyleViewModel @Inject constructor(
    private val calculateLifestyleMetricsUseCase: CalculateLifestyleMetricsUseCase,
    private val syncHistoricalDataUseCase: SyncHistoricalDataUseCase
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow(LifeOrbitPeriod.MONTH)
    val selectedPeriod: StateFlow<LifeOrbitPeriod> = _selectedPeriod.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val metrics: StateFlow<LifestyleMetrics> = _selectedPeriod
        .flatMapLatest { period ->
            calculateLifestyleMetricsUseCase(period)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LifestyleMetrics()
        )

    fun selectPeriod(period: LifeOrbitPeriod) {
        _selectedPeriod.value = period
    }

    fun syncFromDevice(context: Context, daysBack: Int? = 30) {
        viewModelScope.launch {
            syncHistoricalDataUseCase(context, daysBack).collect { progress ->
                _syncProgress.value = if (progress.isDone) null else progress
            }
        }
    }
}
