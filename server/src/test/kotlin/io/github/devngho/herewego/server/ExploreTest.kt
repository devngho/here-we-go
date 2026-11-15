package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.api.ExploreResponse
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.fixtures.FakePlaceSearch
import io.github.devngho.herewego.server.model.PlaceCategory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun app(search: FakePlaceSearch? = FakePlaceSearch(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module(Config(appKey = null), search, llm = null, store = Store(":memory:")) }
            block()
        }

    private suspend fun ApplicationTestBuilder.explore(body: String): HttpResponse =
        client.post("/v1/explore") {
            header(HEADER_DEVICE_ID, "device-1234")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val at = """"location":{"lat":37.4854,"lng":127.0363}"""

    @Test
    fun `without an arrived place there is no here, only an address`() = app {
        val res = explore("""{$at,"now":"2026-11-15T15:00:00+09:00"}""")
        assertEquals(res.bodyAsText(), HttpStatusCode.OK, res.status)
        val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())

        assertNull(body.here)
        // 가장 가까운 후보(양재어린이공원은 빠지므로 양재 커피)의 주소에서 동네까지.
        assertEquals("서울특별시 서초구 강남대로", body.address)
        assertEquals(RankedBy.rules, body.rankedBy)
        assertTrue(body.speech.isNotBlank())
        val names = (body.nextCourse + body.forYou).map { it.place.name }
        assertTrue("$names", "양재 커피" in names && "양재 칼국수" in names)
        // 술집, 카페도 되는 모임공간, 폐업한 곳은 빠진다.
        assertTrue("$names", "양재 호프" !in names && "모임센터" !in names && "폐업한 카페" !in names)
        assertEquals(names.size, names.distinct().size)
        body.nextCourse.forEach { assertTrue(it.place.hoursEstimated && it.place.id.startsWith("google:") && it.place.mapsUrl == it.place.placeUrl && "g_mp" !in it.place.mapsUrl!!) }
    }

    @Test
    fun `after arriving at a restaurant a cafe comes first and the restaurant is not suggested`() = app {
        val res = explore("""{$at,"now":"2026-11-15T13:10:00+09:00","arrivedPlace":{"name":"양재 칼국수"}}""")
        val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())

        assertEquals("양재 칼국수", body.here?.name)
        assertEquals(PlaceCategory.RESTAURANT, body.here?.category)
        assertEquals(PlaceCategory.CAFE, body.nextCourse.first().place.category)
        assertTrue((body.nextCourse + body.forYou).none { it.place.name == "양재 칼국수" })
    }

    @Test
    fun `each list holds at most two places of a category and skips children's parks`() = app {
        val res = explore("""{$at,"now":"2026-11-15T15:00:00+09:00","profile":{"interests":["CAFE"]}}""")
        val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())
        listOf(body.nextCourse, body.forYou).forEach { list ->
            assertTrue(list.groupingBy { it.place.category }.eachCount().values.all { it <= 2 })
        }
        assertTrue((body.nextCourse + body.forYou).none { "어린이공원" in it.place.name })
    }

    @Test
    fun `at a cafe the personal list does not offer more cafes`() = app {
        val res = explore("""{$at,"now":"2026-11-15T15:00:00+09:00","arrivedPlace":{"name":"양재 커피","category":"CAFE"},"profile":{"interests":["CAFE"]}}""")
        val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())
        assertTrue(body.forYou.isNotEmpty())
        assertTrue(body.forYou.none { it.place.category == PlaceCategory.CAFE })
    }

    @Test
    fun `interests in the request change the personal ranking`() = app {
        val res = explore("""{$at,"now":"2026-11-15T15:00:00+09:00","profile":{"interests":["GALLERY"]}}""")
        val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())
        val gallery = (body.nextCourse + body.forYou).first { it.place.category == PlaceCategory.GALLERY }
        assertTrue("${gallery.reasons}", gallery.reasons.any { "즐겨 찾는" in it })
    }

    @Test
    fun `places are cached between requests in the same area`() {
        val search = FakePlaceSearch()
        app(search) {
            explore("""{$at,"now":"2026-11-15T15:00:00+09:00"}""")
            val first = search.calls.get()
            // 여덟 카테고리를 네 묶음으로 찾는다.
            assertEquals(4, first)
            explore("""{$at,"now":"2026-11-15T15:05:00+09:00"}""")
            assertEquals(first, search.calls.get())
        }
    }

    @Test
    fun `bad time and bad coordinates are 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, explore("""{$at,"now":"15:00"}""").status)
        assertEquals(HttpStatusCode.BadRequest, explore("""{"location":{"lat":137.0,"lng":127.0},"now":"2026-11-15T15:00:00+09:00"}""").status)
        assertEquals(HttpStatusCode.BadRequest, explore("""{"now":"2026-11-15T15:00:00+09:00"}""").status)
    }

    @Test
    fun `without a Places key the endpoint is 503`() = app(search = null) {
        val res = explore("""{$at,"now":"2026-11-15T15:00:00+09:00"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertTrue(res.bodyAsText(), "NOT_CONFIGURED" in res.bodyAsText())
    }
}
