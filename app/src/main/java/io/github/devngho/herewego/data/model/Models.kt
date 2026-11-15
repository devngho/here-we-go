package io.github.devngho.herewego.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.ElectricCar
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Forest
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Museum
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LocalConvenienceStore
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalTime

/** WGS84 좌표. */
data class GeoPoint(val latitude: Double, val longitude: Double)

enum class PlaceCategory(
    val label: String,
    val icon: ImageVector,
    /** false 이면 일정/목적지 용도로만 쓰이고 추천 후보에서는 빠진다. */
    val recommendable: Boolean = true,
) {
    RESTAURANT("식당", Icons.Outlined.Restaurant),
    CAFE("카페", Icons.Outlined.Coffee),
    BAKERY("베이커리", Icons.Outlined.BakeryDining),
    PARK("공원", Icons.Outlined.Park),
    GYM("헬스장", Icons.Outlined.FitnessCenter),
    BOOKSTORE("서점", Icons.AutoMirrored.Outlined.MenuBook),
    GALLERY("전시·문화", Icons.Outlined.Museum),
    FLORIST("꽃집", Icons.Outlined.LocalFlorist),
    MART("마트", Icons.Outlined.LocalGroceryStore),
    CONVENIENCE("편의점", Icons.Outlined.LocalConvenienceStore),
    WINE_SHOP("와인샵", Icons.Outlined.WineBar),
    GIFT_SHOP("선물가게", Icons.Outlined.Redeem),
    PHARMACY("약국", Icons.Outlined.LocalPharmacy),
    EV_CHARGING("충전소", Icons.Outlined.ElectricCar),
    OFFICE("업무", Icons.Outlined.Domain, recommendable = false),
    HOME("집", Icons.Outlined.Home, recommendable = false),
    LANDMARK("장소", Icons.Outlined.Place, recommendable = false),
}

data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val location: GeoPoint,
    val address: String,
    val rating: Double,
    /** 평균 체류 시간(분). 빈 시간 추천 시 사용. */
    val stayMinutes: Int,
    val openTime: LocalTime = LocalTime.of(9, 0),
    val closeTime: LocalTime = LocalTime.of(22, 0),
    /** 대표 메뉴 또는 그 장소에서 할 수 있는 일. */
    val highlights: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 내비게이션 POI ID (알고 있는 경우). */
    val poiId: String = "",
    /** 서버에서 온 장소: 영업시간이 실제 값이 아니라 카테고리 기본값이라 화면에 보여 주지 않는다. */
    val hoursEstimated: Boolean = false,
    val ratingCount: Int = 0,
    /** 리뷰 경향 한 문장. */
    val reviewSummary: String = "",
    /** 평점·영업시간·메뉴·리뷰의 출처인 Google 지도 링크. 비어 있지 않으면 화면에 출처를 표시한다. */
    val mapsUrl: String = "",
) {
    fun isOpenAt(time: LocalTime): Boolean =
        if (closeTime > openTime) time >= openTime && time < closeTime
        else time >= openTime || time < closeTime // 자정을 넘기는 영업
}

/** 사용자가 장소에 가는 '의도'. Func 2 에서 사용. */
enum class Purpose(
    val label: String,
    val icon: ImageVector,
    val needs: List<PurposeNeed>,
) {
    HOUSEWARMING(
        "집들이", Icons.Outlined.Home, listOf(
            PurposeNeed(PlaceCategory.FLORIST, "화분이나 꽃다발", "새 집에 어울리는 공기정화 화분은 무난한 집들이 선물이에요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.MART, "휴지·세제", "'잘 풀리라'는 의미의 휴지, '거품처럼 일어나라'는 세제는 집들이 단골 선물이에요."),
            PurposeNeed(PlaceCategory.WINE_SHOP, "함께 마실 와인", "식사 자리에 곁들일 와인 한 병이면 분위기가 살아요."),
            PurposeNeed(PlaceCategory.BAKERY, "디저트", "식사 후 함께 나눌 케이크나 쿠키를 챙겨 보세요."),
        )
    ),
    BIRTHDAY(
        "생일 파티", Icons.Outlined.Celebration, listOf(
            PurposeNeed(PlaceCategory.BAKERY, "케이크", "케이크는 도착 직전에 사야 모양이 망가지지 않아요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.FLORIST, "꽃다발", "작은 꽃다발 하나로도 특별한 하루가 돼요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.GIFT_SHOP, "선물", "취향을 담은 소품 선물을 골라 보세요."),
        )
    ),
    HOSPITAL_VISIT(
        "병문안", Icons.Outlined.LocalHospital, listOf(
            PurposeNeed(PlaceCategory.MART, "과일·음료", "과일 바구니나 음료 세트가 무난해요. 생화는 반입이 제한되는 병원이 많아요."),
            PurposeNeed(PlaceCategory.PHARMACY, "건강식품", "비타민이나 건강음료도 좋은 선택이에요."),
            PurposeNeed(PlaceCategory.BAKERY, "간단한 간식", "보호자와 나눠 먹을 빵이나 쿠키를 챙겨 보세요."),
        )
    ),
    PARENTS(
        "부모님 댁 방문", Icons.Outlined.Favorite, listOf(
            PurposeNeed(PlaceCategory.MART, "제철 과일", "제철 과일 한 상자면 반가워하세요."),
            PurposeNeed(PlaceCategory.BAKERY, "빵·떡", "함께 차 마시며 나눌 간식을 챙겨 보세요."),
            PurposeNeed(PlaceCategory.PHARMACY, "영양제", "부모님 건강을 챙기는 영양제도 좋아요."),
        )
    ),
    PICNIC(
        "피크닉·캠핑", Icons.Outlined.Forest, listOf(
            PurposeNeed(PlaceCategory.MART, "식재료·음료", "도착 전에 장보기를 끝내면 편해요."),
            PurposeNeed(PlaceCategory.CONVENIENCE, "얼음·일회용품", "얼음은 녹지 않게 도착 직전에 사는 게 좋아요.", preferNearDestination = true),
            PurposeNeed(PlaceCategory.BAKERY, "샌드위치", "가볍게 먹을 샌드위치를 챙겨 보세요."),
            PurposeNeed(PlaceCategory.EV_CHARGING, "충전", "외곽으로 나가기 전에 배터리를 채워 두세요."),
        )
    ),
    BUSINESS_MEETING(
        "비즈니스 미팅", Icons.Outlined.Handshake, listOf(
            PurposeNeed(PlaceCategory.CAFE, "미팅 전 커피", "미팅 전 잠깐 자료를 정리할 카페예요."),
            PurposeNeed(PlaceCategory.BAKERY, "다과", "상대방과 나눌 다과를 준비하면 좋은 인상을 줘요."),
            PurposeNeed(PlaceCategory.GIFT_SHOP, "답례품", "첫 미팅이라면 작은 답례품도 좋아요."),
        )
    ),
    OFFICE_VISIT(
        "회사 방문", Icons.Outlined.Domain, listOf(
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
