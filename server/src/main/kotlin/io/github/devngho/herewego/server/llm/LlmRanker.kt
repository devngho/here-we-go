package io.github.devngho.herewego.server.llm

import io.github.devngho.herewego.server.model.PlaceCategory
import io.github.devngho.herewego.server.model.PurposeNeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** LLM 에게 보여 줄 후보 하나. [facts] 는 코드가 계산한 사실(거리, 시각 등)만 담는다. */
data class Candidate(
    val key: String,
    val name: String,
    val category: PlaceCategory,
    val facts: String,
    /** 이 후보를 넣을 수 있는 목록. */
    val groups: Set<String>,
    /** Google 지도에서 확인한 평점·영업시간·메뉴·리뷰 한 줄 ([insightLine]). 모르면 null. */
    val insight: String? = null,
)

data class Pick(val key: String, val reason: String)

data class FreeTextPurpose(val label: String, val needs: List<PurposeNeed>)

/**
 * 추천의 마지막 단계: 규칙으로 추린 후보 중에서 LLM 이 고르고 순서를 정하고 한 줄 이유를 쓴다.
 *
 * LLM 은 후보를 ID 로만 가리킨다. 목록에 없는 ID, 넣을 수 없는 목록, 중복은 버린다.
 * 거리·시각 같은 숫자는 LLM 의 출력에서 가져오지 않는다.
 * 시간 초과·오류·거절이면 null 을 돌려주고, 부르는 쪽이 규칙 기반 순서를 그대로 쓴다.
 */
