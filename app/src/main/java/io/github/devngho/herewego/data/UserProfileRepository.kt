package io.github.devngho.herewego.data

import ai.pleos.playground.navi.data.RecentDestinationInfo
import io.github.devngho.herewego.data.model.LearnedPlace
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 사용자 분석 저장소 (프로토타입: 메모리 저장).
 *
 * 사용자가 고른 관심사 + 방문 기록을 합쳐 카테고리 선호도를 만든다. 방문 기록은 내비게이션의
 * 최근 목적지([RecentDestinationInfo])를 이름 키워드로 분류해 학습한다.
 */
class UserProfileRepository {

    private val initial = UserProfile(
        interests = setOf(PlaceCategory.CAFE, PlaceCategory.PARK, PlaceCategory.GYM),
        visitCounts = mapOf(
            PlaceCategory.CAFE to 8,
            PlaceCategory.RESTAURANT to 6,
            PlaceCategory.GYM to 5,
            PlaceCategory.PARK to 3,
            PlaceCategory.BOOKSTORE to 2,
        ),
    )

    private val _profile = MutableStateFlow(initial)
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    fun toggleInterest(category: PlaceCategory) = _profile.update {
        it.copy(interests = if (category in it.interests) it.interests - category else it.interests + category)
    }

    /** 서버에 저장돼 있던 프로필로 바꾼다 (앱을 다시 켰을 때). */
    fun replace(profile: UserProfile) {
        _profile.value = profile
    }

    fun reset() {
        _profile.value = initial
    }

    /** @return 새로 학습한 장소 수 */
    fun learnFrom(recent: List<RecentDestinationInfo>): Int {
        var learned = 0
        _profile.update { profile ->
            val known = profile.learnedPlaces.map { it.name }.toSet()
            val newOnes = recent.mapNotNull { r ->
                if (r.name in known) null else classify(r.name)?.let { LearnedPlace(r.name, it) }
            }
            learned = newOnes.size
            val counts = profile.visitCounts.toMutableMap()
            newOnes.forEach { counts[it.category] = (counts[it.category] ?: 0) + 1 }
            profile.copy(visitCounts = counts, learnedPlaces = newOnes + profile.learnedPlaces)
        }
        return learned
    }

    companion object {
        private val KEYWORDS: List<Pair<Regex, PlaceCategory>> = listOf(
            Regex("카페|커피|coffee|스타벅스|투썸|이디야", RegexOption.IGNORE_CASE) to PlaceCategory.CAFE,
            Regex("헬스|피트니스|짐|gym|크로스핏|요가|필라테스|수영장", RegexOption.IGNORE_CASE) to PlaceCategory.GYM,
            Regex("공원|숲|산책|둘레길|천$") to PlaceCategory.PARK,
            Regex("서점|책방|문고|도서관") to PlaceCategory.BOOKSTORE,
            Regex("베이커리|빵|제과|bakery", RegexOption.IGNORE_CASE) to PlaceCategory.BAKERY,
            Regex("식당|맛집|국수|고기|밥|분식|레스토랑") to PlaceCategory.RESTAURANT,
            Regex("미술관|박물관|전시|갤러리|아트") to PlaceCategory.GALLERY,
            Regex("마트|market|시장", RegexOption.IGNORE_CASE) to PlaceCategory.MART,
            Regex("꽃") to PlaceCategory.FLORIST,
            Regex("충전") to PlaceCategory.EV_CHARGING,
        )

        fun classify(name: String): PlaceCategory? = KEYWORDS.firstOrNull { (re, _) -> re.containsMatchIn(name) }?.second
    }
}
