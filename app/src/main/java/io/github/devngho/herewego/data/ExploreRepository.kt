package io.github.devngho.herewego.data

import io.github.devngho.herewego.remote.RANK_RULES
import android.util.Log
import io.github.devngho.herewego.data.model.GeoPoint
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.domain.NearbyRecommendation
import io.github.devngho.herewego.navi.TripRefiner
import io.github.devngho.herewego.remote.ArrivedPlace
import io.github.devngho.herewego.remote.ExploreRequest
import io.github.devngho.herewego.remote.ExploreResponse
import io.github.devngho.herewego.remote.HereWeGoApi
import io.github.devngho.herewego.remote.LatLng
import io.github.devngho.herewego.remote.ProfileDto
import io.github.devngho.herewego.remote.isoToday
import io.github.devngho.herewego.remote.toPlace
import io.github.devngho.herewego.remote.toRecommendation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt

/** 서버가 돌려준 "지금 여기" 추천. */
data class RemoteExplore(
    /** 서버가 확인한 현재 장소 (앱이 도착한 장소를 알려 준 경우). */
    val here: Place?,
    val address: String,
    val thingsToDo: List<String>,
    val nextCourse: List<NearbyRecommendation>,
    val forYou: List<NearbyRecommendation>,
    val speech: String,
    /** LLM 이 고른 결과인지 (아니면 규칙 기반 순위). */
    val byLlm: Boolean = false,
)

sealed interface ExploreRemoteState {
    /** 서버 주소가 설정되지 않음. */
    data object Disabled : ExploreRemoteState
    data object Loading : ExploreRemoteState
    data class Loaded(val result: RemoteExplore) : ExploreRemoteState
    /** 서버에 닿지 못해 내장 샘플 데이터로 대체 중. */
    data object Failed : ExploreRemoteState
}

/**
 * Func 1 추천을 서버에서 받아 온다.
 *
 * 위치가 약 200m 이상 바뀌거나, 있는 장소·관심사·시간대가 바뀔 때만 다시 요청한다.
 * 위치 콜백이 올 때마다 요청하면 서버의 장소 검색 호출(유료)을 금방 쓴다.
 */
class ExploreRepository(
    private val api: HereWeGoApi?,
    location: StateFlow<EffectiveLocation>,
    profile: StateFlow<UserProfile>,
    private val clock: ClockRepository,
    scope: CoroutineScope,
    private val refiner: TripRefiner,
) {
    private data class Query(
        val latCell: Int,
        val lngCell: Int,
        val point: GeoPoint,
        val place: Place?,
        val profile: UserProfile,
        val hour: Int,
        val refresh: Int,
        val location: EffectiveLocation,
    ) {
        /** 같은 격자 안의 작은 움직임은 같은 요청으로 본다. */
        val key get() = listOf(latCell, lngCell, place?.id, profile.interests, profile.visitCounts, hour, refresh)
    }

    private val _state = MutableStateFlow<ExploreRemoteState>(if (api == null) ExploreRemoteState.Disabled else ExploreRemoteState.Loading)
    val state: StateFlow<ExploreRemoteState> = _state.asStateFlow()

    private val refreshCount = MutableStateFlow(0)

    init {
        if (api != null) scope.launch {
            combine(location, profile, clock.now, refreshCount) { loc, p, now, refresh ->
                Query(
                    latCell = (loc.point.latitude / CELL_DEGREES).roundToInt(),
                    lngCell = (loc.point.longitude / CELL_DEGREES).roundToInt(),
                    point = loc.point, place = loc.place, profile = p, hour = now.hour, refresh = refresh, location = loc,
                )
            }.distinctUntilChanged { a, b -> a.key == b.key }.collectLatest { q ->
                _state.value = ExploreRemoteState.Loading
                // 1) 규칙 기반 순위는 1초 안에 온다. 먼저 보여 준다.
                try {
                    show(q, api.explore(q.toRequest(clock.current(), RANK_RULES)).toResult())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "explore failed, using built-in data: $e")
                    _state.value = ExploreRemoteState.Failed
                    return@collectLatest
                }
                // 2) LLM 이 고른 결과는 몇 초 뒤에 온다. 실패하면 1)을 그대로 둔다.
                try {
                    val ranked = api.explore(q.toRequest(clock.current(), rank = null)).toResult()
                    if (ranked.byLlm) show(q, ranked)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "LLM-ranked explore failed, keeping rule-based result: $e")
                }
            }
        }
    }

    /** 서버 결과를 바로 보여 주고, 내비의 실제 주행 시간이 오면 화면에 보이는 4곳씩을 고친다. */
    private suspend fun show(q: Query, result: RemoteExplore) {
        _state.value = ExploreRemoteState.Loaded(result)
        if (!refiner.canRefine(q.location)) return
        _state.value = ExploreRemoteState.Loaded(
            result.copy(
                nextCourse = refiner.refineNearby(result.nextCourse.take(4)),
                forYou = refiner.refineNearby(result.forYou.take(4)),
            )
        )
    }

    /** 사용자가 새로고침을 눌렀을 때. */
    fun refresh() {
        refreshCount.value++
    }

    private fun Query.toRequest(now: LocalTime, rank: String?) = ExploreRequest(
        location = LatLng(point.latitude, point.longitude),
        now = isoToday(now),
        arrivedPlace = place?.let { ArrivedPlace(it.name, it.category) },
        profile = ProfileDto(profile.interests, profile.visitCounts),
        rank = rank,
    )

    private companion object {
        const val TAG = "ExploreRepository"

        /** 약 200m. */
        const val CELL_DEGREES = 0.002
    }
}

private fun ExploreResponse.toResult() = RemoteExplore(
    here = here?.toPlace(),
    address = address,
    thingsToDo = thingsToDo,
    nextCourse = nextCourse.mapNotNull { it.toRecommendation() },
    forYou = forYou.mapNotNull { it.toRecommendation() },
    speech = speech,
    byLlm = rankedBy == "llm",
)
