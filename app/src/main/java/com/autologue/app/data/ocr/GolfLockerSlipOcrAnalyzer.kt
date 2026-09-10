package com.autologue.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class GolfLockerSlipResult(
    val isLockerSlip: Boolean,
    val clubName: String,
    val lockerNumber: String?,
    val teeOffTime: LocalTime?,
    val courseName: String?,
    val date: LocalDate,
    val playerName: String?,
    val gender: String?,
    val rawText: String
)

@Singleton
class GolfLockerSlipOcrAnalyzer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /**
     * 실제 스마트폰 카메라 고해상도(12~50MP) 사진 로딩 시
     * 안드로이드 힙 한도 초과(OOM) 및 C++ 네이티브 SIGSEGV 크래시를 방지하기 위해
     * 최대 1024px 이하로 안전하게 다운샘플링합니다. (메모리 사용량 95% 이상 절감)
     */
    private fun decodeSafeSampledBitmap(uri: Uri, maxDimension: Int = 1024): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            var inSampleSize = 1
            var halfW = origW
            var halfH = origH
            while (halfW > maxDimension || halfH > maxDimension) {
                inSampleSize *= 2
                halfW /= 2
                halfH /= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // ARGB_8888 대비 메모리 50% 절감
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun analyzeLockerSlip(imageUri: Uri, fallbackDate: LocalDate? = null): GolfLockerSlipResult = withContext(Dispatchers.IO) {
        var sampledBitmap: Bitmap? = null
        try {
            sampledBitmap = decodeSafeSampledBitmap(imageUri, 1024)
            val image = if (sampledBitmap != null) {
                InputImage.fromBitmap(sampledBitmap, 0)
            } else {
                InputImage.fromFilePath(context, imageUri)
            }
            val visionText = recognizer.process(image).await()
            val raw = visionText.text

            parseLockerSlipText(raw, fallbackDate)
        } catch (t: Throwable) {
            // OutOfMemoryError 및 C++ Native 예외를 포괄하는 Throwable 안전망
            GolfLockerSlipResult(
                isLockerSlip = false,
                clubName = "일반 사진",
                lockerNumber = null,
                teeOffTime = null,
                courseName = null,
                date = fallbackDate ?: LocalDate.now(),
                playerName = null,
                gender = null,
                rawText = "OCR 스킵: ${t.message}"
            )
        } finally {
            sampledBitmap?.recycle()
        }
    }

    /**
     * [M-03] ML Kit TextRecognizer 네이티브 리소스 명시적 해제.
     * @Singleton이므로 앱 수명 동안 점유하지만, 필요 시 명시적으로 해제할 수 있는 경로 제공.
     * GolfViewModel.onCleared() 또는 앱 종료 시 호출 가능.
     */
    fun close() {
        runCatching { recognizer.close() }
    }

    fun parseLockerSlipText(raw: String, fallbackDate: LocalDate? = null): GolfLockerSlipResult {
        return parse(raw, fallbackDate)
    }

    companion object {
        fun parse(raw: String, fallbackDate: LocalDate? = null): GolfLockerSlipResult {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            val upperRaw = raw.uppercase()
            runCatching {
                android.util.Log.d("SLIP_RAW", "--- OCR RAW START ---\n$raw\n--- OCR RAW END ---")
            }

        // 1. Is this a Golf Locker Slip? (단어 단위 독립 검사 및 영문 오탐 방지)
        val ccGcRegex = Regex("""\b(CC|GC|C\.C|G\.C|COUNTRY\s*CLUB|GOLF\s*CLUB|GOLF\s*RESORT|GOLF\s*LINKS)\b""", RegexOption.IGNORE_CASE)
        val hasCcGc = ccGcRegex.containsMatchIn(raw)
        val hasExplicitLocker = upperRaw.contains("락카") || upperRaw.contains("라커") || upperRaw.contains("락커") ||
            upperRaw.contains("LOCKER") || upperRaw.contains("비밀번호") || upperRaw.contains("PASSWORD") || upperRaw.contains("정산기")
        val hasGolfTerms = upperRaw.contains("골프") || upperRaw.contains("GOLF") || upperRaw.contains("TEE-OFF") || upperRaw.contains("TEE OFF") ||
            upperRaw.contains("티오프") || upperRaw.contains("티업") || upperRaw.contains("그린피") || upperRaw.contains("골프장") || upperRaw.contains("골프백") ||
            upperRaw.contains("라운드") || upperRaw.contains("ROUND") || upperRaw.contains("캐디") || upperRaw.contains("CADDIE") ||
            upperRaw.contains("CART") || upperRaw.contains("카트") || upperRaw.contains("자동차키") || upperRaw.contains("문신") ||
            upperRaw.contains("COURSE") || upperRaw.contains("홀") || upperRaw.contains("HOLE")
        val hasThankYouNotice = raw.contains("감사합니다") || raw.contains("환영합니다") || upperRaw.contains("THANK YOU") || upperRaw.contains("WELCOME")

        // 2. Club Name Extraction (영문 및 상단/하단 전방위 정밀 매칭)
        // 국내 대표 골프장 한글 및 영문 키워드 매핑 테이블
        val knownClubs = mapOf(
            // 경기 / 여주 / 이천
            "스카이밸리" to "스카이밸리 CC",
            "SKY VALLEY" to "스카이밸리 CC",
            "SKYVALLEY" to "스카이밸리 CC",
            "아리지" to "아리지 CC",
            "ARIJI" to "아리지 CC",
            "세라지오" to "세라지오 CC",
            "CERAGIO" to "세라지오 CC",
            "솔모로" to "솔모로 CC",
            "SOLMORO" to "솔모로 CC",
            "페럼클럽" to "페럼클럽",
            "FERRUM" to "페럼클럽",
            "블루헤런" to "블루헤런 CC",
            "BLUE HERON" to "블루헤런 CC",
            "자유" to "자유 CC",
            "JAYU" to "자유 CC",
            "트리니티" to "트리니티 클럽",
            "TRINITY" to "트리니티 클럽",
            "해슬리" to "해슬리 나인브릿지",
            "HAESLEY" to "해슬리 나인브릿지",
            "금강" to "금강 CC",
            "KUMGANG" to "금강 CC",
            "신라" to "신라 CC",
            "SHILLA" to "신라 CC",
            "루트52" to "루트52 CC",
            "ROUTE 52" to "루트52 CC",
            "ROUTE52" to "루트52 CC",
            "소피아그린" to "소피아그린 CC",
            "SOPHIA GREEN" to "소피아그린 CC",
            "남여주" to "남여주 GC",
            "NAMYEOJU" to "남여주 GC",
            "사우스스프링스" to "사우스스프링스 CC",
            "SOUTH SPRINGS" to "사우스스프링스 CC",
            "SOUTHSPRINGS" to "사우스스프링스 CC",
            "블랙스톤" to "블랙스톤 CC",
            "BLACK STONE" to "블랙스톤 CC",
            "BLACKSTONE" to "블랙스톤 CC",
            "웰링턴" to "웰링턴 CC",
            "WELLINGTON" to "웰링턴 CC",
            "비에이비스타" to "비에이비스타 CC",
            "BA VISTA" to "비에이비스타 CC",
            "BAVISTA" to "비에이비스타 CC",
            "뉴스프링빌" to "뉴스프링빌 CC",
            "NEW SPRINGVILLE" to "뉴스프링빌 CC",
            "H1" to "H1 CLUB",

            // 경기 포천 / 가평 / 파주
            "필로스" to "필로스 CC",
            "PHILOS" to "필로스 CC",
            "베어크리크" to "베어크리크 GC",
            "BEAR CREEK" to "베어크리크 GC",
            "BEARCREEK" to "베어크리크 GC",
            "일동레이크" to "일동레이크 GC",
            "ILDONG LAKE" to "일동레이크 GC",
            "몽베르" to "몽베르 CC",
            "MONTVERT" to "몽베르 CC",
            "아도니스" to "포천아도니스 CC",
            "ADONIS" to "포천아도니스 CC",
            "포레스트힐" to "포레스트힐 CC",
            "FOREST HILL" to "포레스트힐 CC",
            "아난티" to "아난티 클럽",
            "ANANTI" to "아난티 클럽",
            "가평베네스트" to "가평베네스트 GC",
            "GAPYEONG BENEST" to "가평베네스트 GC",
            "크리스탈밸리" to "크리스탈밸리 CC",
            "CRYSTAL VALLEY" to "크리스탈밸리 CC",
            "프리스틴밸리" to "프리스틴밸리 CC",
            "PRISTINE VALLEY" to "프리스틴밸리 CC",
            "서원밸리" to "서원밸리 CC",
            "SEOWON VALLEY" to "서원밸리 CC",
            "서원힐스" to "서원힐스 CC",
            "SEOWON HILLS" to "서원힐스 CC",
            "송추" to "송추 CC",
            "SONGCHU" to "송추 CC",
            "레이크우드" to "레이크우드 CC",
            "LAKEWOOD" to "레이크우드 CC",
            "티클라우드" to "티클라우드 CC",
            "T-CLOUD" to "티클라우드 CC",

            // 경기 광주 / 용인 / 안성
            "남촌" to "남촌 CC",
            "NAMCHON" to "남촌 CC",
            "이스트밸리" to "이스트밸리 CC",
            "EAST VALLEY" to "이스트밸리 CC",
            "곤지암" to "곤지암 GC",
            "GONJIAM" to "곤지암 GC",
            "뉴서울" to "뉴서울 CC",
            "NEW SEOUL" to "뉴서울 CC",
            "화산" to "화산 CC",
            "HWASAN" to "화산 CC",
            "신원" to "신원 CC",
            "SHINWON" to "신원 CC",
            "아시아나" to "아시아나 CC",
            "ASIANA" to "아시아나 CC",
            "레이크사이드" to "레이크사이드 CC",
            "LAKESIDE" to "레이크사이드 CC",
            "글렌로스" to "글렌로스 GC",
            "GLENROSS" to "글렌로스 GC",
            "태광" to "태광 CC",
            "TAEKWANG" to "태광 CC",
            "은화삼" to "은화삼 CC",
            "EUNHWASAM" to "은화삼 CC",
            "지산" to "지산 CC",
            "JISAN" to "지산 CC",
            "안성베네스트" to "안성베네스트 GC",
            "ANSEONG BENEST" to "안성베네스트 GC",
            "안양" to "안양 CC",
            "ANYANG" to "안양 CC",

            // 강원 / 춘천 / 원주
            "라데나" to "라데나 GC",
            "LADENA" to "라데나 GC",
            "라비에벨" to "라비에벨 CC",
            "LAVIEESTBELLE" to "라비에벨 CC",
            "LA VIE EST BELLE" to "라비에벨 CC",
            "더플레이어스" to "더플레이어스 CC",
            "THE PLAYERS" to "더플레이어스 CC",
            "제이드팰리스" to "제이드팰리스 GC",
            "JADE PALACE" to "제이드팰리스 GC",
            "오크밸리" to "오크밸리 CC",
            "OAK VALLEY" to "오크밸리 CC",
            "오크크릭" to "오크크릭 GC",
            "OAK CREEK" to "오크크릭 GC",
            "성문안" to "성문안 CC",
            "SEONGMUNAN" to "성문안 CC",
            "휘슬링락" to "휘슬링락 CC",
            "WHISTLING ROCK" to "휘슬링락 CC",
            "카스카디아" to "카스카디아 CC",
            "CASCADIA" to "카스카디아 CC",
            "웰리힐리" to "웰리힐리 CC",
            "WELLI HILLI" to "웰리힐리 CC",
            "WELLIHILLI" to "웰리힐리 CC",
            "비발디파크" to "비발디파크 CC",
            "VIVALDI PARK" to "비발디파크 CC",
            "소노펠리체" to "소노펠리체 CC",
            "SONO FELICE" to "소노펠리체 CC",
            "동강시스타" to "동강시스타 CC",
            "파인리즈" to "파인리즈 CC",
            "PINE RIDGE" to "파인리즈 CC",
            "메이플비치" to "메이플비치 CC",
            "MAPLE BEACH" to "메이플비치 CC",
            "샌드파인" to "샌드파인 GC",
            "SAND PINE" to "샌드파인 GC",
            "클럽모우" to "클럽모우 CC",
            "CLUB MOW" to "클럽모우 CC",

            // 충청 / 영남 / 호남 / 인천 / 제주
            "킹스데일" to "킹스데일 GC",
            "KINGS DALE" to "킹스데일 GC",
            "KINGSDALE" to "킹스데일 GC",
            "우정힐스" to "우정힐스 CC",
            "WOOJEONG HILLS" to "우정힐스 CC",
            "세종필드" to "세종필드 GC",
            "SEJONG FIELD" to "세종필드 GC",
            "레인보우힐스" to "레인보우힐스 CC",
            "RAINBOW HILLS" to "레인보우힐스 CC",
            "클럽72" to "클럽72",
            "CLUB 72" to "클럽72",
            "스카이72" to "스카이72",
            "SKY 72" to "스카이72",
            "잭니클라우스" to "잭니클라우스 GC",
            "JACK NICKLAUS" to "잭니클라우스 GC",
            "베어즈베스트" to "베어즈베스트 청라",
            "BEARS BEST" to "베어즈베스트 청라",
            "드림파크" to "드림파크 CC",
            "DREAM PARK" to "드림파크 CC",
            "블루원" to "블루원 CC",
            "BLUE ONE" to "블루원 CC",
            "BLUEONE" to "블루원 CC",
            "사우스링스" to "사우스링스 영암",
            "SOUTH LYNX" to "사우스링스 영암",
            "핀크스" to "핀크스 GC",
            "PINX" to "핀크스 GC",
            "나인브릿지" to "나인브릿지",
            "NINE BRIDGES" to "나인브릿지",
            "테디밸리" to "테디밸리 골프앤리조트",
            "TEDDY VALLEY" to "테디밸리 골프앤리조트",
            "롯데스카이힐" to "롯데스카이힐 CC",
            "LOTTE SKYHILL" to "롯데스카이힐 CC"
        )

        var clubName: String? = null
        val normalizedRaw = raw.replace(Regex("""[^가-힣A-Za-z0-9]"""), "").uppercase()

        // 2-0. 전체 텍스트에서 사전(knownClubs) 정규화 매칭 (영문/한글 공백 무관 매칭)
        for ((kw, name) in knownClubs) {
            val normKw = kw.replace(Regex("""[^가-힣A-Za-z0-9]"""), "").uppercase()
            if (normKw.length >= 2 && normalizedRaw.contains(normKw)) {
                clubName = name
                break
            }
        }

        // 2-1. 최하단 영역(마지막 6줄) 특화 탐색: 감사/환영 문구, 법인/상호, 주소/전화번호 연계
        if (clubName == null) {
            // A. 한글 및 영문 감사/환영 문구
            val bottomThanksRegex = Regex("""([가-힣A-Za-z0-9\s&'-]{2,25}?)(?:\([을를]\)|[을를])?\s*(?:방문|찾아|이용)해\s*주셔서\s*감사""")
            val bottomWelcomeRegex = Regex("""([가-힣A-Za-z0-9\s&'-]{2,25}?)(?:에\s*오신\s*것을\s*환영)""")
            val engThanksRegex = Regex("""(?:THANK\s*YOU\s*FOR\s*(?:VISITING|COMING\s*TO)|WELCOME\s*TO)\s*([A-Za-z0-9\s&'-]{2,25})""", RegexOption.IGNORE_CASE)

            val thanksMatch = bottomThanksRegex.find(raw) ?: bottomWelcomeRegex.find(raw) ?: engThanksRegex.find(raw)
            if (thanksMatch != null) {
                val cand = thanksMatch.groupValues[1].trim()
                if (cand.isNotBlank() && cand.length >= 2) {
                    val matchedKnown = knownClubs[cand] ?: knownClubs.entries.firstOrNull {
                        cand.contains(it.key, ignoreCase = true) || it.key.contains(cand, ignoreCase = true)
                    }?.value
                    clubName = matchedKnown ?: run {
                        val upperCand = cand.uppercase()
                        if (upperCand.endsWith("CC") || upperCand.endsWith("GC") || cand.endsWith("골프장") || cand.endsWith("클럽") || upperCand.endsWith("CLUB")) {
                            cand
                        } else {
                            "$cand GC"
                        }
                    }
                }
            }

            // B. 하단 사업자/상호: "(주)아리지컨트리클럽", "주식회사 필로스", "킹스데일 주식회사"
            if (clubName == null) {
                val corpRegex = Regex("""(?:주식회사|\(주\))\s*([가-힣A-Za-z0-9\s&]{2,20})|([가-힣A-Za-z0-9\s&]{2,20})\s*(?:주식회사|\(주\))""")
                for (line in lines.takeLast(6)) {
                    val m = corpRegex.find(line)
                    if (m != null) {
                        val cand = (m.groups[1]?.value ?: m.groups[2]?.value)?.trim()
                        if (!cand.isNullOrBlank() && cand.length >= 2) {
                            val matchedKnown = knownClubs[cand] ?: knownClubs.entries.firstOrNull { cand.contains(it.key, ignoreCase = true) }?.value
                            if (matchedKnown != null) {
                                clubName = matchedKnown
                                break
                            } else if (cand.contains("골프") || cand.contains("클럽") || cand.contains("컨트리") || cand.uppercase().contains("CC") || cand.uppercase().contains("GC")) {
                                clubName = cand
                                break
                            }
                        }
                    }
                }
            }

            // C. 하단 주소/전화번호 라인 연계 탐색: "TEL : 031-xxx-xxxx / 필로스 CC"
            if (clubName == null) {
                val bottomContactRegex = Regex("""(?:TEL|전화|주소|위치|도로명)\s*[:：]?\s*.*?\b([가-힣A-Za-z0-9\s&]{2,20}?\s*(?:CC|GC|골프장|골프클럽|클럽|C\.C|G\.C))\b""", RegexOption.IGNORE_CASE)
                for (line in lines.takeLast(6)) {
                    val m = bottomContactRegex.find(line)
                    if (m != null) {
                        val cand = m.groupValues[1].trim()
                        val matchedKnown = knownClubs[cand] ?: knownClubs.entries.firstOrNull { cand.contains(it.key, ignoreCase = true) }?.value
                        clubName = matchedKnown ?: cand
                        break
                    }
                }
            }
        }

        // 2-2. 상단 영역(처음 5줄) 및 하단 영역(마지막 5줄) 라인 직접 탐색 (영문/한글 CC/GC, Golf Club 패턴)
        if (clubName == null) {
            val searchLines = lines.take(5) + lines.takeLast(5)
            for (line in searchLines) {
                val cleaned = line.replace(Regex("""[^가-힣A-Za-z0-9\s\.\-_&]"""), "").trim()
                if (cleaned.length < 2) continue

                // 알려진 구장 매핑 우선
                val matched = knownClubs.entries.firstOrNull { cleaned.contains(it.key, ignoreCase = true) }?.value
                if (matched != null) {
                    clubName = matched
                    break
                }

                // 영문 또는 한글 CC/GC/골프클럽 패턴
                if (ccGcRegex.containsMatchIn(cleaned) || cleaned.contains("골프") || cleaned.contains("클럽") || cleaned.contains("컨트리")) {
                    clubName = cleaned
                    break
                }
            }
        }

        // 2-3. 본문 명시적 레이블 탐색 (예: "골프장 : 스카이밸리", "CLUB : SKY VALLEY")
        if (clubName == null) {
            val labelClubRegex = Regex("""(?:골프장|골프클럽|구장|Club|Golf\s*Club)\s*[:：]?\s*([가-힣A-Za-z0-9\s&]{2,25})""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = labelClubRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].trim()
                    val matched = knownClubs[cand] ?: knownClubs.entries.firstOrNull { cand.contains(it.key, ignoreCase = true) }?.value
                    clubName = matched ?: if (cand.endsWith("CC") || cand.endsWith("GC") || cand.endsWith("클럽") || cand.endsWith("골프장")) cand else "$cand CC"
                    break
                }
            }
        }

        // 2-4. 영문 구장명 단독 정규식 폴백 (예: "LADENA GOLF CLUB", "BEAR CREEK G.C.", "THE PLAYERS GOLF CLUB")
        if (clubName == null) {
            val engStandaloneRegex = Regex("""\b([A-Za-z0-9&'\s]{2,25}\s+(?:CC|GC|C\.C|G\.C|COUNTRY\s*CLUB|GOLF\s*CLUB|GOLF\s*LINKS|GOLF\s*RESORT))\b""", RegexOption.IGNORE_CASE)
            val engMatch = engStandaloneRegex.find(raw)
            if (engMatch != null) {
                val cand = engMatch.groupValues[1].trim()
                val matched = knownClubs.entries.firstOrNull { cand.contains(it.key, ignoreCase = true) }?.value
                clubName = matched ?: cand
            }
        }

        val hasKnownClub = clubName != null

        // 3. Locker Number Extraction
        var lockerNumber: String? = null
        val genderLockerRegex = Regex("""(?:\([남여]\)|\[[남여]\]|[남여])\s*([A-Za-z]?\s*[-–]?\s*\d{2,4})|([A-Za-z]?\s*[-–]?\s*\d{2,4})\s*(?:\([남여]\)|\[[남여]\])""")
        for (line in lines) {
            val m = genderLockerRegex.find(line)
            if (m != null) {
                val matched = (m.groups[1]?.value ?: m.groups[2]?.value)?.replace(" ", "")?.trim()
                if (!matched.isNullOrBlank() && matched != "7777" && !matched.startsWith("202")) {
                    lockerNumber = matched
                    break
                }
            }
        }
        if (lockerNumber == null) {
            for (i in lines.indices) {
                val line = lines[i].trim()
                val isNearbyGender = line.contains("남") || line.contains("여") ||
                    (i > 0 && (lines[i-1].contains("남") || lines[i-1].contains("여"))) ||
                    (i < lines.size - 1 && (lines[i+1].contains("남") || lines[i+1].contains("여")))

                val standaloneMatch = Regex("""^[A-Za-z]?\s*[-–]?\s*\d{2,4}$""").find(line)
                if (standaloneMatch != null) {
                    val cand = standaloneMatch.value.replace(" ", "").trim()
                    if (cand != "7777" && cand != "0000" && !cand.startsWith("202")) {
                        lockerNumber = cand
                        if (isNearbyGender) break
                    }
                }
            }
        }
        if (lockerNumber == null) {
            val labelLockerRegex = Regex("""(?:락카|라커|락커|LOCKER|NO\.?)\s*[:#번호\s]*([A-Za-z]?\s*[-–]?\s*\d{2,4})""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = labelLockerRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].replace(" ", "").trim()
                    if (cand != "7777" && !cand.startsWith("202")) {
                        lockerNumber = cand
                        break
                    }
                }
            }
        }

        // 4. Course & Tee-off Time Extraction (영문 OUT/IN, Hill, Lake, East, West 등 완벽 지원)
        var courseName: String? = null
        var teeOffTime: LocalTime? = null

        // 4-1. 코스 라인에서 코스명과 시간(0651 또는 06:51) 동시 추출 (예: "코  스 : Hill 0651", "Course : OUT / IN 07:20", "COURSE : LAKE 0715")
        val courseLineRegex = Regex("""(?:코\s*스|Course)\s*[:：]?\s*([가-힣A-Za-z0-9/]+(?:\s*[/]\s*[가-힣A-Za-z0-9]+)?)(?:\s+([0-2]\d[0-5]\d|[0-2]?\d:[0-5]\d))?""", RegexOption.IGNORE_CASE)
        for (line in lines) {
            val m = courseLineRegex.find(line)
            if (m != null) {
                val candCourse = m.groupValues[1].trim()
                if (candCourse.isNotBlank() && !candCourse.equals("코스", ignoreCase = true) && !candCourse.equals("COURSE", ignoreCase = true)) {
                    courseName = candCourse
                }
                val timeStr = m.groupValues.getOrNull(2)?.trim()
                if (!timeStr.isNullOrBlank()) {
                    runCatching {
                        if (timeStr.contains(":")) {
                            val parts = timeStr.split(":")
                            teeOffTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
                        } else if (timeStr.length == 4) {
                            val h = timeStr.substring(0, 2).toInt()
                            val min = timeStr.substring(2, 4).toInt()
                            teeOffTime = LocalTime.of(h, min)
                        }
                    }
                }
                break
            }
        }

        // 4-2. 알려진 코스명 폴백 탐색 (영문 및 한글 대표 코스명)
        if (courseName == null) {
            val knownCourses = listOf(
                "OUT / IN", "OUT/IN", "OUT", "IN",
                "EAST / WEST", "EAST/WEST", "EAST", "WEST", "SOUTH", "NORTH",
                "마운틴", "달님", "해님", "별님", "레이크", "힐", "밸리", "파인", "서", "동", "남", "북",
                "Mountain", "Hill", "Lake", "Valley", "Pine", "Ocean", "Creek", "River", "Forest"
            )
            for (course in knownCourses) {
                // 단어 경계 또는 특수문자 뒤 매칭
                val regex = Regex("""(?:\b|[^가-힣A-Za-z0-9])${Regex.escape(course)}(?:\b|[^가-힣A-Za-z0-9]|코스)""", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(raw)) {
                    courseName = course
                    break
                }
            }
        }

        // 4-3. 티오프 시간 별도 라인 추출 (미추출 시)
        if (teeOffTime == null) {
            val timeWithLabel = Regex("""(?:Tee-Off|Tee\s*Off|T/O|Time|시간|티오프|티업|달님|마운틴|서|동|남|북|Hill|힐|밸리|레이크|OUT|IN|EAST|WEST)\s*[:：|]?\s*([0-2]?\d:[0-5]\d|[0-2]\d[0-5]\d)""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = timeWithLabel.find(line)
                if (m != null) {
                    val timeStr = m.groupValues[1].trim()
                    runCatching {
                        if (timeStr.contains(":")) {
                            val parts = timeStr.split(":")
                            teeOffTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
                        } else if (timeStr.length == 4) {
                            val h = timeStr.substring(0, 2).toInt()
                            val min = timeStr.substring(2, 4).toInt()
                            teeOffTime = LocalTime.of(h, min)
                        }
                    }
                    if (teeOffTime != null) break
                }
            }
        }
        if (teeOffTime == null) {
            val genericTime = Regex("""\b([0-2]?\d:[0-5]\d)\b""")
            for (line in lines) {
                val m = genericTime.find(line)
                if (m != null) {
                    runCatching {
                        val parts = m.groupValues[1].split(":")
                        teeOffTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
                    }
                    if (teeOffTime != null) break
                }
            }
        }

        // 5. Date Extraction (구분자 포함 YYYY-MM-DD 및 8자리 YYYYMMDD 지원)
        var parsedDate: LocalDate? = null
        val dateRegex = Regex("""(20\d{2})[-./년]\s*(\d{1,2})[-./월]\s*(\d{1,2})""")
        for (line in lines) {
            val m = dateRegex.find(line)
            if (m != null) {
                val y = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val d = m.groupValues[3].toInt()
                runCatching {
                    parsedDate = LocalDate.of(y, month, d)
                }
                if (parsedDate != null) break
            }
        }
        if (parsedDate == null) {
            // 구분자 없는 8자리 YYYYMMDD (예: "날  짜 : 20260907", "20260907")
            val date8Regex = Regex("""(?:날\s*짜|DATE)\s*[:：]?\s*\b(20\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\b""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = date8Regex.find(line)
                if (m != null) {
                    val y = m.groupValues[1].toInt()
                    val month = m.groupValues[2].toInt()
                    val d = m.groupValues[3].toInt()
                    runCatching {
                        parsedDate = LocalDate.of(y, month, d)
                    }
                    if (parsedDate != null) break
                }
            }
        }
        val finalDate = parsedDate ?: (fallbackDate ?: LocalDate.now())

        // 6. Player Name & Gender Extraction
        // 1순위: 'OOO 고객님', 'OOO 회원님', 'OOO 님' (실제 라운더 본인 우선)
        var playerName: String? = null
        val playerSuffixRegex = Regex("""([가-힣]{2,4})\s*(?:고객님|회원님|님)""")
        for (line in lines) {
            val m = playerSuffixRegex.find(line)
            if (m != null) {
                val cand = m.groupValues[1].trim()
                if (cand != "고객" && cand != "회원" && cand != "예약") {
                    playerName = cand
                    break
                }
            }
        }
        // 2순위: 명시적 레이블 (회원명, 성명, 이름, GUEST, NAME, 예약자 등)
        if (playerName == null) {
            val playerLabelRegex = Regex("""(?:회원명|고객명|성\s*명|이\s*름|GUEST|NAME|MEMBER|예약\s*고객|예약자)\s*[:：]?\s*([가-힣]{2,4}|[A-Za-z]{2,15})""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = playerLabelRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].trim()
                    if (cand != "고객" && cand != "회원" && cand != "예약" && cand != "회원명" && cand != "성명") {
                        playerName = cand
                        break
                    }
                }
            }
        }

        var gender: String? = null
        if (raw.contains("(남)") || raw.contains("남성") || raw.contains("락카(남)") || upperRaw.contains("MALE") || upperRaw.contains("(M)")) gender = "남"
        else if (raw.contains("(여)") || raw.contains("여성") || raw.contains("락카(여)") || upperRaw.contains("FEMALE") || upperRaw.contains("(F)")) gender = "여"

        // 7. Final Slip Decision (구체적인 골프장명이 식별되었거나 명확한 골프/라커 특성이 있을 때만 인정)
        val isLockerSlip = ((hasExplicitLocker && (hasGolfTerms || hasCcGc || hasKnownClub || lockerNumber != null)) ||
                (hasKnownClub && (lockerNumber != null || teeOffTime != null || hasGolfTerms)) ||
                (lockerNumber != null && teeOffTime != null && (hasGolfTerms || hasCcGc || hasThankYouNotice)) ||
                (lockerNumber != null && (hasGolfTerms && hasThankYouNotice)) ||
                (hasCcGc && (lockerNumber != null || (teeOffTime != null && hasGolfTerms)))) && clubName != null

        return GolfLockerSlipResult(
            isLockerSlip = isLockerSlip,
            clubName = if (isLockerSlip) (clubName ?: "골프장") else "일반 사진",
            lockerNumber = lockerNumber,
            teeOffTime = teeOffTime,
            courseName = courseName,
            date = finalDate,
            playerName = playerName,
            gender = gender,
            rawText = raw
        )
    }
}
}
