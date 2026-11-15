package io.github.devngho.herewego.server.service

import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.places.Box
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 경로 API 없이 "가는 길"의 후보를 찾기 위한 검색 영역.
 * 검색 호출마다 요금이 붙으므로 영역 수를 작게 유지한다.
 */
object RouteSampling {
    private const val SEGMENT_METERS = 8_000.0
    private const val MAX_BOXES = 3
    /** 직선에서 이만큼 벗어난 곳까지 찾는다. 엔진이 허용하는 우회 거리(편도 1.75km 이상)를 덮는다. */
    private const val PAD_METERS = 2_000.0

    /**
     * 출발지 → 목적지 직선을 최대 [MAX_BOXES] 구간으로 나누고, 구간마다 여유를 둔 사각형을 만든다.
     * 한 사각형의 결과는 20건뿐이라 먼 길을 한 사각형으로 덮으면 경로에서 먼 곳만 걸린다.
     */
    fun boxes(origin: GeoPoint, destination: GeoPoint): List<Box> {
        val distance = Geo.distanceMeters(origin, destination)
        val count = ceil(distance / SEGMENT_METERS).toInt().coerceIn(1, MAX_BOXES)
        fun at(t: Double) = GeoPoint(
            origin.latitude + (destination.latitude - origin.latitude) * t,
            origin.longitude + (destination.longitude - origin.longitude) * t,
        )
        val padLat = PAD_METERS / 111_000.0
        val padLng = PAD_METERS / (111_000.0 * cos(Math.toRadians(origin.latitude)))
        return (0 until count).map { i ->
            val a = at(i.toDouble() / count)
            val b = at((i + 1).toDouble() / count)
            Box(
                GeoPoint(min(a.latitude, b.latitude) - padLat, min(a.longitude, b.longitude) - padLng),
                GeoPoint(max(a.latitude, b.latitude) + padLat, max(a.longitude, b.longitude) + padLng),
            )
        }
    }

    /**
     * 빈 시간에 들를 곳을 찾는 원: A 근처, B 근처, 그리고 둘이 멀면 그 사이.
     * @param nearMeters 'A 근처'·'B 근처'로 치는 거리.
     */
    fun circles(a: GeoPoint, b: GeoPoint, nearMeters: Int): List<Pair<GeoPoint, Int>> {
        val distance = Geo.distanceMeters(a, b)
        val ends = if (distance <= nearMeters) listOf(a to nearMeters) else listOf(a to nearMeters, b to nearMeters)
        if (distance <= nearMeters * 2.5) return ends
        val mid = GeoPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2)
        return ends + (mid to (distance / 2).toInt().coerceIn(1_500, 4_000))
    }
}
