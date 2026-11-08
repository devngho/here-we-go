package io.github.devngho.activities.domain

import io.github.devngho.activities.data.model.GeoPoint
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.data.model.PlaceCategory.BAKERY
import io.github.devngho.activities.data.model.PlaceCategory.BOOKSTORE
import io.github.devngho.activities.data.model.PlaceCategory.CAFE
import io.github.devngho.activities.data.model.PlaceCategory.CONVENIENCE
import io.github.devngho.activities.data.model.PlaceCategory.GALLERY
import io.github.devngho.activities.data.model.PlaceCategory.GIFT_SHOP
import io.github.devngho.activities.data.model.PlaceCategory.GYM
import io.github.devngho.activities.data.model.PlaceCategory.OFFICE
import io.github.devngho.activities.data.model.PlaceCategory.PARK
import io.github.devngho.activities.data.model.PlaceCategory.PHARMACY
import io.github.devngho.activities.data.model.PlaceCategory.RESTAURANT
import io.github.devngho.activities.data.model.Purpose
import io.github.devngho.activities.data.model.PurposeNeed
import io.github.devngho.activities.data.model.Schedule
import io.github.devngho.activities.data.model.UserProfile
import java.time.Duration
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

// ---- 결과 모델 ---------------------------------------------------------------------------------

/** Func 1: 현재 장소 근처 추천. */
data class NearbyRecommendation(
    val place: Place,
    val distanceMeters: Double,
    val score: Double,
    val reasons: List<String>,
)

/** Func 2: 목적지까지 가는 길에 들를 곳. */
data class RouteStop(
    val place: Place,
    val detourMeters: Double,
    /** 0 = 출발지 부근, 1 = 목적지 부근 */
    val progress: Double,
    val score: Double,
    val reasons: List<String>,
) {
    val detourMinutes: Int get() = Geo.extraDriveMinutes(detourMeters)
}

data class PurposeSuggestion(val need: PurposeNeed, val stops: List<RouteStop>)

/** Func 3: 빈 시간 활용. */
enum class GapZone(val label: String) { NEAR_A("A 근처"), ON_ROUTE("가는 길"), NEAR_B("B 근처") }

data class GapStop(
    val place: Place,
    val zone: GapZone,
    val arriveAt: LocalTime,
    val stayMinutes: Int,
    val leaveAt: LocalTime,
    val arriveBAt: LocalTime,
    val score: Double,
    val reasons: List<String>,
)

data class GapPlan(
    /** 실제 종료 ~ B 시작 사이 총 시간. */
    val totalGapMinutes: Int,
    /** A → B 이동 시간. */
    val travelMinutes: Int,
    /** 이동·여유 시간을 빼고 순수하게 쓸 수 있는 시간. */
    val freeMinutes: Int,
    /** B 에 제때 도착하려면 이때까지 출발. */
    val leaveBy: LocalTime,
    val stops: Map<GapZone, List<GapStop>>,
)

// ---- 엔진 --------------------------------------------------------------------------------------

/**
 * 규칙 기반 추천 엔진. 점수 = 취향 + 동선 궁합 + 시간대 적합도 + 평점 − 거리 페널티.
 * 프로토타입이므로 가중치는 경험적으로 정했다.
 */
class RecommendationEngine(private val places: List<Place>) {

    private val candidates get() = places.filter { it.category.recommendable }

    // ---------------------------------------------------------------- Func 1

    /** 현재 장소에서 할 수 있는 일(메뉴/액티비티). */
    fun thingsToDo(current: Place, now: LocalTime): List<String> = buildList {
        addAll(current.highlights)
        when (current.category) {
            RESTAURANT -> if (now.hour in 11..13) add("점심 특선 메뉴가 있는지 물어보세요")
            CAFE -> add("디저트와 함께 30분 정도 쉬어 가기")
            PARK -> add("가볍게 스트레칭하고 한 바퀴 걷기")
            GYM -> add("운동 후 단백질 보충 잊지 마세요")
            else -> Unit
        }
    }

