package io.github.devngho.herewego.server.places

import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.model.Hours
import io.github.devngho.herewego.server.model.Place
import io.github.devngho.herewego.server.model.PlaceCategory
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 앱 카테고리 하나를 Google 에서 어떻게 찾는지와, 영업시간·체류 시간을 모를 때의 기본값.
 *
 * @param group 주변 검색을 함께 보내는 묶음. 같은 묶음의 카테고리는 호출 한 번으로 찾는다.
 *   결과가 20건뿐이라 흔한 종류(식당, 헬스장)는 따로 묶어야 다른 종류가 밀려나지 않는다.
 * @param types 주변 검색에 쓰는 Google 장소 종류.
 * @param query 경로를 따라 찾을 때 쓰는 검색어.
 */
data class CategorySpec(
    val group: String,
    val types: List<String>,
    val query: String,
    val open: LocalTime,
    val close: LocalTime,
    val stayMinutes: Int,
)

object GoogleCategories {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val MIDNIGHT = t(0)
    private val ALL_DAY = t(0) to t(23, 59)

    val specs: Map<PlaceCategory, CategorySpec> = mapOf(
        PlaceCategory.RESTAURANT to CategorySpec("food", listOf("restaurant"), "맛집", t(11), t(21, 30), 60),
        PlaceCategory.CAFE to CategorySpec("cafe", listOf("cafe"), "카페", t(8), t(22), 40),
        PlaceCategory.BAKERY to CategorySpec("cafe", listOf("bakery"), "베이커리", t(8), t(21), 20),
        PlaceCategory.PARK to CategorySpec("culture", listOf("park"), "공원", ALL_DAY.first, ALL_DAY.second, 40),
        PlaceCategory.GALLERY to CategorySpec("culture", listOf("museum", "art_gallery"), "미술관", t(10), t(18), 60),
        PlaceCategory.BOOKSTORE to CategorySpec("culture", listOf("book_store"), "서점", t(10), t(21), 30),
        PlaceCategory.GIFT_SHOP to CategorySpec("culture", listOf("gift_shop"), "소품샵", t(10), t(21), 20),
        PlaceCategory.GYM to CategorySpec("gym", listOf("gym", "fitness_center"), "헬스장", t(6), t(23), 60),
        PlaceCategory.FLORIST to CategorySpec("florist", listOf("florist"), "꽃집", t(9), t(20), 15),
        PlaceCategory.MART to CategorySpec("mart", listOf("supermarket", "grocery_store"), "마트", t(10), t(22), 30),
        PlaceCategory.CONVENIENCE to CategorySpec("convenience", listOf("convenience_store"), "편의점", ALL_DAY.first, ALL_DAY.second, 10),
        PlaceCategory.WINE_SHOP to CategorySpec("wine", listOf("liquor_store"), "와인샵", t(11), t(22), 15),
        PlaceCategory.PHARMACY to CategorySpec("pharmacy", listOf("pharmacy"), "약국", t(9), t(20), 10),
        PlaceCategory.EV_CHARGING to CategorySpec("ev", listOf("electric_vehicle_charging_station"), "전기차 충전소", ALL_DAY.first, ALL_DAY.second, 30),
    )

    /** 운전자에게 권하지 않는 곳. primaryType 이 이것이면 다른 종류가 붙어 있어도 버린다. */
    private val REJECTED = setOf("bar", "pub", "wine_bar", "night_club", "karaoke", "playground", "dog_park")

    private fun categoryOf(type: String): PlaceCategory? = when (type) {
        "cafe", "coffee_shop", "tea_house", "dessert_shop", "ice_cream_shop" -> PlaceCategory.CAFE
        "bakery", "donut_shop" -> PlaceCategory.BAKERY
        "restaurant", "sandwich_shop", "meal_takeaway", "food_court", "diner" -> PlaceCategory.RESTAURANT
        "park", "city_park", "national_park", "garden", "botanical_garden" -> PlaceCategory.PARK
        "gym", "fitness_center", "yoga_studio" -> PlaceCategory.GYM
        "book_store" -> PlaceCategory.BOOKSTORE
        "museum", "art_museum", "history_museum", "art_gallery" -> PlaceCategory.GALLERY
        "florist" -> PlaceCategory.FLORIST
        "supermarket", "grocery_store", "hypermarket", "warehouse_store" -> PlaceCategory.MART
        "convenience_store" -> PlaceCategory.CONVENIENCE
        "liquor_store" -> PlaceCategory.WINE_SHOP
        "gift_shop" -> PlaceCategory.GIFT_SHOP
        "pharmacy", "drugstore" -> PlaceCategory.PHARMACY
        "electric_vehicle_charging_station" -> PlaceCategory.EV_CHARGING
        else -> if (type.endsWith("_restaurant")) PlaceCategory.RESTAURANT else null
    }

