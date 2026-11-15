package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.api.ExploreResponse
import io.github.devngho.herewego.server.api.GapPlanResponse
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.PurposePlanResponse
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.fixtures.FakePlaceSearch
import io.github.devngho.herewego.server.llm.Llm
import io.github.devngho.herewego.server.llm.LlmException
import io.github.devngho.herewego.server.model.PlaceCategory
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRankingTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 프롬프트의 후보 목록에서 이름으로 id 를 찾아 답을 만드는 가짜 LLM. */
    private class FakeLlm(private val answer: (prompt: String, idOf: (String) -> String) -> String) : Llm {
        var calls = 0
        val prompts = mutableListOf<String>()

        override suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject {
            calls++
            prompts += user
            fun idOf(name: String) = user.lineSequence().firstOrNull { " | $name | " in it }?.substringBefore(" | ") ?: "missing"
            return Json.parseToJsonElement(answer(user, ::idOf)).jsonObject
        }
    }

    private fun app(llm: Llm, config: Config = Config(appKey = null), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, FakePlaceSearch(), llm, Store(":memory:")) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String) = client.post(path) {
        header(HEADER_DEVICE_ID, "device-1234")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private val exploreBody = """{"location":{"lat":37.4854,"lng":127.0363},"now":"2026-11-15T15:00:00+09:00","profile":{"interests":["GALLERY"]}"""

    @Test
    fun `the LLM's picks come first with its reason and the computed travel time`() {
        val llm = FakeLlm { _, id ->
            """{"nextCourse":[{"id":"${id("양재 빵집")}","reason":"간식으로 좋아요"},{"id":"p999","reason":"없는 곳"}],
                "forYou":[{"id":"${id("양재 미술관")}","reason":"전시를 좋아하시니까요"},{"id":"${id("양재 빵집")}","reason":"중복"}]}"""
        }
        app(llm) {
            val body = json.decodeFromString<ExploreResponse>(post("/v1/explore", "$exploreBody}").bodyAsText())
            assertEquals(RankedBy.llm, body.rankedBy)
            val first = body.nextCourse.first()
            assertEquals("양재 빵집", first.place.name)
            assertEquals("간식으로 좋아요", first.reasons.first())
            assertTrue("${first.reasons}", first.reasons.last().startsWith("도보 ") || first.reasons.last().startsWith("차로 "))
            assertEquals("양재 미술관", body.forYou.first().place.name)
            // 목록에 없는 id 와 두 목록에 걸친 중복은 버려지고, 여유분은 규칙 순서로 채워진다.
            val all = (body.nextCourse + body.forYou).map { it.place.name }
            assertEquals(all.size, all.distinct().size)
            assertTrue(body.nextCourse.size > 1)
            assertTrue(body.speech.contains("양재 빵집"))
            // LLM 에는 코드가 아는 사실만 보낸다.
            assertTrue(llm.prompts.single(), "평소 관심사: 전시·문화" in llm.prompts.single())
        }
    }

    @Test
    fun `rank=rules skips the LLM`() {
        val llm = FakeLlm { _, _ -> error("must not be called") }
        app(llm) {
            val body = json.decodeFromString<ExploreResponse>(post("/v1/explore", """$exploreBody,"rank":"rules"}""").bodyAsText())
            assertEquals(RankedBy.rules, body.rankedBy)
            assertEquals(0, llm.calls)
        }
    }

    @Test
    fun `an LLM error or timeout falls back to the rule-based ranking`() {
        val failing = object : Llm {
            override suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject = throw LlmException("blocked")
        }
        app(failing) {
            val res = post("/v1/explore", "$exploreBody}")
            assertEquals(HttpStatusCode.OK, res.status)
            val body = json.decodeFromString<ExploreResponse>(res.bodyAsText())
            assertEquals(RankedBy.rules, body.rankedBy)
            assertTrue(body.nextCourse.isNotEmpty())
        }
        val slow = object : Llm {
            override suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject {
                delay(60_000)
                return JsonObject(emptyMap())
            }
        }
        app(slow, Config(appKey = null, llmTimeoutMillis = 50)) {
            val body = json.decodeFromString<ExploreResponse>(post("/v1/explore", "$exploreBody}").bodyAsText())
            assertEquals(RankedBy.rules, body.rankedBy)
        }
    }

    @Test
    fun `identical requests reuse the cached LLM answer`() {
        val llm = FakeLlm { _, id -> """{"nextCourse":[{"id":"${id("양재 커피")}","reason":"쉬어 가요"}],"forYou":[]}""" }
        app(llm) {
            post("/v1/explore", "$exploreBody}")
            post("/v1/explore", "$exploreBody}")
            assertEquals(1, llm.calls)
        }
    }

    private val route = """"origin":{"lat":37.4854,"lng":127.0363},"destination":{"name":"판교 친구네","lat":37.4020,"lng":127.1086},"now":"2026-11-15T13:10:00+09:00""""

    @Test
    fun `a free-text purpose becomes needs and stops`() {
        val llm = FakeLlm { prompt, id ->
            if (prompt.startsWith("목적:")) {
                """{"label":"친구 결혼식","needs":[
                    {"category":"FLORIST","item":"꽃다발","reason":"축하 꽃다발을 준비해 보세요.","preferNearDestination":true},
                    {"category":"NOT_A_CATEGORY","item":"?","reason":"?","preferNearDestination":false}]}"""
            } else {
                """{"need1":[{"id":"${id("판교 꽃집")}","reason":"식장 근처라 편해요"}]}"""
            }
        }
        app(llm) {
            val res = post("/v1/purpose/plan", """{$route,"purposeText":"친구 결혼식 가는 길"}""")
            assertEquals(res.bodyAsText(), HttpStatusCode.OK, res.status)
            val body = json.decodeFromString<PurposePlanResponse>(res.bodyAsText())
            assertEquals("친구 결혼식", body.purpose.label)
            assertEquals("CUSTOM", body.purpose.id)
            // 앱이 모르는 카테고리는 버려진다.
            assertEquals(listOf(PlaceCategory.FLORIST), body.suggestions.map { it.need.category })
            val stop = body.suggestions.single().stops.first()
            assertEquals("판교 꽃집", stop.place.name)
            assertEquals("식장 근처라 편해요", stop.reasons[1])
            assertEquals(RankedBy.llm, body.rankedBy)
            assertTrue(body.speech, "친구 결혼식" in body.speech)
        }
    }

    @Test
    fun `gap times stay the engine's when the LLM reorders`() {
        val llm = FakeLlm { _, id -> """{"NEAR_A":[{"id":"${id("양재 빵집")}","reason":"잠깐 들르기 좋아요"}],"ON_ROUTE":[],"NEAR_B":[]}""" }
        val body = """{
            "a":{"title":"양재 미팅","place":{"name":"양재 사무실","lat":37.4854,"lng":127.0363},"start":"2026-11-15T14:00:00+09:00","expectedEnd":"2026-11-15T16:00:00+09:00"},
            "b":{"title":"판교 약속","place":{"name":"판교 미술관","lat":37.4010,"lng":127.1080},"start":"2026-11-15T17:00:00+09:00","expectedEnd":"2026-11-15T19:00:00+09:00"},
            "actualEnd":"2026-11-15T15:00:00+09:00"%s}"""
        app(llm) {
            val ranked = json.decodeFromString<GapPlanResponse>(post("/v1/gap/plan", body.format("")).bodyAsText())
            val ruled = json.decodeFromString<GapPlanResponse>(post("/v1/gap/plan", body.format(""","rank":"rules"""")).bodyAsText())
            assertEquals(RankedBy.llm, ranked.rankedBy)
            val nearA = ranked.stops.values.flatten().filter { it.zone.name == "NEAR_A" }
            assertEquals("양재 빵집", nearA.first().place.name)
            assertEquals("잠깐 들르기 좋아요", nearA.first().reasons[1])
            val same = ruled.stops.values.flatten().first { it.place.name == "양재 빵집" }
            assertEquals(same.arriveAt, nearA.first().arriveAt)
            assertEquals(same.arriveBAt, nearA.first().arriveBAt)
            assertEquals(ruled.leaveBy, ranked.leaveBy)
        }
    }
}
