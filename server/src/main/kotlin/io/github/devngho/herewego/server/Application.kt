package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.api.ApiGuard
import io.github.devngho.herewego.server.api.BudgetedLlm
import io.github.devngho.herewego.server.api.BudgetedPlaceSearch
import io.github.devngho.herewego.server.api.ErrorBody
import io.github.devngho.herewego.server.api.ErrorResponse
import io.github.devngho.herewego.server.api.ExploreRequest
import io.github.devngho.herewego.server.api.GapPlanRequest
import io.github.devngho.herewego.server.api.LatLng
import io.github.devngho.herewego.server.api.LearnedPlaceDto
import io.github.devngho.herewego.server.api.PaidCallBudget
import io.github.devngho.herewego.server.api.ProfileDto
import io.github.devngho.herewego.server.api.PurposeDto
import io.github.devngho.herewego.server.api.PurposePlanRequest
import io.github.devngho.herewego.server.api.RequestLimiter
import io.github.devngho.herewego.server.api.ScheduleDto
import io.github.devngho.herewego.server.api.VisitsRequest
import io.github.devngho.herewego.server.api.VisitsResponse
import io.github.devngho.herewego.server.api.deviceId
import io.github.devngho.herewego.server.domain.NameClassifier
import io.github.devngho.herewego.server.llm.GeminiClient
import io.github.devngho.herewego.server.llm.Llm
import io.github.devngho.herewego.server.llm.LlmRanker
import io.github.devngho.herewego.server.llm.PlaceInsights
import io.github.devngho.herewego.server.model.Purpose
import io.github.devngho.herewego.server.places.GooglePlacesClient
import io.github.devngho.herewego.server.places.PlaceRepository
import io.github.devngho.herewego.server.places.PlaceSearch
import io.github.devngho.herewego.server.service.ExploreService
import io.github.devngho.herewego.server.service.GapService
import io.github.devngho.herewego.server.service.PurposeService
import io.github.devngho.herewego.server.service.parseTime
import io.github.devngho.herewego.server.service.toDto
import io.github.devngho.herewego.server.service.toPoint
import io.github.devngho.herewego.server.store.Store
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }.start(wait = true)
}

/**
 * @param search 장소 검색 구현. 기본값은 [Config.googlePlacesKey] 로 만든 Google Places 클라이언트이고,
 *   키가 없으면 장소가 필요한 요청은 503 을 돌려준다. 테스트에서는 가짜 구현을 넣는다.
 */
