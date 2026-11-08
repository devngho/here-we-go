package io.github.devngho.activities.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.activities.AppContainer
import io.github.devngho.activities.data.EffectiveLocation
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.data.model.UserProfile
import io.github.devngho.activities.domain.NearbyRecommendation
import io.github.devngho.activities.domain.SpeechScripts
import io.github.devngho.activities.navi.NaviConnection
import io.github.devngho.activities.ui.appViewModelFactory
import io.github.devngho.activities.voice.VoiceState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime

data class ExploreUiState(
    val location: EffectiveLocation,
    val now: LocalTime,
    val thingsToDo: List<String>,
    /** 동선 궁합 위주: 지금 장소 다음 코스 */
    val nextCourse: List<NearbyRecommendation>,
    /** 취향 위주: 사용자 맞춤 */
    val forYou: List<NearbyRecommendation>,
    val topInterests: List<PlaceCategory>,
    val connection: NaviConnection,
    val preferNavi: Boolean,
)

/** Func 1: 특별한 의도 없이 어떤 장소에 왔을 때. */
class ExploreViewModel(private val c: AppContainer) : ViewModel() {

    val selectablePlaces: List<Place> =
        c.places.filter { it.category.recommendable || it.category == PlaceCategory.OFFICE }

    val uiState: StateFlow<ExploreUiState> = combine(
        c.location.location, c.clock.now, c.profile.profile, c.navi.connection, c.location.preferNavi,
    ) { loc, now, profile, conn, preferNavi ->
        compute(loc, now, profile, conn, preferNavi)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        compute(
            c.location.location.value, c.clock.current(), c.profile.profile.value,
            c.navi.connection.value, c.location.preferNavi.value,
        ),
    )

    private fun compute(
        loc: EffectiveLocation,
        now: LocalTime,
        profile: UserProfile,
        conn: NaviConnection,
        preferNavi: Boolean,
    ): ExploreUiState {
        // 차량 화면에서는 섹션당 4개(2열 × 2행)만 보여 주어 한눈에 파악되게 한다.
        val next = c.engine.nearby(loc.place, loc.point, profile, now, personalWeight = 0.15, limit = 4)
        val forYou = c.engine.nearby(
            loc.place, loc.point, profile, now,
            radiusMeters = 3_000.0,
            personalWeight = 0.55,
            limit = 4,
            exclude = next.map { it.place.id }.toSet(),
        )
        return ExploreUiState(
            location = loc,
            now = now,
            thingsToDo = loc.place?.let { c.engine.thingsToDo(it, now) }.orEmpty().take(4),
            nextCourse = next,
            forYou = forYou,
            topInterests = profile.topCategories(),
            connection = conn,
            preferNavi = preferNavi,
        )
    }

    fun moveTo(place: Place) = c.location.moveTo(place)

    fun setPreferNavi(value: Boolean) {
        c.location.setPreferNavi(value)
        if (value) c.navi.refresh()
    }

    fun refreshLocation() = c.navi.refresh()

    val voice: StateFlow<VoiceState> = c.voice.state

    fun readAloud() {
        val s = uiState.value
        c.voice.speak(SpeechScripts.explore(s.location.place, s.location.address, s.nextCourse, s.forYou))
    }

    fun stopReading() = c.voice.stop()

    companion object {
        val Factory = appViewModelFactory { ExploreViewModel(it) }
    }
}
