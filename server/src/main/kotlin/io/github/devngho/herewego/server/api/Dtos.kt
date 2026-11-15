package io.github.devngho.herewego.server.api

import io.github.devngho.herewego.server.domain.GapZone
import io.github.devngho.herewego.server.model.PlaceCategory
import kotlinx.serialization.Serializable

/*
 * /v1 API 계약. 앱 저장소에도 같은 모양의 DTO 가 있으므로 여기를 바꾸면 앱도 함께 바꿔야 한다.
 * 시각은 ISO-8601 오프셋 문자열("2026-11-15T15:00:00+09:00"), 시:분은 "HH:mm".
 */

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class PlaceDto(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val location: LatLng,
    val address: String,
    val stayMinutes: Int,
    /** 영업시간이 실제 값이 아니라 카테고리 기본값이면 true. */
    val hoursEstimated: Boolean = true,
    val placeUrl: String? = null,
    /** 요청한 날의 영업시간 "HH:mm". [hoursEstimated] 가 true 면 카테고리 기본값. 24시간 영업은 둘 다 "00:00". */
    val openTime: String? = null,
    val closeTime: String? = null,
    /** 요청한 날이 휴무일이면 true. 추천 목록에는 나오지 않고 `here` 에만 나올 수 있다. */
    val closedToday: Boolean = false,
    /** Google 지도 평점(1.0~5.0)과 평가 수. 확인하지 못했으면 생략. */
    val rating: Double? = null,
    val ratingCount: Int? = null,
    /** 대표 메뉴 또는 즐길 거리, 최대 4개. */
    val highlights: List<String> = emptyList(),
    /** 리뷰 경향 한 문장. */
    val reviewSummary: String? = null,
    /**
     * rating, 실제 영업시간, highlights, reviewSummary 의 출처인 Google 지도 링크.
     * 이 값들을 화면에 보여 줄 때는 "Google Maps" 표기와 함께 이 링크를 보여 줘야 한다.
     */
    val mapsUrl: String? = null,
)

/** 최종 순위를 누가 정했는지. LLM 이 실패하면 규칙 기반 결과를 돌려준다. */
@Serializable
enum class RankedBy { llm, rules }

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

// ---- Func 1: 지금 여기 --------------------------------------------------------------------------

@Serializable
data class ArrivedPlace(val name: String, val category: PlaceCategory? = null)

@Serializable
data class ExploreRequest(
    val location: LatLng,
    val now: String,
    /** 이 앱의 안내로 도착한 장소 (onDestinationArrived). */
    val arrivedPlace: ArrivedPlace? = null,
    /** 서버에 저장된 프로필 대신 쓸 값. 프로필 저장(M4) 전까지는 앱이 항상 보낸다. */
    val profile: ProfileDto? = null,
    /** "rules" 이면 LLM 을 거치지 않고 규칙 기반 순위를 바로 돌려준다. 앱은 이것을 먼저 보여 주고 LLM 결과로 바꾼다. */
    val rank: String? = null,
)

@Serializable
data class NearbyDto(
    val place: PlaceDto,
    val distanceMeters: Int,
    /** 직선거리 기반 추정. 앱이 내비 ETA 로 보정한다. */
    val estimatedMinutes: Int,
    val walkable: Boolean,
    val reasons: List<String>,
)

@Serializable
data class ExploreResponse(
    /** 앱이 도착한 장소를 알려 준 경우에만 있다. null 이면 응답에서 생략된다. */
    val here: PlaceDto? = null,
    val address: String,
    val thingsToDo: List<String>,
    val nextCourse: List<NearbyDto>,
    val forYou: List<NearbyDto>,
    val speech: String,
    val rankedBy: RankedBy,
)

// ---- Func 2: 가는 길에 --------------------------------------------------------------------------

@Serializable
data class PurposeDto(val id: String, val label: String)

@Serializable
data class DestinationDto(val name: String, val lat: Double, val lng: Double)

