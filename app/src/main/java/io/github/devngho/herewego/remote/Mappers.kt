package io.github.devngho.herewego.remote

import io.github.devngho.herewego.data.model.GeoPoint
import io.github.devngho.herewego.data.model.LearnedPlace
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.PurposeNeed
import io.github.devngho.herewego.data.model.Schedule
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.domain.GapPlan
import io.github.devngho.herewego.domain.GapStop
import io.github.devngho.herewego.domain.GapZone
import io.github.devngho.herewego.domain.NearbyRecommendation
import io.github.devngho.herewego.domain.PurposeSuggestion
import io.github.devngho.herewego.domain.RouteStop
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 서버 DTO ↔ 앱 모델. 서버가 앱이 모르는 카테고리·구역을 보내면 그 항목만 버린다.
 */

/** 오늘 날짜의 [time] 을 서버가 받는 ISO-8601 오프셋 문자열로. */
fun isoToday(time: LocalTime): String =
    OffsetDateTime.of(LocalDate.now(), time, ZoneId.systemDefault().rules.getOffset(Instant.now()))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

fun UserProfile.toDto() = ProfileDto(interests, visitCounts, learnedPlaces.map { LearnedPlaceDto(it.name, it.category) })

fun ProfileDto.toProfile() = UserProfile(interests, visitCounts, learnedPlaces.map { LearnedPlace(it.name, it.category) })

/** 서버에 저장된 일정. 장소는 이름과 좌표만 남아 있다. */
fun ScheduleDto.toSchedule(placeId: String) = Schedule(
    title = title,
    place = Place(
        id = placeId, name = place.name, category = PlaceCategory.LANDMARK,
        location = GeoPoint(place.lat, place.lng), address = "", rating = 0.0, stayMinutes = 0,
    ),
    start = OffsetDateTime.parse(start).toLocalTime(),
    expectedEnd = OffsetDateTime.parse(expectedEnd).toLocalTime(),
)

fun Place.toDestination() = DestinationDto(name, location.latitude, location.longitude)

fun Schedule.toDto() = ScheduleDto(title = title, place = place.toDestination(), start = isoToday(start), expectedEnd = isoToday(expectedEnd))

private fun time(hhmm: String?): LocalTime? = hhmm?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

private fun category(name: String): PlaceCategory? = PlaceCategory.entries.firstOrNull { it.name == name }

fun PlaceDto.toPlace(): Place? {
    val category = category(category) ?: return null
    return Place(
        id = id, name = name, category = category,
        location = GeoPoint(location.lat, location.lng),
        address = address,
        rating = rating ?: 0.0, // 서버가 Google 지도에서 확인하지 못했으면 0. 0 이면 화면에 표시하지 않는다.
        stayMinutes = stayMinutes,
        openTime = time(openTime) ?: LocalTime.of(9, 0),
        closeTime = time(closeTime) ?: LocalTime.of(22, 0),
        highlights = highlights,
        // 시각을 읽지 못했거나 휴무일이면 영업시간을 보여 주지 않는다.
        hoursEstimated = hoursEstimated || closedToday || time(openTime) == null || time(closeTime) == null,
        ratingCount = ratingCount ?: 0,
        reviewSummary = reviewSummary.orEmpty(),
        mapsUrl = mapsUrl.orEmpty(),
    )
}

fun NearbyDto.toRecommendation(): NearbyRecommendation? =
    place.toPlace()?.let { NearbyRecommendation(it, distanceMeters.toDouble(), score = 0.0, reasons = reasons) }

/** @param perNeed 화면에 보일 개수. 서버는 내비 ETA 로 걸러낼 여유분까지 보낸다. */
fun PurposePlanResponse.toSuggestions(perNeed: Int): List<PurposeSuggestion> = suggestions.mapNotNull { s ->
    val category = category(s.need.category) ?: return@mapNotNull null
    PurposeSuggestion(
        need = PurposeNeed(category, s.need.item, s.need.reason, s.need.preferNearDestination),
        stops = s.stops.mapNotNull { stop ->
            stop.place.toPlace()?.let { RouteStop(it, stop.detourMeters.toDouble(), stop.progress, score = 0.0, reasons = stop.reasons) }
        }.take(perNeed),
    )
}

fun GapPlanResponse.toPlan(perZone: Int): GapPlan {
    val byZone = stops.mapNotNull { (zoneName, list) ->
        val zone = GapZone.entries.firstOrNull { it.name == zoneName } ?: return@mapNotNull null
        zone to list.mapNotNull { s ->
            val place = s.place.toPlace() ?: return@mapNotNull null
            GapStop(
                place, zone, LocalTime.parse(s.arriveAt), s.stayMinutes, LocalTime.parse(s.leaveAt), LocalTime.parse(s.arriveBAt),
                score = 0.0, reasons = s.reasons,
            )
        }.take(perZone)
    }.toMap()
    return GapPlan(
        totalGapMinutes = totalGapMinutes,
        travelMinutes = travelMinutes,
        freeMinutes = freeMinutes,
        leaveBy = LocalTime.parse(leaveBy),
        stops = GapZone.entries.associateWith { byZone[it].orEmpty() },
    )
}
