package io.github.devngho.herewego.server.model

import kotlin.math.min

/**
 * 사용자 취향 모델.
 *
 * - [interests]: 사용자가 직접 고른 관심 카테고리
 * - [visitCounts]: 방문 기록(내비 최근 목적지 학습 포함)으로부터 집계한 카테고리별 방문 수
 */
data class UserProfile(
    val interests: Set<PlaceCategory>,
    val visitCounts: Map<PlaceCategory, Int>,
    val learnedPlaces: List<LearnedPlace> = emptyList(),
) {
    /** 0.0 ~ 1.0 범위의 카테고리 선호도. */
    fun affinity(category: PlaceCategory): Double {
        val base = if (category in interests) 0.6 else 0.15
        val visits = visitCounts[category] ?: 0
        return min(1.0, base + min(0.4, visits / 20.0))
    }

    /** 방문 비중이 높은 상위 카테고리. */
    fun topCategories(limit: Int = 3): List<PlaceCategory> =
        PlaceCategory.entries
            .filter { it.recommendable }
            .sortedByDescending { affinity(it) }
            .take(limit)
}

/** 내비 최근 목적지에서 학습된 장소. */
data class LearnedPlace(val name: String, val category: PlaceCategory)
