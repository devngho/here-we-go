package io.github.devngho.herewego.domain

import io.github.devngho.herewego.data.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 지도/경로 API 없이 동작하도록 만든 간단한 거리·시간 추정 유틸. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 직선거리 대비 실제 도로 거리 보정 계수. */
    private const val ROAD_FACTOR = 1.3

    /** 도심 평균 주행 속도 (m/min) ≈ 30km/h. */
    private const val DRIVE_M_PER_MIN = 500.0

    /** 보행 속도 (m/min). */
    private const val WALK_M_PER_MIN = 70.0

    /** 이 거리 이하는 걸어서 이동한다고 본다. */
    const val WALKABLE_METERS = 700.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun driveMinutes(meters: Double): Int =
        if (meters < 50) 0 else max(3, (meters * ROAD_FACTOR / DRIVE_M_PER_MIN).roundToInt())

    /** 우회로 늘어나는 주행 시간 (최소 3분 보정 없이). */
    fun extraDriveMinutes(meters: Double): Int = max(1, (meters * ROAD_FACTOR / DRIVE_M_PER_MIN).roundToInt())

    fun walkMinutes(meters: Double): Int = max(1, (meters * ROAD_FACTOR / WALK_M_PER_MIN).roundToInt())

    /** 걷기 적당하면 도보, 아니면 차량 이동 시간. */
    fun moveMinutes(meters: Double): Int =
        if (meters <= WALKABLE_METERS) walkMinutes(meters) else driveMinutes(meters)

    /** origin → p → destination 으로 들렀을 때 늘어나는 거리. */
    fun detourMeters(origin: GeoPoint, destination: GeoPoint, p: GeoPoint): Double =
        distanceMeters(origin, p) + distanceMeters(p, destination) - distanceMeters(origin, destination)

    /** p 를 origin→destination 선분에 투영했을 때의 진행률(0=출발, 1=도착). */
    fun progressRatio(origin: GeoPoint, destination: GeoPoint, p: GeoPoint): Double {
        val k = cos(Math.toRadians(origin.latitude))
        val dx = (destination.longitude - origin.longitude) * k
        val dy = destination.latitude - origin.latitude
        val px = (p.longitude - origin.longitude) * k
        val py = p.latitude - origin.latitude
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return 0.0
        return ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
    }
}

fun formatDistance(meters: Double): String =
    if (meters < 1000) "${(meters / 10).roundToInt() * 10}m" else "%.1fkm".format(meters / 1000)

fun formatMove(meters: Double): String =
    if (meters <= Geo.WALKABLE_METERS) "도보 ${Geo.walkMinutes(meters)}분" else "차로 ${Geo.driveMinutes(meters)}분"
