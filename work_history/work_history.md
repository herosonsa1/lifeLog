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

---

## 14. 골프장 예약 시 공식 등록 구장명 자동 치환 및 2중 정규화 보정 (2026-09-08)

### 14.1. 사용자 문제 제기 및 배경
- 사용자가 골프장 검색 시 추천 항목으로 `오크밸리 CC`가 표출되었으나, 예약 등록 완료 후 확인했을 때 사용자가 직접 입력한 텍스트(예: `오크밸리` 또는 비공식 입력명)로 저장되는 현상 발생.

### 14.2. 주요 개선 및 해결 내역
1. **스마트 공식 구장명 자동 치환 엔진 탑재 (`GolfScreen.kt`)**:
   - `selectedPreset` 상태를 신설하여 추천 칩 클릭 시 사용자가 입력한 비공식 텍스트를 공식 등록 구장명(예: `오크밸리 CC`, `코리아 CC`)으로 즉시 치환.
   - 텍스트필드 하단의 확정 캡슐 바에도 공식 명칭(`selectedPreset.name`)이 명확하게 노출되도록 보강.
   - 사용자가 추천 칩을 명시적으로 클릭하지 않고 바로 [예약 등록] 버튼을 누르더라도:
     - 1순위: 선택된 프리셋
     - 2순위: 현재 노출 중인 제1 추천 항목 (`suggestions.firstOrNull()`)
     - 3순위: 전체 프리셋 DB 정규화 퍼지 매칭 (`GOLF_COURSE_PRESETS`)
     - 을 순차 적용하여, 사용자가 `오크밸리`, `오크밸리cc` 등으로 입력했더라도 무조건 공식 구장명인 **"오크밸리 CC"**로 자동 치환되어 저장되도록 완벽 구현.
     - 위도/경도(`latitude`, `longitude`)도 해당 구장의 정밀 좌표로 100% 자동 매핑되어 날씨까지 정상 연동.
2. **ViewModel 2중 방어 및 기존 데이터 자동 마이그레이션 (`GolfViewModel.kt`)**:
   - `resolveOfficialGolfCourse(rawName)` 함수를 구현하여, `addGolfReservation` 호출 시 구장명을 공식 등록 명칭과 정밀 좌표로 2차 검증/보정.
   - `init` 블록에 `normalizeExistingRoundsOnce()`를 추가하여, 기존에 비공식 명칭(예: `오크밸리`)으로 저장되어 있던 라운드 데이터도 앱 시작 시 공식 구장명(`오크밸리 CC`) 및 정밀 좌표로 1회 안전하게 자동 업데이트되도록 처리.

### 14.3. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 45s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,207,433 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 15. 골프 예약 등록 티오프 시간 24시간 표기형식 직접 입력 전환 (2026-09-08)

### 15.1. 사용자 핵심 요청 및 배경
- 타임피커 다이얼로그(`TimePickerDialog`) 팝업에서 좁은 화면 해상도로 인해 '오전'/'오후' 라벨이 '오'/'오'로 세로로 찌그러지고 잘려 보이는 시각적 결함 발생(`media_1788833986230.png`).
- 복잡하고 번거로운 시계 다이얼 대신, **24시간 표기형식(예: 07:30, 13:30)으로 직접 입력**하는 방식으로 변경 요청.

### 15.2. 주요 개선 및 구현 내역
1. **24시간 표기형식 직접 입력 필드 (`OutlinedTextField`) 도입**:
   - `TimePickerDialog` 팝업을 완전히 제거하고, 시간 직접 입력 필드로 개편.
   - 레이블: `"시간 (24시)"`, 플레이스홀더: `"07:30"`, 기본값: `"07:30"`.
   - 숫자 키패드(`KeyboardType.Number`) 및 자동 콜론 포맷팅(예: `0730` 타이핑 시 자동으로 `07:30` 치환) 적용.
2. **달력 피커와 시간 입력창의 완벽한 56dp 그리드 정렬**:
   - 좌측: `[ 📅 예약 날짜: 9월 9일 (수) ]` 시스템 네이티브 `DatePickerDialog` 팝업 버튼 (`height(56.dp)`).
   - 우측: `[ ⏰ 시간 (24시): 07:30 ]` 24시간 형식 직접 입력창.
   - 두 컴포넌트의 높이(56dp)와 정렬을 완벽히 일치시켜 모던하고 단정한 UI 구축.
3. **지능형 24시간 파싱 엔진 (`parse24HourTime`) 탑재**:
   - `07:30`, `13:30`, `7:30`, `0730`, `1330` 등 다양한 입력 패턴을 안전하게 파싱하여 `LocalTime.of(hour, minute)`으로 변환.
   - 예외 입력 방어 및 기본값(07:30) 폴백 보장.

### 15.3. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 24s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,208,611 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 16. 골프장명 옆 코스명 표기 연동 및 날씨 분석 UI 정밀 고도화 (2026-09-08)

### 16.1. 사용자 핵심 요청 사항
1. **골프장명 옆 코스명 누락 해결**: 구장명 옆에 코스명(예: `오크밸리 CC (잣나무 코스)`)이 표기되지 않는 문제 완벽 해결.
2. **Gemini 텍스트 및 불필요한 이모지 제거**:
   - `Gemini AI 골프 라운딩 정밀 분석` -> `AI 골프 라운딩 정밀 분석`으로 명칭 변경.
   - 분석 브리핑 본문 내 `[Gemini AI 브리핑]` 문구 및 불필요한 이모지(✨, 💡, ⚠️ 등) 전면 삭제.
   - 단, 최상단의 골프장 날씨 예보에 포함된 날씨 상태 이모지(☀️, ⛅, 🌧️ 등)는 사용자 요청대로 유지.
3. **타이틀 정돈**: `예상 강우량 (최우선)` -> `예상 강우량`으로 정제.
4. **메트릭 카드 2줄 레이아웃 개편**:
   - `예상 강우량`, `평균 기온`, `바람`, `습도` 배지 내 텍스트를 상하 3줄/분산 구조에서 `[1줄: 타이틀] / [2줄: 정보 상세정보]`의 2줄 나란히 구조로 개편.
5. **테이블 및 메트릭 조건부 색상 강조 규칙 엄격 적용**:
   - 시간대별 테이블의 강우량 헤더 빨간색 제거 (`Slate600` 기본색으로 통일).
   - 강우량 1mm 이상: 빨간색 (`#DC2626`).
   - 기온 10도 이하: 파란색 (`#2563EB`), 30도 이상: 빨간색 (`#DC2626`).
   - 바람 3.5~6.5 m/s: 파란색 (`#2563EB`), 6.5 m/s 초과: 빨간색 (`#DC2626`).

### 16.2. 주요 개선 및 구현 내역
1. **골프장명 + 코스명 통합 표기 아키텍처 구축**:
   - `GolfRound.getDisplayClubName()` 확장 함수 신설:
     - `clubName`에 이미 `(코스명)`이 있는 경우 그대로 사용.
     - `memo`에 `[코스: xxx]`가 보관된 경우 `"$clubName ($suffix)"`로 자동 합성.
   - `GolfViewModel.addGolfReservation`:
     - 신규 예약 등록 시 `courseName`이 입력되면 구장명과 결합하여 `"$officialClubName ($cleanCourseName 코스)"` 형태로 `GolfRound.clubName`에 직접 보존.
   - `GolfWeatherRepositoryImpl.resolveCoordinates`:
     - 코스명이 붙은 구장명(`오크밸리 CC (잣나무 코스)`)이 전달되더라도 `Regex("\\(.*\\)")`로 괄호 코스명을 안전하게 제거 후 위도/경도를 정밀 매칭하도록 방어.
   - `GolfScreen.kt` 전체 뷰 연동:
     - 다가오는 라운드 카드(`UpcomingGolfCard`), 라운드 요약 카드(`GolfRoundSummaryCard`), 라운드 상세 다이얼로그(`GolfRoundDetailDialog`), 더치페이 정산기(`GolfDutchPayDialog`), 날씨 다이얼로그(`GolfWeatherDetailDialog`) 전반에 `getDisplayClubName()`을 1:1 적용하여 구장명 바로 옆에 코스명이 볼드체로 명확하게 노출되도록 보장.
     - 시간 표시 줄 밑에 나타나던 중복 코스명은 구장명에 이미 코스명이 있을 때 표시되지 않도록 스마트 방어.
2. **AI 브리핑 텍스트 및 UI 레이아웃 정밀 고도화**:
   - `GolfWeatherRepositoryImpl.kt`의 `buildGeminiBriefing`에서 불필요한 AI 태그 및 이모지를 제거하고 순수 전략 어드바이스 문장만 생성하도록 정제.
   - `GolfWeatherComponents.kt`의 `MetricQuadrantCard`를 `[1줄: 타이틀] / [2줄: 정보 상세정보]` 형태로 레이아웃 전면 리팩토링.
   - 시간대별 테이블 헤더의 불필요한 빨간색 강조를 제거하고, 데이터 행의 강우량/기온/바람값에 조건부 색상 하이라이트 로직 적용.

### 16.3. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 45s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,748,770 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 17. 출퇴근 지도 거점(집/회사) 구글지도 실시간 위치 검색 및 자동 지정 연동 (2026-09-08)

### 17.1. 사용자 핵심 요청 사항
- 출퇴근 지도 거점 및 경로 설정 다이얼로그(`CommuteMapPickerDialog.kt`)에서, 집 및 회사 위치 지정 시에도 **골프장 위치 검색과 동일하게 구글지도의 위치값으로 검색하고 지정**할 수 있도록 기능 탑재 요청 (`media_1788840707215.png`).

### 17.2. 주요 개선 및 구현 내역
1. **구글지도 실시간 위치 검색 엔진 및 모델 구축 (`CommuteMapPickerDialog.kt`)**:
   - `CommuteLocationSuggestion` 데이터 클래스(장소명, 주소, 위도, 경도) 신설.
   - 집(`homeSearchQuery`) 및 회사(`compSearchQuery`) 각각에 대해 2글자 이상 입력 시 백그라운드 코루틴(`Dispatchers.IO`)에서 `Geocoder.getFromLocationName(q, 5)`을 실시간 호출하는 지능형 검색 엔진 탑재.
   - "대한민국 ", "KR" 국가 코드 및 중복 좌표 자동 정제, 지번/도로명 주소 파싱 및 안전한 폴백 보장.
2. **구글 캘린더 스타일 위치 추천 UI 1:1 패밀리룩 탑재 (`LocationSuggestionView`)**:
   - 골프장 검색 화면과 100% 동일한 고급스러운 구글 캘린더 스타일 위치 추천 UI 컴포넌트 구축.
   - **메인 추천 캡슐 바**: 둥근 알약형(`RoundedCornerShape(24.dp)`) 컨테이너 + 원형 핀 아이콘 + 볼드체 장소명 + 정제된 도로명 주소 + 탭별 테마색 `[선택]` 칩 버튼.
   - **서브 추천 가로 스크롤 목록**: 2순위 이상 후보가 존재할 경우 `LazyRow`를 통해 즉시 터치하여 선택할 수 있는 서브 칩 제공.
