package com.autologue.app.domain.usecase.lifestyle

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.FinancialVelocity
import com.autologue.app.domain.model.GolfAthleticTrajectory
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.LifeBalanceScores
import com.autologue.app.domain.model.LifeOrbitPeriod
import com.autologue.app.domain.model.LifestyleInsight
import com.autologue.app.domain.model.LifestyleMetrics
import com.autologue.app.domain.model.MobilityOrbit
import com.autologue.app.domain.model.SpatioTemporalFootprint
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.isSelfTransfer
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CalculateLifestyleMetricsUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: GolfRepository,
    private val diaryRepository: DiaryRepository
) {
    operator fun invoke(period: LifeOrbitPeriod): Flow<LifestyleMetrics> {
        return combine(
            transactionRepository.getAllTransactionsFlow(),
            vehicleRepository.getAllVehicleLogsFlow(),
            golfRepository.getAllGolfRoundsFlow(),
            diaryRepository.getDiaryEntriesFlow()
        ) { transactions, vehicleLogs, golfRounds, diaries ->
            val now = LocalDateTime.now()
            val cutoff = period.days?.let { now.minusDays(it.toLong()) }

            // 1. 기간별 필터링
            val filteredTx = transactions.filter { tx ->
                cutoff == null || !tx.timestamp.isBefore(cutoff)
            }
            val filteredVehicle = vehicleLogs.filter { log ->
                cutoff == null || !log.timestamp.isBefore(cutoff)
            }
            val filteredGolf = golfRounds.filter { round ->
                cutoff == null || !round.roundDate.isBefore(cutoff)
            }
            val filteredDiaries = diaries.filter { diary ->
                cutoff == null || !diary.date.isBefore(cutoff)
            }

            // [핵심] 실제 기기 데이터 존재 여부 엄격 검사 (더미/기본값 오염 원천 방지)
            val hasAnyData = filteredTx.isNotEmpty() || filteredVehicle.isNotEmpty() || filteredGolf.isNotEmpty() || filteredDiaries.isNotEmpty()

            if (!hasAnyData) {
                return@combine LifestyleMetrics(
                    period = period,
                    balanceScores = LifeBalanceScores(
                        activityScore = 0,
                        financeScore = 0,
                        mobilityScore = 0,
                        achievementScore = 0,
                        overallScore = 0
                    ),
                    archetype = "일상 데이터 수집 대기 중",
                    archetypeSubtitle = "모바일폰의 결제 내역, 이동 동선, 사진, 골프 기록이 수집되면 나만의 라이프스타일 궤도를 정밀하게 분석합니다.",
                    financialVelocity = FinancialVelocity(),
                    mobilityOrbit = MobilityOrbit(mobilityActivityLevel = "데이터 없음"),
                    golfTrajectory = GolfAthleticTrajectory(performanceTrend = "라운드 기록 대기"),
                    spatialFootprint = SpatioTemporalFootprint(primaryArea = "거점 분석 대기"),
                    insights = listOf(
                        LifestyleInsight(
                            id = "sync_guide",
                            category = "데이터 동기화",
                            title = "내 모바일폰의 일상 기록 불러오기",
                            description = "단말기에 저장된 과거 결제 SMS, 갤러리 사진, 골프 스코어카드를 원클릭으로 동기화할 수 있습니다.",
                            actionGuidance = "상단의 [내 폰 데이터 동기화] 버튼을 눌러 최근 기록을 즉시 불러와보세요.",
                            isPositive = true
                        ),
                        LifestyleInsight(
                            id = "realtime_guide",
                            category = "실시간 수집",
                            title = "카드/페이 푸시 및 SMS 자동 기록",
                            description = "결제 알림 및 문자가 오면 가계부와 주유/골프 기록이 자동으로 분석되어 실시간 축적됩니다.",
                            actionGuidance = "알림 접근 권한과 SMS 수신을 허용해두시면 앱을 켜지 않아도 모든 일상이 자동 기록됩니다.",
                            isPositive = true
                        )
                    )
                )
            }

            val effectiveDays = period.days ?: max(1, calculateDaysSpan(filteredDiaries.map { it.date } + filteredTx.map { it.timestamp }))

            // 2. 재정 속도 (Financial Velocity) 계산
            val pureExpenses = filteredTx.filter { tx ->
                !tx.isSelfTransfer() &&
                        tx.category != ExpenseCategory.INCOME &&
                        tx.category != ExpenseCategory.TRANSFER &&
                        tx.amount > 0
            }
            val totalExpense = pureExpenses.sumOf { it.amount }
            val dailyAverageExpense = totalExpense / max(1, effectiveDays)

            val categoryGroups = pureExpenses.groupBy { it.category }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
            val topCategoryEntry = categoryGroups.maxByOrNull { it.value }
            val topCategoryName = topCategoryEntry?.key?.displayName ?: "미분류"
            val topCategoryAmount = topCategoryEntry?.value ?: 0L
            val topCategoryRatio = if (totalExpense > 0) (topCategoryAmount.toDouble() / totalExpense * 100).coerceIn(0.0, 100.0) else 0.0

            val lifestyleCategories = setOf(
                ExpenseCategory.FOOD,
                ExpenseCategory.CAFE,
                ExpenseCategory.GOLF_FIELD,
                ExpenseCategory.GOLF_SCREEN,
                ExpenseCategory.GOLF_EQUIPMENT,
                ExpenseCategory.SHOPPING
            )
            val lifestyleExpenseAmount = pureExpenses.filter { it.category in lifestyleCategories }.sumOf { it.amount }
            val lifestyleExpenseRatio = if (totalExpense > 0) (lifestyleExpenseAmount.toDouble() / totalExpense * 100).coerceIn(0.0, 100.0) else 0.0

            val financialVelocity = FinancialVelocity(
                totalExpense = totalExpense,
                dailyAverageExpense = dailyAverageExpense,
                topCategoryName = topCategoryName,
                topCategoryAmount = topCategoryAmount,
                topCategoryRatio = (topCategoryRatio * 10).roundToInt() / 10.0,
                lifestyleExpenseRatio = (lifestyleExpenseRatio * 10).roundToInt() / 10.0
            )

            // 3. 모빌리티 궤도 (Mobility Orbit) 계산
            val tripLogs = filteredVehicle.filter { it.logType == VehicleLogType.TRIP_DRIVING }
            val refuelingLogs = filteredVehicle.filter { it.logType == VehicleLogType.REFUELING }
            val totalDrivingKm = tripLogs.sumOf { it.tripDistanceKm } + filteredDiaries.sumOf { it.drivingDistanceKm }
            val drivingDates = (tripLogs.map { it.timestamp.toLocalDate() } + filteredDiaries.filter { it.drivingDistanceKm > 0 }.map { it.date.toLocalDate() }).distinct()
            val drivingDaysCount = drivingDates.size
            val totalFuelCost = refuelingLogs.sumOf { it.fuelCost }
            val validEfficiencyLogs = refuelingLogs.mapNotNull { it.estimatedEfficiencyKmPerL }.filter { it in 3.0..40.0 }
            val averageFuelEconomy = if (validEfficiencyLogs.isNotEmpty()) {
                (validEfficiencyLogs.average() * 10).roundToInt() / 10.0
            } else {
                0.0
            }

            val mobilityActivityLevel = when {
                totalDrivingKm >= 600.0 -> "광역 이동형"
                totalDrivingKm >= 200.0 -> "활동적 도심 주행"
                totalDrivingKm > 0.0 -> "근거리 생활 이동"
                else -> "대중교통/도보 중심"
            }

            val mobilityOrbit = MobilityOrbit(
                totalDrivingKm = (totalDrivingKm * 10).roundToInt() / 10.0,
                averageFuelEconomy = averageFuelEconomy,
                drivingDaysCount = drivingDaysCount,
                totalFuelCost = totalFuelCost,
                mobilityActivityLevel = mobilityActivityLevel
            )

            // 4. 골프 운동 궤적 (Golf Athletic Trajectory) 계산
            val roundCount = filteredGolf.size
            val scoredRounds = filteredGolf.mapNotNull { it.totalScore }.filter { it in 50..150 }
            val averageScore = if (scoredRounds.isNotEmpty()) {
                (scoredRounds.average() * 10).roundToInt() / 10.0
            } else {
                0.0
            }
            val bestScore = scoredRounds.minOrNull()
            val puttsRounds = filteredGolf.mapNotNull { it.totalPutts }.filter { it in 18..60 }
            val averagePutts = if (puttsRounds.isNotEmpty()) {
                (puttsRounds.average() * 10).roundToInt() / 10.0
            } else {
                null
            }
            val totalFieldSteps = filteredGolf.mapNotNull { it.steps }.sum()

            val performanceTrend = when {
                roundCount == 0 -> "라운드 준비 중"
                averageScore in 72.0..85.0 -> "싱글/상급자 안정권"
                averageScore in 86.0..95.0 -> "안정적 80~90대 달성"
                averageScore > 95.0 -> "100타 돌파 집중 훈련"
                else -> "실전 경기력 데이터 축적"
            }

            val golfTrajectory = GolfAthleticTrajectory(
                roundCount = roundCount,
                averageScore = averageScore,
                bestScore = bestScore,
                averagePutts = averagePutts,
                totalFieldSteps = totalFieldSteps,
                performanceTrend = performanceTrend
            )

            // 5. 시공간 풋프린트 (SpatioTemporal Footprint) 계산
            val recordedDays = filteredDiaries.map { it.date.toLocalDate() }.distinct().size
            val placeNames = filteredDiaries.mapNotNull { it.placeName?.trim() }.filter { it.isNotBlank() }
            val addresses = filteredDiaries.mapNotNull { it.address?.trim() }.filter { it.isNotBlank() }
            val placeClusterCount = (placeNames + addresses).distinct().size
            val totalPhotosArchived = filteredDiaries.sumOf { it.photoUris.size } + filteredGolf.sumOf { it.matchingPhotoUris.size + (if (it.scorecardPhotoUri != null) 1 else 0) }

            val primaryArea = extractPrimaryArea(placeNames, addresses)

            val spatialFootprint = SpatioTemporalFootprint(
                recordedDays = recordedDays,
                placeClusterCount = placeClusterCount,
                totalPhotosArchived = totalPhotosArchived,
                primaryArea = primaryArea
            )

            // 6. 4대 라이프 밸런스 지수 계산 (실제 데이터 기반)
            val activityScore = calculateActivityScore(recordedDays, totalPhotosArchived, totalDrivingKm, totalFieldSteps, effectiveDays)
            val financeScore = calculateFinanceScore(pureExpenses.size, dailyAverageExpense, topCategoryRatio, lifestyleExpenseRatio)
            val mobilityScore = calculateMobilityScore(totalDrivingKm, drivingDaysCount, averageFuelEconomy, effectiveDays)
            val achievementScore = calculateAchievementScore(roundCount, averageScore, bestScore, totalFieldSteps)

            // 데이터가 있는 영역들만 가중 평균 계산
            val activeScores = mutableListOf<Pair<Int, Double>>()
            if (recordedDays > 0 || totalPhotosArchived > 0) activeScores.add(Pair(activityScore, 0.3))
            if (pureExpenses.isNotEmpty()) activeScores.add(Pair(financeScore, 0.25))
            if (totalDrivingKm > 0.0 || drivingDaysCount > 0) activeScores.add(Pair(mobilityScore, 0.2))
            if (roundCount > 0) activeScores.add(Pair(achievementScore, 0.25))

            val overallScore = if (activeScores.isNotEmpty()) {
                val totalWeight = activeScores.sumOf { it.second }
                (activeScores.sumOf { it.first * it.second } / totalWeight).roundToInt().coerceIn(10, 99)
            } else {
                0
            }

            val balanceScores = LifeBalanceScores(
                activityScore = activityScore,
                financeScore = financeScore,
                mobilityScore = mobilityScore,
                achievementScore = achievementScore,
                overallScore = overallScore
            )

            // 7. 아키타입(페르소나) 도출
            val (archetype, archetypeSubtitle) = determineArchetype(
                activityScore = activityScore,
                financeScore = financeScore,
                mobilityScore = mobilityScore,
                achievementScore = achievementScore,
                roundCount = roundCount,
                totalDrivingKm = totalDrivingKm
            )

            // 8. 맞춤형 라이프스타일 인사이트 생성
            val insights = generateInsights(
                financialVelocity = financialVelocity,
                mobilityOrbit = mobilityOrbit,
                golfTrajectory = golfTrajectory,
                spatialFootprint = spatialFootprint
            )

            LifestyleMetrics(
                period = period,
                balanceScores = balanceScores,
                archetype = archetype,
                archetypeSubtitle = archetypeSubtitle,
                financialVelocity = financialVelocity,
                mobilityOrbit = mobilityOrbit,
                golfTrajectory = golfTrajectory,
                spatialFootprint = spatialFootprint,
                insights = insights
            )
        }
    }

    private fun calculateDaysSpan(dates: List<LocalDateTime>): Int {
        if (dates.isEmpty()) return 30
        val minDate = dates.minOrNull() ?: return 30
        val maxDate = dates.maxOrNull() ?: return 30
        val days = ChronoUnit.DAYS.between(minDate.toLocalDate(), maxDate.toLocalDate()).toInt()
        return max(1, days + 1)
    }

    private fun extractPrimaryArea(places: List<String>, addresses: List<String>): String {
        val candidates = mutableListOf<String>()
        addresses.forEach { addr ->
            val match = Regex("([가-힣]+(?:구|군|시))\\s*([가-힣]+(?:동|읍|면))?").find(addr)
            if (match != null) {
                val group = match.value.trim()
                if (group.isNotBlank()) candidates.add(group)
            }
        }
        if (candidates.isEmpty()) {
            places.take(5).forEach { candidates.add(it) }
        }
        val top = candidates.groupBy { it }.maxByOrNull { it.value.size }?.key
        return top ?: "활동 거점 분석 완료"
    }

    private fun calculateActivityScore(
        recordedDays: Int,
        photoCount: Int,
        drivingKm: Double,
        fieldSteps: Int,
        effectiveDays: Int
    ): Int {
        if (recordedDays == 0 && photoCount == 0 && fieldSteps == 0 && drivingKm == 0.0) return 0
        val dayRatio = (recordedDays.toDouble() / max(1, effectiveDays)).coerceIn(0.0, 1.0)
        val photoFactor = (photoCount / 30.0).coerceIn(0.0, 1.0)
        val mobilityFactor = (drivingKm / 300.0).coerceIn(0.0, 1.0)
        val stepFactor = (fieldSteps / 20000.0).coerceIn(0.0, 1.0)
        val raw = (dayRatio * 35) + (photoFactor * 25) + (mobilityFactor * 20) + (stepFactor * 20)
        return raw.roundToInt().coerceIn(10, 98)
    }

    private fun calculateFinanceScore(
        txCount: Int,
        dailyAvgExpense: Long,
        topRatio: Double,
        lifestyleRatio: Double
    ): Int {
        if (txCount == 0) return 0
        var score = 70
        if (topRatio > 60.0) score -= 15
        else if (topRatio in 25.0..45.0) score += 10

        if (lifestyleRatio in 20.0..50.0) score += 10
        else if (lifestyleRatio > 75.0) score -= 10

        if (dailyAvgExpense in 20000L..150000L) score += 5
        return score.coerceIn(10, 96)
    }

    private fun calculateMobilityScore(
        drivingKm: Double,
        drivingDays: Int,
        avgEfficiency: Double,
        effectiveDays: Int
    ): Int {
        if (drivingKm <= 0.0 && drivingDays == 0) return 0
        var score = 50
        if (drivingKm > 200.0) score += 20
        else if (drivingKm > 50.0) score += 10

        if (avgEfficiency >= 12.0) score += 15
        else if (avgEfficiency >= 9.0) score += 8

        val freqRatio = drivingDays.toDouble() / max(1, effectiveDays)
        if (freqRatio in 0.2..0.8) score += 10
        return score.coerceIn(10, 97)
    }

    private fun calculateAchievementScore(
        roundCount: Int,
        averageScore: Double,
        bestScore: Int?,
        fieldSteps: Int
    ): Int {
        if (roundCount == 0) return 0
        var score = 50
        if (roundCount >= 2) score += 20
        else if (roundCount == 1) score += 10

        if (averageScore in 72.0..88.0) score += 20
        else if (averageScore in 89.0..99.0) score += 10

        if (bestScore != null && bestScore < 90) score += 5
        if (fieldSteps > 15000) score += 5
        return score.coerceIn(10, 98)
    }

    private fun determineArchetype(
        activityScore: Int,
        financeScore: Int,
        mobilityScore: Int,
        achievementScore: Int,
        roundCount: Int,
        totalDrivingKm: Double
    ): Pair<String, String> {
        return when {
            roundCount >= 2 && achievementScore >= 70 -> {
                Pair(
                    "필드 중심의 액티브 스포츠 리더",
                    "주기적인 필드 라운드와 강도 높은 야외 활동을 통해 자기관리와 체력을 주도적으로 이끌고 있습니다."
                )
            }
            totalDrivingKm >= 300.0 && mobilityScore >= 70 -> {
                Pair(
                    "광역 네트워크 모빌리티 개척자",
                    "넓은 이동 반경과 효율적인 차량 동선을 바탕으로 다양한 생활 거점을 역동적으로 연결하고 있습니다."
                )
            }
            financeScore >= 70 && activityScore >= 60 -> {
                Pair(
                    "균형 감각이 탁월한 스마트 웰니스 아키텍트",
                    "규칙적인 지출 통제와 풍부한 일상 아카이빙을 조화롭게 양립하며 안정된 라이프스타일을 설계하고 있습니다."
                )
            }
            activityScore >= 60 -> {
                Pair(
                    "생생한 기록 중심의 라이프 아카이비스트",
                    "사진과 공간 기록을 세밀하게 축적하며 매일의 소중한 순간과 성취를 밀도 있게 관리하고 있습니다."
                )
            }
            else -> {
                Pair(
                    "나만의 라이프스타일을 개척하는 탐험가",
                    "모바일 기기에 축적되는 소비, 이동, 스포츠 데이터를 기반으로 최적의 라이프스타일 궤도를 탐색 중입니다."
                )
            }
        }
    }

    private fun generateInsights(
        financialVelocity: FinancialVelocity,
        mobilityOrbit: MobilityOrbit,
        golfTrajectory: GolfAthleticTrajectory,
        spatialFootprint: SpatioTemporalFootprint
    ): List<LifestyleInsight> {
        val list = mutableListOf<LifestyleInsight>()

        // 1. 재정 궤도 조언
        if (financialVelocity.totalExpense > 0) {
            if (financialVelocity.topCategoryRatio >= 45.0) {
                list.add(
                    LifestyleInsight(
                        id = "fin_concentration",
                        category = "재정 궤도",
                        title = "${financialVelocity.topCategoryName} 지출 비중 집중",
                        description = "전체 지출 중 ${financialVelocity.topCategoryName} 항목이 ${financialVelocity.topCategoryRatio}%를 차지하고 있습니다.",
                        actionGuidance = "주요 지출 항목의 정기/비정기 비율을 분리하고 예산 상한선을 설정해 안정적 현금흐름을 유지하세요.",
                        isPositive = false
                    )
                )
            } else {
                list.add(
                    LifestyleInsight(
                        id = "fin_balance",
                        category = "재정 궤도",
                        title = "균형잡힌 지출 포트폴리오",
                        description = "특정 항목에 과도하게 치우치지 않고 일평균 ${String.format("%,d", financialVelocity.dailyAverageExpense)}원의 안정된 소비 템포를 보입니다.",
                        actionGuidance = "현재의 건전한 소비 리듬을 유지하며 분기별 잉여 예산을 자기계발이나 자산 형성에 재투자하세요.",
                        isPositive = true
                    )
                )
            }
        }

        // 2. 스포츠 & 골프 궤적 조언
        if (golfTrajectory.roundCount > 0) {
            val scoreText = if (golfTrajectory.averageScore > 0) "평균 ${golfTrajectory.averageScore}타" else ""
            val stepText = if (golfTrajectory.totalFieldSteps > 0) "누적 ${String.format("%,d", golfTrajectory.totalFieldSteps)}보" else ""
            list.add(
                LifestyleInsight(
                    id = "golf_achievement",
                    category = "스포츠 성취",
                    title = "필드 라운드와 운동량 결합 성과",
                    description = "기간 내 총 ${golfTrajectory.roundCount}회 라운드를 소화하며 $scoreText, ${stepText}의 강도 높은 운동을 달성했습니다.",
                    actionGuidance = "평균 퍼트 수와 드라이브 티샷 편차를 집중 보완하여 한 단계 높은 스코어 안정권으로 진입하세요.",
                    isPositive = true
                )
            )
        }

        // 3. 모빌리티 & 시공간 동선 조언
        if (mobilityOrbit.totalDrivingKm > 0.0) {
            val ecoText = if (mobilityOrbit.averageFuelEconomy > 0) " (평균 연비 ${mobilityOrbit.averageFuelEconomy}km/L)" else ""
            list.add(
                LifestyleInsight(
                    id = "mobility_efficiency",
                    category = "모빌리티 동선",
                    title = "실주행 동선 데이터 확보",
                    description = "누적 ${mobilityOrbit.totalDrivingKm}km$ecoText 주행으로 이동 패턴이 활발히 기록되고 있습니다.",
                    actionGuidance = "정기 주유 및 타이어 공기압 점검을 통해 연비 효율을 지속적으로 최적화하세요.",
                    isPositive = true
                )
            )
        }

        return list
    }
}