fun Application.module(
    config: Config,
    search: PlaceSearch? = config.googlePlacesKey?.let { GooglePlacesClient(it) },
    llm: Llm? = config.geminiApiKey?.let { GeminiClient(it, config.llmModel) },
    store: Store = Store(config.dbPath),
) {
    monitor.subscribe(ApplicationStopped) { store.close() }

    val budget = PaidCallBudget(store, config)
    val guardedLlm = llm?.let { BudgetedLlm(it, budget) }
    val places = search?.let { PlaceRepository(BudgetedPlaceSearch(it, budget)) }
    val insights =
        guardedLlm
            ?.takeIf { config.insightMaxPlaces > 0 }
            ?.let { PlaceInsights(it, this, config.insightTimeoutMillis.milliseconds, config.insightMaxPlaces) }
    val ranker = guardedLlm?.let { LlmRanker(it, config.llmTimeoutMillis.milliseconds) }
    val explore = places?.let { ExploreService(it, ranker, insights) }
    val purpose = places?.let { PurposeService(it, ranker, insights) }
    val gap = places?.let { GapService(it, ranker, insights) }

    fun notConfigured(): Nothing =
        throw ApiException(HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED", "서버에 GOOGLE_PLACES_KEY 가 설정되지 않았어요")

    install(CallLogging)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            e.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(e.status, ErrorResponse(ErrorBody(e.code, e.message)))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorBody("BAD_REQUEST", e.message ?: "잘못된 요청이에요")))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log
                .error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorBody("INTERNAL", "서버 오류가 발생했어요")))
        }
    }

    if (search == null) environment.log.warn("GOOGLE_PLACES_KEY is not set: place endpoints respond 503")
    if (llm == null) environment.log.warn("GEMINI_API_KEY is not set: results use rule-based ranking only")
    if (config.appKey == null) environment.log.warn("APP_KEY is not set: /v1 accepts requests without X-App-Key")

    routing {
        get("/healthz") { call.respondText("ok") }

        route("/v1") {
            install(ApiGuard) {
                appKey = config.appKey
                limiter = RequestLimiter(config.apiRequestsPerMinute, config.deviceRequestsPerMinute)
            }

            /** Func 2 프리셋 목적. */
            get("/purposes") {
                call.respond(Purpose.entries.map { PurposeDto(it.name, it.label) })
            }

            /** Func 1: 지금 여기. */
            post("/explore") {
                val service = explore ?: notConfigured()
                val req = call.receive<ExploreRequest>()
                // 요청에 프로필이 없으면 이 기기에 저장된 프로필을 쓴다.
                call.respond(service.explore(req.copy(profile = req.profile ?: store.profile(call.deviceId))))
            }

            /** Func 2: 가는 길에. */
            post("/purpose/plan") {
                val service = purpose ?: notConfigured()
                val req = call.receive<PurposePlanRequest>()
                call.respond(service.plan(req.copy(profile = req.profile ?: store.profile(call.deviceId))))
            }

            /** Func 3: 빈 시간. */
            post("/gap/plan") {
                val service = gap ?: notConfigured()
                val req = call.receive<GapPlanRequest>()
                call.respond(service.plan(req.copy(profile = req.profile ?: store.profile(call.deviceId))))
            }

            // ---- 기기별 프로필 ----

            get("/profile") { call.respond(store.profile(call.deviceId) ?: ProfileDto()) }

            put("/profile") {
                val profile = call.receive<ProfileDto>()
                store.saveProfile(call.deviceId, profile)
                call.respond(profile)
            }

            /** 내비 최근 목적지·도착한 곳을 이름으로 분류해 방문 횟수에 더한다. 이미 배운 이름은 다시 세지 않는다. */
            post("/profile/visits") {
                val visits = call.receive<VisitsRequest>().visits
                val current = store.profile(call.deviceId) ?: ProfileDto()
                val known = current.learnedPlaces.map { it.name }.toSet()
                val learned =
                    visits
                        .distinctBy { it.name }
                        .filter { it.name !in known }
                        .mapNotNull { v -> NameClassifier.classify(v.name)?.let { LearnedPlaceDto(v.name, it) } }
                val counts = current.visitCounts.toMutableMap()
                learned.forEach { counts.merge(it.category, 1, Int::plus) }
                val updated = current.copy(visitCounts = counts, learnedPlaces = learned + current.learnedPlaces)
                if (learned.isNotEmpty()) store.saveProfile(call.deviceId, updated)
                call.respond(VisitsResponse(learned.size, updated))
            }

            // ---- 기기별 일정 ----

            get("/schedules") { call.respond(store.schedules(call.deviceId)) }

            post("/schedules") {
                val schedule = call.receive<ScheduleDto>().validated()
                call.respond(HttpStatusCode.Created, store.addSchedule(call.deviceId, schedule))
            }

            put("/schedules/{id}") {
                val id = call.parameters["id"].orEmpty()
                val schedule = call.receive<ScheduleDto>().validated()
                if (!store.updateSchedule(call.deviceId, id, schedule)) throw scheduleNotFound()
                call.respond(schedule.copy(id = id))
            }

            delete("/schedules/{id}") {
                if (!store.deleteSchedule(call.deviceId, call.parameters["id"].orEmpty())) throw scheduleNotFound()
                call.respond(HttpStatusCode.NoContent)
            }

            /** 목적지·일정 장소 검색. */
            get("/places/search") {
                val repo = places ?: notConfigured()
                val q =
                    call.request.queryParameters["q"]
                        ?.trim()
                        .orEmpty()
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                val lng = call.request.queryParameters["lng"]?.toDoubleOrNull()
                if (q.isEmpty() || lat == null || lng == null) {
                    throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "q, lat, lng 가 필요해요")
                }
                call.respond(repo.searchByName(q, LatLng(lat, lng).toPoint()).map { it.toDto() })
            }
        }
    }
}

private fun scheduleNotFound() = ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "일정을 찾을 수 없어요")

/** 저장하기 전에 좌표와 시각 형식을 확인한다. */
private fun ScheduleDto.validated(): ScheduleDto {
    LatLng(place.lat, place.lng).toPoint()
    parseTime(start)
    parseTime(expectedEnd)
    if (title.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "일정 제목이 필요해요")
    return this
}
