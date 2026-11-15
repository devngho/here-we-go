package io.github.devngho.herewego.remote

import io.github.devngho.herewego.data.model.PlaceCategory
import kotlinx.serialization.Serializable

/*
 * herewego-server 의 /v1 API 와 주고받는 모양. server/src/main/kotlin/.../server/api/Dtos.kt 와 맞춰야 한다.
 * 시각은 ISO-8601 오프셋 문자열.
 */

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class PlaceDto(
    val id: String,
    val name: String,
    /** 서버가 새 카테고리를 추가해도 깨지지 않도록 문자열로 받는다. */
    val category: String,
    val location: LatLng,
    val address: String = "",
    val stayMinutes: Int = 0,
    val hoursEstimated: Boolean = true,
    val placeUrl: String? = null,
    /** 요청한 날의 영업시간 "HH:mm". [hoursEstimated] 가 true 면 카테고리 기본값. */
    val openTime: String? = null,
    val closeTime: String? = null,
    val closedToday: Boolean = false,
    /** 아래 값들은 서버가 Google 지도에서 확인한 것이다. 보여 줄 때는 [mapsUrl] 로 출처를 표시한다. */
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val highlights: List<String> = emptyList(),
    val reviewSummary: String? = null,
    val mapsUrl: String? = null,
)

@Serializable
data class ArrivedPlace(val name: String, val category: PlaceCategory? = null)

@Serializable
data class ProfileDto(
    val interests: Set<PlaceCategory> = emptySet(),
    val visitCounts: Map<PlaceCategory, Int> = emptyMap(),
    val learnedPlaces: List<LearnedPlaceDto> = emptyList(),
)

@Serializable
data class LearnedPlaceDto(val name: String, val category: PlaceCategory)

@Serializable
data class ExploreRequest(
    val location: LatLng,
    val now: String,
    val arrivedPlace: ArrivedPlace? = null,
    val profile: ProfileDto? = null,    /** "rules" 이면 LLM 을 거치지 않은 빠른 결과. */
    val rank: String? = null,
)

@Serializable
data class NearbyDto(
    val place: PlaceDto,
    val distanceMeters: Int,
    val estimatedMinutes: Int = 0,
    val walkable: Boolean = false,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class ExploreResponse(
    val here: PlaceDto? = null,
    val address: String = "",
    val thingsToDo: List<String> = emptyList(),
    val nextCourse: List<NearbyDto> = emptyList(),
    val forYou: List<NearbyDto> = emptyList(),
    val speech: String = "",
    val rankedBy: String = "rules",
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
    val purposeId: String? = null,
    val purposeText: String? = null,
    val now: String,
    val profile: ProfileDto? = null,    /** "rules" 이면 LLM 을 거치지 않은 빠른 결과. */
    val rank: String? = null,
)

@Serializable
data class NeedDto(
    val category: String,
    val item: String,
    val reason: String = "",
    val preferNearDestination: Boolean = false,
)

@Serializable
data class RouteStopDto(
    val place: PlaceDto,
    val detourMeters: Int,
    val detourMinutes: Int = 0,
    val progress: Double = 0.5,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class SuggestionDto(val need: NeedDto, val stops: List<RouteStopDto> = emptyList())

@Serializable
data class PurposePlanResponse(
    val purpose: PurposeDto? = null,
    val suggestions: List<SuggestionDto> = emptyList(),
    val speech: String = "",
    val rankedBy: String = "rules",
)

// ---- Func 3: 빈 시간 ----------------------------------------------------------------------------

@Serializable
data class ScheduleDto(
    val id: String? = null,
    val title: String,
    val place: DestinationDto,
    val start: String,
    val expectedEnd: String,
)

@Serializable
data class GapPlanRequest(
    val a: ScheduleDto,
    val b: ScheduleDto,
    val actualEnd: String,
    val travelMinutesOverride: Int? = null,
    val profile: ProfileDto? = null,    /** "rules" 이면 LLM 을 거치지 않은 빠른 결과. */
    val rank: String? = null,
)

@Serializable
data class GapStopDto(
    val place: PlaceDto,
    val zone: String,
    /** "HH:mm" */
    val arriveAt: String,
    val stayMinutes: Int,
    val leaveAt: String,
    val arriveBAt: String,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class GapPlanResponse(
    val totalGapMinutes: Int,
    val travelMinutes: Int,
    val freeMinutes: Int,
    val leaveBy: String,
    val stops: Map<String, List<GapStopDto>> = emptyMap(),
    val speech: String = "",
    val rankedBy: String = "rules",
)
