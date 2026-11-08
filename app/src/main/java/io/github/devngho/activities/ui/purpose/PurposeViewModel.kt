package io.github.devngho.activities.ui.purpose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.activities.AppContainer
import io.github.devngho.activities.data.EffectiveLocation
import io.github.devngho.activities.data.SamplePlaces
import io.github.devngho.activities.data.model.GeoPoint
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.data.model.Purpose
import io.github.devngho.activities.domain.Geo
import io.github.devngho.activities.domain.PurposeSuggestion
import io.github.devngho.activities.domain.RouteStop
import io.github.devngho.activities.domain.SpeechScripts
import io.github.devngho.activities.voice.VoiceState
import io.github.devngho.activities.ui.appViewModelFactory
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
)

private data class PurposeInput(
    val purpose: Purpose = Purpose.HOUSEWARMING,
    val destination: Place = SamplePlaces.friendHouse,
    val selectedIds: List<String> = emptyList(),
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

    val uiState: StateFlow<PurposeUiState> = combine(
        input, destinations, c.location.location, c.profile.profile, c.clock.now,
    ) { inp, dests, origin, profile, now ->
        val suggestions = c.engine.forPurpose(inp.purpose, origin.point, inp.destination, profile, now)
        val allStops = suggestions.flatMap { it.stops }
        PurposeUiState(
            purpose = inp.purpose,
            destination = inp.destination,
            destinations = dests,
            origin = origin,
            directMeters = Geo.distanceMeters(origin.point, inp.destination.location),
            suggestions = suggestions,
            selected = inp.selectedIds.mapNotNull { id -> allStops.firstOrNull { it.place.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PurposeUiState())

    fun selectPurpose(purpose: Purpose) = input.update { it.copy(purpose = purpose, selectedIds = emptyList()) }

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
        c.voice.speak(SpeechScripts.purpose(s.purpose, s.destination, s.suggestions, s.selected))
    }

    fun stopReading() = c.voice.stop()

    private fun naviPlace(id: String, name: String, address: String, lat: Double, lng: Double, poiId: String) =
        Place(
            id = id, name = name, category = PlaceCategory.LANDMARK, location = GeoPoint(lat, lng),
            address = address, rating = 0.0, stayMinutes = 0, poiId = poiId,
        )

    companion object {
        val Factory = appViewModelFactory { PurposeViewModel(it) }
    }
}
