package com.autologue.app.data.parser

import com.autologue.app.domain.model.ExpenseCategory

object RuleMatcherEngine {

    private val KEYWORD_MAP = mapOf(
        ExpenseCategory.FUEL to listOf(
            "주유", "충전", "GS칼텍스", "SK에너지", "에쓰오일", "S-OIL", "현대오일뱅크", "알뜰주유소", "E1", "슈퍼차저"
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
        ExpenseCategory.LIVING to listOf(
            "이마트", "홈플러스", "롯데마트", "트레이더스", "코스트코", "하나로마트", "슈퍼", "마트", "GS수퍼", "지에스리테일", "GS더프레시", "아이스크림",
            "넷플릭스", "유튜브", "왓챠", "디즈니", "웨이브", "티빙", "스포티파이", "멤버십", "네이버플러스",
            "천재교과서", "교과서", "교재", "학원", "학습", "메가스터디", "EBS",
            "LGUPLUS", "LG유플러스", "SKT", "KT", "통신", "통신요금", "관리비", "도시가스", "전기요금"
        ),
        ExpenseCategory.TRANSPORT to listOf(
            "코레일", "SRT", "택시", "카카오T", "지하철", "버스", "티머니", "하이패스"
        ),
        ExpenseCategory.TRANSFER to listOf(
            "수수료", "통지수수료", "입출통지수수료", "이체수수료", "출금", "이체", "송금", "카카오페이 충전"
        )
    )

    fun classifyMerchant(merchantName: String, isIncome: Boolean = false): ExpenseCategory {
        if (isIncome) return ExpenseCategory.INCOME
        val upper = merchantName.uppercase()
        for ((category, keywords) in KEYWORD_MAP) {
            for (kw in keywords) {
                if (upper.contains(kw.uppercase())) {
                    return category
                }
            }
        }
        return ExpenseCategory.ETC
    }
}