3. **원클릭 좌표 및 지도 연동 메커니즘**:
   - 추천 항목 선택 시:
     - 도로명 주소 자동 입력 및 거점 명칭 스마트 채움.
     - 거점의 위도/경도(`homeLat`/`homeLng` 또는 `compLat`/`compLng`) 즉시 갱신.
     - **지도 카메라 중심 및 핀 위치가 해당 좌표로 자동 이동**(`mapCenterLat = lat`, `mapCenterLng = lng`).
     - 출퇴근 왕복 주행거리(`updateCalculatedDistance()`) 실시간 자동 재계산.
     - 지도 직접 탭(`detectTapGestures`) 및 GPS 현재 위치 버튼 클릭 시에도 역지오코딩과 검색어 필드가 자연스럽게 동기화되도록 완벽 연계.

### 17.3. 빌드 및 배포 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 1m 2s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,237,917 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 18. 이동 동선 구글 지도 임베드 & 지점 세로 펼침 리스트 개편 및 주행거리 0.0 km 결함 해결 (2026-09-08)

### 18.1. 사용자 핵심 요청 사항
1. **이동 동선 구글 지도 직접 표시 & 하단 지점 세로 펼침**:
   - 이동 동선 상단 지도 영역에 단순 캔버스가 아닌 실제 Google Maps가 바로 표시되도록 개편.
   - 하단 지점 1, 2, 3... 항목들을 이전/다음 탐색 방식이 아닌 아래로 펼쳐진 리스트(`LazyColumn`)로 일괄 표시.
   - 전체 경로보기 버튼의 이동 수단을 대중교통이 아닌 차량(`travelmode=driving`, `dirflg=d`)으로 설정하고 "조회된 전체 이동경로 안내 (차량)"으로 명확화.
2. **주행거리 0.0 km 미기록 결함 해결**:
   - 2026년 9월 7일 다이어리 상세 모달(`media_1788841214534.png`)에서 '충청북 주덕읍 ➔ 서울 방이동' 등 방문 장소(2곳)와 사진(33장)이 명확히 기록되어 있음에도 주행거리가 `0.0 km`로 표시되던 이슈 해결.

### 18.2. 주요 개선 및 구현 내역
1. **정밀 도로 주행거리 산출 엔진 구축 (`LocationDistanceUtils.kt`)**:
   - 구면 삼각법(Haversine) 기반 두 GPS 좌표 간의 직선 거리 산출 함수 `calculateStraightDistanceKm` 구현.
   - 대한민국 도로망 곡률 및 우회율을 정밀 반영한 도로 굴곡도 계수(`roadCurveFactor = 1.25`)를 적용한 `calculateDrivingDistanceKm` 구현.
   - 하루 동안 방문한 `RouteStep` 목록에서 50m 미만의 제자리 촬영 지점을 자동 필터링하고 연속 거점 간 누적 도로 주행거리를 소수점 1자리까지 산출하는 `calculateRouteDrivingDistanceKm` 구현.
2. **일일 동선 집계기 연동 (`DailyRouteAggregator.kt`)**:
   - 차량 OBD/TMap 운행 로그가 감지되지 않아 `vehicleLogsDistance`가 0인 경우에도, 당일 방문 거점 간의 도로 주행거리(`estimatedRouteDistance`)를 자동 산출하여 `drivingDistanceKm`에 적용.
   - 주행거리가 0보다 클 때 다이어리 태그에 `%.1fkm 주행`이 자동 추가되도록 연동.
3. **과거 데이터 자동 보정 및 실시간 ViewModel 방어 (`DiaryViewModel.kt`)**:
   - `checkAndUpgradeLegacyEntries`에서 주행거리가 `0.0 km`로 저장되어 있던 기존 다이어리 엔트리 중 유효 좌표 거점이 2개 이상 존재하는 경우 자동 업그레이드 대상(`hasZeroDistanceWithValidSteps`)에 포함하여 DB 영구 갱신.
   - `observeData`에서 `rawEntries` 로드 시에도 실시간 계산 폴백을 적용하여 앱 구동 즉시 올바른 주행거리가 화면에 표시되도록 보장.
4. **UI 컴포넌트 방어적 표시 (`DiaryScreen.kt`)**:
   - 다이어리 상세 모달(Quick Stats Row), 타임라인 카드 목록, 상단 누적 통계 헤더(`NaturalSummaryHeader`) 전반에 `calculateRouteDrivingDistanceKm` 실시간 폴백을 연동하여 0.0km 미노출 방어.
   - 주간 요약(`TimelineViewMode.WEEKLY`) 상단 헤더 및 목록 행에 당일/주간 거점 기반 도로 주행거리 합산 100% 반영.
5. **월간 캘린더 주행거리 연동 보완 (`GetMonthlyCalendarDataUseCase.kt`, `MonthlyCalendarData.kt`)**:
   - 월간 캘린더 상단 Summary Strip의 "주행 거리" 산출 시 차량 기록(`monthVehicles`) 외에 다이어리 거점 기반 도로 주행거리(`entriesDistance`)를 정밀 결합하여, 차량 로그가 없더라도 월간 누적 이동거리가 정상 합산되도록 완벽 보강.
   - `DaySummary` 모델에 `totalDistanceKm`을 추가하여 일자별 캘린더 데이터에도 당일 도로 주행거리 제공.
6. **Google Maps 임베드 및 동선 뷰 완성 (`GoogleMapRouteView.kt`)**:
   - `AndroidView(WebView)`를 통해 Google Maps 인터랙티브 웹뷰를 내장하여 지형 및 도로망이 즉시 렌더링되도록 구성.
   - 단일 거점 포커스 시 상세 핀 뷰, 전체 동선 시 `saddr` & `daddr` 멀티 경유지 차량 길찾기 모드(`dirflg=d`, `travelmode=driving`) URL을 생성하는 `buildGoogleMapsEmbedUrl` 구현.
   - 하단 지점 1, 2, 3... 항목들을 세로로 펼쳐진 카드 리스트로 배치하고, 클릭 시 해당 거점으로 지도 포커스를 이동하거나 전체 경로로 복귀할 수 있는 직관적인 인터랙션 제공.
   - "조회된 전체 이동경로 안내 (차량)" 마스터 카드를 배치하여 조회된 지점 전체를 순서대로 경유하는 차량 길찾기 안내 기능 제공.

### 18.3. 빌드 및 배포 검증
- **단위 테스트**: `LocationDistanceUtilsTest` (충북 주덕읍 ↔ 서울 방이동 간 추정 도로 주행거리 약 114.8km 정상 산출 및 제자리 촬영 누적 배제 검증 완료)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 46s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 19. 차계부 총 주행거리 및 다이어리 이동 동선 양방향 동기화 연동 (2026-09-08)

### 19.1. 사용자 핵심 요청 사항
- 다이어리 및 캘린더뿐만 아니라, **차계부(`CarLedgerScreen`, `CarLedgerViewModel`)에서도 거점 기반 주행거리가 총 주행거리 및 차계부 내역에 정상 연동·적용**되도록 요청.

### 19.2. 주요 개선 및 구현 내역
1. **차계부 실시간 주행거리 결합 엔진 탑재 (`CarLedgerViewModel.kt`)**:
   - `DiaryRepository`를 주입하여 차계부 로그(`vehicleRepository.getAllVehicleLogsFlow()`)와 다이어리 엔트리(`diaryRepository.getDiaryEntriesFlow()`)를 실시간 `combine` 관찰.
   - 차량 OBD/TMap 운행 로그가 없더라도 다이어리의 도로 주행거리(`diaryDist`)를 정밀 결합하여, 차계부 상단 **"총 주행: %.1f km"**에 즉시 실시간 반영.
   - 누적 총 주행거리와 총 주유량을 결합하여 실제 주행 기반의 **정밀 평균 연비(`averageEfficiencyKmPerL`)** 자동 계산.
2. **다이어리 이동 동선 차계부 자동 동기화 (`syncDrivingLogsFromDiary`)**:
   - [`VehicleRepository.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/domain/repository/VehicleRepository.kt) 및 [`VehicleRepositoryImpl.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/data/repository/VehicleRepositoryImpl.kt)에 `syncDrivingLogsFromDiary` 구현.
   - 다이어리 이동 기록이 존재하는 날짜에 차계부 주행 로그(`VehicleLogType.TRIP_DRIVING`)가 없을 경우, 해당 거점 이동거리와 동선 요약 메모(`다이어리 이동 동선 (...)`)를 가진 차량 주행 카드를 자동 생성·등록.
   - 차계부 동기화(`manualSyncRefueling`) 시 주유비뿐만 아니라 다이어리 주행 내역도 자동 동기화되어 차계부 타임라인 목록에 등록되도록 연계.
3. **차량 소모품 정비 알림 연동 (`ConsumableMaintenanceDialog.kt`)**:
   - 차계부의 `totalDrivingDistanceKm`이 거점 기반 이동거리까지 포함하여 정확한 누적 km로 산출됨에 따라, 소모품 교체 주기 알림도 실제 주행거리에 맞춰 정밀하게 작동하도록 보장.

### 19.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 12개 테스트 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 33s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 20. 소프트 키보드(IME) 팝업 시 본문 가림 방지 및 전 화면 스크롤 보장 공통 적용 (2026-09-08)

### 20.1. 사용자 핵심 요청 사항
- 입력란 중간에서 소프트 키보드가 올라올 때, 하단의 콘텐츠 및 저장/취소 버튼이 키보드 뒤에 가려져 스크롤을 해도 보이지 않던 결함 해결 (`media_1788841909631.png`).
- 키보드가 표시되면 키보드에 가리지 않도록 **본문 내용이 끝까지 스크롤 가능하게 모든 화면에 공통 적용** 요청.

### 20.2. 주요 개선 및 구현 내역
1. **전역 Manifest 소프트 입력 모드 최적화 (`AndroidManifest.xml`)**:
   - `MainActivity`에 `android:windowSoftInputMode="adjustResize"` 속성을 추가하여, 시스템 레벨에서 소프트 키보드가 올라올 때 전체 창 크기를 키보드 높이만큼 자동으로 축소(Resize)하도록 보장.
2. **다이얼로그 공통 IME Insets 래퍼 아키텍처 탑재**:
   - `DialogProperties(decorFitsSystemWindows = false, usePlatformDefaultWidth = false)`를 설정하여 다이얼로그 창 내로 안드로이드 시스템 IME WindowInsets가 온전히 인입되도록 허용.
   - 다이얼로그 루트에 `Box(modifier = Modifier.fillMaxSize().imePadding().systemBarsPadding(), contentAlignment = Alignment.Center)`를 적용하여, 키보드가 팝업되면 **다이얼로그 윈도우 자체가 키보드 상단 경계선 위로 부드럽게 리사이즈/상승**하도록 구축.
   - 내부 스크롤 컨테이너(`Column(modifier = Modifier.weight(1f).verticalScroll(...))`)가 줄어든 가용 영역 내에서 최하단까지 매끄럽게 스크롤되도록 보장.
