package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.api.ExploreResponse
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.RankedBy
import io.github.devngho.herewego.server.fixtures.FakePlaceSearch
import io.github.devngho.herewego.server.llm.Llm
import io.github.devngho.herewego.server.store.Store
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Google Places 의 평점·영업시간과, 리뷰를 LLM 으로 요약한 대표 메뉴가 응답에 채워지는지. */
class PlaceInsightsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 순위는 항상 '양재 빵집'을 고른다. 리뷰 요약은 [digest] 가 장소 이름을 받아 JSON 조각을 돌려준다 (null 이면 답에서 뺀다).
     */
    private class FakeLlm(private val digest: suspend (place: String) -> String?) : Llm {
        val pickPrompts = CopyOnWriteArrayList<String>()
        val digestPrompts = CopyOnWriteArrayList<String>()

        override suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject {
            if (user.startsWith("상황:")) {
                pickPrompts += user
                val id = user.lineSequence().first { " | 양재 빵집 | " in it }.substringBefore(" | ")
                return Json.parseToJsonElement("""{"nextCourse":[{"id":"$id","reason":"소금빵이 인기예요"}],"forYou":[]}""").jsonObject
            }
            digestPrompts += user
            val items = mutableListOf<String>()
            for (line in user.lines().filter { it.matches(Regex("p\\d+ \\| .*")) }) {
                val (id, name) = line.split(" | ")
                digest(name)?.let { items += """{"id":"$id",$it}""" }
            }
            return Json.parseToJsonElement("""{"places":[${items.joinToString(",")},{"id":"p999","highlights":["없는 곳"],"reviews":""}]}""").jsonObject
        }
    }

    private fun app(llm: Llm?, config: Config = Config(appKey = null), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config, FakePlaceSearch(details = true), llm, Store(":memory:")) }
        block()
    }

    /** 2026-11-15 는 일요일. */
    private suspend fun ApplicationTestBuilder.explore(rank: String? = null, time: String = "15:00"): ExploreResponse = json.decodeFromString(
        client.post("/v1/explore") {
            header(HEADER_DEVICE_ID, "device-1234")
            contentType(ContentType.Application.Json)
            val r = rank?.let { ""","rank":"$it"""" }.orEmpty()
            setBody("""{"location":{"lat":37.4854,"lng":127.0363},"now":"2026-11-15T$time:00+09:00","profile":{"interests":["GALLERY"]}$r}""")
        }.bodyAsText()
    )

    private fun ExploreResponse.place(name: String) = (nextCourse + forYou).map { it.place }.firstOrNull { it.name == name }
    private fun String.lineOf(name: String) = lineSequence().first { " | $name | " in it }

    private val bakeryDigest = """"highlights":["소금빵","크루아상","a","b","c"],"reviews":"빵이 금방 품절된다는\n평이 많아요 | 주차는 불편""""

    @Test
    fun `rating and today's hours come with the search, without any LLM`() = app(llm = null) {
        val body = explore()
        assertEquals(RankedBy.rules, body.rankedBy)
        with(body.place("양재 빵집")!!) {
            assertEquals(4.6, rating)
            assertEquals(321, ratingCount)
            // 일요일의 영업시간. 브레이크 타임은 첫 개점과 마지막 폐점으로 합친다.
            assertEquals("09:00", openTime)
            assertEquals("21:30", closeTime)
            assertFalse(hoursEstimated)
            assertEquals("https://maps.google.com/?cid=6", mapsUrl)
            assertTrue(highlights.isEmpty())
        }
        // 오늘 쉬는 곳은 추천하지 않는다.
        assertNull(body.place("양재 칼국수"))
        // 24시간 영업.
        with(body.place("양재 미술관")!!) { assertEquals("00:00", openTime); assertEquals("00:00", closeTime); assertFalse(hoursEstimated) }
        // 영업시간이 없는 곳은 카테고리 기본값, 평점이 없는 곳은 생략.
        with(body.place("양재 커피")!!) { assertTrue(hoursEstimated); assertEquals("08:00", openTime); assertNull(rating) }
        with(body.place("양재 커피 2호점")!!) { assertTrue(hoursEstimated); assertEquals(3.9, rating) }
        // 평점이 높으면 규칙 기반 이유에도 나온다.
        assertTrue((body.nextCourse + body.forYou).first { it.place.name == "양재 빵집" }.reasons.contains("평점 4.6"))
    }

    @Test
    fun `reviews are digested into highlights in one call alongside the ranking`() {
        val llm = FakeLlm { place -> if (place == "양재 빵집") bakeryDigest else null }
        app(llm) {
            val body = explore()
            assertEquals(RankedBy.llm, body.rankedBy)
            val first = body.nextCourse.first()
            assertEquals("양재 빵집", first.place.name)
            assertEquals("소금빵이 인기예요", first.reasons.first())
            // 메뉴는 4개까지, 줄바꿈과 칸 구분자는 지운다.
            assertEquals(listOf("소금빵", "크루아상", "a", "b"), first.place.highlights)
            assertEquals("빵이 금방 품절된다는 평이 많아요 주차는 불편", first.place.reviewSummary)
            // 리뷰가 없는 곳과 목록에 없는 id 는 채우지 않는다.
            assertTrue((body.nextCourse + body.forYou).filter { it.place.name != "양재 빵집" }.all { it.place.highlights.isEmpty() })

            // 리뷰가 있는 후보만, 장소당 3개까지, 한 번의 호출로 보낸다.
            val digest = llm.digestPrompts.single()
            assertTrue(digest, "| 양재 빵집 | 베이커리\n- 소금빵이 정말 맛있어요. 오후에는 품절\n- 크루아상 추천 | 주차는 불편\n- 친절해요" in digest)
            assertFalse(digest, "네 번째 리뷰" in digest || "양재 커피 |" in digest || "양재 칼국수" in digest)

            // 요약이 아직 없을 때 순위를 매기는 LLM 은 평점·영업시간과 리뷰 발췌를 본다.
            val prompt = llm.pickPrompts.single()
            assertTrue(prompt, prompt.lineOf("양재 빵집").endsWith("| 평점 4.6(321명) / 오늘 09:00~21:30 / 리뷰 발췌: “소금빵이 정말 맛있어요. 오후에는 품절” “크루아상 추천   주차는 불편”"))
            assertTrue(prompt, prompt.lineOf("양재 커피").endsWith("| 정보 없음"))
            assertTrue(prompt, prompt.lineOf("양재 미술관").endsWith("| 오늘 00:00~00:00"))

            // 요약은 캐시되어 규칙 기반 응답과 다음 순위 프롬프트에 쓰인다.
            assertEquals(listOf("소금빵", "크루아상", "a", "b"), explore(rank = "rules").place("양재 빵집")!!.highlights)
            explore(time = "15:01")
            // 이미 요약한 곳은 다시 보내지 않는다 (새로 후보가 된 곳만 따로 요약한다).
            assertEquals(1, llm.digestPrompts.count { "양재 빵집" in it })
            assertTrue(llm.pickPrompts.last().lineOf("양재 빵집").endsWith("/ 대표: 소금빵, 크루아상, a, b / 리뷰: 빵이 금방 품절된다는 평이 많아요 주차는 불편"))
        }
    }

    @Test
    fun `a slow digest does not hold the response and is used later`() {
        val release = CompletableDeferred<Unit>()
        val llm = FakeLlm { place ->
            release.await()
            if (place == "양재 빵집") bakeryDigest else null
        }
        app(llm, Config(appKey = null, insightTimeoutMillis = 200)) {
            // 규칙 기반 요청은 요약을 시작만 하고 기다리지 않는다.
            assertTrue(explore(rank = "rules").place("양재 빵집")!!.highlights.isEmpty())
            val ranked = explore()
            assertEquals(RankedBy.llm, ranked.rankedBy)
            assertTrue(ranked.place("양재 빵집")!!.highlights.isEmpty())
            // 이미 돌고 있는 요약을 다시 시작하지 않는다.
            assertEquals(1, llm.digestPrompts.size)

            release.complete(Unit)
            repeat(50) {
                if (explore(rank = "rules").place("양재 빵집")!!.highlights.isNotEmpty()) return@app
                delay(20)
            }
            error("the late digest never reached a response")
        }
    }

    @Test
    fun `INSIGHT_MAX_PLACES limits the digest and 0 turns it off`() {
        val limited = FakeLlm { """"highlights":["x"],"reviews":""""" }
        app(limited, Config(appKey = null, insightMaxPlaces = 1)) {
            explore()
            assertEquals(1, limited.digestPrompts.single().lineSequence().count { it.matches(Regex("p\\d+ \\| .*")) })
        }
        val off = FakeLlm { error("must not digest") }
        app(off, Config.fromEnv(mapOf("INSIGHT_MAX_PLACES" to "0"))) {
            val body = explore()
            assertEquals(RankedBy.llm, body.rankedBy)
            assertEquals(0, off.digestPrompts.size)
            // 평점은 요약과 관계없이 나온다.
            assertEquals(4.6, body.place("양재 빵집")!!.rating)
        }
    }
}
