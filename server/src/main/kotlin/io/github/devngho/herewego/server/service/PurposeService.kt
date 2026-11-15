package io.github.devngho.herewego.server.service

import io.github.devngho.herewego.server.domain.PurposeSuggestion
import io.github.devngho.herewego.server.domain.hhmm
import io.github.devngho.herewego.server.places.GoogleCategories
import io.github.devngho.herewego.server.llm.Candidate
import io.github.devngho.herewego.server.llm.LlmRanker
import io.github.devngho.herewego.server.llm.PlaceInsights
import io.github.devngho.herewego.server.llm.insightLine
import io.github.devngho.herewego.server.model.PurposeNeed
import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.api.DirectDto
import io.github.devngho.herewego.server.api.NeedDto
import io.github.devngho.herewego.server.api.PurposeDto
import io.github.devngho.herewego.server.api.PurposePlanRequest
import io.github.devngho.herewego.server.api.PurposePlanResponse
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.api.RouteStopDto
import io.github.devngho.herewego.server.api.SuggestionDto
import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.domain.RecommendationEngine
import io.github.devngho.herewego.server.domain.SpeechScripts
import io.github.devngho.herewego.server.places.PlaceRepository
import io.github.devngho.herewego.server.model.Purpose
import io.ktor.http.HttpStatusCode
import kotlin.math.roundToInt

/** Func 2: 목적에 맞춰 가는 길에 들를 곳. */
class PurposeService(private val places: PlaceRepository, private val ranker: LlmRanker? = null, private val insights: PlaceInsights? = null) {

    suspend fun plan(req: PurposePlanRequest): PurposePlanResponse {
        val (purposeDto, needs) = resolvePurpose(req)
        val origin = req.origin.toPoint()
        val destination = req.destination.toPlace("destination")
        val now = parseTime(req.now)

        val useLlm = ranker != null && req.rank != ExploreService.RANK_RULES
        val candidates = places.inBoxes(needs.map { it.category }, RouteSampling.boxes(origin, destination.location), parseDay(req.now))
            .map { insights.fill(it) }
        // 앱이 내비 ETA 로 다시 걸러낼 수 있도록 필요 품목당 화면에 보일 개수(3)의 두 배를 돌려준다.
        val ruled = RecommendationEngine(candidates)
            .forPurpose(needs, origin, destination, req.profile.toProfile(), now, perNeed = PER_NEED)
        val ranked = insights.alongside(ruled.map { s -> s.stops.map { it.place } }, useLlm) { rankWithLlm(purposeDto.label, destination.name, now, ruled) }
        val suggestions = (ranked ?: ruled).map { s -> s.copy(stops = s.stops.map { it.copy(place = insights.fill(it.place)) }) }

        val direct = Geo.distanceMeters(origin, destination.location)
        return PurposePlanResponse(
            purpose = purposeDto,
            direct = DirectDto(direct.roundToInt(), Geo.driveMinutes(direct)),
            suggestions = suggestions.map { s ->
                SuggestionDto(
                    need = NeedDto(s.need.category, s.need.item, s.need.reason, s.need.preferNearDestination),
                    stops = s.stops.map { RouteStopDto(it.place.toDto(), it.detourMeters.roundToInt(), it.detourMinutes, it.progress, it.reasons) },
                )
            },
            speech = SpeechScripts.purpose(purposeDto.label, destination, suggestions, emptyList()),
            rankedBy = if (ranked != null) RankedBy.llm else RankedBy.rules,
        )
    }

    /** 프리셋이면 정해진 표를, 자유 입력이면 LLM 이 만든 '챙길 것'을 쓴다. */
    private suspend fun resolvePurpose(req: PurposePlanRequest): Pair<PurposeDto, List<PurposeNeed>> {
        req.purposeId?.let { id ->
            val preset = Purpose.entries.firstOrNull { it.name == id }
                ?: throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_PURPOSE", "purposeId 가 올바르지 않아요")
            return PurposeDto(preset.name, preset.label) to preset.needs
        }
        val text = req.purposeText?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_PURPOSE", "purposeId 나 purposeText 가 필요해요")
        // 자유 입력은 규칙으로 대체할 수 없다. LLM 이 없거나 답하지 못하면 프리셋을 고르도록 알린다.
        val generated = ranker?.needsFor(text, GoogleCategories.specs.keys.toList())
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "PURPOSE_UNAVAILABLE", "지금은 목적을 이해하지 못했어요. 목록에서 골라 주세요")
        return PurposeDto(FREE_TEXT_ID, generated.label) to generated.needs
    }

    /** 필요 품목마다 규칙 기반 상위 후보 중에서 LLM 이 다시 고른다. 실패하면 null. */
    private suspend fun rankWithLlm(purpose: String, destination: String, now: java.time.LocalTime, ruled: List<PurposeSuggestion>): List<PurposeSuggestion>? {
        ranker ?: return null
        val byId = ruled.flatMap { it.stops }.associateBy { it.place.id }
        val candidates = ruled.flatMapIndexed { i, s ->
            s.stops.map { Candidate(it.place.id, it.place.name, it.place.category, it.reasons.joinToString(", "), setOf("need${i + 1}"), it.place.insightLine()) }
        }.distinctBy { it.key }
        val groups = ruled.mapIndexed { i, s -> "need${i + 1}" to "${s.need.item}: ${s.need.reason}" }.toMap()
        val picks = ranker.pick("지금 ${now.hhmm()}. ${destination}에 ${purpose} 목적으로 가는 길이에요.", groups, candidates, perGroup = 3) ?: return null
        if (picks.values.all { it.isEmpty() }) return null
        return ruled.mapIndexed { i, s ->
            val chosen = picks["need${i + 1}"].orEmpty().mapNotNull { pick ->
                // 첫 이유(우회 시간)는 코드가 계산한 값. 앱이 내비로 다시 잰다.
                byId[pick.key]?.let { it.copy(reasons = listOf(it.reasons.first(), pick.reason)) }
            }
            val ids = chosen.map { it.place.id }.toSet()
            s.copy(stops = (chosen + s.stops.filter { it.place.id !in ids }).take(PER_NEED))
        }
    }

    private companion object {
        const val PER_NEED = 6
        const val FREE_TEXT_ID = "CUSTOM"
    }
}