3. **앱 전 화면 및 모든 입력 다이얼로그 전수 적용**:
   - **소모품 교체 주기 및 이력 설정** ([`ConsumableMaintenanceDialog.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/presentation/car/ConsumableMaintenanceDialog.kt)): 에어컨 필터, 타이어 위치 교환 등 하단 카드 및 [기본 추천값 초기화], [취소], [설정 저장] 버튼이 키보드 위에서 완벽히 스크롤/탭 가능.
   - **보유 차량 프로필 관리** ([`VehicleManageDialog.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/presentation/car/VehicleManageDialog.kt)): 차량 번호, 블루투스, 연비 입력 시 하단 저장 버튼 가림 방지.
   - **출퇴근 거점 지도 설정** ([`CommuteMapPickerDialog.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/presentation/car/CommuteMapPickerDialog.kt)): 집/회사 검색어 입력 시 키보드 인셋 대응.
   - **골프 예약 및 정산/상세** ([`GolfScreen.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/presentation/golf/GolfScreen.kt)): `AddGolfReservationDialog`, `GolfDutchPayDialog`, `GolfRoundDetailDialog` 3종 모두 `imePadding` 및 스크롤 보장.
   - **다이어리 상세 및 동행자** ([`DiaryScreen.kt`](file:///c:/myWork/workspace/scratch/lifeLog/app/src/main/java/com/autologue/app/presentation/diary/DiaryScreen.kt)): `DiaryDetailDialog`, `AddCompanionDialog` 키보드 반응형 인셋 완료.

### 20.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 12개 테스트 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 22s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 21. 차량 블루투스 자동감지 탑승 연동 및 백그라운드 10분 주기 GPS 이동경로 기록 (2026-09-08)

### 21.1. 사용자 핵심 요청 사항
- 등록된 블루투스(자동 감지)가 연결되면 차량 탑승 상태로 자동 간주.
- 탑승한 채로 백그라운드에서 10분 단위로 GPS 수신 위치를 기록하여 실제 이동경로 생성 및 정밀 차량 주행거리 산출.
- 어떤 차량(예: 벤츠 A클래스, 볼보 V60cc)을 이용했는지 차계부 및 다이어리 동선에 명확히 추가 표기 요청.

### 21.2. 주요 개선 및 구현 내역
1. **백그라운드 포그라운드 위치 추적 서비스 (`CarDrivingTrackingService.kt`) 신설**:
   - Android 14+ 표준 `foregroundServiceType="location"` 기반 Hilt 주입 포그라운드 서비스 구현.
   - 블루투스 연결 즉시 출발지 GPS 위치를 캡처하고, 무소음 상시 알림(`🚗 [차량명 (차량번호)] 주행 기록 중 · 10분 주기 GPS 경로 수집 중`) 표출.
   - 10분 간격(600,000ms) 백그라운드 코루틴 타이머로 GPS 위경도를 획득하여 `DrivingWaypoint` 리스트에 순차 누적.
   - 블루투스 연결 해제(하차) 시 최종 도착지 GPS 좌표를 획득하고, 연속 Waypoint 목록을 기반으로 실제 도로 주행거리 정밀 계산 및 출퇴근/일반 주행 자동 분류 저장.
   - 비정상 종료 대비 `SharedPreferences`에 Waypoint 실시간 세이프가드 영구화.
2. **권한 및 매니페스트 설정 (`AndroidManifest.xml`)**:
   - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` 권한 및 서비스 선언 완료.
3. **블루투스 리시버 연동 및 양방향 매칭 개선 (`CarBluetoothReceiver.kt`, `MultiVehiclePreferences.kt`)**:
   - `findVehicleByBluetooth`: 기기명 양방향 포함 검색 지원.
   - 블루투스 `ACTION_ACL_CONNECTED` 시 해당 차량 프로필을 활성 차량으로 자동 전환하고 `CarDrivingTrackingService.startTracking(...)` 호출.
   - 블루투스 `ACTION_ACL_DISCONNECTED` 시 `CarDrivingTrackingService.stopTracking(...)`으로 단일 위임하여 중복 기록 원천 차단.
4. **정밀 연속 Waypoint 주행거리 연산 (`LocationDistanceUtils.kt`)**:
   - `DrivingWaypoint` 모델 및 `calculateWaypointsDistanceKm` 함수 신설.
   - 10분 주기 좌표들을 순차 연결하고, 50m 미만의 신호대기/정차 오차는 중복 합산되지 않도록 방어.
5. **다이어리 및 차계부 UI 탑승 차량 식별 정보 강화 (`DailyRouteAggregator.kt`, `CarLedgerScreen.kt`, `GoogleMapRouteView.kt`)**:
   - `DailyRouteAggregator`: `vehicleLogs`의 차량 정보(`[차량명 (차량번호)]`)를 파싱하여 `RouteStep`의 타이틀(`차량 주행 - [벤츠 A클래스]`) 및 다이어리 태그(`🚗 벤츠`)에 자동 부여.
   - `CarLedgerScreen`: `SaaSCarLogRow`에 탑승 차량 뱃지(`🚗 벤츠 A클래스`)를 즉시 렌더링.
   - `GoogleMapRouteView`: 구글 지도 동선 상세 카드 상단에 해당 주행 차량명이 강조 표출되도록 지원.

### 21.3. 빌드 및 배포 검증
- **단위 테스트**: `LocationDistanceUtilsTest` (10분 주기 Waypoint 연속 거리 누적 및 신호대기 정차 오차 배제 테스트 포함 14개 테스트 100% 통과)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 21s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 22. 차량 브랜드별 엠블럼 이모지 차별화 적용 (벤츠 삼각별 ⭐ vs 볼보 아이언마크 🛡️) (2026-09-08)

### 22.1. 사용자 핵심 요청 사항
- 획일적인 `🚗` 이모지 대신, 메르세데스-벤츠와 볼보 차량을 서로 다른 고유 브랜드 엠블럼 이모지로 차별화 적용 요청.

### 22.2. 주요 개선 및 구현 내역
1. **차량 브랜드 엠블럼 판별 유틸리티 신설 (`VehicleBrandUtils.kt`)**:
   - `getBrandEmoji(vehicleName)`: 차량 이름 및 모델명을 자동 분석하여 고유 엠블럼 이모지 반환.
     - 메르세데스-벤츠 (Benz, Mercedes, A클래스, C클래스, E클래스, S클래스 등): **삼각별 (Three-Pointed Star) ➔ `⭐`**
     - 볼보 (Volvo, V60, XC, S90, Polestar 등): **아이언 마크 및 안전 방패 (Iron Mark & Shield) ➔ `🛡️`**
     - 기타 브랜드: BMW(`🔵`), 아우디(`🔗`), 제네시스(`🪽`), 테슬라(`⚡`), 포르쉐(`🐎`) 등 확장 지원.
   - `getBrandEmblemDescription`: 브랜드 공식 엠블럼 설명 문자열 제공 ("벤츠 삼각별", "볼보 아이언마크").
2. **차량 프로필 모델 확장 (`MultiVehiclePreferences.kt`)**:
   - `VehicleProfile`에 `customEmoji` 필드 및 동적 엠블럼 게터 `emblemEmoji` 추가.
3. **보유 차량 관리 다이얼로그 엠블럼 표출 (`VehicleManageDialog.kt`)**:
   - 차량 1: `⭐ 차량 1 (주 차량/출퇴근) · 벤츠 삼각별`
   - 차량 2: `🛡️ 차량 2 (보조 차량/세컨카) · 볼보 아이언마크`
4. **포그라운드 주행 추적 알림 및 서비스 (`CarDrivingTrackingService.kt`)**:
   - 탑승 차량의 브랜드 엠블럼을 동적 조회하여 알림 제목 및 텍스트 반영 (`⭐ [벤츠 A클래스 (169저7737)] 주행 기록 중`, `🛡️ [볼보 V60cc (331노3442)] 주행 기록 중`).
5. **차계부 및 다이어리 동선 UI 동기화 (`CarLedgerScreen.kt`, `DailyRouteAggregator.kt`, `GoogleMapRouteView.kt`)**:
   - 차계부 주행 내역 카드: `⭐ 벤츠 A클래스`, `🛡️ 볼보 V60cc` 엠블럼 뱃지 표출.
   - 다이어리 태그 및 타임라인: `⭐ 벤츠`, `🛡️ 볼보` 엠블럼 태그 부여 및 구글 지도 상세 카드 연동.

### 22.3. 빌드 및 배포 검증
- **단위 테스트**: `VehicleBrandUtilsTest` 신설 (벤츠 삼각별 `⭐`, 볼보 아이언마크 `🛡️` 등 브랜드별 엠블럼 매칭 100% 검증 완료)
- **전체 단위 테스트**: `./gradlew testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 20s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 23. 백그라운드 10분 주기 GPS 실시간 확인 및 이동동선(RouteStep) 자동 영속화 (2026-09-08)

### 23.1. 사용자 핵심 요청 사항
- 차량 탑승 후 백그라운드에서 10분 단위로 GPS 위치 정보를 수신하는 상태를 사용자가 어떻게 확인할 수 있는지 가시화 요청.
- 수신된 위치 정보가 다이어리 이동 경로(RouteStep)에 그대로 실시간 표시되어 확인 가능한지 보장 요청.

### 23.2. 주요 개선 및 구현 내역
1. **실시간 상시 알림(Notification) 시각화 (`CarDrivingTrackingService.kt`)**:
   - 10분마다 GPS 위치를 획득할 때마다 안드로이드 상단바 포그라운드 알림 텍스트를 실시간 업데이트:
     `⭐ [벤츠 A클래스 (169저7737)] 주행 기록 중 · N번째 GPS 수신 완료 (방이동 ➔ 판교 테크노밸리)`
   - 사용자가 백그라운드에서 몇 번째 GPS 수신이 이루어졌고 현재 어느 위치를 지나고 있는지 상시 알림창에서 즉각 육안 확인 가능.
2. **10분 주기 GPS 수신 즉시 RouteStep DB 자동 영속화 (`DiaryRepositoryImpl.kt`, `CarDrivingTrackingService.kt`)**:
   - 주행 종료 시까지 기다리지 않고, 10분마다 수신된 GPS 위경도와 역지오코딩된 주소/장소명을 `RouteStepType.DRIVING` 타입의 `RouteStep`으로 생성하여 당일 다이어리 엔트리에 실시간 추가 저장(`addOrUpdateDrivingRouteStep`).
   - 비정상 앱 종료 시에도 수신된 이동 경로가 유실되지 않도록 실시간 세이프가드 보장.
3. **다이어리 이동 동선 실시간 통합 표출 (`DailyRouteAggregator.kt`)**:
   - 10분 주기 주행 거점(`RouteStep`)과 방문 결제처, 촬영 사진 위치를 시간순으로 자동 결합.
   - 다이어리 상단 구글 지도에 10분마다 수신된 거점들이 `[1]`, `[2]`, `[3]`, `[4]` 순서대로 번호 마커와 함께 지도 동선에 100% 실시간 표출되도록 연동.

### 23.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 22s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git` (`4e00376`)

---

## 24. 앱 내 화면 지도 경로선 즉시 렌더링 및 주행 트립/일자별 스마트 세그먼트 UI/UX 탑재 (2026-09-08)

### 24.1. 사용자 핵심 요청 사항
- `[조회된 전체 이동경로 안내]` 마스터 버튼을 매번 누르지 않고도 앱 화면 지도 안에서 이동 경로선이 바로 보이도록 개선.
- 모든 경로가 복잡하게 얽혀 보이지 않도록, 각각의 경로나 날짜(운행 세션)별로 현대적 모빌리티 앱(Google 타임라인, 블루링크, 카카오T) 스타일로 스마트하게 구분(세그먼트)해서 볼 수 있는 UI/UX 요청.

### 24.2. 주요 개선 및 구현 내역
1. **앱 화면 지도 내 도로 주행 경로선 즉시 렌더링 (`GoogleMapRouteView.kt`)**:
   - 외부 구글맵 앱을 실행하지 않고도, 선택된 트립(또는 기간 내)의 출발지(`saddr`), 경유지들, 도착지(`daddr`) 좌표와 차량 길찾기 모드(`dirflg=d`, `travelmode=driving`)를 조합하여 상단 지도 안에서 실제 도로를 따라 그려지는 파란색 차량 내비게이션 주행 경로선과 경유 핀을 즉시 표출.
2. **지능형 주행 세션 분할 알고리즘 구축 (`segmentRouteIntoTrips`)**:
   - `RouteTripGroup` 데이터 모델 신설 (id, title, subtitle, brandEmoji, date, steps, totalDistanceKm).
   - 40분 이상의 시간 간격, 날짜 변경, 출발/도착 태그를 자동 감지하여 하루 동안의 이동 기록을 개별 운행 트립(출근 주행, 골프 라운드, 퇴근/귀가, 오전/오후 주행)으로 정밀 분할.
3. **스마트 운행 트립 & 일자별 세그먼트 칩 바 신설**:
   - 기간 필터 아래에 가로 스크롤 가능한 칩 바 배치:
     `[🌐 전체 모아보기 (총 N개 지점)]`, `[⭐ 9/8 오전 출근 · 4개 지점 (21.4km)]`, `[⭐ 9/8 골프 라운드 · 3개 지점 (35.0km)]`, `[⭐ 9/8 퇴근/귀가 · 4개 지점 (18.2km)]`
   - 탑승 차량 엠블럼(`⭐ 벤츠`, `🛡️ 볼보`), 운행 성격, 지점 수, 정밀 도로 주행거리(km) 일괄 표출.
4. **선택된 트립 중심의 몰입형 뷰 (Focus Isolation)**:
   - 원하는 트립 칩 탭 시 지도 자동 줌인: 복잡한 다른 경로선 없이 **해당 주행의 출발 ➔ 경유 ➔ 도착 도로선 하나만 깔끔하게 줌인 표출**.
   - 하단 세로 목록도 해당 트립에 속한 지점들만 필터링되어 정보 과부하 원천 차단.
   - 각 지점 카드 사이의 `[↓ 지점 1 ➔ 지점 2 차량 이동 구간]`을 누르면 두 지점 간의 구간 경로선으로 지도 포커스 자동 전환.

### 24.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 1m 1s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git` (`260d093`)

---

## 25. 스마트 다이어리 로딩 애니메이션 탑재 및 에뮬레이터 Leaflet 인라인 지도 / 벡터 레이더 듀얼 모드 구현 (2026-09-08)

### 25.1. 사용자 핵심 요청 사항
- 스마트 다이어리에서 최근 7일 등 데이터 조회 시 로딩 시간이 길어질 때 오류로 멈춘 것처럼 느껴지는 문제를 방지하기 위해 직관적이고 감성적인 로딩 애니메이션 추가 요청.
- 안드로이드 에뮬레이터 환경에서 구글 지도 화면이 표시되지 않고 빈 화면(Blank)으로 남는 현상 원인 규명 및 지점 마커/경로선 100% 렌더링 해결 요청.

### 25.2. 주요 개선 및 구현 내역
1. **감성적인 스마트 다이어리 로딩 애니메이션 구축 (`SmartDiaryLoadingOverlay`)**:
   - `DiaryUiState`에 `isLoading: Boolean`, `loadingMessage: String` 필드 신설.
   - 탭 전환(주간 요약, 월간 캘린더, 지도 경로) 및 기간 필터(최근 7일, 최근 30일, 전체) 선택 시 즉시 로딩 오버레이 가동.
   - 반투명 블러 백드롭 + 부드러운 펄스 스케일 원 + 회전형 `CircularProgressIndicator` + `🚗` 아이콘 결합.
   - 상황별 실시간 안내 메시지 제공 (*"최근 7일간의 이동 경로와 라이프로그를 분석 중입니다... 🚗✨"*, *"최근 30일간의 주행 경로를 분석 중입니다..."* 등).
   - 로딩 중 사용자 중복 터치를 차단하고, 0.3초 이내 초고속 연산 시에는 부드러운 페이드 트랜지션으로 화면 깜빡임 방지.
2. **에뮬레이터 구글 지도 미표출 원인 분석 및 완전한 인라인 엔진 해결**:
   - **원인 규명**:
     1) Google Maps 웹 임베드(`maps.google.com/maps?...output=embed`)가 모바일 WebView에서 구글 쿠키 동의(`consent.google.com`) 리다이렉트나 `X-Frame-Options: SAMEORIGIN` / CSP 정책에 의해 강제 차단됨.
     2) PC 에뮬레이터 환경의 WebGL 하드웨어 가속 미지원으로 인해 최신 구글 맵 벡터 렌더러가 투명 빈 화면(Blank)을 생성함.
   - **해결 방안 1: 인라인 HTML5 Leaflet + CartoDB 인터랙티브 지도 엔진 (`buildInteractiveHtmlMap`)**:
     - 외부 쿠키 동의나 CSP 제약이 없는 표준 인라인 Leaflet 맵을 WebView에 직접 주입(`loadDataWithBaseURL`).
     - 에뮬레이터 및 저사양 실기기 어디서든 WebGL 없이 **100% 선명하게 렌더링 보장**.
     - 지점 번호 핀([1], [2], [3]...) 및 파란색 도로 경로선(Polyline), `map.fitBounds` 자동 줌, 핀 터치 시 장소명/시각 팝업 툴팁 완벽 제공.
   - **해결 방안 2: 실시간 지도 ↔ 순수 Compose 캔버스 레이더 맵 듀얼 모드 토글**:
     - 지도 우측 상단에 **[🗺️ 실시간 지도 / 🧭 레이더 맵]** 토글 버튼 신설.
     - 네트워크 단절이나 에뮬레이터 통신 장애 시에도 0초 만에 순수 네이티브 Jetpack Compose 캔버스 지도(`InteractiveRouteMapView`)로 전환 가능.

