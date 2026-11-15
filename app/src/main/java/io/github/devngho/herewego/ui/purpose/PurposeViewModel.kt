package io.github.devngho.herewego.ui.purpose

import io.github.devngho.herewego.remote.HereWeGoApi
import io.github.devngho.herewego.remote.RANK_RULES
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.remote.PurposePlanRequest
import io.github.devngho.herewego.remote.Remote
import io.github.devngho.herewego.remote.fetchLatest
import io.github.devngho.herewego.remote.isoToday
import io.github.devngho.herewego.remote.toDestination
import io.github.devngho.herewego.remote.toDto
import io.github.devngho.herewego.remote.toLatLng
import io.github.devngho.herewego.remote.toSuggestions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.herewego.AppContainer
import io.github.devngho.herewego.data.EffectiveLocation
import io.github.devngho.herewego.data.SamplePlaces
import io.github.devngho.herewego.data.model.GeoPoint
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.Purpose
import io.github.devngho.herewego.domain.Geo
import io.github.devngho.herewego.domain.PurposeSuggestion
import io.github.devngho.herewego.domain.RouteStop
import io.github.devngho.herewego.domain.SpeechScripts
import io.github.devngho.herewego.voice.VoiceState
import io.github.devngho.herewego.ui.appViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class PurposeUiState(
    val purpose: Purpose = Purpose.HOUSEWARMING,
    val destination: Place = SamplePlaces.friendHouse,
    val destinations: List<Place> = SamplePlaces.anchors,
    val origin: EffectiveLocation? = null,
    val directMeters: Double = 0.0,
    val suggestions: List<PurposeSuggestion> = emptyList(),
    /** 경유지로 고른 장소 (최대 2) */
    val selected: List<RouteStop> = emptyList(),
    /** 사용자가 직접 입력한 목적. null 이면 [purpose] 프리셋을 쓴다. */
    val customPurpose: String? = null,
    /** 서버가 줄여 준 목적 이름 ("친구 결혼식"). */
    val customLabel: String? = null,
    /** 직접 입력한 목적의 결과를 기다리는 중이거나(true) 받지 못했는지(false). 결과가 있으면 null. */
    val customPending: Boolean? = null,
    val speech: String? = null,
)

private data class PurposeInput(
    val purpose: Purpose = Purpose.HOUSEWARMING,
    val destination: Place = SamplePlaces.friendHouse,
    val selectedIds: List<String> = emptyList(),
    val customPurpose: String? = null,
)

/** Func 2: 특정 의도를 가지고 목적지로 갈 때. */
class PurposeViewModel(private val c: AppContainer) : ViewModel() {

    private val input = MutableStateFlow(PurposeInput())

    /** 앵커 + 내비 현재 목적지 + 내비 최근 목적지 */
    private val destinations = combine(c.navi.destination, c.navi.recentDestinations) { current, recent ->
        val fromNavi = buildList {
            // 경로가 없으면 내비는 이름이 빈 DestinationInfo 를 돌려준다.
            current?.takeIf { it.name.isNotBlank() }
                ?.let { add(naviPlace("navi_dest", "현재 목적지 · ${it.name}", it.address, it.latitude, it.longitude, it.poiId)) }
            recent.filter { it.name.isNotBlank() }.forEachIndexed { i, r ->
                add(naviPlace("navi_recent_$i", r.name, r.address, r.latitude, r.longitude, r.poiId))
            }
        }
        (fromNavi + SamplePlaces.anchors).distinctBy { it.name }
    }

    /** 내장 엔진(샘플 데이터)으로 바로 계산한 결과. 서버 응답이 오기 전과 실패했을 때 쓴다. */
    private val local = combine(
        input, destinations, c.location.location, c.profile.profile, c.clock.now,
    ) { inp, dests, origin, profile, now ->
        inp to PurposeUiState(
            purpose = inp.purpose,
            destination = inp.destination,
            destinations = dests,
            origin = origin,
            directMeters = Geo.distanceMeters(origin.point, inp.destination.location),
            suggestions = c.engine.forPurpose(inp.purpose, origin.point, inp.destination, profile, now),
        )
    }

    private class RemotePurpose(val suggestions: List<PurposeSuggestion>, val label: String?, val speech: String?)

    private data class RemoteKey(
        val purpose: Purpose,
        val customPurpose: String?,
        val destination: Place,
        /** 약 200m 격자. 주행 중 위치가 조금 바뀔 때마다 다시 묻지 않는다. */
        val originCell: Pair<Int, Int>,
        val profile: UserProfile,
        val hour: Int,
    )