    /**
     * Google 장소 종류를 앱 카테고리로 분류한다. 추천 대상이 아니면 null.
     * primaryType 이 있으면 그것만 본다: 부가 종류까지 보면 '카페도 되는 모임공간' 같은 곳이 카페로 섞인다.
     * primaryType 이 없는 곳만 types 를 앞에서부터(구체적인 것부터) 본다.
     */
    fun classify(primaryType: String?, types: List<String>): PlaceCategory? {
        if (primaryType != null) return if (primaryType in REJECTED) null else categoryOf(primaryType)
        if (types.any { it in REJECTED }) return null
        return types.firstNotNullOfOrNull { categoryOf(it) }
    }

    /** 체류 시간은 Google 이 주지 않는다. 세부 종류로 카테고리 기본값을 조금 다듬는다. */
    private fun stayMinutes(primaryType: String?, category: PlaceCategory): Int = when (primaryType) {
        "fast_food_restaurant", "sandwich_shop", "meal_takeaway" -> 25
        "hamburger_restaurant", "ramen_restaurant" -> 35
        "barbecue_restaurant", "korean_barbecue_restaurant", "family_restaurant", "buffet_restaurant", "seafood_restaurant" -> 80
        "fine_dining_restaurant" -> 100
        "museum", "art_museum", "history_museum" -> 70
        "art_gallery" -> 40
        "national_park", "botanical_garden" -> 90
        else -> specs[category]?.stayMinutes ?: 0
    }

    /** 요일별 영업시간. 영업시간 정보가 없으면 null, 쉬는 날은 값이 null. 브레이크 타임은 첫 개점과 마지막 폐점으로 합친다. */
    fun weeklyHours(hours: GoogleHours?): Map<DayOfWeek, Hours?>? {
        val periods = hours?.periods?.filter { it.open != null }?.takeIf { it.isNotEmpty() } ?: return null
        if (periods.size == 1 && periods[0].close == null) return DayOfWeek.entries.associateWith { Hours(MIDNIGHT, MIDNIGHT) }
        fun time(p: GoogleDayTime) = runCatching { LocalTime.of(p.hour % 24, p.minute) }.getOrDefault(MIDNIGHT)
        val byDay = periods.groupBy { DayOfWeek.of(if (it.open!!.day % 7 == 0) 7 else it.open.day % 7) }
        return DayOfWeek.entries.associateWith { day ->
            byDay[day]?.sortedBy { it.open!!.hour * 60 + it.open.minute }?.let { Hours(time(it.first().open!!), it.last().close?.let(::time) ?: MIDNIGHT) }
        }
    }

    /**
     * Google 지도의 상호에는 검색 노출용 문구가 붙어 있을 때가 많다
     * ("셀렉티드닉스 강남역점 [セレクティドニックス]｜韓国カフェ | 江南駅カフェ", "레이지요거트 강남점 Lazy Yogurt Gangnam").
     * 화면과 음성 안내에 쓸 수 있게 구분자 뒤와, 한글 이름 뒤에 덧붙인 영문 표기를 뗀다.
     */
    fun cleanName(raw: String): String {
        val head = raw.split('|', '｜', '[', '【').first().trim()
        val korean = head.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").replace(Regex("\\s+[A-Za-z][A-Za-z0-9 '&.,-]*$"), "").trim()
        return if (korean.count { it in '가'..'힣' } >= 2) korean else head
    }

    /**
     * @param fallback 분류할 수 없는 곳에 줄 카테고리. null 이면 그런 곳은 버린다.
     * @return 분류할 수 없거나, 폐업했거나, 좌표가 없으면 null.
     */
    fun toPlace(g: GooglePlace, fallback: PlaceCategory? = null): Place? {
        val category = classify(g.primaryType, g.types) ?: fallback ?: return null
        val name = g.displayName?.text?.let(::cleanName)?.ifEmpty { null } ?: return null
        val location = g.location ?: return null
        if (g.businessStatus != null && g.businessStatus != "OPERATIONAL") return null
        // 어린이공원은 놀이터라서 어른의 '다음 코스'로는 맞지 않는다. 장소 종류로는 일반 공원과 구분되지 않는다.
        if (category == PlaceCategory.PARK && "어린이공원" in name) return null
        val spec = specs[category]
        // 링크 뒤의 호출 추적용 매개변수는 뗀다.
        val url = g.googleMapsUri.substringBefore("&g_mp=")
        return Place(
            id = "google:${g.id}",
            name = name,
            category = category,
            location = GeoPoint(location.latitude, location.longitude),
            address = g.formattedAddress.removePrefix("대한민국 "),
            rating = g.rating?.takeIf { it in 1.0..5.0 },
            ratingCount = g.userRatingCount?.takeIf { it > 0 },
            stayMinutes = stayMinutes(g.primaryType, category),
            openTime = spec?.open ?: ALL_DAY.first,
            closeTime = spec?.close ?: ALL_DAY.second,
            placeUrl = url,
            mapsUrl = url,
            hoursEstimated = true,
            weeklyHours = weeklyHours(g.regularOpeningHours),
            reviews = g.reviews.mapNotNull { r -> r.text?.text?.replace(Regex("\\s+"), " ")?.trim()?.take(REVIEW_CHARS)?.ifEmpty { null } }.take(REVIEWS_KEPT),
        )
    }

    private const val REVIEWS_KEPT = 3
    private const val REVIEW_CHARS = 200
}