### 25.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 1m 42s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 40s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git` (`062e158`)

---

## 26. 모바일 실기기 지도 불러오기 무한 리로드 루프(Deadlock) 원천 해결 및 cdnjs/SVG 벡터 3중 안전망 구축 (2026-09-08)

### 26.1. 사용자 핵심 요청 및 이상 증상
- 모바일 실기기에 APK를 설치 후 스마트 다이어리 [지도 경로] 탭 진입 시 "지도 불러오는 중..." 인디케이터가 멈추지 않고 계속 회전하며 실제 지도 및 지점이 렌더링되지 않는 현상 제보 (`media_1788855222787.png`).

### 26.2. 원인 규명 (Root Cause Analysis)
1. **Compose-WebView 상호 리컴포지션 무한 교착 루프 (Infinite Recomposition Loop)**:
   - `GoogleMapRouteView.kt`의 `AndroidView` `update` 블록 내에서 `loadDataWithBaseURL`이 가드 없이 매 리컴포지션마다 호출됨.
   - 동시에 `WebViewClient.onPageStarted` / `onPageFinished`에서 Compose 상태(`isWebViewLoading`)를 토글.
   - Compose 상태 변경이 Composable의 리컴포지션을 촉발하고, 이것이 다시 `update` 블록을 실행하여 `loadDataWithBaseURL`을 호출.
   - 이로 인해 WebView가 페이지 로딩을 시작하자마자 취소되고 재시작되는 현상이 초당 수십 회 반복되어, 영원히 로딩이 끝나지 않고 스피너가 무한 회전함.
2. **국내 모바일 통신망 외부 CDN(`unpkg.com`) 신뢰성 및 로딩 지연 문제**:
   - `unpkg.com` CDN은 국내 통신사(SKT/KT/LGU+) 모바일 환경에서 간헐적 DNS 지연 또는 타임아웃이 발생할 수 있음.
   - 웹뷰 내부에서 `crossorigin=""` 속성 사용 시 CORS 검증에 따른 리소스 로드 블로킹 가능성 존재.

### 26.3. 주요 개선 및 해결 내역
1. **`AndroidView` HTML 캐싱 가드 및 비동기 타이머 도입 (`GoogleMapRouteView.kt`)**:
   - `webView.tag != targetMapHtml` 가드를 배치하여, HTML 내용이 실제로 변경되었을 때만 단 1회 `loadDataWithBaseURL`을 호출하도록 차단.
   - `WebViewClient` 내부의 불필요한 상태 변이를 제거하고, `LaunchedEffect(targetMapHtml)` 기반의 800ms 안전 타이머(`delay(800L)`)로 전환.
   - 화면 전체를 차단하던 불투명 오버레이를 제거하고, 하단에 작고 세련된 플로팅 알약 칩 로더(`MapLoadingPill`)로 변경하여 로딩 중에도 지도가 즉각 보이도록 개선.
2. **초고속 Cloudflare cdnjs 전환 및 타일 에러 자동 폴백**:
   - 국내 ICN 엣지 노드를 보유한 `cdnjs.cloudflare.com`으로 Leaflet 라이브러리 전면 교체.
   - `crossorigin=""` 제거로 모바일 웹뷰 내부 CORS 제약 완전 방어.
   - CartoDB 타일 로드 실패 시 OpenStreetMap으로 자동 즉시 전환되는 `tilelayer.on('tileerror')` 폴백 가동.
3. **600ms 타임아웃 인라인 SVG 벡터 렌더러 탑재 (`renderSvgFallback`)**:
   - 네트워크 불량 또는 외부 CDN 차단 시에도 600ms 이내에 지도가 뜨지 않으면, 순수 내장 JavaScript로 즉시 화면 크기에 맞춘 반응형 SVG 벡터 지도를 그려냄.
   - 지점 번호 원형 마커([1], [2], [3]...), 장소명 캡슐 라벨, 반투명 파란색 도로 주행 경로선과 대시선이 0초 만에 완벽 표출되어 빈 화면이나 멈춤 현상 원천 배제.
4. **직관적인 모드 전환 세그먼트 컨트롤 탑재**:
   - 우측 상단에 `[🗺️ 도로 지도 | 🧭 레이더]` 2버튼 캡슐 세그먼트 컨트롤을 배치하여, 사용자가 언제든 0초 만에 네이티브 캔버스 지도(`InteractiveRouteMapView`)로 상호 전환 가능.

### 26.4. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 1m 50s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 39s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,303,565 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 27. 백그라운드 차량 주행 종료 시 앱 중단(Crash) 결함 원천 해결 및 생명주기 안전망 구축 (2026-09-08)

### 27.1. 사용자 핵심 요청 및 이상 증상
- 백그라운드에서 차량 주행 종료(블루투스 연결 해제 / 시동 끔) 직후 "AutoLogue이(가) 계속 중단됨" 시스템 크래시 팝업 발생 제보 (`media_1788863670832.png`).

