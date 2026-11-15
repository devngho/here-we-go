package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.model.Hours
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.places.GoogleCategories
import io.github.devngho.herewego.server.places.GoogleDayTime
import io.github.devngho.herewego.server.places.GoogleHours
import io.github.devngho.herewego.server.places.GoogleLatLng
import io.github.devngho.herewego.server.places.GooglePeriod
import io.github.devngho.herewego.server.places.GooglePlace
import io.github.devngho.herewego.server.places.GoogleReview
import io.github.devngho.herewego.server.places.GoogleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class GoogleCategoriesTest {

    @Test
    fun `classifies real Google place types`() {
        val cases = listOf(
            Triple("korean_restaurant", listOf("korean_restaurant", "restaurant", "food"), PlaceCategory.RESTAURANT),
            Triple("hamburger_restaurant", listOf("hamburger_restaurant", "american_restaurant", "restaurant"), PlaceCategory.RESTAURANT),
            Triple("coffee_shop", listOf("coffee_shop", "cafe"), PlaceCategory.CAFE),
            Triple("bakery", listOf("bakery", "cafe", "food_store"), PlaceCategory.BAKERY),
            Triple("fitness_center", listOf("fitness_center", "gym", "health"), PlaceCategory.GYM),
            Triple("art_museum", listOf("art_museum", "museum"), PlaceCategory.GALLERY),
            Triple("supermarket", listOf("supermarket", "grocery_store"), PlaceCategory.MART),
            Triple("liquor_store", listOf("liquor_store", "store"), PlaceCategory.WINE_SHOP),
            Triple("electric_vehicle_charging_station", emptyList(), PlaceCategory.EV_CHARGING),
            // 주 종류가 없는 곳은 부가 종류를 본다.
            Triple(null, listOf("florist", "store", "point_of_interest"), PlaceCategory.FLORIST),
        )
        cases.forEach { (primary, types, expected) -> assertEquals("$primary $types", expected, GoogleCategories.classify(primary, types)) }
    }

    @Test
    fun `bars and places whose main type is something else are not recommended`() {
        assertNull(GoogleCategories.classify("bar", listOf("bar", "restaurant")))
        assertNull(GoogleCategories.classify(null, listOf("restaurant", "pub")))
        assertNull(GoogleCategories.classify("event_venue", listOf("event_venue", "cafe")))
        assertNull(GoogleCategories.classify("playground", listOf("playground", "park")))
        assertNull(GoogleCategories.classify("performing_arts_theater", listOf("performing_arts_theater")))
    }

    private val cafe = GooglePlace(
        id = "ChIJabc", displayName = GoogleText("프릳츠 양재점"), primaryType = "coffee_shop", types = listOf("coffee_shop", "cafe"),
        location = GoogleLatLng(37.48535, 127.03220), formattedAddress = "대한민국 서울특별시 서초구 강남대로37길 24-11",
        rating = 4.4, userRatingCount = 512, googleMapsUri = "https://maps.google.com/?cid=123&g_mp=tracking",
        businessStatus = "OPERATIONAL",
        regularOpeningHours = GoogleHours(listOf(
            GooglePeriod(GoogleDayTime(1, 8, 0), GoogleDayTime(1, 15, 0)),
            GooglePeriod(GoogleDayTime(1, 16, 30), GoogleDayTime(1, 22, 0)),
            // 토요일 밤에 열어 일요일 새벽에 닫는다.
            GooglePeriod(GoogleDayTime(6, 18, 0), GoogleDayTime(0, 2, 0)),
        )),
        reviews = listOf(GoogleReview(GoogleText("산미 있는 커피가\n좋아요")), GoogleReview(GoogleText(" ")), GoogleReview(GoogleText("x".repeat(500)))),
    )

    @Test
    fun `converts a Google place with rating, weekly hours and review excerpts`() {
        val place = GoogleCategories.toPlace(cafe)!!
        assertEquals("google:ChIJabc", place.id)
        assertEquals(PlaceCategory.CAFE, place.category)
        assertEquals("서울특별시 서초구 강남대로37길 24-11", place.address)
        assertEquals(4.4, place.rating)
        assertEquals(512, place.ratingCount)
        assertEquals("https://maps.google.com/?cid=123", place.mapsUrl)
        assertEquals(listOf("산미 있는 커피가 좋아요", "x".repeat(200)), place.reviews)
        // 요일을 정하기 전에는 카테고리 기본값.
        assertTrue(place.hoursEstimated)

        with(place.on(DayOfWeek.MONDAY)) {
            assertFalse(hoursEstimated)
            assertEquals(LocalTime.of(8, 0), openTime)
            assertEquals(LocalTime.of(22, 0), closeTime)
            assertTrue(isOpenAt(LocalTime.of(12, 0)))
        }
        with(place.on(DayOfWeek.SATURDAY)) {
            assertTrue(isOpenAt(LocalTime.of(23, 0)) && isOpenAt(LocalTime.of(1, 0)))
            assertFalse(isOpenAt(LocalTime.of(12, 0)))
        }
        with(place.on(DayOfWeek.TUESDAY)) {
            assertTrue(closedToday)
            assertFalse(hoursEstimated)
            assertFalse(isOpenAt(LocalTime.of(12, 0)))
        }
    }

    @Test
    fun `a place without hours keeps the category defaults and one open all day is open at any time`() {
        val unknown = GoogleCategories.toPlace(cafe.copy(regularOpeningHours = null))!!.on(DayOfWeek.MONDAY)
        assertTrue(unknown.hoursEstimated)
        assertEquals(LocalTime.of(8, 0), unknown.openTime)

        val allDay = GoogleCategories.weeklyHours(GoogleHours(listOf(GooglePeriod(GoogleDayTime(0, 0, 0)))))!!
        assertEquals(Hours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT), allDay[DayOfWeek.WEDNESDAY])
        val gym = GoogleCategories.toPlace(cafe.copy(primaryType = "gym", regularOpeningHours = GoogleHours(listOf(GooglePeriod(GoogleDayTime(0, 0, 0))))))!!
        assertTrue(gym.on(DayOfWeek.SUNDAY).isOpenAt(LocalTime.of(3, 0)))
    }

    @Test
    fun `search-bait suffixes are removed from names`() {
        assertEquals("셀렉티드닉스 (Selected Nick's) 강남역점", GoogleCategories.cleanName("셀렉티드닉스 (Selected Nick's) 강남역점 [セレクティドニックス]｜韓国カフェ | 江南駅カフェ"))
        assertEquals("레이지요거트 강남점", GoogleCategories.cleanName("레이지요거트 강남점 Lazy Yogurt Gangnam"))
        assertEquals("디어블룸", GoogleCategories.cleanName("디어블룸(DearBloom)"))
        assertEquals("호보식당 역삼점", GoogleCategories.cleanName("호보식당 역삼점 | Gangnam BBQ"))
        // 한글 이름이 없으면 그대로, 이름 가운데의 영문도 그대로.
        assertEquals("Flower Gift Korea", GoogleCategories.cleanName("Flower Gift Korea"))
        assertEquals("메가MGC커피 강남역점", GoogleCategories.cleanName("메가MGC커피 강남역점"))
        assertEquals("F45 역삼", GoogleCategories.cleanName("F45 역삼"))
    }

    @Test
    fun `closed businesses, unclassified places and children's parks are dropped`() {
        assertNull(GoogleCategories.toPlace(cafe.copy(businessStatus = "CLOSED_PERMANENTLY")))
        assertNull(GoogleCategories.toPlace(cafe.copy(primaryType = "hospital", types = listOf("hospital"))))
        assertEquals(PlaceCategory.LANDMARK, GoogleCategories.toPlace(cafe.copy(primaryType = "hospital"), fallback = PlaceCategory.LANDMARK)!!.category)
        assertNull(GoogleCategories.toPlace(cafe.copy(primaryType = "park", displayName = GoogleText("양재어린이공원"))))
        assertEquals(35, GoogleCategories.toPlace(cafe.copy(primaryType = "hamburger_restaurant"))!!.stayMinutes)
    }

    @Test
    fun `every recommendable category can be searched and shares a call with its group`() {
        assertEquals(PlaceCategory.entries.filter { it.recommendable }.toSet(), GoogleCategories.specs.keys)
        // 지금 여기의 여덟 카테고리는 네 번의 호출로 찾는다.
        assertEquals(4, io.github.devngho.herewego.server.service.ExploreService.EXPLORE_CATEGORIES.map { GoogleCategories.specs.getValue(it).group }.distinct().size)
        // 검색에 쓰는 종류는 그 카테고리로 분류되어야 한다.
        GoogleCategories.specs.forEach { (category, spec) -> spec.types.forEach { assertEquals(it, category, GoogleCategories.classify(it, listOf(it))) } }
    }
}
