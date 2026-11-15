package io.github.devngho.herewego.server.service

import io.github.devngho.herewego.server.domain.hhmm
import io.github.devngho.herewego.server.llm.Candidate
import io.github.devngho.herewego.server.llm.LlmRanker
import io.github.devngho.herewego.server.llm.PlaceInsights
import io.github.devngho.herewego.server.llm.insightLine
import java.time.DayOfWeek
import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.api.DestinationDto
import io.github.devngho.herewego.server.api.ExploreRequest
import io.github.devngho.herewego.server.api.ExploreResponse
import io.github.devngho.herewego.server.api.LatLng
import io.github.devngho.herewego.server.api.NearbyDto
import io.github.devngho.herewego.server.api.PlaceDto
import io.github.devngho.herewego.server.api.ProfileDto
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.domain.NearbyRecommendation
import io.github.devngho.herewego.server.domain.RecommendationEngine
import io.github.devngho.herewego.server.domain.SpeechScripts
import io.github.devngho.herewego.server.places.PlaceRepository
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.model.Place
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.model.UserProfile
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/** Func 1: 지금 여기. */
class ExploreService(private val places: PlaceRepository, private val ranker: LlmRanker? = null, private val insights: PlaceInsights? = null) {

    suspend fun explore(req: ExploreRequest): ExploreResponse = coroutineScope {
        val origin = req.location.toPoint()
        val now = parseTime(req.now)
        val profile = req.profile.toProfile()

        val day = parseDay(req.now)
        val found = async { places.nearby(EXPLORE_CATEGORIES, origin, SEARCH_RADIUS_METERS, day).map { insights.fill(it) } }
        // 좌표만으로는 어느 가게에 있는지 알 수 없다. 앱이 도착한 장소를 알려 줄 때만 '지금 있는 곳'을 정한다.
        val hereFound = req.arrivedPlace?.let { arrived ->
            places.findByName(arrived.name, origin, day)
                ?: Place(
                    id = "arrived:${arrived.name}", name = arrived.name,
                    category = arrived.category ?: PlaceCategory.LANDMARK, location = origin,
                    address = "", rating = null, stayMinutes = 0, hoursEstimated = true,
                )
        }
        val candidates = found.await()

        val engine = RecommendationEngine(candidates)
        // 앱이 내비 ETA 로 다시 걸러낼 수 있도록 화면에 보일 개수(4)의 두 배를 돌려준다.
        val next = engine.nearby(
            hereFound, origin, profile, now,
            personalWeight = 0.15, limit = RESULT_LIMIT, maxPerCategory = MAX_PER_CATEGORY,
        )
        // 방금 카페에 왔는데 취향이라는 이유로 또 카페를 권하지 않는다.
        val sameAsHere = candidates.filter { it.category == hereFound?.category }.map { it.id }.toSet()
        val forYou = engine.nearby(
            hereFound, origin, profile, now,
            radiusMeters = SEARCH_RADIUS_METERS.toDouble(),
            personalWeight = 0.55,
            limit = RESULT_LIMIT,
            exclude = next.map { it.place.id }.toSet() + sameAsHere,
            maxPerCategory = MAX_PER_CATEGORY,
        )
        // 역지오코딩을 따로 부르지 않고 가장 가까운 후보의 주소에서 동네 이름을 얻는다.
        val addr = hereFound?.address?.ifBlank { null }
            ?: candidates.minByOrNull { Geo.distanceMeters(origin, it.location) }?.address?.split(" ")?.take(3)?.joinToString(" ").orEmpty()

        val useLlm = ranker != null && req.rank != RANK_RULES
        // LLM 에 보여 줄 후보는 화면에 나갈 것보다 넉넉하게 뽑는다.
        val nextPool = if (useLlm) engine.nearby(hereFound, origin, profile, now, personalWeight = 0.15, limit = POOL, maxPerCategory = POOL_PER_CATEGORY) else next
        val youPool = if (useLlm) engine.nearby(
            hereFound, origin, profile, now, radiusMeters = SEARCH_RADIUS_METERS.toDouble(), personalWeight = 0.55,
            limit = POOL, exclude = sameAsHere, maxPerCategory = POOL_PER_CATEGORY,
        ) else forYou
        val shortlist = listOf(listOfNotNull(hereFound), nextPool.map { it.place }, youPool.map { it.place })
        val ranked = insights.alongside(shortlist, useLlm) { rankWithLlm(nextPool, youPool, hereFound, profile, now, addr) }

        val (rankedNext, rankedForYou) = ranked?.let { (n, f) ->
            // LLM 이 고른 곳을 앞에 두고, 앱이 걸러낼 여유분은 규칙 순서로 채운다.
            val chosen = (n + f).map { it.place.id }.toSet()
            val nextFilled = (n + next.filter { it.place.id !in chosen }).take(RESULT_LIMIT)
            val used = chosen + nextFilled.map { it.place.id }
            nextFilled to (f + forYou.filter { it.place.id !in used }).take(RESULT_LIMIT)
        } ?: (next to forYou)
        // 방금 끝난 리뷰 요약(대표 메뉴 등)을 응답에 채운다.
        val here = hereFound?.let { insights.fill(it) }
        val finalNext = rankedNext.map { it.copy(place = insights.fill(it.place)) }
        val finalForYou = rankedForYou.map { it.copy(place = insights.fill(it.place)) }

        ExploreResponse(
            here = here?.toDto(),
            address = addr,
            thingsToDo = here?.let { engine.thingsToDo(it, now) }.orEmpty().take(4),
            nextCourse = finalNext.map { it.toDto() },
            forYou = finalForYou.map { it.toDto() },
            speech = SpeechScripts.explore(here, addr, finalNext, finalForYou),
            rankedBy = if (ranked != null) RankedBy.llm else RankedBy.rules,
        )
    }

