package io.github.devngho.herewego.server.model

import java.time.DayOfWeek
import java.time.LocalTime

/** 하루 영업시간. 24시간 영업은 00:00~00:00. */
data class Hours(val open: LocalTime, val close: LocalTime)

/** WGS84 좌표. */
data class GeoPoint(val latitude: Double, val longitude: Double)

enum class PlaceCategory(
    val label: String,
    /** false 이면 일정/목적지 용도로만 쓰이고 추천 후보에서는 빠진다. */
    val recommendable: Boolean = true,
) {
    RESTAURANT("식당"),
    CAFE("카페"),
    BAKERY("베이커리"),
    PARK("공원"),
    GYM("헬스장"),
    BOOKSTORE("서점"),
    GALLERY("전시·문화"),
    FLORIST("꽃집"),
    MART("마트"),
    CONVENIENCE("편의점"),
    WINE_SHOP("와인샵"),
    GIFT_SHOP("선물가게"),
    PHARMACY("약국"),
    EV_CHARGING("충전소"),
    OFFICE("업무", recommendable = false),
    HOME("집", recommendable = false),
    LANDMARK("장소", recommendable = false),
}

data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val location: GeoPoint,
    val address: String,
    /** Google 지도 평점. 평가가 없는 곳은 null. */
    val rating: Double?,
    /** 평균 체류 시간(분). 빈 시간 추천 시 사용. 장소 종류별 기본값이다. */
    val stayMinutes: Int,
    val openTime: LocalTime = LocalTime.of(9, 0),
    val closeTime: LocalTime = LocalTime.of(22, 0),
    /** 대표 메뉴 또는 그 장소에서 할 수 있는 일. */
    val highlights: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 내비게이션 POI ID (알고 있는 경우). */
    val poiId: String = "",
    val placeUrl: String = "",
    /** 영업시간·체류 시간이 실제 값이 아니라 카테고리 기본값이면 true. */
    val hoursEstimated: Boolean = false,
    val ratingCount: Int? = null,
    /** 요청한 날이 휴무일. [openTime]/[closeTime] 은 의미가 없다. */
    val closedToday: Boolean = false,
    /** 리뷰 경향 한 문장. */
    val reviewSummary: String = "",
    /** 평점·영업시간·메뉴·리뷰의 출처인 Google 지도 링크. 이 값들을 보여 주는 화면에 함께 표시해야 한다. */
    val mapsUrl: String = "",
    /** 요일별 영업시간. 정보가 없으면 null, 쉬는 날은 값이 null. [on] 으로 [openTime]/[closeTime] 에 반영한다. */
    val weeklyHours: Map<DayOfWeek, Hours?>? = null,
    /** 리뷰 발췌. LLM 에만 보여 주고 응답에는 넣지 않는다. */
    val reviews: List<String> = emptyList(),
) {
    /** [day] 요일의 실제 영업시간을 채운다. 주간 시간표가 없으면 카테고리 기본값 그대로. */
    fun on(day: DayOfWeek): Place {
        val weekly = weeklyHours ?: return this
        val today = weekly[day]
        return copy(openTime = today?.open ?: openTime, closeTime = today?.close ?: closeTime, closedToday = today == null, hoursEstimated = false)
    }

    fun isOpenAt(time: LocalTime): Boolean =
        if (closedToday) false
        else if (closeTime > openTime) time >= openTime && time < closeTime
        else time >= openTime || time < closeTime // 자정을 넘기는 영업
}

