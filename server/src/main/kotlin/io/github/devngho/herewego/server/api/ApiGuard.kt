package io.github.devngho.herewego.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey

const val HEADER_APP_KEY = "X-App-Key"
const val HEADER_DEVICE_ID = "X-Device-Id"

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)

class ApiGuardConfig {
    /** null 이면 앱 키를 검사하지 않는다. */
    var appKey: String? = null
    var limiter: RequestLimiter = RequestLimiter(60, 20)
}

private val DeviceIdKey = AttributeKey<String>("deviceId")
private val DEVICE_ID = Regex("[A-Za-z0-9_-]{8,64}")

/** /v1 아래 모든 요청에 앱 키와 기기 ID 를 요구한다. */
val ApiGuard =
    createRouteScopedPlugin("ApiGuard", ::ApiGuardConfig) {
        val appKey = pluginConfig.appKey
        val limiter = pluginConfig.limiter
        onCall { call ->
            if (appKey != null && call.request.headers[HEADER_APP_KEY] != appKey) {
                throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "$HEADER_APP_KEY 가 올바르지 않아요")
            }
            val deviceId = call.request.headers[HEADER_DEVICE_ID]
            if (deviceId == null || !DEVICE_ID.matches(deviceId)) {
                throw ApiException(HttpStatusCode.BadRequest, "DEVICE_ID_REQUIRED", "$HEADER_DEVICE_ID 헤더가 필요해요")
            }
            limiter.check(deviceId)
            call.attributes.put(DeviceIdKey, deviceId)
        }
    }

/** 익명 기기 ID. [ApiGuard] 가 설치된 경로에서만 쓸 수 있다. */
val ApplicationCall.deviceId: String get() = attributes[DeviceIdKey]