    /** 두 목록의 규칙 기반 상위 후보를 LLM 에 보여 주고 다시 고르게 한다. 실패하면 null. */
    private suspend fun rankWithLlm(
        nextPool: List<NearbyRecommendation>, youPool: List<NearbyRecommendation>, here: Place?, profile: UserProfile, now: LocalTime, address: String,
    ): Pair<List<NearbyRecommendation>, List<NearbyRecommendation>>? {
        ranker ?: return null
        val byId = (nextPool + youPool).associateBy { it.place.id }
        val candidates = byId.values.map { rec ->
            Candidate(
                key = rec.place.id, name = rec.place.name, category = rec.place.category, facts = rec.reasons.last(),
                groups = buildSet {
                    if (nextPool.any { it.place.id == rec.place.id }) add(GROUP_NEXT)
                    if (youPool.any { it.place.id == rec.place.id }) add(GROUP_FOR_YOU)
                },
                insight = rec.place.insightLine(),
            )
        }
        val situation = buildString {
            append("지금 ${now.hhmm()}. ")
            append(here?.let { "${it.name}(${it.category.label})에 있어요. " } ?: "${address.ifBlank { "알 수 없는 곳" }} 근처에 있어요. ")
            if (profile.interests.isNotEmpty()) append("평소 관심사: ${profile.interests.joinToString(", ") { it.label }}.")
        }
        val picks = ranker.pick(
            situation,
            mapOf(GROUP_NEXT to "지금 있는 곳과 시간대에 이어서 가기 좋은 다음 코스", GROUP_FOR_YOU to "이 사람의 관심사에 맞는 곳"),
            candidates, perGroup = 4,
        ) ?: return null
        fun resolve(group: String) = picks[group].orEmpty().mapNotNull { pick ->
            // 이유는 LLM 이 쓴 한 구절 + 코드가 계산한 이동 시간.
            byId[pick.key]?.let { it.copy(reasons = listOf(pick.reason, it.reasons.last())) }
        }
        val next = resolve(GROUP_NEXT)
        val forYou = resolve(GROUP_FOR_YOU)
        return if (next.isEmpty() && forYou.isEmpty()) null else next to forYou
    }