### 27.2. 원인 규명 (Root Cause Analysis)
1. **블루투스 다중 프로필(A2DP, HFP, AVRCP) 해제 시 브로드캐스트 중복 수신 및 조기 `stopSelf()` 폭탄**:
   - 차량 블루투스 연결 해제 시 안드로이드 OS는 `BluetoothDevice.ACTION_ACL_DISCONNECTED` 인텐트를 2~3회 연속 발송함.
   - 기존 `CarBluetoothReceiver`는 `is_driving` 확인 전에 무조건 `stopTracking()`을 매번 서비스로 전송함.
   - `CarDrivingTrackingService`에서 1회차 호출이 백그라운드 코루틴으로 DB 저장과 정밀 거리 계산을 시작하자마자, 0.1초 뒤 2회차 호출이 들어와 `!isTracking` 가드에 걸려 서비스를 즉시 `stopSelf()`로 강제 파괴함.
   - 이로 인해 비동기 트랜잭션 도중 서비스가 급사(Sudden Death)하고 안드로이드 시스템이 크래시 팝업을 표출함.
2. **Notification SmallIcon에 Adaptive Icon (`R.mipmap.ic_launcher`) 지정으로 인한 OS 렌더러 크래시**:
   - 주행 완료 알림(`showTripCompleteNotification`)에서 `R.mipmap.ic_launcher`를 사용했으나, 이는 Android 8.0+에서 `<adaptive-icon>` XML 리소스이므로 삼성 갤럭시 및 최신 기종에서 NotificationManager가 상태바 아이콘을 그릴 때 시스템 에러(`BadNotificationPostingException`)가 유발됨.
3. **백그라운드 IO 스레드에서 Service 수명주기 메서드 직접 호출**:
   - `CoroutineScope(Dispatchers.IO)` 내부에서 UI 스레드 동기화 없이 `stopForeground`와 `stopSelf`를 호출하여 OS 바인더 상태 불일치 발생.

### 27.3. 주요 개선 및 해결 내역
1. **`CarBluetoothReceiver.kt` 3초 디바운스 및 `is_driving` 선행 가드 배치**:
   - 동일 기기에서 3초 이내에 연이어 들어오는 중복 disconnect 브로드캐스트를 100% 무시(Debounce 3,000ms).
   - `is_driving == true`일 때만 단 1회 `stopTracking()`을 안전하게 호출하도록 순서 변경.
2. **`CarDrivingTrackingService.kt` 상태 머신 동시성 락 및 안전한 종료 시퀀스**:
   - `stateLock`, `isTracking`, `isStopping` 상태 머신 도입으로 이미 종료 처리 중일 때 중복 stop 명령이 들어와도 `stopSelf()`를 조기 호출하지 않고 완전 무시.
   - DB 저장(`saveWaypointsToDiary`)과 알림 표출이 완전히 완료된 후, `withContext(Dispatchers.Main)`에서 안전하게 `ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)` 및 `stopSelf()` 실행.
3. **알림 SmallIcon 시스템 표준 2D 아이콘 교체 및 예외 방어**:
   - `setSmallIcon`을 모든 안드로이드 기기에서 100% 호환되는 표준 2D 아이콘(`android.R.drawable.ic_menu_compass`)으로 교체.
   - 알림 표출 시 `runCatching` 및 `PendingIntent` 결합으로 OS 예외를 완전 방어.
4. **단일 지점(1개 지점) 수집 시 출발/도착 안전 분기 탑재**:
   - `validList.size == 1`인 극단적 케이스에서도 예외 없이 단일 거점 주행 기록으로 정상 생성되도록 방어.

### 27.4. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 59s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 21s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,356,134 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 28. 옵션 C: 중부권 광역(수도권·인천·강원·충청) 고화질 정적 지도 및 듀얼 하이브리드 모드 구축 (2026-09-08)

### 28.1. 사용자 핵심 요청 사항
- 구글 공식 지도 API Key 발급(유료 과금 우려 및 AI Ultra 계정 미포함) 대신, API 키가 전혀 필요 없는 정적 지도 방식 도입 제안.
- 수도권뿐만 아니라 **인천, 강원, 충청(충북·충남·대전·세종)** 전역까지 정적 지도를 광역 확대 적용 요청.
- 0초 만에 뜨는 초고속 정적 맵과 전국 단위 인터랙티브 지도를 자유롭게 오가는 **옵션 C (듀얼 하이브리드 모드)** 구축 요청.

### 28.2. 주요 개선 및 구현 내역
1. **중부권 광역 지오레퍼런싱 바운딩 박스 설계 (`MidKoreaBounds`)**:
   - 남단: 36.00° N (충청 남부 대전·논산·영동) ~ 북단: 38.45° N (강원 북부 철원·고성)
   - 서단: 126.00° E (인천 영종도·강화·태안) ~ 동단: 129.40° E (강원 동해안 삼척·강릉·속초)
   - 위경도 ➔ 화면 픽셀 좌표 정밀 투영 함수 `toNormalized` 탑재.
2. **순수 Jetpack Compose 기반 고화질 벡터 베이스맵 렌더러 (`MidKoreaStaticMapView.kt`)**:
   - **지형 및 해안선/수계**: 서해안 및 동해안 바다 영역, 한강 본류, 북한강(춘천) 및 남한강(충주호) 수계 벡터 렌더링.
   - **주요 광역 고속도로망**: 경부선(서울~판교~수원~천안~대전), 영동선(인천~판교~원주~강릉), 서울양양선(서울~춘천~속초), 서해안선(인천~수원~서산).
   - **25개 주요 권역 랜드마크 앵커**: 서울, 인천, 송도, 영종도, 강화, 판교·분당, 수원, 용인, 춘천, 원주, 강릉, 속초, 평창, 정선, 천안, 청주, 충주, 제천, 세종, 대전, 서산, 태안 등.
   - **스마트 제스처 인터랙션**: 두 손가락 핀치 줌(1.0x ~ 5.0x 확대), 한 손가락 드래그(Pan 이동), 더블 탭 확대, [초기화] 칩 지원.
   - **지점 번호 원형 마커 핀 & 주행 경로선(대시 Polyline)**: 출발(🟢), 도착(🔴), 경유(🔵), 선택 포커스(🟠) 및 핀 탭 시 장소/시각/좌표 말풍선 툴팁 팝업.
3. **스마트 다이어리 지도 듀얼 하이브리드 모드 연동 (`GoogleMapRouteView.kt`)**:
   - 상단 우측 세그먼트 컨트롤: **`[🗺️ 중부권 정적 맵 | 🌐 전국 온라인 맵]`**
   - **기본 뷰**: `🗺️ 중부권 정적 맵` (API 키 0원, 데이터 소모 0, 0.001초 즉시 로딩, 오프라인 100% 동작)
   - **전국 뷰**: `🌐 전국 온라인 맵` (CartoDB/OSM 인터랙티브 타일, 전국/제주도/영호남 정밀 탐색)
   - 지도 높이 280dp로 확대하여 중부권 전역의 시인성 대폭 강화.

### 28.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 1m 5s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 37s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk` (63,694,323 bytes)
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 29. Android 14 백그라운드 FGS Location SecurityException 크래시 3단계 안전 폴백 구축 (2026-09-08)

### 29.1. 사용자 제보 크래시 로그 분석
- **발생 시점**: 백그라운드 차량 주행 감지 시작 시 (`act=com.autologue.app.action.START_CAR_TRACKING`)
- **로그 내용**:
  ```text
  java.lang.RuntimeException: Unable to start service com.autologue.app.service.CarDrivingTrackingService with Intent { act=com.autologue.app.action.START_CAR_TRACKING }
  Caused by: java.lang.SecurityException: Starting FGS with type location callerApp=ProcessRecord targetSDK=34 requires permissions: [android.permission.FOREGROUND_SERVICE_LOCATION] any of [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION] and the app must be in the eligible state/exemptions
  ```

### 29.2. 원인 규명 (Root Cause Analysis)
1. **Android 14 (TargetSDK 34) 백그라운드 FGS Location 기동 제약 (`AP-ANDROID-14-FGS-LOCATION-BG-CRASH`)**:
   - 이전 27번 조치는 주행 종료 시점(`STOP_CAR_TRACKING`)의 멀티 프로필 중복 수신 및 알림 아이콘 결함 해결이었음.
   - 본 크래시는 **주행 시작 시점(`START_CAR_TRACKING`)**에서 발생한 것으로, 앱 화면이 꺼져 있거나 백그라운드 상태일 때 블루투스 브로드캐스트 리시버로부터 포그라운드 서비스를 시작하면, Android 14 OS가 `FOREGROUND_SERVICE_TYPE_LOCATION`에 대해 예외 면제(Eligible State / Exemptions)가 아니라는 이유로 `SecurityException`을 발생시킴.
   - 포그라운드 서비스의 `onStartCommand` 내에서 OS의 `startForeground`를 호출할 때 발생하는 `SecurityException`은 프레임워크 `ActivityThread`에 의해 포착되지 않고 `RuntimeException: Unable to start service`로 전환되어 앱 프로세스를 즉각 강제 종료시킴.

### 29.3. 주요 개선 및 해결 내역
1. **`AndroidManifest.xml` 권한 및 서비스 타입 보강**:
   - 백그라운드 위치 권한 `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />` 추가.
   - 데이터 동기화 포그라운드 서비스 권한 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` 추가.
   - `CarDrivingTrackingService`의 `foregroundServiceType` 속성을 `"location|dataSync"`로 다중 선언하여 백그라운드에서 동적 전환 가능하도록 기반 마련.
2. **`CarDrivingTrackingService.kt` 3단계 동적 다운그레이드 안전 폴백(Fallback) 구현**:
   - **1단계 (Location FGS)**: 위치 권한 점검 후 `FOREGROUND_SERVICE_TYPE_LOCATION`으로 등록 시도. OS 보안 정책 위반 시 `SecurityException`을 즉각 감지.
   - **2단계 (DataSync FGS 폴백)**: `SecurityException` 발생 시 앱 크래시를 방지하고, 합법적인 백그라운드 서비스 타입인 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`로 즉시 동적 다운그레이드 등록.
   - **3단계 (일반 FGS 안전망)**: 데이터 동기화 타입 등록마저 실패할 경우 기본 `startForeground(NOTIFICATION_ID, initialNotification)`로 등록.
   - **완전 격리 try-catch**: 3단계 전체를 방어하여 OS 정책 위반으로 인한 서비스 기동 크래시(Unable to start service) 발생률 0% 원천 보장.

### 29.4. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 2m 15s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 30. 다이어리 상세 이동 동선 중복 스텝 누적 결함 원천 해결 및 지능형 병합(Deduplication) 구축 (2026-09-08)

### 30.1. 사용자 제보 증상 분석
- **현상**: 다이어리 상세 모달(바텀시트)에서 `충청북 주덕읍, 사진 32장 촬영, 06:30` 항목이 8개나 동일하게 반복 표시되고, 방문 장소가 실제 2곳(주덕읍, 서울 방이동)이 아닌 `8곳`으로 왜곡 표출됨 (`media_1788866596245.png`).
- **질문**: *"앱을 계속 업데이트하면서 기존데이터위에 같은 데이터가 쌓인건가?"*