    /**
     * 근처에서 이어서 가기 좋은 곳.
     * @param personalWeight 0 이면 동선 궁합 위주, 1 에 가까울수록 사용자 취향 위주.
     */
    fun nearby(
        current: Place?,
        origin: GeoPoint,
        profile: UserProfile,
        now: LocalTime,
        radiusMeters: Double = 1_500.0,
        personalWeight: Double = 0.35,
        limit: Int = 6,
        exclude: Set<String> = emptySet(),
    ): List<NearbyRecommendation> {
        val from = current?.category ?: OFFICE
        return candidates.asSequence()
            .filter { it.category !in ERRANDS }
            .filter { it.id != current?.id && it.id !in exclude && it.isOpenAt(now) }
            .map { it to Geo.distanceMeters(origin, it.location) }
            .filter { (_, d) -> d <= radiusMeters }
            .map { (p, d) ->
                val a = profile.affinity(p.category)
                val c = complement(from, p.category)
                val t = timeFit(p.category, now)
                val r = ratingScore(p.rating)
                val w = personalWeight
                val score = w * a + (0.65 - w) * c + 0.2 * t + 0.15 * r - 0.2 * (d / radiusMeters)
                val reasons = buildList {
                    if (c >= 0.7) add(complementReason(from, p.category))
                    if (a >= 0.6) add("평소 즐겨 찾는 ${p.category.label}")
                    if (t >= 0.9) add(timeReason(p.category))
                    if (p.rating >= 4.6) add("평점 ${p.rating}")
                    p.tags.firstOrNull()?.let { add(it) }
                    add(formatMove(d))
                }
                NearbyRecommendation(p, d, score, reasons)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    // ---------------------------------------------------------------- Func 2

    fun forPurpose(
        purpose: Purpose,
        origin: GeoPoint,
        destination: Place,
        profile: UserProfile,
        now: LocalTime,
        perNeed: Int = 3,
    ): List<PurposeSuggestion> {
        val direct = Geo.distanceMeters(origin, destination.location)
        val maxDetour = max(2_500.0, direct * 0.25)
        return purpose.needs.map { need ->
            val stops = candidates.asSequence()
                .filter { it.category == need.category && it.isOpenAt(now) }
                .map { p ->
                    val detour = Geo.detourMeters(origin, destination.location, p.location)
                    val progress = Geo.progressRatio(origin, destination.location, p.location)
                    Triple(p, detour, progress)
                }
                .filter { (_, detour, _) -> detour <= maxDetour }
                .map { (p, detour, progress) ->
                    val timing = if (need.preferNearDestination) progress else 0.5
                    val score = 0.35 * (1 - detour / maxDetour) +
                        0.25 * ratingScore(p.rating) +
                        0.15 * profile.affinity(p.category) +
                        0.25 * timing
                    val reasons = buildList {
                        add(if (detour < 300) "경로상 바로 들를 수 있어요" else "우회 +${Geo.extraDriveMinutes(detour)}분")
                        when {
                            progress >= 0.75 -> add(if (need.preferNearDestination) "도착 직전에 사서 신선하게" else "목적지 근처")
                            progress <= 0.25 -> add("출발 직후")
                            else -> add("가는 길 중간")
                        }
                        if (p.rating >= 4.6) add("평점 ${p.rating}")
                    }
                    RouteStop(p, detour, progress, score, reasons)
                }
                .sortedByDescending { it.score }
                .take(perNeed)
                .toList()
            PurposeSuggestion(need, stops)
        }
    }

    // ---------------------------------------------------------------- Func 3

    fun forGap(
        a: Schedule,
        b: Schedule,
        actualEnd: LocalTime,
        profile: UserProfile,
        travelMinutesOverride: Int? = null,
        bufferMinutes: Int = 10,
        nearRadiusMeters: Double = 1_200.0,
        perZone: Int = 4,
    ): GapPlan {
        val aLoc = a.place.location
        val bLoc = b.place.location
        val travel = travelMinutesOverride ?: Geo.driveMinutes(Geo.distanceMeters(aLoc, bLoc))
        val totalGap = minutesBetween(actualEnd, b.start)
        val free = totalGap - travel - bufferMinutes
        val leaveBy = b.start.minusMinutes((travel + bufferMinutes).toLong())

        if (free < 15) {
            return GapPlan(totalGap, travel, free, leaveBy, GapZone.entries.associateWith { emptyList() })
        }

        val maxDetour = max(3_000.0, Geo.distanceMeters(aLoc, bLoc) * 0.3)
        val stops = candidates.asSequence()
            .filter { it.id != a.place.id && it.id != b.place.id }
            .mapNotNull { p ->
                val dA = Geo.distanceMeters(aLoc, p.location)
                val dB = Geo.distanceMeters(p.location, bLoc)
                val zone = when {
                    dA <= nearRadiusMeters -> GapZone.NEAR_A
                    dB <= nearRadiusMeters -> GapZone.NEAR_B
                    Geo.detourMeters(aLoc, bLoc, p.location) <= maxDetour -> GapZone.ON_ROUTE
                    else -> return@mapNotNull null
                }
                val toP = if (zone == GapZone.NEAR_A) Geo.moveMinutes(dA) else Geo.driveMinutes(dA)
                // B 근처라면 차는 B 에 대고 걸어가는 것으로 본다.
                val fromP = if (zone == GapZone.NEAR_B) Geo.moveMinutes(dB) else Geo.driveMinutes(dB)
                val available = totalGap - bufferMinutes - toP - fromP
                val stay = min(p.stayMinutes, available)
                if (stay < 15 || stay < p.stayMinutes / 2) return@mapNotNull null

                val arrive = actualEnd.plusMinutes(toP.toLong())
                if (!p.isOpenAt(arrive)) return@mapNotNull null
                val leave = arrive.plusMinutes(stay.toLong())
                val arriveB = leave.plusMinutes(fromP.toLong())
                val spare = minutesBetween(arriveB, b.start)

                val aff = profile.affinity(p.category)
                val fit = stay.toDouble() / p.stayMinutes
                val score = 0.4 * aff + 0.2 * timeFit(p.category, arrive) + 0.2 * ratingScore(p.rating) + 0.2 * fit
                val reasons = buildList {
                    add("${stay}분 머물 수 있어요")
                    if (aff >= 0.6) add("평소 즐겨 찾는 ${p.category.label}")
                    when (zone) {
                        GapZone.NEAR_A -> add("A에서 ${formatMove(dA)}")
                        GapZone.ON_ROUTE -> add("우회 +${Geo.extraDriveMinutes(Geo.detourMeters(aLoc, bLoc, p.location))}분")
                        GapZone.NEAR_B -> add("B까지 ${formatMove(dB)}")
                    }
                    add("B 도착 ${arriveB.hhmm()} (여유 ${spare}분)")
                }
                GapStop(p, zone, arrive, stay, leave, arriveB, score, reasons)
            }
            .toList()
            .groupBy { it.zone }
            .mapValues { (_, list) -> list.sortedByDescending { it.score }.take(perZone) }

        return GapPlan(
            totalGapMinutes = totalGap,
            travelMinutes = travel,
            freeMinutes = free,
            leaveBy = leaveBy,
            stops = GapZone.entries.associateWith { stops[it].orEmpty() },
        )
    }

    // ---------------------------------------------------------------- 휴리스틱 테이블

    private companion object {
        /** '할 거리'가 아닌 '장보기/용무' 성격의 카테고리. Func 1 추천에서는 제외하고 Func 2 에서 쓴다. */
        val ERRANDS = setOf(
            PlaceCategory.MART, CONVENIENCE, PHARMACY, PlaceCategory.EV_CHARGING,
            PlaceCategory.FLORIST, PlaceCategory.WINE_SHOP,
        )
    }

    private fun ratingScore(rating: Double) = ((rating - 3.5) / 1.5).coerceIn(0.0, 1.0)

    /** from 다음에 to 를 가는 게 얼마나 자연스러운가 (0~1). */
    private fun complement(from: PlaceCategory, to: PlaceCategory): Double = when (from) {
        RESTAURANT -> mapOf(CAFE to 1.0, BAKERY to 0.8, PARK to 0.75, BOOKSTORE to 0.5, GALLERY to 0.5, GIFT_SHOP to 0.4)
        CAFE -> mapOf(BOOKSTORE to 0.85, PARK to 0.8, GALLERY to 0.75, GIFT_SHOP to 0.5, BAKERY to 0.4)
        BAKERY -> mapOf(CAFE to 0.9, PARK to 0.75)
        PARK -> mapOf(CAFE to 0.9, RESTAURANT to 0.75, CONVENIENCE to 0.5)
        GYM -> mapOf(RESTAURANT to 0.85, CAFE to 0.6, PHARMACY to 0.3)
        BOOKSTORE -> mapOf(CAFE to 1.0, GALLERY to 0.6)
        GALLERY -> mapOf(CAFE to 0.9, GIFT_SHOP to 0.7, RESTAURANT to 0.6)
        OFFICE -> mapOf(CAFE to 0.8, RESTAURANT to 0.8, GYM to 0.6, PARK to 0.6)
        else -> emptyMap()
    }[to] ?: 0.2

    private fun complementReason(from: PlaceCategory, to: PlaceCategory): String = when (to) {
        CAFE -> if (from == RESTAURANT) "식사 후 커피 한 잔" else "잠깐 쉬어 가기 좋은 카페"
        BAKERY -> "디저트로 달콤하게 마무리"
        PARK -> if (from == RESTAURANT) "소화도 시킬 겸 가벼운 산책" else "바람 쐬며 산책"
        BOOKSTORE -> "여유롭게 책 구경"
        GALLERY -> "가볍게 둘러볼 전시"
        RESTAURANT -> if (from == GYM) "운동 후 든든한 한 끼" else "출출할 때 식사"
        GIFT_SHOP -> "구경하는 재미가 있는 소품샵"
        GYM -> "남는 시간에 운동 한 세트"
        else -> "${to.label} 들르기 좋아요"
    }

    private fun timeFit(category: PlaceCategory, now: LocalTime): Double {
        val h = now.hour
        return when (category) {
            CAFE -> when (h) { in 13..17 -> 1.0; in 9..12 -> 0.7; in 18..21 -> 0.6; else -> 0.3 }
            RESTAURANT -> if (h in 11..13 || h in 17..20) 1.0 else 0.3
            BAKERY -> if (h in 8..11 || h in 14..17) 0.9 else 0.5
            PARK -> when (h) { in 7..18 -> 0.9; in 19..21 -> 0.6; else -> 0.2 }
            GYM -> if (h in 6..8 || h in 18..22) 1.0 else 0.5
            GALLERY, BOOKSTORE -> if (h in 11..19) 0.8 else 0.3
            else -> 0.5
        }
    }

    private fun timeReason(category: PlaceCategory): String = when (category) {
        GYM -> "운동하기 좋은 시간대"
        RESTAURANT -> "지금이 식사 시간"
        CAFE -> "오후 커피 타임"
        PARK -> "산책하기 좋은 시간"
        else -> "지금 시간대에 잘 맞아요"
    }
}

fun minutesBetween(from: LocalTime, to: LocalTime): Int = Duration.between(from, to).toMinutes().toInt()

fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)
