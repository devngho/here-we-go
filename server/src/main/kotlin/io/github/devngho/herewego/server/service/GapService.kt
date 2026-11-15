package io.github.devngho.herewego.server.service

import io.github.devngho.herewego.server.domain.GapPlan
import io.github.devngho.herewego.server.domain.GapZone
import io.github.devngho.herewego.server.llm.Candidate
import io.github.devngho.herewego.server.llm.LlmRanker
import io.github.devngho.herewego.server.llm.PlaceInsights
import io.github.devngho.herewego.server.llm.insightLine
import io.github.devngho.herewego.server.model.UserProfile
import java.time.LocalTime
import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.api.GapPlanRequest
import io.github.devngho.herewego.server.api.GapPlanResponse
import io.github.devngho.herewego.server.api.GapStopDto
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.api.ScheduleDto
import io.github.devngho.herewego.server.domain.RecommendationEngine
import io.github.devngho.herewego.server.domain.SpeechScripts
import io.github.devngho.herewego.server.domain.hhmm
import io.github.devngho.herewego.server.places.PlaceRepository
import io.github.devngho.herewego.server.model.Schedule
import io.ktor.http.HttpStatusCode

/** Func 3: 앞선 일정이 일찍 끝나 생긴 빈 시간에 들를 곳. */
class GapService(private val places: PlaceRepository, private val ranker: LlmRanker? = null, private val insights: PlaceInsights? = null) {

    suspend fun plan(req: GapPlanRequest): GapPlanResponse {
        val a = req.a.toSchedule("schedule-a")
        val b = req.b.toSchedule("schedule-b")
        val actualEnd = parseTime(req.actualEnd)
        if (req.travelMinutesOverride != null && req.travelMinutesOverride < 0) {
            throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "travelMinutesOverride 는 0 이상이어야 해요")
        }

        val circles = RouteSampling.circles(a.place.location, b.place.location, NEAR_METERS)
        val candidates = places.nearbyAll(GAP_CATEGORIES, circles, parseDay(req.actualEnd))
            // 일정 장소 자체(같은 이름의 가게)는 들를 곳으로 권하지 않는다.
            .filter { it.name != a.place.name && it.name != b.place.name }
            .map { insights.fill(it) }

        val ruled = RecommendationEngine(candidates).forGap(
            a, b, actualEnd, req.profile.toProfile(),
            travelMinutesOverride = req.travelMinutesOverride,
            nearRadiusMeters = NEAR_METERS.toDouble(),
            perZone = PER_ZONE,
            maxPerCategory = ExploreService.MAX_PER_CATEGORY,
        )
        val useLlm = ranker != null && req.rank != ExploreService.RANK_RULES
        val ranked = insights.alongside(ruled.stops.values.map { stops -> stops.map { it.place } }, useLlm) {
            rankWithLlm(a, b, actualEnd, req.profile.toProfile(), ruled)
        }
        val plan = (ranked ?: ruled).let { p -> p.copy(stops = p.stops.mapValues { (_, stops) -> stops.map { it.copy(place = insights.fill(it.place)) } }) }
        return GapPlanResponse(
            totalGapMinutes = plan.totalGapMinutes,
            travelMinutes = plan.travelMinutes,
            freeMinutes = plan.freeMinutes,
            leaveBy = plan.leaveBy.hhmm(),
            stops = plan.stops.mapValues { (_, stops) ->
                stops.map {
                    GapStopDto(it.place.toDto(), it.zone, it.arriveAt.hhmm(), it.stayMinutes, it.leaveAt.hhmm(), it.arriveBAt.hhmm(), it.reasons)
                }
            },
            speech = SpeechScripts.gap(a, b, plan),
            rankedBy = if (ranked != null) RankedBy.llm else RankedBy.rules,
        )
    }

    /** 구역마다 시간이 맞는 후보 중에서 LLM 이 다시 고른다. 도착·출발 시각은 엔진이 계산한 값을 그대로 둔다. */
    private suspend fun rankWithLlm(a: Schedule, b: Schedule, actualEnd: LocalTime, profile: UserProfile, ruled: GapPlan): GapPlan? {
        ranker ?: return null
        val all = ruled.stops.values.flatten()
        if (all.isEmpty()) return null
        val byId = all.associateBy { it.place.id }
        val candidates = all.map { Candidate(it.place.id, it.place.name, it.place.category, it.reasons.joinToString(", "), setOf(it.zone.name), it.place.insightLine()) }
        val groups = mapOf(
            GapZone.NEAR_A.name to "지금 있는 ${a.place.name} 근처에서 시간을 보낼 곳",
            GapZone.ON_ROUTE.name to "${b.place.name}(으)로 가는 길에 들를 곳",
            GapZone.NEAR_B.name to "${b.place.name} 근처에 먼저 가서 시간을 보낼 곳",
        ).filterKeys { key -> ruled.stops[GapZone.valueOf(key)].orEmpty().isNotEmpty() }
        val situation = buildString {
            append("${a.title} 일정이 ${actualEnd.hhmm()}에 일찍 끝났고, 다음 일정 ${b.title}은(는) ${b.start.hhmm()}에 시작해요. ")
            append("이동 시간을 빼면 ${ruled.freeMinutes}분이 비어요. ")
            if (profile.interests.isNotEmpty()) append("평소 관심사: ${profile.interests.joinToString(", ") { it.label }}.")
        }
        val picks = ranker.pick(situation, groups, candidates, perGroup = 3) ?: return null
        if (picks.values.all { it.isEmpty() }) return null
        return ruled.copy(stops = ruled.stops.mapValues { (zone, stops) ->
            val chosen = picks[zone.name].orEmpty().mapNotNull { pick ->
                // 머무는 시간 + LLM 의 한 구절 + 이동·도착 시각. 숫자는 모두 엔진이 계산한 값이다.
                byId[pick.key]?.let { it.copy(reasons = listOf(it.reasons.first(), pick.reason) + it.reasons.takeLast(2)) }
            }
            val ids = chosen.map { it.place.id }.toSet()
            (chosen + stops.filter { it.place.id !in ids }).take(PER_ZONE)
        })
    }

    private fun ScheduleDto.toSchedule(id: String) = Schedule(title, place.toPlace(id), parseTime(start), parseTime(expectedEnd))

    private companion object {
        /** 'A 근처'·'B 근처'로 치는 거리이자 그 주변을 찾는 반경. */
        const val NEAR_METERS = 1_200

        /** 헬스장은 검색 호출을 하나 더 쓰는데 일정 사이에 들르기에는 맞지 않아 뺀다. */
        val GAP_CATEGORIES = ExploreService.EXPLORE_CATEGORIES - io.github.devngho.herewego.server.model.PlaceCategory.GYM

        /** 구역당 화면에 보일 개수(3)의 두 배. */
        const val PER_ZONE = 6
    }
}