    /** 서버(실제 장소)의 추천. 서버 주소가 없으면 요청하지 않는다. */
    private val remote: Flow<Remote<RemotePurpose>> = c.api?.let { api ->
        combine(input, c.location.location, c.profile.profile, c.clock.now) { inp, origin, profile, now ->
            RemoteKey(
                inp.purpose, inp.customPurpose, inp.destination,
                (origin.point.latitude / 0.002).roundToInt() to (origin.point.longitude / 0.002).roundToInt(),
                profile, now.hour,
            )
        }.fetchLatest(
            TAG,
            // 직접 입력한 목적은 서버가 이해하지 못한 것일 수 있다. 화면에 따로 안내하므로 연결 실패 메시지는 띄우지 않는다.
            { e -> if (input.value.customPurpose == null) c.notifyServerDown(e) },
            { key -> purposePlan(api, key, RANK_RULES) },
            { key -> purposePlan(api, key, rank = null) },
        )
    } ?: flowOf(Remote.Failed)

    private suspend fun purposePlan(api: HereWeGoApi, key: RemoteKey, rank: String?): RemotePurpose {
        val response = api.purposePlan(
            PurposePlanRequest(
                origin = c.location.location.value.point.toLatLng(),
                destination = key.destination.toDestination(),
                purposeId = key.purpose.name.takeIf { key.customPurpose == null },
                purposeText = key.customPurpose,
                now = isoToday(c.clock.current()),
                profile = key.profile.toDto(),
                rank = rank,
            )
        )
        val origin = c.location.location.value
        val suggestions = response.toSuggestions(perNeed = 3)
            .let { if (c.refiner.canRefine(origin)) c.refiner.refineStops(origin, key.destination, it) else it }
        return RemotePurpose(suggestions, response.purpose?.label, response.speech.ifBlank { null })
    }

    val uiState: StateFlow<PurposeUiState> = combine(local, remote) { (inp, state), remote ->
        val loaded = (remote as? Remote.Loaded)?.value
        // 직접 입력한 목적은 내장 엔진이 다룰 수 없다. 서버 결과가 없으면 비워 둔다.
        val suggestions = loaded?.suggestions ?: if (inp.customPurpose != null) emptyList() else state.suggestions
        val allStops = suggestions.flatMap { it.stops }
        state.copy(
            suggestions = suggestions,
            selected = inp.selectedIds.mapNotNull { id -> allStops.firstOrNull { it.place.id == id } },
            customPurpose = inp.customPurpose,
            customLabel = loaded?.label?.takeIf { inp.customPurpose != null },
            customPending = when {
                inp.customPurpose == null || loaded != null -> null
                else -> remote is Remote.Loading
            },
            speech = loaded?.speech,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PurposeUiState())

    /** 목적지 검색. 서버가 없으면 null 이고 화면은 검색창을 보여 주지 않는다. */
    val searchPlaces = c.searchPlaces

    fun selectPurpose(purpose: Purpose) = input.update { it.copy(purpose = purpose, customPurpose = null, selectedIds = emptyList()) }

    /** 목록에 없는 목적을 직접 입력한다 ("친구 결혼식 가는 길"). 서버가 있을 때만 쓸 수 있다. */
    fun setCustomPurpose(text: String) {
        val trimmed = text.trim().take(100)
        if (trimmed.isNotEmpty()) input.update { it.copy(customPurpose = trimmed, selectedIds = emptyList()) }
    }

    val canEnterPurpose: Boolean = c.api != null

    fun selectDestination(place: Place) = input.update { it.copy(destination = place, selectedIds = emptyList()) }

    fun toggleStop(stop: RouteStop) {
        val ids = input.value.selectedIds
        when {
            stop.place.id in ids -> input.update { it.copy(selectedIds = ids - stop.place.id) }
            ids.size >= 2 -> c.showMessage("경유지는 최대 2곳까지 추가할 수 있어요")
            else -> input.update { it.copy(selectedIds = ids + stop.place.id) }
        }
    }

    fun startGuidance() {
        val state = uiState.value
        c.navi.requestRouteWithWaypoints(
            destination = state.destination,
            waypoints = state.selected.sortedBy { it.progress }.map { it.place },
        )
    }

    val voice: StateFlow<VoiceState> = c.voice.state

    fun readAloud() {
        val s = uiState.value
        // 경유지를 고르기 전에는 서버가 만든 요약을, 고른 뒤에는 고른 곳을 알려 주는 문장을 읽는다.
        val label = if (s.customPurpose != null) s.customLabel ?: s.customPurpose else s.purpose.label
        c.voice.speak(s.speech?.takeIf { s.selected.isEmpty() } ?: SpeechScripts.purpose(label, s.destination, s.suggestions, s.selected))
    }

    fun stopReading() = c.voice.stop()

    private fun naviPlace(id: String, name: String, address: String, lat: Double, lng: Double, poiId: String) =
        Place(
            id = id, name = name, category = PlaceCategory.LANDMARK, location = GeoPoint(lat, lng),
            address = address, rating = 0.0, stayMinutes = 0, poiId = poiId,
        )

    companion object {
        private const val TAG = "PurposeViewModel"
        val Factory = appViewModelFactory { PurposeViewModel(it) }
    }
}
