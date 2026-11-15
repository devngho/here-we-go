package io.github.devngho.herewego.remote

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.UUID

/** herewego-server 클라이언트. 실패하면 예외를 던지고, 호출한 쪽이 내장 데이터로 대체한다. */
class HereWeGoApi(context: Context, baseUrl: String, private val appKey: String) {

    private val base = baseUrl.trimEnd('/')
    private val deviceId: String = deviceId(context.applicationContext)

    private val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            // LLM 순위는 6~7초, 서버의 대기 한도는 12초다.
            requestTimeoutMillis = 20_000
        }
        defaultRequest {
            header("X-Device-Id", deviceId)
            if (appKey.isNotEmpty()) header("X-App-Key", appKey)
        }
    }

    suspend fun explore(request: ExploreRequest): ExploreResponse =
        http.post("$base/v1/explore") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun purposePlan(request: PurposePlanRequest): PurposePlanResponse =
        http.post("$base/v1/purpose/plan") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun gapPlan(request: GapPlanRequest): GapPlanResponse =
        http.post("$base/v1/gap/plan") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** 목적지·일정 장소 검색. [near] 에 가까운 결과가 먼저 온다. */
    suspend fun searchPlaces(query: String, near: LatLng): List<PlaceDto> =
        http.get("$base/v1/places/search") {
            parameter("q", query)
            parameter("lat", near.lat)
            parameter("lng", near.lng)
        }.body()

    // ---- 기기별 프로필 · 일정 (서버에 저장) ----

    suspend fun profile(): ProfileDto = http.get("$base/v1/profile").body()

    suspend fun saveProfile(profile: ProfileDto) {
        http.put("$base/v1/profile") {
            contentType(ContentType.Application.Json)
            setBody(profile)
        }
    }

    suspend fun schedules(): List<ScheduleDto> = http.get("$base/v1/schedules").body()

    suspend fun addSchedule(schedule: ScheduleDto): ScheduleDto =
        http.post("$base/v1/schedules") {
            contentType(ContentType.Application.Json)
            setBody(schedule)
        }.body()

    suspend fun updateSchedule(id: String, schedule: ScheduleDto) {
        http.put("$base/v1/schedules/$id") {
            contentType(ContentType.Application.Json)
            setBody(schedule)
        }
    }

    private companion object {
        /** 로그인 없이 서버가 기기를 구분하는 익명 ID. 처음 한 번 만들어 저장한다. */
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("herewego", Context.MODE_PRIVATE)
            prefs.getString("device_id", null)?.let { return it }
            return UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        }
    }
}
