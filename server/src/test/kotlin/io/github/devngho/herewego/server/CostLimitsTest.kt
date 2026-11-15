package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.api.BudgetedLlm
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.github.devngho.herewego.server.api.PaidCallBudget
import io.github.devngho.herewego.server.api.RequestLimiter
import io.github.devngho.herewego.server.llm.Llm
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.places.Box
import io.github.devngho.herewego.server.places.GooglePlace
import io.github.devngho.herewego.server.places.PlaceSearch
import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.store.UsageLimit
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class CostLimitsTest {
    private class MutableClock(
        var time: Instant = Instant.parse("2026-11-15T12:00:00Z"),
    ) : Clock() {
        override fun instant(): Instant = time

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test
    fun `request limiter caps each device and total even when IDs rotate then resets`() {
        var now = 0L
        val limiter = RequestLimiter(3, 2) { now }
        limiter.check("a")
        limiter.check("a")
        assertEquals("RATE_LIMITED", assertThrows(ApiException::class.java) { limiter.check("a") }.code)
        limiter.check("b")
        assertEquals(60L, assertThrows(ApiException::class.java) { limiter.check("c") }.retryAfterSeconds)
        now = 60_000
        limiter.check("a")
        limiter.check("b")
    }

    @Test
    fun `API throttles with retry header but health remains available`() =
        testApplication {
            application {
                module(
                    Config(apiRequestsPerMinute = 2, deviceRequestsPerMinute = 1),
                    search = null,
                    llm = null,
                    store = Store(":memory:"),
                )
            }
            assertEquals(HttpStatusCode.OK, client.get("/v1/purposes") { header(HEADER_DEVICE_ID, "device-0001") }.status)
            val limited = client.get("/v1/purposes") { header(HEADER_DEVICE_ID, "device-0001") }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.headers["Retry-After"]!!.toLong() in 1..60)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
            assertEquals(HttpStatusCode.OK, client.get("/v1/purposes") { header(HEADER_DEVICE_ID, "device-0002") }.status)
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/purposes") { header(HEADER_DEVICE_ID, "device-0003") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        }

    @Test
    fun `paid endpoint is blocked before search despite rotating IDs`() =
        testApplication {
            val calls = AtomicInteger()
            val search =
                object : PlaceSearch {
                    override suspend fun nearby(
                        types: List<String>,
                        at: GeoPoint,
                        radiusMeters: Int,
                    ): List<GooglePlace> = error("unused")

                    override suspend fun byText(
                        query: String,
                        near: GeoPoint?,
                        within: Box?,
                        details: Boolean,
                    ): List<GooglePlace> {
                        calls.incrementAndGet()
                        return emptyList()
                    }
                }
            application { module(Config(placesCallsPerDay = 1), search = search, llm = null, store = Store(":memory:")) }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/places/search?q=cafe&lat=37&lng=127") { header(HEADER_DEVICE_ID, "device-0001") }.status,
            )
            val blocked = client.get("/v1/places/search?q=cafe&lat=37&lng=127") { header(HEADER_DEVICE_ID, "device-0002") }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertTrue(blocked.bodyAsText().contains("PAID_API_LIMIT"))
            assertNotNull(blocked.headers["Retry-After"])
            assertEquals(1, calls.get())
        }

    @Test
    fun `quota persists across store and budget recreation`() =
        runBlocking {
            val file = Files.createTempFile("usage-quota", ".db")
            val clock = MutableClock()
            val config = Config(placesCallsPerDay = 1)
            try {
                Store(file.toString()).use { PaidCallBudget(it, config, clock).call("places") {} }
                Store(file.toString()).use { store ->
                    val result = runCatching { PaidCallBudget(store, config, clock).call("places") { fail("must not call provider") } }
                    assertEquals("PAID_API_LIMIT", (result.exceptionOrNull() as ApiException).code)
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `minute day and month caps reset at UTC boundaries with independent providers`() =
        runBlocking {
            Store(":memory:").use { store ->
                val clock = MutableClock()
                val budget = PaidCallBudget(store, Config(placesCallsPerMinute = 1, placesCallsPerDay = 1, placesCallsPerMonth = 2), clock)
                budget.call("places") {}
                assertTrue(runCatching { budget.call("places") {} }.exceptionOrNull() is ApiException)
                budget.call("gemini") {} // separate budget
                clock.time = clock.time.plusSeconds(60)
                assertTrue(runCatching { budget.call("places") {} }.exceptionOrNull() is ApiException) // day still exhausted
                clock.time = Instant.parse("2026-11-16T00:00:00Z")
                budget.call("places") {}
                clock.time = Instant.parse("2026-11-17T00:00:00Z")
                assertTrue(runCatching { budget.call("places") {} }.exceptionOrNull() is ApiException) // month exhausted
                clock.time = Instant.parse("2026-12-01T00:00:00Z")
                budget.call("places") {}
            }
        }

    @Test
    fun `parallel attempts cannot overshoot and failed calls still consume budget`() =
        runBlocking {
            Store(":memory:").use { store ->
                val budget = PaidCallBudget(store, Config(placesCallsPerDay = 5), MutableClock())
                val calls = AtomicInteger()
                (1..40)
                    .map {
                        async {
                            runCatching {
                                budget.call("places") {
                                    calls.incrementAndGet()
                                    error("upstream failure")
                                }
                            }
                        }
                    }.awaitAll()
                assertEquals(5, calls.get())
            }
        }

    @Test
    fun `rejected reservation rolls back every counter`() =
        runBlocking {
            Store(":memory:").use { store ->
                val a = UsageLimit("a", 2, 100)
                assertEquals(100L, store.reserveUsage(listOf(a, UsageLimit("blocked", 0, 100)), 0))
                assertNull(store.reserveUsage(listOf(a), 0))
                assertNull(store.reserveUsage(listOf(a), 0))
                assertEquals(100L, store.reserveUsage(listOf(a), 0))
            }
        }

    @Test
    fun `zero kills paid calls and oversized Gemini prompts never reach provider`() =
        runBlocking {
            Store(":memory:").use { store ->
                val budget = PaidCallBudget(store, Config(geminiCallsPerDay = 0), MutableClock())
                var calls = 0
                val llm =
                    BudgetedLlm(
                        object : Llm {
                            override suspend fun generate(
                                system: String,
                                user: String,
                                schema: JsonObject,
                            ): JsonObject {
                                calls++
                                return JsonObject(emptyMap())
                            }
                        },
                        budget,
                    )
                assertEquals(
                    "PROMPT_TOO_LARGE",
                    (
                        runCatching {
                            llm.generate("", "x".repeat(64_001), JsonObject(emptyMap()))
                        }.exceptionOrNull() as ApiException
                    ).code,
                )
                assertEquals(
                    "PAID_API_LIMIT",
                    (runCatching { llm.generate("", "hello", JsonObject(emptyMap())) }.exceptionOrNull() as ApiException).code,
                )
                assertEquals(0, calls)
            }
        }

    @Test
    fun `invalid limit configuration fails startup instead of removing protection`() {
        for (value in listOf("-1", "unlimited", "999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { Config.fromEnv(mapOf("PLACES_CALLS_PER_DAY" to value)) }
        }
        assertEquals(0, Config.fromEnv(mapOf("PLACES_CALLS_PER_DAY" to "0")).placesCallsPerDay)
    }
}