/** 사용자가 장소에 가는 '의도'. Func 2 에서 사용. */
enum class Purpose(
    val label: String,
    val needs: List<PurposeNeed>,
) {
    HOUSEWARMING(
        "집들이", listOf(
            PurposeNeed(PlaceCategory.FLORIST, "화분이나 꽃다발", "새 집에 어울리는 공기정화 화분은 무난한 집들이 선물이에요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.MART, "휴지·세제", "'잘 풀리라'는 의미의 휴지, '거품처럼 일어나라'는 세제는 집들이 단골 선물이에요."),
            PurposeNeed(PlaceCategory.WINE_SHOP, "함께 마실 와인", "식사 자리에 곁들일 와인 한 병이면 분위기가 살아요."),
            PurposeNeed(PlaceCategory.BAKERY, "디저트", "식사 후 함께 나눌 케이크나 쿠키를 챙겨 보세요."),
        )
    ),
    BIRTHDAY(
        "생일 파티", listOf(
            PurposeNeed(PlaceCategory.BAKERY, "케이크", "케이크는 도착 직전에 사야 모양이 망가지지 않아요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.FLORIST, "꽃다발", "작은 꽃다발 하나로도 특별한 하루가 돼요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.GIFT_SHOP, "선물", "취향을 담은 소품 선물을 골라 보세요."),
        )
    ),
    HOSPITAL_VISIT(
        "병문안", listOf(
            PurposeNeed(PlaceCategory.MART, "과일·음료", "과일 바구니나 음료 세트가 무난해요. 생화는 반입이 제한되는 병원이 많아요."),
            PurposeNeed(PlaceCategory.PHARMACY, "건강식품", "비타민이나 건강음료도 좋은 선택이에요."),
            PurposeNeed(PlaceCategory.BAKERY, "간단한 간식", "보호자와 나눠 먹을 빵이나 쿠키를 챙겨 보세요."),
        )
    ),
    PARENTS(
        "부모님 댁 방문", listOf(
            PurposeNeed(PlaceCategory.MART, "제철 과일", "제철 과일 한 상자면 반가워하세요."),
            PurposeNeed(PlaceCategory.BAKERY, "빵·떡", "함께 차 마시며 나눌 간식을 챙겨 보세요."),
            PurposeNeed(PlaceCategory.PHARMACY, "영양제", "부모님 건강을 챙기는 영양제도 좋아요."),
        )
    ),
    PICNIC(
        "피크닉·캠핑", listOf(
            PurposeNeed(PlaceCategory.MART, "식재료·음료", "도착 전에 장보기를 끝내면 편해요."),
            PurposeNeed(PlaceCategory.CONVENIENCE, "얼음·일회용품", "얼음은 녹지 않게 도착 직전에 사는 게 좋아요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.BAKERY, "샌드위치", "가볍게 먹을 샌드위치를 챙겨 보세요."),
            PurposeNeed(PlaceCategory.EV_CHARGING, "충전", "외곽으로 나가기 전에 배터리를 채워 두세요."),
        )
    ),
    BUSINESS_MEETING(
        "비즈니스 미팅", listOf(
            PurposeNeed(PlaceCategory.CAFE, "미팅 전 커피", "미팅 전 잠깐 자료를 정리할 카페예요."),
            PurposeNeed(PlaceCategory.BAKERY, "다과", "상대방과 나눌 다과를 준비하면 좋은 인상을 줘요."),
            PurposeNeed(PlaceCategory.GIFT_SHOP, "답례품", "첫 미팅이라면 작은 답례품도 좋아요."),
        )
    ),
    OFFICE_VISIT(
        "회사 방문", listOf(
            PurposeNeed(PlaceCategory.CAFE, "팀 커피", "방문하는 팀에 돌릴 커피를 사 가 보세요."),
            PurposeNeed(PlaceCategory.CONVENIENCE, "생필품", "급하게 필요한 물건은 편의점에서 해결해요."),
        )
    ),
}

data class PurposeNeed(
    val category: PlaceCategory,
    val item: String,
    val reason: String,
    /** 꽃·케이크·얼음처럼 도착 직전에 사는 게 좋은 품목. */
    val preferNearDestination: Boolean = false,
)

/** Func 3: 일정. */
data class Schedule(
    val title: String,
    val place: Place,
    val start: LocalTime,
    val expectedEnd: LocalTime,
)
