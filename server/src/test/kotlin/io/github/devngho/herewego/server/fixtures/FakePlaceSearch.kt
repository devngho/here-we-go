package io.github.devngho.herewego.server.fixtures

import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.places.Box
import io.github.devngho.herewego.server.places.GoogleDayTime
import io.github.devngho.herewego.server.places.GoogleHours
import io.github.devngho.herewego.server.places.GoogleLatLng
import io.github.devngho.herewego.server.places.GooglePeriod
import io.github.devngho.herewego.server.places.GooglePlace
import io.github.devngho.herewego.server.places.GoogleReview
import io.github.devngho.herewego.server.places.GoogleText
import io.github.devngho.herewego.server.places.PlaceSearch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 양재역 근처와 판교의 고정된 결과를 돌려주는 가짜 Google Places.
 * @param details true 면 몇 곳에 평점·영업시간·리뷰를 붙인다. false 면 실제로 정보가 없는 곳처럼 이름과 위치만 준다.
 */
class FakePlaceSearch(private val details: Boolean = false) : PlaceSearch {
    val calls = AtomicInteger()
    val nearbyTypes = mutableListOf<List<String>>()

    private fun p(id: String, name: String, primaryType: String?, lat: Double, lng: Double, vararg types: String) = GooglePlace(
        id = id, displayName = GoogleText(name), primaryType = primaryType, types = listOfNotNull(primaryType) + types,
        location = GoogleLatLng(lat, lng), formattedAddress = "대한민국 서울특별시 서초구 강남대로 $id 1층",
        googleMapsUri = "https://maps.google.com/?cid=$id&g_mp=abc", businessStatus = "OPERATIONAL",
    )

    private fun hours(vararg days: Triple<Int, Int, Int>) = GoogleHours(days.map { (day, open, close) ->
        GooglePeriod(GoogleDayTime(day, open / 100, open % 100), GoogleDayTime(day, close / 100, close % 100))
    })

    private fun reviews(vararg texts: String) = texts.map { GoogleReview(GoogleText(it)) }

    private val places: List<GooglePlace> = listOf(
        p("1", "양재 칼국수", "korean_restaurant", 37.4850, 127.0350, "restaurant").let {
            // 일요일(0)에는 쉰다.
            if (details) it.copy(rating = 4.2, userRatingCount = 80, regularOpeningHours = hours(Triple(1, 1100, 2100), Triple(2, 1100, 2100)), reviews = reviews("칼국수 국물이 진해요")) else it
        },
        p("2", "양재 호프", "bar", 37.4851, 127.0351, "restaurant"),
        p("3", "양재 커피", "cafe", 37.4856, 127.0366),
        p("31", "양재 커피 2호점", "coffee_shop", 37.4857, 127.0367, "cafe").let {
            if (details) it.copy(rating = 3.9, userRatingCount = 12, reviews = reviews("조용해요")) else it
        },
        p("32", "양재 커피 3호점", "cafe", 37.4858, 127.0368),
        p("33", "양재 커피 4호점", "cafe", 37.4859, 127.0369),
        // 카페도 되는 모임공간: 주 종류가 카페가 아니다.
        p("4", "모임센터", "event_venue", 37.4855, 127.0365, "cafe"),
        p("11", "양재 마트", "supermarket", 37.4640, 127.0430),
        p("5", "양재 미술관", "art_museum", 37.4870, 127.0380, "museum").let {
            // 24시간 영업: 닫는 시각이 없는 기간 하나.
            if (details) it.copy(regularOpeningHours = GoogleHours(listOf(GooglePeriod(GoogleDayTime(0, 0, 0))))) else it
        },
        p("8", "양재 꽃집", "florist", 37.4830, 127.0400),
        p("81", "판교 꽃집", null, 37.4040, 127.1060, "florist", "store"),
        p("9", "양재 와인", "liquor_store", 37.4700, 127.0500),
        p("10", "판교 미술관", "museum", 37.4010, 127.1080),
        p("6", "양재 빵집", "bakery", 37.4858, 127.0360).let {
            if (details) it.copy(
                rating = 4.6, userRatingCount = 321,
                // 일요일(0)은 브레이크 타임이 있고, 월요일(1)은 쉰다.
                regularOpeningHours = hours(Triple(0, 900, 1500), Triple(0, 1600, 2130), Triple(2, 800, 2000)),
                reviews = reviews("소금빵이 정말 맛있어요.\n오후에는 품절", "크루아상 추천 | 주차는 불편", "친절해요", "네 번째 리뷰"),
            ) else it
        },
        p("7", "양재 시민의숲", "park", 37.4710, 127.0380),
        p("71", "양재어린이공원", "park", 37.4853, 127.0362),
        p("99", "폐업한 카페", "cafe", 37.4856, 127.0364).copy(businessStatus = "CLOSED_PERMANENTLY"),
    )

    private val typeOfQuery = mapOf(
        "꽃집" to "florist", "마트" to "supermarket", "와인샵" to "liquor_store", "베이커리" to "bakery",
        "맛집" to "restaurant", "카페" to "cafe", "공원" to "park", "미술관" to "museum",
    )

    override suspend fun nearby(types: List<String>, at: GeoPoint, radiusMeters: Int): List<GooglePlace> {
        calls.incrementAndGet()
        synchronized(nearbyTypes) { nearbyTypes += types }
        return places.filter { p -> p.types.any { it in types } && Geo.distanceMeters(at, GeoPoint(p.location!!.latitude, p.location!!.longitude)) <= radiusMeters }
    }

    override suspend fun byText(query: String, near: GeoPoint?, within: Box?, details: Boolean): List<GooglePlace> {
        calls.incrementAndGet()
        val found = if (within != null) {
            val type = typeOfQuery[query]
            places.filter { p ->
                type in p.types && p.location!!.latitude in within.low.latitude..within.high.latitude &&
                    p.location!!.longitude in within.low.longitude..within.high.longitude
            }
        } else {
            places.filter { it.displayName!!.text == query }
        }
        return if (details) found else found.map { it.copy(rating = null, userRatingCount = null, regularOpeningHours = null, reviews = emptyList()) }
    }
}
