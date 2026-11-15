package io.github.devngho.herewego.server.api

import io.github.devngho.herewego.server.Config
import io.github.devngho.herewego.server.llm.Llm
import io.github.devngho.herewego.server.model.GeoPoint
import io.github.devngho.herewego.server.places.Box
import io.github.devngho.herewego.server.places.GooglePlace
import io.github.devngho.herewego.server.places.PlaceSearch
import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.store.UsageLimit
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A bounded map: at most the global limit's worth of device IDs per minute. No trusted proxy/IP needed. */
class RequestLimiter(
    private val globalLimit: Int,
    private val deviceLimit: Int,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var windowStart = nowMillis()
    private var total = 0
    private val devices = mutableMapOf<String, Int>()

    init {
        require(globalLimit >= 0 && deviceLimit >= 0)
    }

    @Synchronized
    fun check(deviceId: String) {
        val now = nowMillis()
        if (now - windowStart >= 60_000) {
            windowStart = now
            total = 0
            devices.clear()
        }
        val used = devices[deviceId] ?: 0
        if (total >= globalLimit || used >= deviceLimit) {
            throw ApiException(
                HttpStatusCode.TooManyRequests,
                "RATE_LIMITED",
                "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.",
                ((60_000 - (now - windowStart) + 999) / 1_000).coerceAtLeast(1),
            )
        }
        total++
        devices[deviceId] = used + 1
    }
}

/** One reservation per actual upstream attempt, not per incoming HTTP request or returned place. */
class PaidCallBudget(
    private val store: Store,
    private val config: Config,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val placesSlots = Semaphore(4)
    private val geminiSlots = Semaphore(2)

    suspend fun <T> call(
        provider: String,
        block: suspend () -> T,
    ): T {
        require(provider == "places" || provider == "gemini")
        val slots = if (provider == "places") placesSlots else geminiSlots
        // A normal gap/route plan fans out into several calls; queue those rather than failing the whole plan.
        slots.acquire()
        try {
            val now = clock.instant()
            val date = now.atZone(ZoneOffset.UTC).toLocalDate()
            val minute = now.truncatedTo(ChronoUnit.MINUTES)
            val dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val monthEnd =
                date
                    .withDayOfMonth(1)
                    .plusMonths(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toEpochSecond()
            val caps =
                if (provider == "places") {
                    listOf(config.placesCallsPerMinute, config.placesCallsPerDay, config.placesCallsPerMonth)
                } else {
                    listOf(config.geminiCallsPerMinute, config.geminiCallsPerDay, config.geminiCallsPerMonth)
                }
            val reset =
                store.reserveUsage(
                    listOf(
                        UsageLimit("$provider:minute:${minute.epochSecond}", caps[0], minute.plusSeconds(60).epochSecond),
                        UsageLimit("$provider:day:$date", caps[1], dayEnd),
                        UsageLimit("$provider:month:${date.withDayOfMonth(1)}", caps[2], monthEnd),
                    ),
                    now.epochSecond,
                )
            if (reset != null) {
                throw ApiException(
                    HttpStatusCode.TooManyRequests,
                    "PAID_API_LIMIT",
                    "외부 API 사용 한도에 도달했어요. 나중에 다시 시도해 주세요.",
                    (reset - now.epochSecond).coerceAtLeast(1),
                )
            }
            return block()
        } finally {
            slots.release()
        }
    }
}

class BudgetedPlaceSearch(
    private val delegate: PlaceSearch,
    private val budget: PaidCallBudget,
) : PlaceSearch {
    override suspend fun nearby(
        types: List<String>,
        at: GeoPoint,
        radiusMeters: Int,
    ): List<GooglePlace> = budget.call("places") { delegate.nearby(types, at, radiusMeters) }

    override suspend fun byText(
        query: String,
        near: GeoPoint?,
        within: Box?,
        details: Boolean,
    ): List<GooglePlace> {
        if (query.length > 256) throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "검색어는 256자 이하여야 해요")
        return budget.call("places") { delegate.byText(query, near, within, details) }
    }
}

class BudgetedLlm(
    private val delegate: Llm,
    private val budget: PaidCallBudget,
) : Llm {
    override suspend fun generate(
        system: String,
        user: String,
        schema: JsonObject,
    ): JsonObject {
        // Bound input cost as well as call count. Never send oversized prompts to the provider.
        if (system.length.toLong() + user.length + schema.toString().length > 64_000) {
            throw ApiException(HttpStatusCode.BadRequest, "PROMPT_TOO_LARGE", "AI 요청이 너무 커요")
        }
        return budget.call("gemini") { delegate.generate(system, user, schema) }
    }
}
