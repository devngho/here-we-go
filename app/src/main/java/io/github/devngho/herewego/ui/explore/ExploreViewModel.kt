package io.github.devngho.herewego.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.herewego.AppContainer
import io.github.devngho.herewego.data.EffectiveLocation
import io.github.devngho.herewego.data.ExploreRemoteState
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.domain.NearbyRecommendation
import io.github.devngho.herewego.domain.SpeechScripts
import io.github.devngho.herewego.navi.NaviConnection
import io.github.devngho.herewego.ui.appViewModelFactory
import io.github.devngho.herewego.voice.VoiceState
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
    /** 추천이 서버(실제 장소)에서 왔는지, 내장 샘플 데이터인지. */
    val remote: ExploreRemoteState = ExploreRemoteState.Disabled,
    /** 서버가 만든 음성 요약. 없으면 앱에서 만든다. */
    val speech: String? = null,
)

/** Func 1: 특별한 의도 없이 어떤 장소에 왔을 때. */
class ExploreViewModel(private val c: AppContainer) : ViewModel() {

    val selectablePlaces: List<Place> =
        c.places.filter { it.category.recommendable || it.category == PlaceCategory.OFFICE }

    private val local = combine(
        c.location.location, c.clock.now, c.profile.profile, c.navi.connection, c.location.preferNavi,
    ) { loc, now, profile, conn, preferNavi ->
        compute(loc, now, profile, conn, preferNavi)
    }

    /** 서버 결과가 있으면 그것을, 없거나 실패하면 내장 엔진의 결과를 보여 준다. */
    val uiState: StateFlow<ExploreUiState> = combine(local, c.explore.state) { state, remote ->
        if (remote !is ExploreRemoteState.Loaded) return@combine state.copy(remote = remote)
        val r = remote.result
        state.copy(
            location = state.location.copy(
                place = state.location.place ?: r.here,
                address = state.location.address.ifBlank { r.address },
            ),
            thingsToDo = r.thingsToDo,
            // 서버는 내비 ETA 로 걸러낼 여유분까지 보낸다. 화면에는 섹션당 4개만 보여 준다.
            nextCourse = r.nextCourse.take(4),
            forYou = r.forYou.take(4),
            remote = remote,
            speech = r.speech.ifBlank { null },
        )
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

    fun refreshLocation() {
        c.navi.refresh()
        c.explore.refresh()
    }

    val voice: StateFlow<VoiceState> = c.voice.state

    fun readAloud() {
        val s = uiState.value
        c.voice.speak(s.speech ?: SpeechScripts.explore(s.location.place, s.location.address, s.nextCourse, s.forYou))
    }

    fun stopReading() = c.voice.stop()

    companion object {
        val Factory = appViewModelFactory { ExploreViewModel(it) }
    }
}
