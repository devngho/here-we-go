package io.github.devngho.herewego.navi

import io.github.devngho.herewego.data.EffectiveLocation
import io.github.devngho.herewego.data.LocationSource
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.Schedule
import io.github.devngho.herewego.domain.GapPlan
import io.github.devngho.herewego.domain.GapStop
import io.github.devngho.herewego.domain.GapZone
import io.github.devngho.herewego.domain.Geo
import io.github.devngho.herewego.domain.NearbyRecommendation
import io.github.devngho.herewego.domain.PurposeSuggestion
import io.github.devngho.herewego.domain.RouteStop
import io.github.devngho.herewego.domain.hhmm
import io.github.devngho.herewego.domain.minutesBetween
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 추천의 2단계: 서버가 직선거리로 추정한 값을 내비의 실제 주행 시간으로 고친다.
 *
 * 내비는 '차량의 현재 위치 → 한 곳'만 잴 수 있다. 그래서
 *  - 현재 위치 → 장소 구간은 실제 값으로 바꾸고,
 *  - 장소 → 목적지 구간은 추정치에 이 경로의 보정 계수(실제 ÷ 추정)를 곱해 쓴다.
 * 요청 하나가 0.1초쯤 걸리므로 화면에 보이는 장소만 잰다.
 */
class TripRefiner(private val navi: NaviRepository) {

    /**
     * 내비로 잰 값이 추천 기준 위치와 같은 출발지인지.
     * 사용자가 데모 위치를 골랐다면 차량 위치와 달라서 재면 안 된다.
     */
    fun canRefine(location: EffectiveLocation): Boolean =
        navi.isConnected && (location.source == LocationSource.NAVI || location.source == LocationSource.ARRIVED)

    /** Func 1: 차로 가야 하는 곳의 거리와 시간을 실제 값으로 바꾼다. 걸어갈 곳은 그대로 둔다. */
    suspend fun refineNearby(list: List<NearbyRecommendation>): List<NearbyRecommendation> = list.map { rec ->
        if (rec.distanceMeters <= Geo.WALKABLE_METERS) return@map rec
        val trip = navi.measureTrip(rec.place) ?: return@map rec
        rec.copy(
            distanceMeters = trip.distance.toDouble(),
            reasons = rec.reasons.map { if (it.startsWith("차로 ")) "차로 ${minutes(trip.eta)}분" else it },
        )
    }

    /** Func 2: 우회 시간을 '현재 위치 → 들를 곳'은 실제 값, '들를 곳 → 목적지'는 보정한 추정치로 다시 계산한다. */
    suspend fun refineStops(origin: EffectiveLocation, destination: Place, suggestions: List<PurposeSuggestion>): List<PurposeSuggestion> {
        val direct = navi.measureTrip(destination) ?: return suggestions
        val estimatedDirect = Geo.driveMinutes(Geo.distanceMeters(origin.point, destination.location))
        return suggestions.map { s ->
            s.copy(stops = s.stops.mapNotNull { stop ->
                val toStop = navi.measureTrip(stop.place) ?: return@mapNotNull stop
                val detour = detourMinutes(
                    toStopSeconds = toStop.eta,
                    directSeconds = direct.eta,
                    estimatedStopToDestMinutes = Geo.driveMinutes(Geo.distanceMeters(stop.place.location, destination.location)),
                    estimatedDirectMinutes = estimatedDirect,
                )
                // 직선으로는 가까워 보여도 실제로는 한참 돌아가는 곳(강 건너, 나들목이 먼 곳)은 뺀다.
                if (detour > max(MAX_DETOUR_MINUTES, minutes(direct.eta) / 2)) return@mapNotNull null
                stop.withDetour(detour)
            })
        }
    }