@Serializable
data class PurposePlanRequest(
    val origin: LatLng,
    val destination: DestinationDto,
    /** 프리셋 id. [purposeText] 와 둘 중 하나는 있어야 한다. */
    val purposeId: String? = null,
    /** 자유 입력 ("친구 결혼식 가는 길"). */
    val purposeText: String? = null,
    val now: String,
    val profile: ProfileDto? = null,
    /** "rules" 이면 LLM 을 거치지 않고 규칙 기반 순위를 바로 돌려준다. 앱은 이것을 먼저 보여 주고 LLM 결과로 바꾼다. */
    val rank: String? = null,
)

@Serializable
data class NeedDto(
    val category: PlaceCategory,
    val item: String,
    val reason: String,
    val preferNearDestination: Boolean = false,
)

@Serializable
data class RouteStopDto(
    val place: PlaceDto,
    val detourMeters: Int,
    val detourMinutes: Int,
    /** 0 = 출발지, 1 = 목적지. */
    val progress: Double,
    val reasons: List<String>,
)

@Serializable
data class SuggestionDto(val need: NeedDto, val stops: List<RouteStopDto>)

@Serializable
data class DirectDto(val meters: Int, val minutes: Int)

@Serializable
data class PurposePlanResponse(
    val purpose: PurposeDto,
    val direct: DirectDto,
    val suggestions: List<SuggestionDto>,
    val speech: String,
    val rankedBy: RankedBy,
)

// ---- Func 3: 빈 시간 ----------------------------------------------------------------------------

@Serializable
data class GapPlanRequest(
    /** 앞선 일정. 일정 저장(M4) 전까지는 요청에 그대로 담아 보낸다. */
    val a: ScheduleDto,
    /** 다음 일정. */
    val b: ScheduleDto,
    /** A 가 실제로 끝난 시각. */
    val actualEnd: String,
    /** 내비 requestTrip 으로 받은 A → B 이동 시간(분). */
    val travelMinutesOverride: Int? = null,
    val profile: ProfileDto? = null,
    /** "rules" 이면 LLM 을 거치지 않고 규칙 기반 순위를 바로 돌려준다. 앱은 이것을 먼저 보여 주고 LLM 결과로 바꾼다. */
    val rank: String? = null,
)

@Serializable
data class GapStopDto(
    val place: PlaceDto,
    val zone: GapZone,
    val arriveAt: String,
    val stayMinutes: Int,
    val leaveAt: String,
    val arriveBAt: String,
    val reasons: List<String>,
)

@Serializable
data class GapPlanResponse(
    val totalGapMinutes: Int,
    val travelMinutes: Int,
    val freeMinutes: Int,
    val leaveBy: String,
    val stops: Map<GapZone, List<GapStopDto>>,
    val speech: String,
    val rankedBy: RankedBy,
)

// ---- 프로필 · 일정 ------------------------------------------------------------------------------

@Serializable
data class ProfileDto(
    val interests: Set<PlaceCategory> = emptySet(),
    val visitCounts: Map<PlaceCategory, Int> = emptyMap(),
    /** 내비 최근 목적지에서 학습한 장소. 같은 곳을 두 번 세지 않기 위해 이름을 남긴다. */
    val learnedPlaces: List<LearnedPlaceDto> = emptyList(),
)

@Serializable
data class LearnedPlaceDto(val name: String, val category: PlaceCategory)

@Serializable
data class VisitsResponse(val learned: Int, val profile: ProfileDto)

@Serializable
data class VisitDto(val name: String, val address: String = "", val lat: Double? = null, val lng: Double? = null)

@Serializable
data class VisitsRequest(val visits: List<VisitDto>)

@Serializable
data class ScheduleDto(
    /** 생성 요청에서는 null. */
    val id: String? = null,
    val title: String,
    val place: DestinationDto,
    val start: String,
    val expectedEnd: String,
)