class LlmRanker(
    private val llm: Llm,
    private val deadline: Duration = 12.seconds,
    private val cacheTtl: Duration = 10.minutes,
) {
    private class Entry(val value: Any, val at: TimeSource.Monotonic.ValueTimeMark)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * @param groups 목록 키 → 목록 설명 (예: "nextCourse" → "지금 장소 다음에 가기 좋은 곳")
     * @return 목록 키 → 고른 후보. 실패하면 null.
     */
    suspend fun pick(situation: String, groups: Map<String, String>, candidates: List<Candidate>, perGroup: Int): Map<String, List<Pick>>? {
        if (candidates.isEmpty()) return null
        val ids = candidates.mapIndexed { i, c -> "p${i + 1}" to c }.toMap()
        val user = buildString {
            appendLine("상황: $situation")
            appendLine()
            appendLine("채울 목록 (각각 최대 ${perGroup}곳):")
            groups.forEach { (key, description) -> appendLine("- $key: $description") }
            appendLine()
            appendLine("후보 (id | 이름 | 종류 | 사실 | 넣을 수 있는 목록 | Google 지도 정보):")
            ids.forEach { (id, c) ->
                appendLine("$id | ${c.name} | ${c.category.label} | ${c.facts} | ${c.groups.joinToString(",")} | ${c.insight ?: NO_INSIGHT}")
            }
        }
        val schema = obj {
            groups.keys.forEach { key ->
                putJsonObject(key) {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "STRING") }
                            putJsonObject("reason") { put("type", "STRING") }
                        }
                        putJsonArray("required") { add(str("id")); add(str("reason")) }
                    }
                }
            }
        }
        val result = cached("pick\n$user") { llm.generate(PICK_SYSTEM, user, schema) } ?: return null

        val used = mutableSetOf<String>()
        return groups.keys.associateWith { group ->
            result[group]?.let { it as? kotlinx.serialization.json.JsonArray }.orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val candidate = ids[o["id"]?.jsonPrimitive?.content] ?: return@mapNotNull null
                val reason = o["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (group !in candidate.groups || reason.isEmpty() || !used.add(candidate.key)) return@mapNotNull null
                Pick(candidate.key, reason.take(40))
            }.take(perGroup)
        }
    }

    /** 자유 입력 목적("친구 결혼식 가는 길")을 가는 길에 챙길 것들로 바꾼다. 실패하면 null. */
    suspend fun needsFor(purposeText: String, allowed: List<PlaceCategory>): FreeTextPurpose? {
        val text = purposeText.trim().take(100)
        val schema = obj {
            putJsonObject("label") { put("type", "STRING") }
            putJsonObject("needs") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("category") {
                            put("type", "STRING")
                            putJsonArray("enum") { allowed.forEach { add(str(it.name)) } }
                        }
                        putJsonObject("item") { put("type", "STRING") }
                        putJsonObject("reason") { put("type", "STRING") }
                        putJsonObject("preferNearDestination") { put("type", "BOOLEAN") }
                    }
                    putJsonArray("required") { listOf("category", "item", "reason", "preferNearDestination").forEach { add(str(it)) } }
                }
            }
        }
        val user = "목적: $text\n\n고를 수 있는 가게 종류:\n" + allowed.joinToString("\n") { "- ${it.name}: ${it.label}" }
        val result = cached("needs\n$user") { llm.generate(NEEDS_SYSTEM, user, schema) } ?: return null

        val needs = result["needs"]?.let { it as? kotlinx.serialization.json.JsonArray }.orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val category = allowed.firstOrNull { it.name == o["category"]?.jsonPrimitive?.content } ?: return@mapNotNull null
            val what = o["item"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (what.isEmpty()) return@mapNotNull null
            PurposeNeed(category, what.take(30), o["reason"]?.jsonPrimitive?.content?.trim().orEmpty().take(80), o["preferNearDestination"]?.jsonPrimitive?.booleanOrNull ?: false)
        }.distinctBy { it.category }.take(4)
        if (needs.isEmpty()) return null
        return FreeTextPurpose(result["label"]?.jsonPrimitive?.content?.trim()?.take(20)?.ifEmpty { null } ?: text.take(20), needs)
    }

    private suspend fun cached(key: String, call: suspend () -> JsonObject): JsonObject? {
        cache[key]?.takeIf { it.at.elapsedNow() < cacheTtl }?.let { return it.value as JsonObject }
        return try {
            withTimeout(deadline) { call() }.also {
                cache[key] = Entry(it, TimeSource.Monotonic.markNow())
                if (cache.size > 500) cache.entries.removeIf { e -> e.value.at.elapsedNow() >= cacheTtl }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            log.warn("LLM did not answer within {}; using rule-based ranking", deadline)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("LLM failed; using rule-based ranking: {}", e.toString())
            null
        }
    }

    private fun obj(properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        val props = buildJsonObject(properties)
        put("properties", props)
        putJsonArray("required") { props.keys.forEach { add(str(it)) } }
    }

    private fun str(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

    private companion object {
        val log = LoggerFactory.getLogger(LlmRanker::class.java)

        const val NO_INSIGHT = "정보 없음"

        const val PICK_SYSTEM = """당신은 차량용 장소 추천 앱 '가보자고'의 추천 담당입니다. 운전자의 상황에 맞는 곳을 후보 중에서 골라 목록마다 좋은 순서로 정합니다.

규칙:
- 후보의 id 만 사용합니다. 후보에 없는 곳은 만들지 않습니다.
- 각 후보는 '넣을 수 있는 목록'에 적힌 목록에만 넣습니다. 한 후보는 한 목록에만 넣습니다.
- 한 목록 안에서는 종류가 겹치지 않게 다양하게 고릅니다. 상황과 시간대에 맞지 않는 곳은 빼도 됩니다.
- reason 은 이 사람에게 왜 좋은지 한국어 해요체로 20자 안팎의 한 구절로 씁니다. 예: "식사 후 가볍게 걷기 좋아요"
- 'Google 지도 정보' 칸은 지도에서 확인한 평점, 오늘 영업시간, 대표 메뉴(또는 즐길 거리), 리뷰 경향입니다. 상황·시간대·관심사에 잘 맞는 메뉴가 있거나 평이 좋은 곳을 앞에 두고, 평점이 낮거나 리뷰에 뚜렷한 불만이 많은 곳은 뒤로 미룹니다. '정보 없음'이라는 이유만으로 빼지는 않습니다.
- reason 은 주어진 사실과 'Google 지도 정보' 칸의 내용만 근거로 씁니다. 대표 메뉴를 언급해도 좋습니다. '정보 없음'인 곳의 메뉴나 평판, 그리고 가격처럼 주어지지 않은 정보는 쓰지 않습니다. 평점과 영업시간은 화면에 따로 표시되므로 reason 에 쓰지 않습니다.
- 거리, 시각, 우회 여부는 화면에 따로 표시됩니다. reason 에 숫자를 쓰지 않고, '가까워요', '경로상에 있어요'처럼 사실을 되풀이하지도 않습니다. 이 상황에서 그곳에서 무엇을 하면 좋은지를 씁니다."""

        const val NEEDS_SYSTEM = """당신은 차량용 장소 추천 앱 '가보자고'의 도우미입니다. 운전자가 어딘가에 가는 목적을 말하면, 가는 길에 들러 챙기면 좋은 것을 최대 4가지 제안합니다.

규칙:
- category 는 주어진 가게 종류 중에서만 고르고, 종류가 겹치지 않게 합니다.
- item 은 살 것을 짧게 씁니다. 예: "축의금 봉투", "꽃다발"
- reason 은 왜 챙기면 좋은지 한국어 해요체 한 문장으로 씁니다.
- 꽃, 케이크, 얼음처럼 도착 직전에 사야 좋은 것은 preferNearDestination 을 true 로 합니다.
- label 은 목적을 10자 안팎의 명사구로 줄입니다. 예: "친구 결혼식"
- 목적과 관계없는 것은 제안하지 않습니다. 술을 마시고 운전하는 것을 전제로 한 제안은 하지 않습니다."""
    }
}