    /**
     * Func 3: 차가 A 에 있을 때, A → 들를 곳의 실제 주행 시간으로 도착·출발 시각을 민다.
     * B 에 늦게 되면 머무는 시간을 줄이고, 그래도 15분을 못 채우면 뺀다.
     */
    suspend fun refineGap(a: Schedule, b: Schedule, actualEnd: LocalTime, plan: GapPlan): GapPlan =
        plan.copy(stops = plan.stops.mapValues { (zone, stops) ->
            // B 근처는 B 까지 가서 걸어가는 곳이라 A 에서의 주행 시간이 계획의 일부(추정)와 겹친다. 그대로 둔다.
            if (zone == GapZone.NEAR_B) return@mapValues stops
            stops.mapNotNull { stop ->
                if (Geo.distanceMeters(a.place.location, stop.place.location) <= Geo.WALKABLE_METERS) return@mapNotNull stop
                val trip = navi.measureTrip(stop.place) ?: return@mapNotNull stop
                shiftStop(stop, minutes(trip.eta), actualEnd, b.start)
            }
        })

    companion object {
        const val MAX_DETOUR_MINUTES = 15

        fun minutes(seconds: Int): Int = ceil(seconds / 60.0).toInt()

        /**
         * 우회 시간(분) = (현재 → 들를 곳) + (들를 곳 → 목적지) − (현재 → 목적지).
         * 가운데 항은 잴 수 없어서, 직행 구간의 '실제 ÷ 추정' 비율로 보정한 추정치를 쓴다.
         */
        fun detourMinutes(toStopSeconds: Int, directSeconds: Int, estimatedStopToDestMinutes: Int, estimatedDirectMinutes: Int): Int {
            val calibration = if (estimatedDirectMinutes > 0) (directSeconds / 60.0) / estimatedDirectMinutes else 1.0
            val total = toStopSeconds / 60.0 + estimatedStopToDestMinutes * calibration
            return max(0.0, total - directSeconds / 60.0).roundToInt()
        }

        /** 들를 곳까지 가는 시간이 [realToMinutes] 로 바뀌었을 때의 일정. B 에 늦지 않게 머무는 시간을 줄인다. */
        fun shiftStop(stop: GapStop, realToMinutes: Int, actualEnd: LocalTime, bStart: LocalTime): GapStop? {
            val delta = realToMinutes - minutesBetween(actualEnd, stop.arriveAt)
            if (delta == 0) return stop
            val late = max(0, minutesBetween(bStart, stop.arriveBAt.plusMinutes(delta.toLong())))
            val stay = stop.stayMinutes - late
            if (stay < 15) return null
            val arrive = stop.arriveAt.plusMinutes(delta.toLong())
            val leave = arrive.plusMinutes(stay.toLong())
            val arriveB = stop.arriveBAt.plusMinutes((delta - late).toLong())
            return stop.copy(
                arriveAt = arrive, stayMinutes = stay, leaveAt = leave, arriveBAt = arriveB,
                reasons = stop.reasons.map {
                    when {
                        it.endsWith("머물 수 있어요") -> "${stay}분 머물 수 있어요"
                        it.startsWith("A에서 차로 ") -> "A에서 차로 ${realToMinutes}분"
                        it.startsWith("B 도착 ") -> "B 도착 ${arriveB.hhmm()} (여유 ${minutesBetween(arriveB, bStart)}분)"
                        else -> it
                    }
                },
            )
        }

        /** 우회 시간에 맞춰 거리와 설명을 바꾼다. 화면과 음성은 detourMeters 에서 분을 다시 계산한다. */
        private fun RouteStop.withDetour(minutes: Int): RouteStop = copy(
            // Geo.extraDriveMinutes 의 역산. 1분 이하는 '경로상'(300m 미만)으로 본다.
            detourMeters = if (minutes <= 1) 0.0 else minutes * 500.0 / 1.3,
            reasons = listOf(if (minutes <= 1) "경로상 바로 들를 수 있어요" else "우회 +${minutes}분") + reasons.drop(1),
        )
    }
}
