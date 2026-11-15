package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.ProfileDto
import io.github.devngho.herewego.server.api.ScheduleDto
import io.github.devngho.herewego.server.api.VisitsResponse
import io.github.devngho.herewego.server.fixtures.FakePlaceSearch
import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.store.Store
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(Config(appKey = null), FakePlaceSearch(), llm = null, store = Store(":memory:")) }
        block()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.device(id: String, body: String? = null) {
        header(HEADER_DEVICE_ID, id)
        body?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

    @Test
    fun `profile is stored per device`() = app {
        assertEquals(ProfileDto(), json.decodeFromString<ProfileDto>(client.get("/v1/profile") { device("device-aaaa") }.bodyAsText()))

        client.put("/v1/profile") { device("device-aaaa", """{"interests":["CAFE","PARK"],"visitCounts":{"CAFE":3}}""") }
        val mine = json.decodeFromString<ProfileDto>(client.get("/v1/profile") { device("device-aaaa") }.bodyAsText())
        assertEquals(setOf(PlaceCategory.CAFE, PlaceCategory.PARK), mine.interests)
        assertEquals(3, mine.visitCounts[PlaceCategory.CAFE])

        val other = json.decodeFromString<ProfileDto>(client.get("/v1/profile") { device("device-bbbb") }.bodyAsText())
        assertTrue(other.interests.isEmpty())
    }

    @Test
    fun `visits are classified once per place name`() = app {
        val body = """{"visits":[{"name":"도곡 스페셜티 커피"},{"name":"판교 요가 & 필라테스"},{"name":"어딘지 모를 곳"}]}"""
        val first = json.decodeFromString<VisitsResponse>(client.post("/v1/profile/visits") { device("device-aaaa", body) }.bodyAsText())
        assertEquals(2, first.learned)
        assertEquals(1, first.profile.visitCounts[PlaceCategory.CAFE])
        assertEquals(1, first.profile.visitCounts[PlaceCategory.GYM])

        val again = json.decodeFromString<VisitsResponse>(client.post("/v1/profile/visits") { device("device-aaaa", body) }.bodyAsText())
        assertEquals(0, again.learned)
        assertEquals(1, again.profile.visitCounts[PlaceCategory.CAFE])
    }

    @Test
    fun `the stored profile is used when a request carries none`() = app {
        client.put("/v1/profile") { device("device-aaaa", """{"interests":["GALLERY"]}""") }
        val res = client.post("/v1/explore") {
            device("device-aaaa", """{"location":{"lat":37.4854,"lng":127.0363},"now":"2026-11-15T15:00:00+09:00","rank":"rules"}""")
        }
        assertTrue(res.bodyAsText(), "평소 즐겨 찾는 전시·문화" in res.bodyAsText())
    }

    private val schedule = """{"title":"판교 미팅","place":{"name":"소프트웨어드림센터","lat":37.4128,"lng":127.0948},"start":"2026-11-15T14:00:00+09:00","expectedEnd":"2026-11-15T16:00:00+09:00"}"""

    @Test
    fun `schedules can be created, listed, changed and deleted by their owner only`() = app {
        val created = client.post("/v1/schedules") { device("device-aaaa", schedule) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = json.decodeFromString<ScheduleDto>(created.bodyAsText()).id!!
        client.post("/v1/schedules") { device("device-aaaa", schedule.replace("판교 미팅", "양재 저녁 약속")) }

        val list = json.decodeFromString<List<ScheduleDto>>(client.get("/v1/schedules") { device("device-aaaa") }.bodyAsText())
        assertEquals(listOf("판교 미팅", "양재 저녁 약속"), list.map { it.title })

        // 다른 기기는 볼 수도 바꿀 수도 없다.
        assertEquals("[]", client.get("/v1/schedules") { device("device-bbbb") }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.put("/v1/schedules/$id") { device("device-bbbb", schedule) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/schedules/$id") { device("device-bbbb") }.status)

        assertEquals(HttpStatusCode.OK, client.put("/v1/schedules/$id") { device("device-aaaa", schedule.replace("판교 미팅", "판교 회의")) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/schedules/${list[1].id}") { device("device-aaaa") }.status)
        val after = json.decodeFromString<List<ScheduleDto>>(client.get("/v1/schedules") { device("device-aaaa") }.bodyAsText())
        assertEquals(listOf("판교 회의"), after.map { it.title })
    }

    @Test
    fun `a schedule with a bad time or coordinates is rejected`() = app {
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/schedules") { device("device-aaaa", schedule.replace("2026-11-15T14:00:00+09:00", "14:00")) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/schedules") { device("device-aaaa", schedule.replace("37.4128", "137.0")) }.status)
    }

    @Test
    fun `data survives reopening the database file`() = runBlocking {
        val file = File.createTempFile("herewego", ".db").apply { delete() }
        try {
            Store(file.path).use { it.saveProfile("device-aaaa", ProfileDto(interests = setOf(PlaceCategory.GYM))) }
            Store(file.path).use {
                assertEquals(setOf(PlaceCategory.GYM), it.profile("device-aaaa")?.interests)
                assertNull(it.profile("device-bbbb"))
            }
        } finally {
            file.delete()
        }
    }
}