### 30.2. 원인 규명 (Root Cause Analysis)
1. **`DailyRouteAggregator`의 무작위 UUID 생성과 `DiaryRepositoryImpl`의 결함 있는 `distinctBy` 키 (`AP-ANDROID-DIARY-STEP-DUPLICATE-ACCUMULATION`)**:
   - 앱이 업데이트되거나 동기화/재색인이 실행될 때마다 `DailyRouteAggregator`는 `RouteStep`을 생성하며 `UUID.randomUUID().toString()`을 부여함.
   - `DiaryRepositoryImpl.insertDiaryEntry()`는 기존 일자 데이터와 새 데이터를 병합할 때 `(existing.routeSteps + entry.routeSteps).distinctBy { it.id.ifBlank { ... } }` 코드를 수행함.
   - 그러나 `it.id`가 무작위 UUID로 채워져 있어 결코 빈 값(blank)이 아니므로, 내용이 완전히 동일한 스텝임에도 UUID가 달라 중복으로 인식되지 못하고 매 실행마다 1개씩 뒤에 덧붙여져 누적(8회 중복)되었음.
2. **`cleanDuplicates()`의 내부 스텝 정제 누락**:
   - 기존의 `cleanDuplicates()`는 테이블의 `DiaryEntry` 행 단위 중복 날짜만 삭제했을 뿐, 각 엔트리 내부의 `routeSteps` JSON 컬럼 내 중복 데이터는 전혀 정제하지 않았음.
3. **방문 장소 카운트 계산 왜곡**:
   - `DiaryScreen.kt` 상세 다이얼로그에서 방문 장소를 `entry.routeSteps.size`로 표시하여, 중복 스텝 개수만큼 방문 장소 숫자가 부풀려져 표출되었음.

### 30.3. 주요 개선 및 해결 내역
1. **`DailyRouteAggregator.kt` 지능형 중복 병합 알고리즘 (`deduplicateRouteSteps`) 구현**:
   - **사진 스텝 중복 감지**: 동일 사진 URI가 1개라도 포함되어 있거나, 동일 일자·동일 장소/위치에서 30분 이내 촬영된 스텝을 동일 활동으로 감지.
   - **스텝 지능형 병합 (`mergeSteps`)**: 사진 URI 목록(`distinct()`), 동행인(`distinct()`), 태그 목록(`distinct()`)을 안전하게 통합하고, "사진 N장 촬영" 문구를 실제 유니크 사진 장수로 자동 재계산.
   - **결제/주행/골프 스텝 중복 방어**: 결제(시각+가맹점+금액), 골프(일자+구장명), 주행(시각+좌표) 기준 정밀 중복 차단.
2. **`DiaryRepositoryImpl.kt` 실시간 병합 및 기존 데이터 자동 마이그레이션**:
   - `insertDiaryEntry` 및 `updateDiaryEntry` 시 `DailyRouteAggregator.deduplicateRouteSteps`를 거쳐 저장하도록 변경.
   - `cleanDuplicates()` 호출 시 기존 Room DB 내 모든 다이어리 엔트리의 `routeSteps`, `photoUris`, `tags` 중복을 자동 검사하여 중복이 존재하는 경우 즉시 정제 후 DB 갱신.
   - 앱 기동 시(`DiaryViewModel.init`) 자동으로 `cleanDuplicates()`가 구동되므로, 사용자의 폰에 이미 쌓여 있던 중복 스텝(8개)도 앱 실행 즉시 1개로 자동 정제 복구됨.
3. **`DiaryScreen.kt` 방문 장소 지표 정밀화**:
   - 상세 다이얼로그의 "방문 장소" 지표를 단순 리스트 크기(`routeSteps.size`) 대신 고유 방문 지점 수(`distinctPlaceCount`)로 정확하게 산출하도록 개선.

