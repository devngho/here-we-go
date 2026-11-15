package io.github.devngho.herewego.server.places

import io.github.devngho.herewego.server.api.ApiException
import io.github.devngho.herewego.server.model.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** Places API (New) 검색 결과 한 건. 요청한 필드 마스크에 없는 값은 비어 있다. */
@Serializable
data class GooglePlace(
    val id: String,
    val displayName: GoogleText? = null,
    /** 가장 구체적인 종류 하나 (korean_restaurant, cafe …). 없는 곳도 있다. */
    val primaryType: String? = null,
    val types: List<String> = emptyList(),
    val location: GoogleLatLng? = null,
    val formattedAddress: String = "",
    val rating: Double? = null,
    val userRatingCount: Int? = null,
    val regularOpeningHours: GoogleHours? = null,
    val reviews: List<GoogleReview> = emptyList(),
    val googleMapsUri: String = "",
    val businessStatus: String? = null,
)

@Serializable
data class GoogleText(val text: String = "")

@Serializable
data class GoogleLatLng(val latitude: Double, val longitude: Double)

/** 24시간 영업은 close 가 없는 기간 하나로 온다. */
@Serializable
data class GoogleHours(val periods: List<GooglePeriod> = emptyList())

@Serializable
data class GooglePeriod(val open: GoogleDayTime? = null, val close: GoogleDayTime? = null)

/** @param day 0 = 일요일 … 6 = 토요일 */
@Serializable
data class GoogleDayTime(val day: Int = 0, val hour: Int = 0, val minute: Int = 0)

@Serializable
data class GoogleReview(val text: GoogleText? = null)

/** 위경도 사각형. */
data class Box(val low: GeoPoint, val high: GeoPoint)

/** 장소 검색 추상화. 테스트에서는 가짜 구현으로 바꾼다. 호출 한 번이 과금 한 번이고 결과는 최대 20건이다. */
interface PlaceSearch {
    /** [at] 반경 안에서 [types] 중 하나에 해당하는 곳을 인기순으로. 평점·영업시간·리뷰를 함께 받는다. */
    suspend fun nearby(types: List<String>, at: GeoPoint, radiusMeters: Int): List<GooglePlace>

    /**
     * 글자로 검색한다. [within] 이 있으면 그 사각형 안으로 제한하고, 없으면 [near] 에 가까운 결과를 우선한다.
     * @param details false 면 평점·영업시간·리뷰 없이 이름·위치만 받는다 (더 싼 요금제).
     */
    suspend fun byText(query: String, near: GeoPoint? = null, within: Box? = null, details: Boolean = true): List<GooglePlace>
}

class GooglePlacesClient(
    private val apiKey: String,
    private val http: HttpClient = defaultClient(),
) : PlaceSearch {

    override suspend fun nearby(types: List<String>, at: GeoPoint, radiusMeters: Int): List<GooglePlace> = search("searchNearby", DETAIL_FIELDS) {
        putJsonArray("includedTypes") { types.forEach { add(JsonPrimitive(it)) } }
        put("maxResultCount", 20)
        put("rankPreference", "POPULARITY")
        putJsonObject("locationRestriction") { circle(at, radiusMeters) }
    }

    override suspend fun byText(query: String, near: GeoPoint?, within: Box?, details: Boolean): List<GooglePlace> =
        search("searchText", if (details) DETAIL_FIELDS else BASIC_FIELDS) {
            put("textQuery", query)
            put("pageSize", 20)
            if (within != null) {
                putJsonObject("locationRestriction") {
                    putJsonObject("rectangle") {
                        putJsonObject("low") { latLng(within.low) }
                        putJsonObject("high") { latLng(within.high) }
                    }
                }
            } else if (near != null) {
                putJsonObject("locationBias") { circle(near, BIAS_RADIUS_METERS) }
            }
        }

    private suspend fun search(method: String, fields: String, body: JsonObjectBuilder.() -> Unit): List<GooglePlace> {
        // 한 요청이 여러 번 검색한다. 한꺼번에 보내 초당 제한에 걸리지 않게 한다.
        val res = limiter.withPermit {
            http.post("$BASE/places:$method") {
                header("X-Goog-Api-Key", apiKey)
                header("X-Goog-FieldMask", fields)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("languageCode", "ko")
                    put("regionCode", "KR")
                    body()
                })
            }
        }
        if (!res.status.isSuccess()) {
            log.warn("Google Places responded {}: {}", res.status, res.bodyAsText().take(200))
            throw ApiException(HttpStatusCode.BadGateway, "UPSTREAM_UNAVAILABLE", "장소 검색 서비스에 연결하지 못했어요")
        }
        return res.body<SearchResponse>().places
    }

    private fun JsonObjectBuilder.circle(center: GeoPoint, radiusMeters: Int) = putJsonObject("circle") {
        putJsonObject("center") { latLng(center) }
        put("radius", radiusMeters.coerceIn(1, 50_000))
    }

    private fun JsonObjectBuilder.latLng(p: GeoPoint) {
        put("latitude", p.latitude)
        put("longitude", p.longitude)
    }

    private val limiter = Semaphore(8)

    @Serializable
    private data class SearchResponse(val places: List<GooglePlace> = emptyList())

    companion object {
        private const val BASE = "https://places.googleapis.com/v1"
        private const val BIAS_RADIUS_METERS = 30_000
        private val log = LoggerFactory.getLogger(GooglePlacesClient::class.java)

        /** 이름·위치만. Text Search Pro 요금. */
        private const val BASIC_FIELDS =
            "places.id,places.displayName,places.primaryType,places.types,places.location,places.formattedAddress,places.googleMapsUri,places.businessStatus"

        /** 평점·영업시간(Enterprise)과 리뷰(Enterprise + Atmosphere)까지. 가장 비싼 요금이 호출 전체에 적용된다. */
        private const val DETAIL_FIELDS =
            "$BASIC_FIELDS,places.rating,places.userRatingCount,places.regularOpeningHours.periods,places.reviews.text"

        fun defaultClient() = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        }
    }
}
