package io.github.devngho.activities.domain

import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.data.model.Purpose
import io.github.devngho.activities.data.model.Schedule

/**
 * 운전 중 화면을 보지 않고도 알 수 있도록 읽어 줄 문장.
 * 귀로 듣는 문장이라 한 번에 2~3가지만, 짧은 문장으로 끊어 말한다.
 */
object SpeechScripts {

    /** 목적지 도착 직후 (Func 1 로 이어짐). */
    fun arrival(place: Place, next: List<NearbyRecommendation>, note: String? = null): String = buildString {
        append("${place.name}에 도착했어요.")
        note?.takeIf { it.isNotBlank() }?.let { append(" ${it.sentence()}") }
        append(highlights(place))
        next.firstOrNull()?.let { append(" ${nextCourse(place, it)}") }
    }

    /** 경유지 도착. */
    fun waypoint(place: Place, note: String?): String = buildString {
        append("경유지 ${place.name}에 도착했어요.")
        note?.takeIf { it.isNotBlank() }?.let { append(" ${it.sentence()}") }
    }

    /** "지금 여기" 화면 요약. */
    fun explore(place: Place?, address: String, next: List<NearbyRecommendation>, forYou: List<NearbyRecommendation>): String =
        buildString {
            append(place?.let { "지금 ${it.name}에 있어요." } ?: "지금 ${address.ifBlank { "알 수 없는 곳" }} 근처에 있어요.")
            place?.let { append(highlights(it)) }
            next.firstOrNull()?.let { append(" ${nextCourse(place, it)}") }
            forYou.firstOrNull()?.let {
                append(" 취향에 맞는 곳으로는 ${moveLabel(it.distanceMeters)} 거리의 ${it.place.name.withEulReul()} 추천해요.")
            }
            if (next.isEmpty() && forYou.isEmpty()) append(" 주변에 추천할 장소가 없어요.")
        }

    /** Func 2: 목적에 맞춰 들를 곳. */
    fun purpose(purpose: Purpose, destination: Place, suggestions: List<PurposeSuggestion>, selected: List<RouteStop>): String =
        buildString {
            append("${destination.name}에 ${purpose.label.withEuroRo()} 가는 길이네요.")
            if (selected.isNotEmpty()) {
                val names = selected.sortedBy { it.progress }.joinToString(", ") { it.place.name }
                append(" ${names.withEulReul()} 들렀다 가도록 안내할게요.")
                return@buildString
            }
            val picks = suggestions.mapNotNull { s -> s.stops.firstOrNull()?.let { s.need to it } }.take(2)
            if (picks.isEmpty()) {
                append(" 경로 근처에 들를 만한 곳이 없어요.")
                return@buildString
            }
            picks.forEach { (need, stop) ->
                val detour = if (stop.detourMeters < 300) "경로상 바로" else "${stop.detourMinutes}분만 돌아서"
                append(" ${need.item.withEunNeun()} $detour ${stop.place.name}에서 살 수 있어요.")
            }
        }

    /** Func 3: 빈 시간 계획. */
    fun gap(a: Schedule, b: Schedule, plan: GapPlan): String = buildString {
        if (plan.freeMinutes <= 0) {
            append("${b.title}까지 여유 시간이 없어요. 바로 출발하는 게 좋아요.")
            return@buildString
        }
        append("${a.title.withIGa()} 일찍 끝나서 ${plan.freeMinutes}분 여유가 있어요.")
        append(" ${plan.leaveBy.hhmm()}까지만 출발하면 ${b.title}에 늦지 않아요.")
        plan.stops.entries.firstNotNullOfOrNull { (zone, stops) -> stops.firstOrNull()?.let { zone to it } }
            ?.let { (zone, stop) ->
                val where = when (zone) {
                    GapZone.NEAR_A -> "근처"
                    GapZone.ON_ROUTE -> "가는 길에"
                    GapZone.NEAR_B -> "${b.place.name} 근처"
                }
                append(" $where ${stop.place.name}에서 ${stop.stayMinutes}분 정도 보내는 건 어떠세요?")
            }
    }

    /**
     * 화면/알림용 메모를 듣기 좋게 바꾼다.
     * "양재 저녁 약속(17:00)에 16:45 도착해요" → "양재 저녁 약속에 16:45 도착해요".
     * "…에 도착했어요" 처럼 도착 안내와 겹치는 메모는 버린다.
     */
    fun spokenNote(note: String): String? =
        note.takeUnless { "도착했어요" in it }
            ?.replace(Regex("\\s*\\([^)]*\\)"), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ helpers

    private fun highlights(place: Place): String {
        val items = place.highlights.take(2)
        if (items.isEmpty()) return ""
        val list = items.joinToString(", ")
        return when (place.category) {
            PlaceCategory.RESTAURANT, PlaceCategory.CAFE, PlaceCategory.BAKERY ->
                " 대표 메뉴는 ${list.withCopula()}."
            else -> " 여기서는 ${list.withEulReul()} 해 보세요."
        }
    }

    private fun nextCourse(from: Place?, rec: NearbyRecommendation): String {
        val time = when (from?.category) {
            PlaceCategory.RESTAURANT -> "식사 후에는"
            PlaceCategory.CAFE -> "쉬고 나서는"
            PlaceCategory.GYM -> "운동 후에는"
            else -> "다음 코스로는"
        }
        return "$time ${moveLabel(rec.distanceMeters)} 거리의 ${rec.place.name} 어떠세요?"
    }

    /** "도보 3분" → "걸어서 3분", "차로 5분" 그대로. */
    private fun moveLabel(meters: Double): String = formatMove(meters).replace("도보", "걸어서")

    private fun String.sentence(): String = trim().let { if (it.last() in ".!?") it else "$it." }
}

// ---------------------------------------------------------------------- 한국어 조사

/** 마지막 글자에 받침이 있는지. 한글이 아니면 null. */
internal fun String.hasBatchim(): Boolean? {
    val last = trimEnd().lastOrNull() ?: return null
    if (last !in '가'..'힣') return null
    return (last - '가') % 28 != 0
}

private fun String.lastBatchimIsRieul(): Boolean {
    val last = trimEnd().lastOrNull() ?: return false
    return last in '가'..'힣' && (last - '가') % 28 == 8
}

internal fun String.withEunNeun() = this + when (hasBatchim()) { true -> "은"; false -> "는"; null -> "은(는)" }
internal fun String.withIGa() = this + when (hasBatchim()) { true -> "이"; false -> "가"; null -> "이(가)" }
internal fun String.withEulReul() = this + when (hasBatchim()) { true -> "을"; false -> "를"; null -> "을(를)" }
internal fun String.withEuroRo() = this + when {
    hasBatchim() == null -> "(으)로"
    hasBatchim() == true && !lastBatchimIsRieul() -> "으로"
    else -> "로"
}

/** 서술격 조사: "규동이에요", "라테예요". */
internal fun String.withCopula() = this + if (hasBatchim() == false) "예요" else "이에요"