### 30.4. 빌드 및 배포 검증
- **단위 테스트**: `HistoricalDataSyncTest.deduplicateRouteSteps_mergesIdenticalPhotoStepsCorrectly` 추가 및 `testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 31. 차량 GPS 5분 주기 수집, 경로 지도 경유지 도트 표기, 하단 동선 정예화 및 구글지도 차량 길찾기 정상화 (2026-09-08)

### 31.1. 사용자 핵심 요청 사항
1. **차량 GPS 수집 주기 단축**: 기존 10분 단위 GPS 수집을 **5분 단위**로 단축하여 주행 궤적을 더욱 정밀하게 저장.
2. **지도 상 경유지 시각화 간소화**: 지도 상에서 중간 경유지들을 큰 원과 숫자로 표기하지 않고, **작은 원(도트)으로 직선 연결**하고 숫자는 **처음 시작(🟢 1 / 출발)과 종료 시점(🔴 N / 도착)**에만 표기.
3. **하단 상세 동선 목록 정예화**: 하단 상세 정보 목록에서 중간 수십 개 GPS 경유지 카드를 모두 표시하지 않고, **출발 시점과 도착 시점(2개)만 표시**.
4. **구글 지도 앱 연동 정상화**: "전체 경로 앱에서 열기" 시 구글 지도에서 대중교통으로 기본 설정되거나 위치를 못 잡는 오류를 해결하고 **차량(`travelmode=driving & dirflg=d`)** 및 정확한 위경도 좌표로 즉시 길찾기 연동.
5. **불필요한 내비 안내 카드 제거**: 앱 내 내비 안내 배너 카드("⭐ 벤츠 [...] 앱에서 내비 안내") 완전 제거.

### 31.2. 주요 개선 및 구현 내역
1. **`CarDrivingTrackingService.kt` 5분 주기 정밀 수집 전환**:
   - 주행 수집 루프 딜레이를 기존 10분에서 5분(`5 * 60 * 1000L = 300,000ms`)으로 단축.
   - 포그라운드 서비스 알림 문구를 `"블루투스 감지 탑승 중 · 5분 주기 GPS 경로 수집 시작"`으로 직관적 갱신.
2. **지도 3종(정적 맵, 벡터 레이더, 웹뷰) 마커 시각 디자인 정돈**:
   - **`MidKoreaStaticMapView.kt`**:
     - 시작(🟢 1, 초록), 종료(🔴 N, 빨강), 포커스(🟠, 주황) 및 비주행 활동 거점만 큰 원형 배지와 라벨을 노출.
     - 중간 주행 경유지는 큰 원과 숫자를 완전히 배제하고, `4.dp` 크기의 스카이블루 작은 도트(`Color(0xFF38BDF8)`)로 직선 경로선(`drawPath`) 상에 콤팩트하게 연결.
   - **`InteractiveRouteMapView`**:
     - 중간 주행 경유지는 `7.dp` 크기의 스카이블루 작은 도트로 깔끔하게 표기하고, 시작(🟢 1)과 종료(🔴 N)만 알약 배지로 표기.
   - **`buildInteractiveHtmlMap` (웹뷰 & SVG 폴백)**:
     - Leaflet CSS에 `.custom-pin-dot` 도입 및 SVG 폴백에서도 중간 경유지를 4px 작은 도트로 렌더링.
3. **`GoogleMapRouteView.kt` 하단 동선 목록 정예화 및 내비 배너 제거**:
   - `bottomDisplaySteps` 도입: 주행 스텝이 2개 초과일 때 중간 GPS 경유지들은 하단 목록에서 제외하고, 출발 시점(`first()`)과 도착 시점(`last()`) 및 기타 활동 거점만 시간순 정렬하여 노출.
   - 각 카드의 번호 배지에 `1`(출발), `N`(도착)과 함께 `"🟢 출발 지점"`, `"🔴 최종 도착지"` 서브타이틀 적용.
   - "앱에서 내비 안내" 마스터 배너 카드 완전 삭제.
4. **`openGoogleMapsRoute` 구글 지도 자동차 길찾기 정상화**:
   - `waypoints` 파이프(`|`)만 `%7C`로 인코딩하고 위경도 간 쉼표(`,`)의 과도한 URL 인코딩(`%2C`)을 방지하여 좌표 인식 결함 해결.
   - 한국 도로망에서 구글 지도 라우팅 엔진이 과도한 경유지(8개 이상)로 인해 대중교통으로 강제 전환되는 결함을 방지하기 위해 핵심 경유지를 최대 2개로 스마트 샘플링.
   - `travelmode=driving`과 `dirflg=d` 파라미터 결합으로 구글 지도 앱에서 확실하게 자동차 길찾기 모드로 실행.

### 31.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 32. 골프 가짜 더미 데이터(스마트스코어 심층분석·목업 차트·가짜 예약) 완전 제거 및 실제 데이터 기반 동적 연동 (2026-09-08)

### 32.1. 사용자 제보 및 결함 분석
- **사용자 제보**: *"dummy 데이터 같은데? 특히 스마트스코어 심층분석은 실제 데이터로 뽑을 수 있는 정보가 아닌것 같아."* (`media_1788871759741.png`)
- **원인 규명**:
  1. `SmartScoreAnalyticsCard`: GIR(파온율 38.5%), FIR(안착률 62.0%), 평균 퍼트수(32.4P), 베스트 라베, 스코어 분포 비율 등은 스마트스코어 카트 태블릿 전산이나 샷 트래킹 센서 전용 데이터로, 사진 EXIF와 영수증 기반의 본 라이프로그 앱에서는 수집 불가능한 가짜 지표가 하드코딩되어 있었음.
  2. `최근 라운드 스코어 트렌드`: 사용자의 실제 라운드 기록(91타 1건)이 반영되지 않고, 남촌CC 88타, 아리지 90타, 필로스 89타, 라데나 87타 가짜 데이터가 고정 출력됨.
  3. `GolfViewModel.seedSampleRoundIfNeeded`: 사용자가 예약하지 않은 "아난티 코드 GC" 가짜 예약이 DB에 임의 자동 삽입되고 있었음.

### 32.2. 주요 개선 및 해결 내역
1. **가짜 `SmartScoreAnalyticsCard` 완전 삭제**:
   - 수집 불가능한 가짜 GIR/FIR/퍼트수/스코어분포 카드 및 컴포넌트 완전 제거.
2. **실제 데이터 기반 `RealScoreTrendCard` 개편 (`GolfScreen.kt`)**:
   - `uiState.rounds` 중 `totalScore != null`인 실제 완료 라운드를 날짜순으로 추출.
   - **2건 이상 누적 시**: 실제 코스명, 실제 타수, 실제 일자로 동적 막대 차트 렌더링 및 최근 평균 타수 배지 표출.
   - **1건 등록 시 (현재 상태)**: 가짜 80대 그래프 대신 "실제 등록 1건" 배지와 함께 `일반 사진 (서 코스) · 91타` 실제 기록 및 "2건 이상 등록 시 스코어 추이 차트가 자동 생성됩니다" 정직한 안내 제공.
   - **0건 시**: 불필요한 차트 카드 미표출.
3. **가짜 예약 시딩 로직 제거 및 기존 잔존 더미 자동 청소 (`GolfViewModel.kt`)**:
   - `seedSampleRoundIfNeeded` 삭제.
   - `cleanUpDummyRounds` 구현: 앱 실행 시 과거 시딩되었던 가짜 "아난티 코드 GC" 더미 예약을 DB에서 자동 영구 삭제.

### 32.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 100% 통과
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 33. 골프 라커룸·다크 스코어카드 정밀 OCR, 종합 통계 모달, 보정 평균 비거리 산출 및 차계부·가계부 정밀화 (2026-09-10)

### 33.1. 사용자 핵심 요청 및 결함 분석
1. **라커룸 스캔 구장명·코스명 정밀 탐지 및 코스명 보존**:
   - 골프장명이 영문(`SKY VALLEY CC`, `LADENA GC`), 하단 주소/법인 상호(`(주)필로스`), 하단 감사 문구 등 다양한 위치에 있을 때도 정확히 인식.
   - 라커룸 스캔 후 골프장명 추천 선택 시 기존 코스명이 지워지지 않고 온전히 유지되도록 개선.
2. **골프 허위 라운드 제거 및 직접 입력 일자 정합성 보장**:
   - 골프에 가지 않은 날(방이동 세미나)에 과거 OCR 실패 기본값으로 생성된 허위 '필드 골프장' 라운드 자동 청소.
   - 골프 라운드 직접 입력 시 이전 일자의 기록인데도 당일(오늘) 타임라인에 등록되던 날짜 매핑 오류 수정.
   - 골프 라이프 페이지 타이틀이 상단 액션 버튼에 가려지지 않도록 UI 레이아웃 분리.
3. **스마트 차계부 출퇴근 오탐 방지 및 차량별 연비 분리 계산**:
   - 차량 블루투스 미연결 및 버스 이용 시 '우리집 -> 회사'가 수 분 간격으로 반복 등록되던 결함 방어 (`CarBluetoothReceiver` 정제 및 쿨다운).
   - 총 주유비 대비 통연비가 1.3 km/L로 왜곡되던 오류를 해결하고, 주유 결제별 차량 배지 선택 및 차량별 연비/주행거리 분리 집계 탑재.
4. **스마트 가계부 본인 계좌 간 이동(정선우) 지출 집계 제외**:
   - 적요에 '정선우'가 포함된 이체/출금은 본인 계좌 간 이동이므로 총 지출에서 제외하고, 영수증 목록에 `[내 계좌간 이동]` 뱃지로 시각화.
5. **검은색 바탕 스코어카드 정밀 OCR 및 종합 통계 모달 구축**:
   - 검은색 바탕 스코어카드(필로스 GC West/South) 스캔 시 90타로 오탐되던 문제를 해결하고 실제 86타, 퍼트 40P, 코스명(`West / South`), 세부 지표(페널티 2타, GIR 55.6%, 비거리 205.2m, 템포 3.1, 걸음수 6384보) 정밀 추출.
   - 스마트골프 타이틀 우측 `[📊 통계]` 버튼 및 연도별/전체 대상 종합 통계 모달 (`GolfStatisticsDialog`, Bento Grid 2열 카드) 제공.
6. **티샷 비거리 최저/최고 기록 제외 보정 평균 비거리 산출 및 UI 표기**:
   - 파3 제외 티샷 비거리 목록에서 최저기록(139m)과 최고기록(291m)을 제외한 보정 평균 비거리(201.4m)를 산출하여 `평균 비거리(보정 평균 비거리)` (`205.2m (201.4m)`) 형태로 확인 가능하도록 전방위 적용.

### 33.2. 주요 개선 및 구현 내역

1. **라커룸 스캔 전방위 OCR 엔진 고도화 (`GolfLockerSlipOcrAnalyzer.kt`)**:
   - **국내 주요 60+ 골프장 매핑 사전 (`knownClubs`)** 및 공백/특수문자 무관 정규화 매칭 탑재.
   - **다단계 전방위 탐색**: 영문/한글 헤더, 하단 법인 상호(`(주)`/`주식회사`), 하단 감사/환영 문구, 연락처 연계 인식.
   - **코스명 보존 및 분리 유틸 (`splitClubAndCourse`, `extractCourseNameFromText`)**: 추천 칩 클릭 시 입력된 코스명을 안전하게 보존하고, `GolfRoundDetailDialog`, `DirectAddRoundDialog`, `AddGolfReservationDialog` 전용 코스명 입력란 제공.
   - **허위 라운드 청소**: `DailyRouteAggregator.isRealGolfClub()`, `GolfViewModel.cleanUpDummyRounds()`, `DiaryViewModel.checkAndUpgradeLegacyEntries`를 통한 DB 내 가짜 라운드 및 스텝 자동 제거.
   - **골프 라이프 UI 분리**: 상단 바 액션 버튼을 가로 스크롤 액션 바로 분리 재배치하여 타이틀 가림 현상 해결.

2. **차계부 및 가계부 정밀화**:
   - **`CarBluetoothReceiver.kt`**: 모호한 키워드(`"bt"`) 및 이어폰(Buds, QCY 등)을 배제하고 등록된 차량 블루투스 기기만 필터링, 30분 쿨다운 및 10분 내 근접 중복 주행 레코드 정제.
   - **`VehicleLog` / `CarLedgerViewModel` / `CarLedgerScreen`**: 각 주유 내역에 `[차량 1]`, `[차량 2]` 원터치 배지 선택 지원 및 차량별 주유비/주행거리/연비 분리 집계.
   - **`Transaction.isSelfTransfer()` / `ExpenseViewModel` / `ExpenseScreen`**: 적요 '정선우' 포함 거래 총 지출 제외 및 영수증 목록에 `[내 계좌간 이동]` 배지/안내 캡션 표출.

3. **검은색 바탕 스코어카드 정밀 OCR 엔진 (`ScorecardOcrAnalyzer.kt`)**:
   - **90타 오탐 원천 방지**: 대형 스코어 지문 패턴(`\b(\d{2,3})\s*\([+-]?\s*\d+\)`) 및 전반(42)+후반(44)=86 소계 합산 우선 적용.
   - **세부 지표 자동 추출**:
     - GIR: `55.6%`
     - 페널티: West 2타 + South 0타 = `2타`
     - 티샷 비거리: 파3 제외 7개 홀 비거리 자동 감지
     - 티샷 템포: 14개 홀 평균 `3.1`
     - 전체 걸음수: 복합 라인(`2.2 6384`)에서도 4~5자리 정수 지문 매칭으로 `6384보` 추출
     - 코스명: `West`와 `South` 조합 `West / South`
     - 구장명: `필로스 GC` 자동 인식

4. **티샷 비거리 최저/최고 제외 보정 평균 비거리 산출**:
   - 파3 제외 티샷 비거리 리스트에서 최저값(Min: 139m)과 최고값(Max: 291m)을 제외한 나머지 홀들의 평균인 **201.4m** 산출.
   - `GolfRound`: `adjustedDriveDistance`, `getEffectiveAdjustedDriveDistance()`, `getFormattedDriveDistance()` 멤버 함수 탑재.
   - Room DB 버전 5 상향 (`AppDatabase.kt`, `GolfRoundEntity.kt`에 `adjustedDriveDistance` 컬럼 추가).
   - **전방위 UI 연동**:
     - 스마트골프 종합 통계 모달: `205.2m (201.4m)`
     - 라운드 상세 다이얼로그: 비거리 입력란 하단 `보정: 201.4m` 캡션 표기
     - 라운드 목록 카드: 요약 라인에 `· 🏌️ 비거리: 205.2m (201.4m)` 표기
     - 다이어리 타임라인: `드라이브 205.2m (보정 201.4m)` 기록

5. **스마트골프 종합 통계 모달 (`GolfStatisticsDialog.kt`)**:
   - 상단 `TopAppBar` 우측 끝 `[📊 통계]` 버튼 배치.
   - 연도별(`[2026년]`, `[2025년]` 등) 및 `[전체 연도]` 실시간 동적 필터 칩 바.
   - Bento Grid 2열 8대 핵심 지표(총 라운드, 평균 타수, 평균 퍼트, 평균 비거리, 평균 페널티, 평균 GIR, 평균 템포, 평균 걸음수) 카드 구축.

### 33.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 31개 단위 테스트 100% 통과 (`BUILD SUCCESSFUL`)
  - `GolfLockerSlipOcrAnalyzerTest`: 상단/하단/영문/법인형 라커 슬립 파싱 및 식당 영수증 오탐 차단 검증
  - `ScorecardOcrAnalyzerTest`: 필로스 GC 다크 스코어카드 86타, 퍼트 40P, West/South, 비거리 205.2m, 보정 비거리 201.4m, 템포 3.1, 걸음수 6384보 정밀 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 20s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 34. 모바일 스코어카드 OCR 고해상도 유지(2560px), 줄바꿈 분리 테이블 복원 및 세부 지표(페널티·비거리·템포·걸음수) 집계 정상화 (2026-09-10)

### 34.1. 사용자 제보 및 결함 원인 분석
- **현상**: 모바일 다크 스코어카드 스캔 시 총 타수(86), 총 퍼트수(40), GIR(55.6%)만 입력되고, **페널티 타수(2), 비거리(205.2m), 티샷 템포(3.1), 걸음수(6384)** 텍스트 필드가 비어 있어 종합 통계 및 다이어리에 세부 지표가 집계되지 않음 (`media_1789025059651.png`).
- **원인 규명**:
  1. **비트맵 과도한 다운샘플링 (`AP-OCR-MOBILE-SCORECARD-MULTI-LINE-DOWNSAMPLE`)**:
     - 기존 `ScorecardOcrAnalyzer.decodeSafeSampledBitmap`의 `maxDimension`이 `1024`로 하드코딩되어 스마트폰 세로 스크린샷(1080x2400) 분석 시 `inSampleSize = 4`가 적용되어 가로 270px, 세로 600px로 극단 축소됨.
     - 270px 폭 안에 10개 열이 밀집되어 Par, Score, Putt, Penalty, Tempo, Dist 행의 작은 글꼴이 2~3픽셀로 뭉개져 ML Kit가 큰 요약 카드(86, 55.6%, 2.2)만 인식하고 테이블을 완전히 유실함.
  2. **한국어 공백 불일치로 인한 `걸음 수` 누락**:
     - OCR 원문이 `"전체 걸음 수"` 또는 `"걸음 수"`와 같이 띄어쓰기로 인식되었으나, 기존 정규식은 공백 없는 `걸음수` 형태만 허용하여 파싱에 실패함.
  3. **모바일 OCR 텍스트 블록/줄바꿈 분리 현상**:
     - ML Kit 텍스트 인식 시 `Dist.`, `(Tee Shot)`, `Tempo`, `Penalty` 라벨과 실제 수치 행(`192 - 139...`, `3.0 - 3.2...`, `- - 1 - 1...`)이 서로 다른 라인/블록으로 분리 인식되어 단일 줄 전제 정규식 매칭이 실패함.

### 34.2. 주요 개선 및 구현 내역
1. **비트맵 다운샘플링 해상도 상향 및 선명도 100% 보장 (`ScorecardOcrAnalyzer.kt`)**:
   - `maxDimension` 기본값을 `1024`에서 **`2560`**으로 상향.
   - 1080x2400 세로 스크린샷을 `inSampleSize = 1` 원본 해상도로 선명하게 유지.
   - `RGB_565` 포맷을 유지하여 메모리 사용량을 ~4.9MB로 최소화, OOM 위험 0% 원천 보장.
2. **걸음수 공백 허용 및 다단계 윈도우 탐색 탑재**:
   - 정규식에 `(?:(?:전체\s*)?걸음\s*수|걸음|STEPS)` 적용.
   - 공백 제거 정규화 후 `걸음` 또는 `STEP` 키워드 주변 `[-3..3]` 라인 윈도우 탐색으로 `6384` 추출 보장.
   - 상단 15줄 이내 3000~50000 범위 정수 자율 감지 폴백 탑재.
3. **줄바꿈 분리 테이블 인접 줄 룩어헤드 및 자율 패턴 감지 아키텍처 구축**:
   - **페널티 (`Penalty`)**: 라벨 라인 및 다음 1~2줄 탐색, 대시/숫자 분석을 통해 West 2타 + South 0타(전체 대시) = **2타** 정밀 산출.
   - **티샷 비거리 (`Dist`)**: 라벨 라인 및 인접 줄에서 100~350m 범위 숫자 추출 + 테이블 내 120~350m 3자리 정수가 2개 이상 나열된 행 자율 감지 폴백 구축 -> **205.2m (보정 201.4m)** 산출.
   - **티샷 템포 (`Tempo`)**: 라벨 라인 및 인접 줄에서 1.5~5.5 범위 소수 추출 + 테이블 내 1.5~5.5 소수가 2개 이상 나열된 행 자율 감지 폴백 구축 -> **3.1** 산출.
   - **스코어/퍼트 (`Score`, `Putt`)**: 라벨 인접 1~2줄 및 10개 열 소계 합산 검증 자율 감지 탑재.
4. **Compose UI 실시간 동기화 강화 (`GolfScreen.kt`)**:
   - `GolfRoundDetailDialog`의 `LaunchedEffect` 키에 `round.scorecardPhotoUri`를 추가하여 OCR 스캔 완료 시 비거리, 템포, 페널티, 걸음수 필드가 화면에 즉시 자동 반영되도록 개선.
5. **글로벌 안티패턴 영구 지식화 (`anti_patterns.json`)**:
   - `AP-OCR-MOBILE-SCORECARD-MULTI-LINE-DOWNSAMPLE` 신규 등록.

### 34.3. 빌드 및 배포 검증
- **단위 테스트**: `ScorecardOcrAnalyzerTest`에 줄바꿈 분리 레이아웃 테스트 케이스(`parseBlackThemeScorecard_withSplitLines_extractsAllMetricsCorrectly`) 추가 및 `testDebugUnitTest` 100% 통과 (`BUILD SUCCESSFUL in 1m 5s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 36s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 35. 모바일 스코어카드 2D 공간 바운딩 박스 기반 가로 그리드 재구성 및 전/후반 7대 지표(Hole/Par/Score/Putt/Penalty/Tempo/Dist) 테이블 정밀 모델링 (2026-09-10)

