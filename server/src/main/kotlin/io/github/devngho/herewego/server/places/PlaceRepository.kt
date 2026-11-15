package io.github.devngho.herewego.server.places

import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.model.Place
import io.github.devngho.herewego.server.model.PlaceCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.DayOfWeek
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Google Places 결과를 앱의 [Place] 로 바꿔 돌려준다. 평점, 요일별 영업시간, 리뷰 발췌가 검색 결과에 함께 온다.
 * 검색 호출마다 요금이 붙으므로 같은 동네의 요청은 약 100m 격자 단위로 잠깐 캐시한다.
 * 영업시간은 요일마다 달라서 캐시에는 주간 시간표를 두고, 돌려줄 때 [DayOfWeek] 에 맞춘다.
 */
class PlaceRepository(
    private val search: PlaceSearch,
    private val ttl: Duration = 10.minutes,
    private val clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private class Entry(val places: List<Place>, val at: kotlin.time.ComparableTimeMark)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** [at] 주변에서 [categories] 에 해당하는 장소. 묶음([CategorySpec.group])마다 한 번씩, 동시에 검색한다. */
    suspend fun nearby(categories: Collection<PlaceCategory>, at: GeoPoint, radiusMeters: Int, day: DayOfWeek): List<Place> = coroutineScope {
        val wanted = categories.toSet()
        wanted.mapNotNull { GoogleCategories.specs[it]?.group }.distinct()
            .map { group -> async { forGroup(group, at, radiusMeters) } }
            .awaitAll().flatten()
            .filter { it.category in wanted }
            .distinctBy { it.id }
            .map { it.on(day) }
    }

    /** 여러 원을 한 번에 찾는다 (일정 장소 두 곳과 그 사이). */
    suspend fun nearbyAll(categories: Collection<PlaceCategory>, circles: List<Pair<GeoPoint, Int>>, day: DayOfWeek): List<Place> = coroutineScope {
        circles.map { (at, radius) -> async { nearby(categories, at, radius, day) } }.awaitAll().flatten().distinctBy { it.id }
    }

    /** 경로를 덮는 사각형들 안에서 카테고리마다 검색어로 찾는다. 호출 수는 카테고리 × 사각형. */
    suspend fun inBoxes(categories: Collection<PlaceCategory>, boxes: List<Box>, day: DayOfWeek): List<Place> = coroutineScope {
        categories.distinct().flatMap { category -> boxes.map { box -> async { forQuery(category, box) } } }
            .awaitAll().flatten().distinctBy { it.id }.map { it.on(day) }
    }

    /** 이름으로 [at] 근처의 장소 하나를 찾는다 (도착한 장소 확인용). */
    suspend fun findByName(name: String, at: GeoPoint, day: DayOfWeek, maxMeters: Int = 500): Place? =
        search.byText(name, near = at)
            .mapNotNull { GoogleCategories.toPlace(it, fallback = PlaceCategory.LANDMARK) }
            .map { it to Geo.distanceMeters(at, it.location) }
            .filter { (_, d) -> d <= maxMeters }
            .minByOrNull { (_, d) -> d }?.first?.on(day)

    /** 목적지·일정 장소를 고르기 위한 이름 검색. 분류할 수 없는 곳(집, 회사, 병원 …)도 LANDMARK 로 돌려준다. 평점·영업시간은 받지 않는다. */
    suspend fun searchByName(query: String, near: GeoPoint): List<Place> =
        search.byText(query, near = near, details = false).mapNotNull { GoogleCategories.toPlace(it, fallback = PlaceCategory.LANDMARK) }

    private suspend fun forGroup(group: String, at: GeoPoint, radius: Int): List<Place> {
        // 캐시가 잘 맞도록 요청한 카테고리와 관계없이 묶음 전체의 종류로 찾는다.
        val types = GoogleCategories.specs.values.filter { it.group == group }.flatMap { it.types }
        return cached("near|$group|${grid(at)}|$radius") { search.nearby(types, at, radius).mapNotNull { GoogleCategories.toPlace(it) } }
    }

    private suspend fun forQuery(category: PlaceCategory, box: Box): List<Place> {
        val spec = GoogleCategories.specs[category] ?: return emptyList()
        return cached("box|$category|${grid(box.low)}|${grid(box.high)}") {
            // 검색어와 실제 분류가 다른 결과('꽃집'에 걸린 카페 등)는 버린다.
            search.byText(spec.query, within = box).mapNotNull { GoogleCategories.toPlace(it) }.filter { it.category == category }
        }
    }

    private fun grid(p: GeoPoint) = "${(p.latitude * 1000).roundToInt()},${(p.longitude * 1000).roundToInt()}"

    private suspend fun cached(key: String, load: suspend () -> List<Place>): List<Place> {
        cache[key]?.takeIf { it.at.elapsedNow() < ttl }?.let { return it.places }
        val places = load()
        cache[key] = Entry(places, clock.markNow())
        if (cache.size > 2_000) cache.entries.removeIf { it.value.at.elapsedNow() >= ttl }
        return places
    }
}
