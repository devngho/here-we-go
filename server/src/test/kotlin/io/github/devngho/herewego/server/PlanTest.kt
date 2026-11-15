package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.api.GapPlanResponse
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.PlaceDto
import io.github.devngho.herewego.server.api.PurposePlanResponse
import io.github.devngho.herewego.server.domain.GapZone
import io.github.devngho.herewego.server.domain.Geo
import io.github.devngho.herewego.server.fixtures.FakePlaceSearch
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.service.RouteSampling
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PlanTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Config(appKey = null), FakePlaceSearch(), llm = null, store = Store(":memory:")) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            header(HEADER_DEVICE_ID, "device-1234")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // 양재(42dot) → 판교(소프트웨어드림센터)
    private val yangjae = """{"lat":37.4854,"lng":127.0363}"""
    private val pangyo = """{"name":"판교 친구네","lat":37.4020,"lng":127.1086}"""

    @Test
    fun `boxes cover the straight line with a margin and stay few`() {
        val a = GeoPoint(37.4854, 127.0363)
        val b = GeoPoint(37.4020, 127.1086)
        val boxes = RouteSampling.boxes(a, b)
        // 약 11km 는 두 구간.
        assertEquals(2, boxes.size)
        fun covered(p: GeoPoint) = boxes.any { p.latitude in it.low.latitude..it.high.latitude && p.longitude in it.low.longitude..it.high.longitude }
        (0..10).forEach { i ->
            val t = i / 10.0
            assertTrue(covered(GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)))
        }
        // 직선에서 1.5km 벗어난 곳도 들어온다.
        assertTrue(covered(GeoPoint(a.latitude + 0.0135, a.longitude)))
        assertEquals(1, RouteSampling.boxes(a, GeoPoint(37.4860, 127.0370)).size)
        assertEquals(3, RouteSampling.boxes(a, GeoPoint(37.0, 127.5)).size)
    }

    @Test
    fun `gap circles are A, B and the middle when they are far apart`() {
        val a = GeoPoint(37.4854, 127.0363)
        val far = RouteSampling.circles(a, GeoPoint(37.4020, 127.1086), 1_200)
        assertEquals(listOf(1_200, 1_200, 4_000), far.map { it.second })
        assertEquals(2, RouteSampling.circles(a, GeoPoint(37.4990, 127.0363), 1_200).size)
        assertEquals(1, RouteSampling.circles(a, GeoPoint(37.4860, 127.0370), 1_200).size)
    }

    @Test
    fun `detour is leaving the straight route and coming back`() {
        val a = GeoPoint(37.40, 127.00)
        val b = GeoPoint(37.40, 127.10)
        // 경로 한가운데에서 북쪽으로 약 1.1km 떨어진 곳
        val mid = GeoPoint(37.41, 127.05)
        assertEquals(2 * Geo.distanceMeters(GeoPoint(37.40, 127.05), mid), Geo.detourMeters(a, b, mid), 1.0)
        // 목적지를 지나친 곳은 목적지까지 왕복
        val beyond = GeoPoint(37.40, 127.11)
        assertEquals(2 * Geo.distanceMeters(b, beyond), Geo.detourMeters(a, b, beyond), 1.0)
        assertEquals(0.0, Geo.detourMeters(a, b, GeoPoint(37.40, 127.03)), 1.0)
    }

    @Test
    fun `housewarming plan suggests a florist near the destination`() = app {
        val res = post("/v1/purpose/plan", """{"origin":$yangjae,"destination":$pangyo,"purposeId":"HOUSEWARMING","now":"2026-11-15T13:10:00+09:00"}""")
        assertEquals(res.bodyAsText(), HttpStatusCode.OK, res.status)
        val body = json.decodeFromString<PurposePlanResponse>(res.bodyAsText())

        assertEquals("집들이", body.purpose.label)
        assertTrue(body.direct.meters in 10_000..13_000 && body.direct.minutes > 0)
        assertEquals(listOf(PlaceCategory.FLORIST, PlaceCategory.MART, PlaceCategory.WINE_SHOP, PlaceCategory.BAKERY), body.suggestions.map { it.need.category })
        val florist = body.suggestions.first().stops
        // 꽃은 도착 직전에 사는 게 좋으므로 목적지 쪽 꽃집이 먼저 온다.
        assertEquals("판교 꽃집", florist.first().place.name)
        assertTrue(florist.first().progress > 0.8)
        assertTrue(body.suggestions.flatMap { it.stops }.all { it.detourMeters >= 0 && it.reasons.isNotEmpty() })
        assertTrue(body.speech.contains("판교 친구네"))
    }

    @Test
    fun `free-text purpose without an LLM asks the user to pick a preset`() = app {
        val res = post("/v1/purpose/plan", """{"origin":$yangjae,"destination":$pangyo,"purposeText":"결혼식","now":"2026-11-15T13:10:00+09:00"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText(), "PURPOSE_UNAVAILABLE" in res.bodyAsText())

        val neither = post("/v1/purpose/plan", """{"origin":$yangjae,"destination":$pangyo,"now":"2026-11-15T13:10:00+09:00"}""")
        assertEquals(HttpStatusCode.BadRequest, neither.status)
    }

    private fun gapBody(bStart: String, actualEnd: String, extra: String = "") = """{
        "a":{"title":"양재 미팅","place":{"name":"양재 사무실","lat":37.4854,"lng":127.0363},"start":"2026-11-15T14:00:00+09:00","expectedEnd":"2026-11-15T16:00:00+09:00"},
        "b":{"title":"판교 약속","place":{"name":"판교 미술관","lat":37.4010,"lng":127.1080},"start":"$bStart","expectedEnd":"2026-11-15T19:00:00+09:00"},
        "actualEnd":"$actualEnd"$extra}"""

    @Test
    fun `gap plan times never make the user late for B`() = app {
        val res = post("/v1/gap/plan", gapBody("2026-11-15T17:00:00+09:00", "2026-11-15T15:00:00+09:00"))
        assertEquals(res.bodyAsText(), HttpStatusCode.OK, res.status)
        val body = json.decodeFromString<GapPlanResponse>(res.bodyAsText())

        assertEquals(120, body.totalGapMinutes)
        assertEquals(body.totalGapMinutes - body.travelMinutes - 10, body.freeMinutes)
        val stops = body.stops.values.flatten()
        assertTrue(stops.isNotEmpty())
        assertTrue(body.stops.getValue(GapZone.NEAR_A).isNotEmpty())
        stops.forEach {
            assertTrue("${it.place.name} arrives B ${it.arriveBAt}", LocalTime.parse(it.arriveBAt) <= LocalTime.of(17, 0))
            assertTrue(LocalTime.parse(it.arriveAt) >= LocalTime.of(15, 0))
        }
        // B 장소 자체는 들를 곳으로 나오지 않는다.
        assertTrue(stops.none { it.place.name == "판교 미술관" })
    }

    @Test
    fun `navi travel time overrides the estimate`() = app {
        val res = post("/v1/gap/plan", gapBody("2026-11-15T17:00:00+09:00", "2026-11-15T15:00:00+09:00", ""","travelMinutesOverride":45"""))
        val body = json.decodeFromString<GapPlanResponse>(res.bodyAsText())
        assertEquals(45, body.travelMinutes)
        assertEquals("16:05", body.leaveBy)
    }

    @Test
    fun `no slack means no stops and a hurry-up message`() = app {
        val res = post("/v1/gap/plan", gapBody("2026-11-15T16:20:00+09:00", "2026-11-15T16:00:00+09:00"))
        val body = json.decodeFromString<GapPlanResponse>(res.bodyAsText())
        assertTrue(body.stops.values.all { it.isEmpty() })
        assertTrue(body.speech, "바로 출발" in body.speech)
    }

    @Test
    fun `place search returns unclassified places as landmarks`() = app {
        val res = client.get("/v1/places/search?q=판교 미술관&lat=37.40&lng=127.10") { header(HEADER_DEVICE_ID, "device-1234") }
        assertEquals(HttpStatusCode.OK, res.status)
        val places = json.decodeFromString<List<PlaceDto>>(res.bodyAsText())
        assertEquals("판교 미술관", places.single().name)

        val bad = client.get("/v1/places/search?q=&lat=37.40&lng=127.10") { header(HEADER_DEVICE_ID, "device-1234") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