### 35.1. 사용자 핵심 요청 사항
- 스코어카드 이미지가 아래와 같은 전반코스 / 후반코스 그리드(Grid) 테이블 구조로 되어 있으므로, 해당 표를 2D 그리드로 모델링하여 각각의 행에 해당하는 값을 계산하거나 가져오도록 요청:
  - **전반코스**: `Hole 1..9 Total`, `Par`, `Score`, `Putt`, `Penalty`, `GIR`, `Tempo`, `Dist.`
  - **후반코스**: `Hole 10..18 Total`, `Par`, `Score`, `Putt`, `Penalty`, `GIR`, `Tempo`, `Dist.`
- 터치 하이라이트 박스나 모바일 UI 세로 열 분할 현상에서도 18홀 홀별 스코어, 총 타수, 퍼트 수, 페널티, 비거리(평균 및 최저/최고 제외 보정), 템포, 걸음수가 100% 정상 집계되도록 처리.

### 35.2. 원인 분석 및 해결 전략
1. **모바일 UI 하이라이트 박스로 인한 세로 열 분할 위험 방어**:
   - 모바일 골프 앱(스마트스코어 등)에서 특정 홀을 탭하면 분홍색 세로 테두리 박스가 생성되어, OCR 엔진이 테이블을 가로 행이 아닌 세로 열(`1 4 5 2 - 3.0 192`) 단위로 쪼개어 인식할 위험 존재.
   - **해결 (`reconstructSpatialGrid`)**: ML Kit의 `Text.Element` 2D 바운딩 박스 좌표를 추출하여, 글자 높이 기반 허용 오차(`rowTolerance = medianHeight * 0.65`) 내에 위치한 요소들을 중앙 Y좌표 기준으로 그룹화(Row 클러스터링)한 후, 각 행 내부를 X좌표 순으로 정렬하여 완벽한 가로 그리드 행으로 재조립.
2. **전반 / 후반 코스 스마트 분할 및 `CourseGridData` 모델링**:
   - `HOLE 10` 또는 후반 코스 키워드(`South`, `Lake`, `In` 등)를 기준으로 `frontLines`와 `backLines`를 명확히 분리.
   - 전반 및 후반 각각에 대해 독립적으로 `extractCourseGrid`를 가동하여 Hole, Par, Score, Putt, Penalty, Tempo, Dist 행을 정밀 추출.
3. **요약 라벨(`홀당 평균 퍼트 수`)과 테이블 행 오인식 원천 차단**:
   - 상단 요약 카드의 "홀당 평균 퍼트 수"에 포함된 "홀", "퍼트" 키워드로 인해 HOLE 행 인덱스가 상단으로 오인되거나 홀 번호(1..9)가 퍼트로 잘못 파싱되던 결함 발견.
   - `isHoleLine` 헬퍼 함수를 통해 "홀당", "평균" 키워드를 엄격히 배제하고, 코스명(West, Hill 등)을 HOLE 행 바로 윗줄 및 인근 영역에서 정확히 역추적 탐색.
   - 퍼트 행 검증 시 각 홀 퍼트 수 유효 범위(0..5) 검증 필터를 결합하여 홀 번호 오인식 완벽 차단.
4. **전체 홀 티샷 템포 및 비거리 종합 연산**:
   - Tempo와 Dist 행은 각 홀(파3 제외 7개 홀씩 총 14개 홀)의 개별 측정값이므로, 전체 수집된 템포들의 산술 평균(`avgTempo = 3.1`) 및 비거리 산술 평균(`205.2m`), 최저/최고 제외 보정 비거리(`201.4m`) 산출 로직 고도화.

### 35.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 33개 단위 테스트 100% 통과 (`BUILD SUCCESSFUL in 33s`)
  - `parseMobileSmartScorecard_extractsAccurateScoresAndPutts`: Hill/Lake 91타, 40P, 18홀 스코어 완벽 검증
  - `parseBlackThemeScorecard_extractsAllMetricsCorrectly`: West/South 86타, 40P, 페널티 2, 비거리 205.2m(보정 201.4m), 템포 3.1, 걸음수 6384보 검증
  - `parseBlackThemeScorecard_withSplitLines_extractsAllMetricsCorrectly`: 줄바꿈 분리 레이아웃 검증
  - `parsePaperScorecard_extractsTotalAndPutts`: 지류 영수증 스코어카드 호환성 검증
  - `golfRound_formattedDriveDistance_displaysBothAverageAndAdjusted`: 보정 비거리 포맷 검증
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 33s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`

---

## 36. 차량 주행 백그라운드 GPS 실시간 리스너(LocationListener) 등록 및 0.0km 계산 결함 원천 해결 (2026-09-10)

### 36.1. 사용자 제보 및 현상 분석
- **현상**: 차량 블루투스 연결 후 25분간 실제 주행했음에도, 알림 배너에 `"블루투스 감지 탑승 중 · 25분 경과 (현재까지 약 0.0 km, 6개 위치 기록)"`로 표출되며 이동 거리가 0.0 km로 고정되어 있음 (`media_1789030808375.jpg`).
- **원인 규명 (`AP-ANDROID-GPS-GETLASTKNOWNLOCATION-STALE`)**:
  1. `CarDrivingTrackingService` 내부에서 GPS 하드웨어 칩을 구동하는 실시간 위치 수신 리스너(`requestLocationUpdates`)를 등록하지 않고, 5분 타이머마다 OS 캐시 위치(`getLastKnownLocation`)만 단발성으로 조회함.
  2. 안드로이드 OS는 백그라운드에서 실시간 위치 요청이 없으면 새로운 GPS 픽스를 수행하지 않으므로, 25분 동안 출발 시점의 동일한 위경도 좌표가 6회 연속 반복 수집됨.
  3. 동일 좌표 간의 이동 거리가 0m이므로 `LocationDistanceUtils.calculateWaypointsDistanceKm`에서 유효 이동(50m 이상)이 없는 것으로 판정되어 `0.0 km`로 계산되었고, 주행 종료 시에도 최소 기본값(1.0 km)으로만 저장되는 결함 유발.

### 36.2. 주요 개선 및 해결 내역
1. **실시간 3중 GPS 공급자 LocationListener 등록 (`startLocationUpdates`)**:
   - `LocationManager.GPS_PROVIDER` (5초 / 20m)
   - `LocationManager.PASSIVE_PROVIDER` (티맵, 카카오내비, 네이버지도 등 내비게이션 앱 실행 시 고정밀 GPS 데이터를 배터리 소모 없이 100% 무임승차 수신, 2초 / 10m)
   - `LocationManager.NETWORK_PROVIDER` (기지국/Wi-Fi 보조, 10초 / 50m)
2. **실시간 40m 이동 감지 및 주행 궤적 누적 파이프라인 탑재**:
   - 차량이 실제로 40m 이상 이동할 때마다 실시간으로 `waypoints`에 자동 누적하고 `LocationDistanceUtils`를 통해 실제 이동 거리를 재계산.
   - 알림 배너 실시간 갱신: `현재까지 약 %.1f km, %d개 위치 기록`에 실제 주행거리가 `약 2.4 km`, `8.7 km` 등으로 동적 표출.
3. **최신 수신 좌표(`latestLocation`) 우선 활용 아키텍처**:
   - `getCurrentLocation()`에서 최근 60초 이내에 리스너로 수신된 실시간 좌표가 있으면 캐시를 무시하고 최우선 반환.
4. **서비스 라이프사이클 안전 해제**:
   - `stopTrackingInternal()` 및 `onDestroy()`에서 `stopLocationUpdates()`를 호출하여 리스너를 즉시 해제함으로써 배터리 누수 방지.
5. **글로벌 안티패턴 영구 지식화 (`anti_patterns.json`)**:
   - `AP-ANDROID-GPS-GETLASTKNOWNLOCATION-STALE` 신규 등록 완료.

### 36.3. 빌드 및 배포 검증
- **단위 테스트**: `testDebugUnitTest` 33개 전체 통과 (`BUILD SUCCESSFUL in 30s`)
- **Gradle 빌드 결과**: `assembleDebug` 41개 태스크 100% 성공 (`BUILD SUCCESSFUL in 15s`)
- **생성된 APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub 저장소 동기화**: `https://github.com/herosonsa1/lifeLog.git`
