package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.fixtures.SamplePlaces
import io.github.devngho.herewego.server.fixtures.SampleProfile
import io.github.devngho.herewego.server.domain.NameClassifier
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.model.Purpose
import io.github.devngho.herewego.server.model.Schedule
import io.github.devngho.herewego.server.domain.GapZone
import io.github.devngho.herewego.server.domain.RecommendationEngine
import io.github.devngho.herewego.server.domain.minutesBetween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RecommendationEngineTest {

    private val engine = RecommendationEngine(SamplePlaces.all)
    private val profile = SampleProfile.default

    @Test
    fun `func1 - after lunch at a restaurant, a cafe comes first`() {
        val here = SamplePlaces.yangjaeRestaurant
        val recs = engine.nearby(here, here.location, profile, LocalTime.of(13, 10), personalWeight = 0.15)

        println("Func1: " + recs.joinToString { "${it.place.name}(${it.reasons.joinToString("/")})" })
        assertTrue(recs.isNotEmpty())
        assertEquals(PlaceCategory.CAFE, recs.first().place.category)
        assertTrue(recs.none { it.place.id == here.id || !it.place.category.recommendable })
    }

    @Test
    fun `func2 - housewarming route has florist and mart within detour`() {
        val origin = SamplePlaces.office42dot.location
        val result = engine.forPurpose(Purpose.HOUSEWARMING, origin, SamplePlaces.friendHouse, profile, LocalTime.of(13, 10))

        result.forEach { s -> println("Func2 ${s.need.item}: " + s.stops.joinToString { "${it.place.name} +${it.detourMinutes}min p=${"%.2f".format(it.progress)}" }) }
        val florist = result.first { it.need.category == PlaceCategory.FLORIST }
        val mart = result.first { it.need.category == PlaceCategory.MART }
        assertTrue(florist.stops.isNotEmpty())
        assertTrue(mart.stops.isNotEmpty())
        // 앱의 원본 테스트는 목적지 쪽 꽃집이 1순위였다. 서버는 우회 거리를 '직선에서 벗어난 거리의 두 배'로 보므로
        // 직선에서 1.7km 떨어진 그 꽃집은 후보에는 남지만 1순위는 아니다. 도착 직전 선호는 PlanTest 에서 확인한다.
        assertTrue(florist.stops.any { it.progress > 0.5 })
    }

    @Test
    fun `func3 - meeting ended an hour early gives options in every zone that respect B start`() {
        val a = Schedule("판교 미팅", SamplePlaces.softwareDreamCenter, LocalTime.of(14, 0), LocalTime.of(16, 0))
        val b = Schedule("양재 약속", SamplePlaces.office42dot, LocalTime.of(17, 0), LocalTime.of(19, 0))
        val plan = engine.forGap(a, b, LocalTime.of(15, 0), profile)

        println("Func3 gap=${plan.totalGapMinutes} travel=${plan.travelMinutes} free=${plan.freeMinutes} leaveBy=${plan.leaveBy}")
        plan.stops.forEach { (zone, stops) ->
            println("  $zone: " + stops.joinToString { "${it.place.name} ${it.arriveAt}-${it.leaveAt} →B ${it.arriveBAt}" })
        }
        assertTrue(plan.freeMinutes >= 15)
        GapZone.entries.forEach { zone -> assertFalse("$zone empty", plan.stops[zone].isNullOrEmpty()) }
        plan.stops.values.flatten().forEach { assertTrue(minutesBetween(it.arriveBAt, b.start) >= 0) }
    }

    @Test
    fun `func3 - no slack means no recommendations`() {
        val a = Schedule("판교 미팅", SamplePlaces.softwareDreamCenter, LocalTime.of(14, 0), LocalTime.of(16, 0))
        val b = Schedule("양재 약속", SamplePlaces.office42dot, LocalTime.of(16, 30), LocalTime.of(18, 0))
        val plan = engine.forGap(a, b, LocalTime.of(16, 0), profile)
        assertTrue(plan.stops.values.all { it.isEmpty() })
    }

    @Test
    fun `profile learns categories from recent destination names`() {
        assertEquals(PlaceCategory.GYM, NameClassifier.classify("판교 요가 & 필라테스"))
        assertEquals(PlaceCategory.CAFE, NameClassifier.classify("도곡 스페셜티 커피"))
        assertEquals(PlaceCategory.PARK, NameClassifier.classify("양재 시민의 숲"))
    }
}
