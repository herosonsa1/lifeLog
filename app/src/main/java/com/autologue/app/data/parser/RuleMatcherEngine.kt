package com.autologue.app.data.parser

import com.autologue.app.domain.model.ExpenseCategory

object RuleMatcherEngine {

    private val KEYWORD_MAP = mapOf(
        ExpenseCategory.FUEL to listOf(
            "주유", "주유소", "충전소", "셀프주유", "주유셀프", "직영주유", "셀프충전", "주유타운",
            "GS칼텍스", "지에스칼텍스", "GS주유", "GS셀프", "GS이엠", "지에스이엠", "지에스이앤알", "지에스엠비즈",
            "SK에너지", "SK주유", "SK엔크린", "엔크린", "SK가스", "SK셀프", "SK네트웍스",
            "에쓰오일", "S-OIL", "SOIL", "에스오일", "구도일", "S오일", "에쓰-오일", "쓰오일",
            "현대오일뱅크", "HD현대오일뱅크", "HD현대", "오일뱅크", "현대오일", "현대셀프",
            "알뜰주유", "알뜰주유소", "알뜰셀프", "자영주유소", "농협주유", "NH주유", "NH-OIL", "NHOIL", "하나로주유",
            "한국도로공사", "한국도로공사하", "한국도로공사상", "도로공사주유", "도로공사충전",
            "ex-oil", "EX-OIL", "EX주유소", "ex주유소", "휴게소주유", "휴게소충전", "만남의광장주유",
            "유진주유", "광신석유", "대성산업",
            "LPG충전", "엘피지충전", "슈퍼차저", "전기차충전", "차지비", "파워큐브", "에버온", "채비", "모두의충전", "일렉링크", "수소충전"
        ),
        ExpenseCategory.GOLF_FIELD to listOf(
            "CC", "GC", "골프클럽", "컨트리클럽", "골프장", "라비에벨", "베어크리크", "남촌", "해슬리", "아난티", "그늘집"
        ),
        ExpenseCategory.GOLF_SCREEN to listOf(
            "골프존", "GDR", "카카오골프", "프렌즈스크린", "SG골프", "스크린골프"
        ),
        ExpenseCategory.CAR_MAINTENANCE to listOf(
            "정비", "타이어", "오토오아시스", "스피드메이트", "블루핸즈", "오토큐", "세차"
        ),
        ExpenseCategory.CAFE to listOf(
            "스타벅스", "투썸", "이디야", "메가커피", "컴포즈", "빽다방", "폴바셋", "카페", "커피", "베이커리", "파리바게뜨", "뚜레쥬르", "커피인류"
        ),
        ExpenseCategory.FOOD to listOf(
            "식당", "음식점", "한식", "중식", "일식", "양식", "고기", "치킨", "피자", "버거", "맥도날드", "버거킹", 
            "배달의민족", "쿠팡이츠", "요기요", "배민", "배민클럽", "우아한형제", "아브뉴프랑"
        ),
        ExpenseCategory.SHOPPING to listOf(
            "쿠팡", "네이버페이", "백화점", "아울렛", "다이소", "올리브영", "무신사", "지그재그", "쿠프마케팅", "상품권", "모바일쿠폰"
        ),
        ExpenseCategory.MEDICAL to listOf(
            "병원", "의원", "약국", "치과", "한의원", "메디컬", "클리닉", "내과", "외과", "소아과", "피부과",
            "안과", "이비인후과", "정형외과", "산부인과", "비뇨기과", "신경외과", "정신건강", "재활의학", "마취통증",
            "건강검진", "검진센터", "검진", "대학병원", "성모병원", "아산병원", "세브란스", "삼성서울", "약제", "조제", "온누리약국"
        ),
        ExpenseCategory.LIVING to listOf(
            "이마트", "홈플러스", "롯데마트", "트레이더스", "코스트코", "하나로마트", "슈퍼", "마트", "GS수퍼", "지에스리테일", "GS더프레시", "아이스크림",
            "넷플릭스", "유튜브", "왓챠", "디즈니", "웨이브", "티빙", "스포티파이", "멤버십", "네이버플러스",
            "천재교과서", "교과서", "교재", "학원", "학습", "메가스터디", "EBS",
            "LGUPLUS", "LG유플러스", "SKT", "KT", "통신", "통신요금", "관리비", "도시가스", "전기요금", "편의점", "CU", "GS25", "세븐일레븐", "이마트24"
        ),
        ExpenseCategory.TRANSPORT to listOf(
            "코레일", "SRT", "택시", "카카오T", "지하철", "버스", "티머니", "하이패스"
        ),
        ExpenseCategory.TRANSFER to listOf(
            "수수료", "통지수수료", "입출통지수수료", "이체수수료", "출금", "이체", "송금", "카카오페이 충전"
        )
    )

    fun classifyMerchant(merchantName: String, isIncome: Boolean = false, originalText: String = ""): ExpenseCategory {
        if (isIncome) return ExpenseCategory.INCOME
        if (SmsParser.isInvalidMerchantName(merchantName)) return ExpenseCategory.ETC

        val upper = merchantName.uppercase().replace(" ", "")
        for ((category, keywords) in KEYWORD_MAP) {
            for (kw in keywords) {
                if (upper.contains(kw.uppercase().replace(" ", ""))) {
                    return category
                }
            }
        }
        // 가맹점명이 일반 기본값("카드 가맹점", "가맹점")이고 실제 카드 승인 문자일 때만 원문 내 주유소 키워드 확인
        if (originalText.isNotBlank() &&
            merchantName.trim() in setOf("카드 가맹점", "가맹점") &&
            SmsParser.isCardApprovalMessage(originalText)
        ) {
            val upperOrig = originalText.uppercase().replace(" ", "")
            val fuelKeywords = KEYWORD_MAP[ExpenseCategory.FUEL] ?: emptyList()
            if (fuelKeywords.any { upperOrig.contains(it.uppercase().replace(" ", "")) }) {
                return ExpenseCategory.FUEL
            }
        }
        return ExpenseCategory.ETC
    }
}