    companion object {
        /** 인기순 20건씩 받으므로 반경이 넓으면 먼 곳만 걸린다. '다음 코스'로 치는 거리(1.5km)보다 조금만 넓게 잡는다. */
        const val SEARCH_RADIUS_METERS = 2_000
        const val RESULT_LIMIT = 8
        const val MAX_PER_CATEGORY = 2
        const val RANK_RULES = "rules"

        /** LLM 에 보여 줄 목록별 후보 수. */
        private const val POOL = 12
        private const val POOL_PER_CATEGORY = 3
        private const val GROUP_NEXT = "nextCourse"
        private const val GROUP_FOR_YOU = "forYou"

        /** '할 거리' 카테고리. 장보기·용무 성격(마트, 약국 …)은 Func 2 에서 쓴다. */
        val EXPLORE_CATEGORIES = listOf(
            PlaceCategory.RESTAURANT, PlaceCategory.CAFE, PlaceCategory.BAKERY, PlaceCategory.PARK,
            PlaceCategory.GYM, PlaceCategory.BOOKSTORE, PlaceCategory.GALLERY, PlaceCategory.GIFT_SHOP,
        )
    }
}

fun LatLng.toPoint(): GeoPoint {
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
        throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "좌표가 올바르지 않아요")
    }
    return GeoPoint(lat, lng)
}

/** 캐시된 리뷰 요약(대표 메뉴 등)을 채운다. 요약이 꺼져 있으면 그대로. */
fun PlaceInsights?.fill(place: Place): Place = this?.apply(place) ?: place

/**
 * 순위를 매기는 LLM 호출([rank])과 [shortlist] 의 리뷰 요약을 동시에 돌린다.
 * LLM 을 쓰지 않는 요청은 요약을 시작만 해 두고 null 을 돌려준다.
 */
suspend fun <T> PlaceInsights?.alongside(shortlist: List<List<Place>>, useLlm: Boolean, rank: suspend () -> T?): T? = coroutineScope {
    if (!useLlm) {
        this@alongside?.digest(shortlist, wait = false)
        return@coroutineScope null
    }
    val digest = async { this@alongside?.digest(shortlist, wait = true) }
    rank().also { digest.await() }
}

/** 영업시간은 요일마다 다르다. */
fun parseDay(iso: String): DayOfWeek {
    parseTime(iso)
    return OffsetDateTime.parse(iso).dayOfWeek
}

/** ISO-8601 오프셋 시각에서 현지 시:분을 꺼낸다. */
fun parseTime(iso: String): LocalTime = try {
    OffsetDateTime.parse(iso).toLocalTime()
} catch (e: DateTimeParseException) {
    throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "시각은 2026-11-15T15:00:00+09:00 형식이어야 해요")
}

fun ProfileDto?.toProfile(): UserProfile =
    UserProfile(interests = this?.interests.orEmpty(), visitCounts = this?.visitCounts.orEmpty())

fun Place.toDto() = PlaceDto(
    id = id, name = name, category = category,
    location = LatLng(location.latitude, location.longitude),
    address = address, stayMinutes = stayMinutes, hoursEstimated = hoursEstimated,
    placeUrl = placeUrl.ifBlank { null },
    openTime = openTime.hhmm().takeUnless { closedToday }, closeTime = closeTime.hhmm().takeUnless { closedToday },
    closedToday = closedToday, rating = rating, ratingCount = ratingCount, highlights = highlights,
    reviewSummary = reviewSummary.ifBlank { null }, mapsUrl = mapsUrl.ifBlank { null },
)

fun DestinationDto.toPlace(id: String) = Place(
    id = id, name = name, category = PlaceCategory.LANDMARK, location = LatLng(lat, lng).toPoint(),
    address = "", rating = null, stayMinutes = 0,
)

fun NearbyRecommendation.toDto() = NearbyDto(
    place = place.toDto(),
    distanceMeters = distanceMeters.roundToInt(),
    estimatedMinutes = Geo.moveMinutes(distanceMeters),
    walkable = distanceMeters <= Geo.WALKABLE_METERS,
    reasons = reasons,
)
