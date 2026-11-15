package io.github.devngho.herewego.server.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 정해진 스키마의 JSON 을 돌려주는 LLM. 실패하면 예외를 던지고, 부르는 쪽이 규칙 기반 결과로 대체한다. */
interface Llm {
    suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject
}

class LlmException(message: String) : RuntimeException(message)

/** Google AI Studio 키로 Gemini API(generateContent)를 부른다. */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(parser) }
        // CIO 의 기본 제한(15초)은 후보가 많은 호출에 짧다. 기다리는 시간은 부르는 쪽(LlmRanker, PlaceInsights)이 정한다.
        engine { requestTimeout = 0 }
    },
) : Llm {

    override suspend fun generate(system: String, user: String, schema: JsonObject): JsonObject {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
                // 운전자가 기다리는 요청이라 깊은 추론보다 응답 시간이 중요하다.
                putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
            }
        }
        val res = http.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!res.status.isSuccess()) throw LlmException("Gemini responded ${res.status}: ${res.bodyAsText().take(200)}")

        val root = res.body<JsonObject>()
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("Gemini returned no candidate (blockReason=${root["promptFeedback"]?.jsonObject?.get("blockReason")})")
        val finish = candidate["finishReason"]?.jsonPrimitive?.content
        if (finish != null && finish != "STOP") throw LlmException("Gemini stopped with $finish")
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("")
            ?: throw LlmException("Gemini returned no text")
        return parser.parseToJsonElement(text).jsonObject
    }

    private companion object {
        val parser = Json { ignoreUnknownKeys = true }
    }
}
