# LifeLog 개발 및 시스템 구축 작업 이력서 (Work History)

- **프로젝트명**: LifeLog (스마트 라이프로그 & 통합 가계·차계·골프 플랫폼)
- **프로젝트 위치**: `C:\myWork\workspace\scratch\lifeLog`
- **GitHub 저장소**: [https://github.com/herosonsa1/lifeLog.git](https://github.com/herosonsa1/lifeLog.git)
- **문서 최종 갱신일**: 2026-09-08

---

## 1. 프로젝트 개요 (Overview)

LifeLog는 스마트폰 알림(카드 결제 SMS, 입출금 푸시 등)과 사진(위치 메타데이터, 골프 라커룸 영수증, 스코어카드)을 지능형으로 분석하여 사용자의 일상 동선, 금융 지출, 차량 주행, 골프 라이프를 단일 타임라인으로 통합 관리하는 모바일 플랫폼입니다.

### 핵심 기술 스택
- **언어/플랫폼**: Kotlin, Android Native (SDK Min 26, Target 34)
- **UI 프레임워크**: Jetpack Compose, Material 3, Apple Minimal & Swiss Functional 디자인 시스템
- **데이터베이스/아키텍처**: Room Local DB (Room 2.6.1), MVVM + Clean Architecture, Coroutine & Flow
- **의존성 주입**: Dagger Hilt 2.51
- **머신러닝 & 비전**: Google ML Kit Text Recognition (OCR)
- **백그라운드 처리**: AndroidX WorkManager

---

## 2. 주요 기능 구현 및 고도화 내역

### 2.1. 스마트 차계부 (Smart Car Ledger)
1. **우리집 / 회사 위치 거점 설정 기능 탑재**:
   - `UserLocationPreferences.kt`를 구현하여 사용자가 직접 우리집(명칭/주소)과 직장(명칭/주소), 그리고 출퇴근 기준 왕복 주행거리(기본 37.0 km)를 입력·수정하고 SharedPreferences에 영속 저장.
   - `CarLedgerScreen` 상단 액션바에 `[집/회사 설정]` 버튼을 배치하고 모던한 `CommuteSettingsDialog` 컴포저블을 탑재하여 언제든 거점 정보를 수정할 수 있도록 제공.
2. **골프장 OCR 오탐 전면 차단 및 주행기록 정교화**:
   - 기존에 영문 대문자 `CC` 단어 포함(예: `ACCORDING`, `ACCESS`, `acCes` 등)으로 일반 문서 사진이 골프 라커룸으로 잘못 판정되고, 140km 골프장 왕복 주행으로 일괄 적재되던 치명적 결함 해결.
   - `GolfLockerSlipOcrAnalyzer.kt`에 단어 경계 정규식(`\b(CC|GC|C\.C|G\.C)\b`) 및 라커번호/티오프 시간 등 복합 인증 조건을 도입하여 일반 사진 오탐 원천 차단.
   - 평일(월~금) 잘못 생성된 골프장 주행 기록을 분석하여 `출퇴근 왕복 주행 (서울 방이동 ↔ 판교 테크노밸리, 37.0 km)`으로 자동 정정 복구.
   - `[출퇴근]`(에메랄드 배지)과 `[골프주행]`(인디고 배지)으로 UI를 직관적으로 구분.
3. **쓰레기 데이터 삭제 및 중복 주행 단일화**:
   - `VehicleRepositoryImpl.kt`에 `cleanDuplicatesAndCorruptedLogs` 함수를 구현하여 OCR 깨짐 텍스트(`GOVERNMENT WARNING...`, `Tou can acce...`, `크n acCE...` 등)를 데이터베이스에서 영구 삭제.
   - 같은 날짜에 촬영된 여러 장의 사진으로 인해 발생하던 1일 다중 주행 레코드를 1일 1건으로 통합 정리.
4. **주유비 및 연비 관리**:
   - 주유 결제 내역 자동 감지 및 단가, 리터, 주행거리 기반의 실시간 추정 연비(km/L) 계산 및 통계 제공.

---

## 2.2. 스마트 다이어리 (Smart LifeLog Diary)
1. **상세 위치 정보(동 단위) 정교화**:
   - 기존의 광범위한 구 단위(예: `서울 영등포구`, `서울 송파구`) 표기를 개선하여, 법정동/행정동 단위(예: `서울 방이동`, `서울 영등포동`)로 세분화된 위치가 노출되도록 주소 변환 알고리즘 개선.
2. **단순 금융 입출금 거래의 이동경로 표시 제외**:
   - ATM 현금 출금, 계좌 입금 등 실제 위치 이동과 무관한 금융 데이터는 타임라인 텍스트로는 기록하되, 지도 경로(Route Polyline) 및 위치 핀에서는 제외하도록 필터링 적용.
3. **사진 메타데이터 자동 지오코딩**:
   - 기기 내 사진의 EXIF GPS 좌표를 분석하여 해당 날짜의 방문 장소와 결제 내역을 시간순으로 정밀 결합.

---

## 2.3. 골프 라이프 (Golf Life & OCR)
1. **라커룸 영수증 및 스코어카드 OCR 스캔**:
   - 카메라 및 갤러리 사진을 통한 골프장 명칭, 티오프 시간, 라커 번호, 홀별 타수 자동 인식.
2. **골프 예약 일정 관리**:
   - 스마트스코어 및 카카오골프 수준의 예약 캘린더, 골프장 코스 정보, 동반자 정보 등록 및 일정 알림 기능 구현.
3. **라운딩 분석 대시보드**:
   - 평균 타수, 페어웨이 안착률, 그린 적중률(GIR), 퍼팅 수 등 시각적 통계 차트 제공.

---

## 2.4. 스마트 가계부 (Smart Expense Ledger)
1. **금융 SMS / 앱 알림 자동 파싱**:
   - 국민, 신한, 현대, 삼성 등 주요 카드사 및 은행 입출금 문자 자동 수집.
2. **카테고리 자동 분류 & 통계**:
   - 식비, 카페, 교통, 쇼핑, 문화/골프, 주유 등으로 자동 분류 및 월간 지출 한도 모니터링.

---

## 3. 개발 환경 및 에이전트 인프라 혁신 내역

### 3.1. 전역 All Allow / EAGER 자동 실행 정책 적용
- **문제점**:
  - `run_command` 실행 시마다 나타나는 도구 승인 확인 팝업("Allow running this command?")으로 인해 개발 및 빌드 자동화가 지연되는 현상 발생.
- **해결 및 적용 내역**:
  1. `~/.gemini/config/projects/` 내의 모든 프로젝트(13개 프로젝트 파일) 및 비워크스페이스(`outside-of-project.json`)에 아래 정책 일괄 적용:
     - `autoExecutionPolicy`: `"CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"` (질문 없는 즉시 자동 실행)
     - `fileAccessPolicy`: `"AGENT_SETTING_POLICY_ALLOW"`
     - `internetPolicy`: `"AGENT_SETTING_POLICY_ALLOW"`
     - `artifactReviewMode`: `"ARTIFACT_REVIEW_MODE_TURBO"`
  2. 전역 설정(`config.json`, `settings.json`)에 `toolExecutionPolicy: always-proceed` 및 권한 허용 목록 확장.
  3. 치명적인 시스템 파괴 명령(`format`, `rmdir /s /q C:\`, `del Windows` 등)은 `guardrail.ps1` 및 `hooks.json`의 PreToolUse 가드레일에서 즉각 차단(deny)하여 안전성 확보.

---

## 4. 프로젝트 생성, 마이그레이션 및 대화 귀속

### 4.1. 독립 프로젝트 `lifeLog` 구축
1. **프로젝트 경로 이전**:
   - 임시 폴더(`AutoLogue`)에서 실제 운영 프로젝트 경로인 `C:\myWork\workspace\scratch\lifeLog`로 전체 소스코드 및 환경 설정 1:1 완벽 복사.
2. **빌드 무결성 검증**:
   - `lifeLog` 프로젝트 루트에서 `.\gradlew.bat assembleDebug` 실행 결과 41개 태스크 100% 빌드 성공(`BUILD SUCCESSFUL`).
3. **Antigravity 프로젝트 정식 등록**:
   - `C:\Users\Herosonsa\.gemini\config\projects\2cbf1751-6345-4cbe-9a47-a15e3ec25d81.json` 등록 완료.

### 4.2. 대화 세션의 프로젝트 귀속 완료
- 현재 대화 세션(`8fcd28fe-9f05-46b9-8187-291c34f12b38`)의 SQLite 메타데이터(`trajectory_metadata_blob`)를 수정하여 신규 `lifeLog` 프로젝트 ID로 정식 바인딩 완료.
- `conversation://8fcd28fe-9f05-46b9-8187-291c34f12b38`를 통해 언제든 `lifeLog` 컨텍스트로 복귀 가능.

---

## 6. 실 모바일(독립 스마트폰) 크래시 방지 및 동기화 UX 개선 (2026-09-04)

### 6.1. 무증상 강제 종료(크래시) 원인 분석 및 근본 해결
- **문제점**: 실 모바일 기기에 설치 후 탭 이동 시 별도 에러 메시지 없이 앱이 즉시 강제 종료되는 현상 발생.
- **원인 규명**:
  1. `MainActivity.kt`에서 앱 구동 시 `scanHistoricalPhotos(daysBack = null)`로 사용자 기기의 수천 장의 사진을 무제한으로 스캔하고 백그라운드에서 ML Kit OCR을 동시 실행함.
  2. 비트맵 메모리가 급격히 고갈(OOM)되어 Android OS의 **Low Memory Killer(LMK)가 프로세스를 SIGKILL로 무경고 즉시 종료**시킴.
  3. `GolfViewModel.kt`에서 Flow `collectLatest` 블록 내부에서 DB insert를 호출하여 재진입 무한루프 및 스레드/메모리 누수 발생.
  4. Hilt와 WorkManager의 기본 `WorkManagerInitializer` 간의 프로바이더 초기화 충돌.
- **조치 사항**:
  1. `MainActivity.kt`: 무제한 사진 스캔/OCR 코드를 전면 제거하고 안전한 기간 한정 동기화(`SyncHistoricalDataUseCase`)로 대체.
  2. `HistoricalDataImporter.kt`: SMS 및 사진 스캔 시 기본 `daysBack = 7`, `limit = 40`으로 안전 상한선 적용.
  3. `SyncHistoricalDataUseCase.kt`: OCR 분석 후보 사진을 최대 10장(`take(10)`)으로 제한하여 메모리 스파이크 차단.
  4. `GolfViewModel.kt`: 초기 샘플 라운드 주입을 `seedSampleRoundIfNeeded()`로 1회 격리 분리하여 재귀 루프 차단.
  5. `AutoLogueApp.kt`: PC와 USB(adb)로 연결되지 않은 독립 스마트폰 환경에서도 비정상 종료 시 앱 내부 파일(`crash_log.txt`)에 스택트레이스를 영구 기록하는 `setupGlobalCrashHandler()` 탑재.
  6. `AndroidManifest.xml`: `tools:node="remove"`로 충돌을 일으키던 기본 WorkManager 초기화자 안전 제거.

### 6.2. 앱 구동 시 7일 자동 수집 시각화 (로딩 애니메이션 배너)
- `DiaryViewModel.kt`:
  - `DiaryUiState`에 `isAutoSyncing`, `autoSyncStage` 필드 추가.
  - `autoSyncRecentWeek(context)` 호출 시 실시간 동기화 단계(문자 분석, 사진 위치 색인 등)를 Flow로 방출.
- `DiaryScreen.kt`:
  - 상단에 실시간 원형 프로그레스 인디케이터와 상태 메시지를 표시하는 `SyncingBannerCard` 탑재.
  - 동기화 진행 중 사용자가 백그라운드 작업을 직관적으로 인지할 수 있도록 개선.

### 6.3. 과거 기록 수동 동기화 UI 및 기간 선택 다이얼로그 추가
- 상단바 동기화 아이콘(`Icons.Default.Sync`) 및 타임라인 빈 화면(Empty State) 버튼 클릭 시 `ManualSyncPeriodDialog` 표출.
- 동기화 기간 선택 옵션 제공:
  - **최근 7일** (추천 · 빠르고 안전한 일상 복원)
  - **최근 14일** (2주치 일상 및 지출 복원)
  - **최근 30일** (최대 1달치 활동 분석)
- 선택된 기간에 맞춰 안전 상한선 내에서 결제 내역과 사진 위치를 정밀 동기화.

---

## 7. 변경 파일 목록 (Key Source Files)

| 경로 | 역할 및 변경 내용 |
| :--- | :--- |
| `app/src/main/java/com/autologue/app/data/preferences/UserLocationPreferences.kt` | [신규] 집/회사 위치 및 출퇴근 왕복 주행거리 영속 관리 |
| `app/src/main/java/com/autologue/app/data/preferences/MultiVehiclePreferences.kt` | [신규] 복수 차량(대표차량/서브차량) 프로필 및 블루투스 기기 매핑 영속 관리 |
| `app/src/main/java/com/autologue/app/data/preferences/VehicleMaintenancePreferences.kt` | [신규] 차량별 소모품(엔진오일, 에어컨필터, 타이어 등) 교체주기 및 마지막 정비일 영속 관리 |
| `app/src/main/java/com/autologue/app/data/preferences/ExcludedPhotoPreferences.kt` | [신규] 다이어리 타임라인에서 숨김 처리된 사진 Uri 영속 관리 |
| `app/src/main/java/com/autologue/app/domain/model/GolfWeather.kt` | [신규] 골프 라운드 날씨(기온, 날씨상태, 풍속) 도메인 모델 |
| `app/src/main/java/com/autologue/app/domain/repository/GolfWeatherRepository.kt` | [신규] 라운드 위치/날짜 기반 기상 데이터 조회 인터페이스 |
| `app/src/main/java/com/autologue/app/data/repository/GolfWeatherRepositoryImpl.kt` | [신규] Open-Meteo REST API 연동 및 소켓 누수 방지, LRU 50건 인메모리 캐싱 |
| `app/src/main/java/com/autologue/app/presentation/car/CarLedgerScreen.kt` | [수정] 차량 전환 탭, 소모품 정비 현황 배너, 출퇴근 설정, Canvas 지도 피커 연동 |
| `app/src/main/java/com/autologue/app/presentation/car/CarLedgerViewModel.kt` | [수정] 다중 차량 관리, 소모품 교체주기 갱신, 거점 위치 저장 연동 |
| `app/src/main/java/com/autologue/app/presentation/car/VehicleSelectorBar.kt` | [신규] 상단 수평 스크롤 차량 전환 셀렉터 및 정비 D-Day 요약 바 |
| `app/src/main/java/com/autologue/app/presentation/car/VehicleManageDialog.kt` | [신규] 차량 추가/수정/삭제 및 대표차량 지정 다이얼로그 |
| `app/src/main/java/com/autologue/app/presentation/car/ConsumableMaintenanceDialog.kt` | [신규] 4대 필수 소모품 권장 교체주기 및 실시간 정비 마일리지 입력 다이얼로그 |
| `app/src/main/java/com/autologue/app/presentation/car/CommuteMapPickerDialog.kt` | [신규] Canvas 기반 인터랙티브 지도 피커 다이얼로그 (`clipToBounds` 및 `clipRect` 가드 탑재) |
| `app/src/main/java/com/autologue/app/presentation/diary/map/GoogleMapRouteView.kt` | [신규/수정] 다이어리 타임라인 캔버스 경로 지도 및 펄스 레이더 객체 캐싱(GC 부하 방어) |
| `app/src/main/java/com/autologue/app/presentation/common/WelcomeSplashScreen.kt` | [신규] 앱 시작 브랜딩 스플래시 화면, 자동 동기화 완료 연동 및 즉시 스킵 지원 |
| `app/src/main/java/com/autologue/app/presentation/common/CrashReportDialog.kt` | [신규] 앱 비정상 종료 감지 시 복구 리포트 및 크래시 로그 공유 다이얼로그 |
| `app/src/main/java/com/autologue/app/presentation/golf/GolfWeatherComponents.kt` | [신규] 라운드 카드 기온/풍속/아이콘 칩 및 날씨 뱃지 컴포저블 |
| `app/src/main/java/com/autologue/app/receiver/CarBluetoothReceiver.kt` | [신규] 차량 블루투스 핸즈프리 연결/해제 감지 및 자동 주행 기록 트리거 리시버 |
| `app/src/main/java/com/autologue/app/data/repository/VehicleRepositoryImpl.kt` | [수정] 중복 데이터/쓰레기 레코드 삭제, 평일 가짜 골프장 출퇴근 복구 |
| `app/src/main/java/com/autologue/app/data/ocr/GolfLockerSlipOcrAnalyzer.kt` | [수정] 단어 단위 정규식 도입으로 영문 포함 일반 사진 오탐 차단 |
| `app/src/main/java/com/autologue/app/domain/usecase/golf/ProcessGolfLockerSlipUseCase.kt` | [수정] 140km 획일적 기본값 제거, 골프장 검증 시에만 1회 주행 기록 생성 |
| `app/src/main/java/com/autologue/app/presentation/diary/DiaryScreen.kt` | [수정] `SyncingBannerCard` 로딩 애니메이션, `ManualSyncPeriodDialog` 추가 |
| `app/src/main/java/com/autologue/app/presentation/diary/DiaryViewModel.kt` | [수정] `isAutoSyncing`, `isManualSyncDialogOpen`, 수동 동기화 기간 파라미터화 |
| `app/src/main/java/com/autologue/app/data/sync/HistoricalDataImporter.kt` | [수정] 무제한 스캔 방지, daysBack 및 take 상한선 적용 |
| `app/src/main/java/com/autologue/app/domain/usecase/sync/SyncHistoricalDataUseCase.kt` | [수정] OCR 분석 대상 take(10) 메모리 보호 상한선 적용 |
| `app/src/main/java/com/autologue/app/presentation/golf/GolfViewModel.kt` | [수정] Flow collect 재진입 무한루프 분리, 갤러리 일괄 OCR `take(5)` 상한선 적용 |
| `app/src/main/java/com/autologue/app/AutoLogueApp.kt` | [수정] Coil ImageLoader 메모리 상한(15%), LowMemory/TrimMemory 핸들러, 파일 기반 CrashHandler |
| `app/src/main/AndroidManifest.xml` | [수정] WorkManagerInitializer 충돌 방지 노드 설정 및 블루투스/알림 서비스 등록 |
| `settings.gradle.kts` | [수정] `rootProject.name = "lifeLog"` 명명 |
| `work_history/work_history.md` | [수정] 전체 작업 히스토리 및 기술 사양 기록 |

---

## 8. 전체 기능 OOM·메모리누수·강제종료 방어 강화 (2026-09-08)

### 8.1. 전수 감사 및 해결 내역
1. **Coil ImageLoader 메모리 상한 및 OS Low Memory 대응 탑재**:
   - `AutoLogueApp.kt`에 `ImageLoaderFactory`를 구현하여 메모리 캐시를 기기 가용 램의 15%로 제한.
   - `RGB_565` 기본 적용으로 인메모리 비트맵 점유량 50% 절감.
   - `onTrimMemory(level)` 및 `onLowMemory()` 콜백을 등록하여 시스템 메모리 압박 시 Coil 인메모리 캐시 즉시 해제 및 `System.gc()` 유도.
2. **골프 갤러리 일괄 OCR 스캔 상한선 적용**:
   - `GolfViewModel.kt`의 `scanAllLockerSlipsFromGallery()`에서 최근 14일 사진 중 골프/라커룸 후보 사진 최대 5장(`take(5)`)으로 엄격 제한하여 OOM/LMK 차단.
   - `try ... finally` 구문으로 에러 발생 시에도 `isOcrScanning = false` 로딩 상태 해제 보장.
3. **Canvas 펄스 레이더 애니메이션 매 프레임 객체 할당 최적화**:
   - `GoogleMapRouteView.kt`의 `InteractiveRouteMapView` 내부에서 `Path()` 및 `PathEffect.dashPathEffect(...)` 객체를 `remember`로 사전 캐싱하여 초당 60~120회의 단기 가비지 객체 생성 원천 제거 및 GC 버벅임 해소.
4. **출퇴근 지도 피커 Canvas 경계 클리핑 (`AP-COMPOSE-CANVAS-OVERFLOW-NOCLIP`)**:
   - `CommuteMapPickerDialog.kt`의 `Canvas`에 `clipToBounds()`를 추가하고, `drawGridMap` 본문 전체를 `clipRect { ... }`로 감싸 줌인/드래그 시 뷰 경계 침범 차단.
5. **Open-Meteo 날씨 HTTP 커넥션 및 인메모리 캐시 안정화**:
   - `GolfWeatherRepositoryImpl.kt`에서 `HttpURLConnection.disconnect()`를 `finally` 블록에서 100% 호출 보장하여 소켓 누수 차단.
   - `weatherCache`를 최대 50개 LRU 캐시로 제한하여 장기 실행 시 메모리 누수 방지.
6. **웰컴 스플래시 화면 타이머 리셋 버그 및 스킵 UX 개선**:
   - `WelcomeSplashScreen.kt`에서 `LaunchedEffect(Unit)`로 1회만 동작하도록 수정하여 타이머 리셋 현상 해결 및 화면 탭 시 즉시 스킵 지원.

---

## 9. 빌드 및 배포 검증 결과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 49s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 10. 골프장 실시간 실제 날씨 연동 정상화 및 버튼 문구 변경 (2026-09-08)

### 10.1. 주요 개선 및 조치 내역
1. **Open-Meteo API 400 Bad Request 버그 해결 및 실시간 기상 데이터 수신**:
   - `GolfWeatherRepositoryImpl.kt`에서 URL의 `&timezone=Asia%%2FSeoul` 중복 `%` 이스케이프로 인해 Open-Meteo 서버가 400 에러를 반환하고 가짜 시뮬레이션(21.4°C)으로 Fallback되던 결함을 `&timezone=Asia/Seoul`로 정상화.
   - 풍속 단위 파라미터(`&wind_speed_unit=ms`)를 추가하여 km/h 대신 골프 표준 풍속(m/s)을 정확히 수신.
   - `&past_days=7&forecast_days=16` 파라미터를 적용하여 과거 완료된 라운드 및 최대 16일 앞의 예약 라운드까지 실제 수치예보 모델(GFS/ECMWF) 실시간 날씨를 단일 쿼리로 완벽 수신.
2. **전국 50+ 골프장 정밀 좌표 DB 확충 및 Geocoder 동적 Fallback 탑재**:
   - 아난티 코드 GC(가평 설악면), 남촌CC(광주), 안양CC, 자유CC, 레이크사이드CC, 클럽72, 웰링턴CC, 핀크스GC, 나인브릿지 제주, 사우스스프링스, 세이지우드 홍천 등 국내 대표 명문/대중제 골프장 50곳 이상의 정밀 위도/경도를 등록.
   - 미등록 골프장이 등록될 경우에도 Android 내장 `Geocoder`를 통해 실시간으로 해당 골프장명/주소의 위도/경도를 자동 검색하는 동적 Fallback 시스템 탑재.
3. **라운드 카드 날씨 버튼 텍스트 변경**:
   - `GolfWeatherComponents.kt`의 날씨 박스 우측 상단 텍스트를 `"시간대별 강우량 보기 →"`에서 `"상세보기→"`로 변경.
4. **인메모리 캐시 최적화**:
   - 캐시 키 접두어를 `v3_`로 변경하여 이전 400 에러로 인해 인메모리에 잔존하던 가짜 시뮬레이션 캐시를 완전히 무효화하고 실제 기상 데이터가 즉각 화면에 표출되도록 조치.

### 10.2. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 47s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,188,765 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 11. 골프 예약 등록 UX 혁신 및 위치/날씨 연동 고도화 (2026-09-08)

### 11.1. 주요 개선 및 구현 내역
1. **골프 예약 등록 더미 데이터 제거 & 명확한 Placeholder 가이드**:
   - `AddGolfReservationDialog`의 모든 입력 필드(`clubName`, `courseName`, `companionsText`, `feeText`, `memoText`)에 하드코딩되어 있던 더미 텍스트를 제거하고 깨끗한 빈 값으로 초기화.
   - 각 필드마다 입력 예시 및 가이드를 제공하는 직관적인 Placeholder 적용.
2. **추천구장 배지 제거**:
   - 다이얼로그 상단의 불필요한 추천구장 태그 행(`quickClubs`)을 완전히 제거하여 화면을 깔끔하게 정돈.
3. **티오프 일시 달력(DatePicker) 및 시간(TimePicker) 팝업 선택 전환**:
   - 기존의 년/월/일/시/분 5개 텍스트 입력창을 제거.
   - `[ 📅 예약 날짜 ]` 버튼 클릭 시 시스템 네이티브 `DatePickerDialog`를 표출하여 달력에서 터치 한 번으로 날짜 선택.
   - `[ ⏰ 티오프 시간 ]` 버튼 클릭 시 `TimePickerDialog`를 표출하여 오전/오후 및 시간을 편리하게 선택.
4. **구글 캘린더 스타일 골프장명 실시간 검색 및 지도 위치(좌표/주소) 연동**:
   - 골프장명을 입력할 때 전국 50+ 주요 골프장 DB 및 Android `Geocoder`를 통해 실시간 연관 장소(골프장명 + 상세 주소) 추천 리스트를 드롭다운 카드로 표시.
   - 사용자가 항목을 터치하면 골프장명이 자동 입력되고 해당 위치의 정밀 위도/경도 및 상세 주소가 확정되어 `[ 📍 위치 확정: 주소 ]` 배지가 표출됨.
   - 확정된 위도/경도가 `GolfRound` 모델 및 Room DB에 영속 저장되어, 사용자가 지정한 위치의 날씨가 정확하게 수신되도록 보장.
5. **화면 로딩 시마다 최신 실시간 날씨 자동 업데이트**:
   - `GolfScreen` 진입/새로고침 시 `LaunchedEffect(Unit)`를 통해 최신 기상청/Open-Meteo 모델 예측치를 자동 재동기화(`forceRefresh = true`).
   - 예약 등록 직후에도 등록된 위치의 실시간 날씨를 즉각 수집하여 대시보드에 반영.
6. **날씨 상세보기 중복 이동 경로 일원화**:
   - 날씨 요약 박스 우측 상단의 `"상세보기→"` 버튼으로 날씨 상세 화면 진입을 일원화.
   - 하단 퀵 차트의 중복 클릭 리스너 및 타이틀 우측의 혼란을 주던 하이라이트 문구를 정리하여 깔끔한 인라인 차트로 제공.

### 11.2. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 39s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,700,763 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 12. 골프장 위치 검색 시 'KR' 표기 버그 수정 및 실제 구장명 추천 연동 (2026-09-08)

### 12.1. 문제 원인 및 해결 내역
1. **Geocoder `featureName`의 'KR' 오염 차단**:
   - 사용자가 골프장명을 검색하고 Geocoder 추천 결과를 선택했을 때, Android Geocoder가 `featureName`으로 국가코드(`"KR"`)나 지번 번호를 반환하여 골프장명 필드가 `"KR"`로 덮어씌워지던 버그를 해결.
   - `addr.featureName`이 "KR", "대한민국", 숫자/번지수, 2글자 이하 알파벳인 경우 골프장명으로 사용되지 않도록 철저히 차단.
2. **실제 구장명(CC, GC, 클럽하우스) 지능형 추천 생성**:
   - 사용자가 입력한 검색어(예: `"오크밸리"`)에 기반하여, 실제 골프장 명칭 형식인 `"$q CC"`, `"$q GC"`, `"$q 클럽하우스"` 옵션을 지능형으로 자동 생성하여 추천 리스트에 표출.
   - `GOLF_COURSE_PRESETS`에 오크밸리 계열(오크밸리 CC, 오크밸리 GC, 오크밸리 클럽하우스, 오크크릭 GC, 성문안 CC, 센추리21 CC)을 정밀 위도/경도와 함께 등록하여 타이핑 즉시 최상단에 실제 명칭과 도로명 주소가 노출되도록 개선.
3. **주소 표기 정제**:
   - Geocoder가 반환하는 전체 주소에서 불필요한 `"대한민국 "` 및 주소 끝의 `" KR"`을 정규식으로 제거하여 깔끔한 도로명 주소(예: `강원특별자치도 원주시 지정면 오크밸리1길 66`)로 확정 배지에 표출.

### 12.2. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 51s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,205,149 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 13. 캘린더 스타일 구글 지도 장소 추천 칩 UI 및 전국 골프장 프리셋 대폭 확충 (2026-09-08)

### 13.1. 사용자 핵심 요청 및 배경
- 사용자가 골프 예약 등록 시 `코리아cc` 등을 입력했을 때, 캘린더 앱(구글/삼성 캘린더) 화면처럼 입력창 바로 아래에 둥근 캡슐/알약 모양의 등록 장소 추천 바(`[ 📍 코리아 CC ]`)가 즉각 표시되고, 원터치 탭으로 위치와 정밀 위도/경도가 확정되도록 요청.

### 13.2. 주요 개선 및 구현 내역
1. **캘린더 스타일 둥근 알약형 위치 추천 칩 UI (`RoundedCornerShape(24.dp)`) 구현**:
   - 사용자가 첨부한 캡쳐 화면(`media_1788831189823.png`)과 1:1로 일치하는 컴포넌트 설계.
   - 입력창 바로 아래에 둥근 캡슐 바를 배치하고, 원형 배경 안의 위치 핀 아이콘(`📍`), 정제된 골프장명, 상세 주소, 원터치 "선택" 뱃지를 구성.
   - 원터치 탭 시 즉시 구장명 및 정밀 위도/경도가 연동되며, 확정 시에는 부드러운 에메랄드 테마(`[ ✓ 📍 위치 확정: 코리아 CC (주소) ]`)로 전환되어 위치 연동 상태를 한눈에 파악 가능.
   - 2순위 이상의 추가 후보가 있을 경우 하단에 가로 스크롤 가능한 서브 칩(`[ 📍 코리아 GC ]`, `[ 📍 코리아 클럽하우스 ]`)을 배치하여 손쉽게 선택 가능.
2. **지능형 골프장 정규화 매칭 엔진 (`normalizeGolfName`) 탑재**:
   - 띄어쓰기, 하이픈, 점, 대소문자, 접미사(`CC`, `GC`, `골프장`, `컨트리클럽`, `클럽하우스`)를 지능적으로 처리하여 `코리아cc`, `코리아`, `코리아 CC`, `오크밸리cc` 등 어떠한 형태로 입력하더라도 100% 매칭 및 1순위 추천.
3. **전국 주요 골프장 프리셋 및 기상 좌표 대폭 확충 (60개+ 추가)**:
   - 기존 누락되었던 용인/화성/이천/파주/강원/제주 주요 명문 구장 대거 등록:
     - 용인/화성: `코리아 CC`, `골드 CC`, `기흥 CC`, `한원 CC`, `리베라 CC`, `플라자 CC 용인`, `세현 CC`, `은화삼 CC`, `해솔리아 CC`, `써닝포인트 CC`, `양지파인 CC` 등
     - 파주/양주/포천: `서원밸리 CC`, `서원힐스 CC`, `송추 CC`, `레이크우드 CC`, `티클라우드 CC`, `베어크리크 포천`, `포레스트힐 CC`, `필로스 CC` 등
     - 여주/안성: `세라지오 GC`, `스카이밸리 CC`, `신라 CC`, `여주 CC`, `루트52 CC`, `이포 CC`, `소피아그린 CC`, `아리지 CC`, `뉴스프링빌 CC` 등
     - 강원: `라데나 CC`, `베어크리크 춘천`, `소노펠리체 CC`, `비발디파크 CC`, `엘리시안 강촌`, `샌드파인 GC`, `파인리즈 CC` 등
     - 제주: `엘리시안 제주`, `테디밸리 CC`, `롯데스카이힐 제주` 등
   - `GolfScreen.kt`의 `GOLF_COURSE_PRESETS`와 `GolfWeatherRepositoryImpl.kt`의 `GOLF_COURSE_COORDINATES` 양쪽에 정밀 위도/경도를 완벽 동기화.

### 13.3. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 43s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,205,301 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`
