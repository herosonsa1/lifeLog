package com.autologue.app.domain.model

enum class LifeOrbitPeriod(val label: String, val days: Int?) {
    WEEK("최근 7일", 7),
    MONTH("최근 30일", 30),
    ALL("전체 기간", null)
}

data class LifeBalanceScores(
    val activityScore: Int = 50,
    val financeScore: Int = 50,
    val mobilityScore: Int = 50,
    val achievementScore: Int = 50,
    val overallScore: Int = 50
)

data class FinancialVelocity(
    val totalExpense: Long = 0L,
    val dailyAverageExpense: Long = 0L,
    val topCategoryName: String = "미분류",
    val topCategoryAmount: Long = 0L,
    val topCategoryRatio: Double = 0.0,
    val lifestyleExpenseRatio: Double = 0.0
)

data class MobilityOrbit(
    val totalDrivingKm: Double = 0.0,
    val averageFuelEconomy: Double = 0.0,
    val drivingDaysCount: Int = 0,
    val totalFuelCost: Long = 0L,
    val mobilityActivityLevel: String = "보통"
)

data class GolfAthleticTrajectory(
    val roundCount: Int = 0,
    val averageScore: Double = 0.0,
    val bestScore: Int? = null,
    val averagePutts: Double? = null,
    val totalFieldSteps: Int = 0,
    val performanceTrend: String = "데이터 축적 중"
)

data class SpatioTemporalFootprint(
    val recordedDays: Int = 0,
    val placeClusterCount: Int = 0,
    val totalPhotosArchived: Int = 0,
    val primaryArea: String = "활동 거점 분석 중"
)

data class LifestyleInsight(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val actionGuidance: String,
    val isPositive: Boolean = true
)

data class LifestyleMetrics(
    val period: LifeOrbitPeriod = LifeOrbitPeriod.MONTH,
    val balanceScores: LifeBalanceScores = LifeBalanceScores(),
    val archetype: String = "라이프스타일 궤도 분석 중",
    val archetypeSubtitle: String = "수집된 일상 데이터를 종합 분석하여 고유 패턴을 도출합니다.",
    val financialVelocity: FinancialVelocity = FinancialVelocity(),
    val mobilityOrbit: MobilityOrbit = MobilityOrbit(),
    val golfTrajectory: GolfAthleticTrajectory = GolfAthleticTrajectory(),
    val spatialFootprint: SpatioTemporalFootprint = SpatioTemporalFootprint(),
    val insights: List<LifestyleInsight> = emptyList()
)

