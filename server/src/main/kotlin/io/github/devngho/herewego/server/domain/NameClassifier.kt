package io.github.devngho.herewego.server.domain

import io.github.devngho.herewego.server.model.PlaceCategory

/** 이름만 아는 장소(내비 최근 목적지)를 키워드로 분류한다. */
object NameClassifier {
    private val KEYWORDS: List<Pair<Regex, PlaceCategory>> = listOf(
        Regex("카페|커피|coffee|스타벅스|투썸|이디야", RegexOption.IGNORE_CASE) to PlaceCategory.CAFE,
        Regex("헬스|피트니스|짐|gym|크로스핏|요가|필라테스|수영장", RegexOption.IGNORE_CASE) to PlaceCategory.GYM,
        Regex("공원|숲|산책|둘레길|천$") to PlaceCategory.PARK,
        Regex("서점|책방|문고|도서관") to PlaceCategory.BOOKSTORE,
        Regex("베이커리|빵|제과|bakery", RegexOption.IGNORE_CASE) to PlaceCategory.BAKERY,
        Regex("식당|맛집|국수|고기|밥|분식|레스토랑") to PlaceCategory.RESTAURANT,
        Regex("미술관|박물관|전시|갤러리|아트") to PlaceCategory.GALLERY,
        Regex("마트|market|시장", RegexOption.IGNORE_CASE) to PlaceCategory.MART,
        Regex("꽃") to PlaceCategory.FLORIST,
        Regex("충전") to PlaceCategory.EV_CHARGING,
    )

    fun classify(name: String): PlaceCategory? = KEYWORDS.firstOrNull { (re, _) -> re.containsMatchIn(name) }?.second
}
