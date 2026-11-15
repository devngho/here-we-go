package io.github.devngho.herewego.ui.schedule

import android.util.Log
import io.github.devngho.herewego.remote.toSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.devngho.herewego.remote.HereWeGoApi
import io.github.devngho.herewego.remote.RANK_RULES
import ai.pleos.playground.navi.data.TripInfo
import io.github.devngho.herewego.data.model.UserProfile
import io.github.devngho.herewego.remote.GapPlanRequest
import io.github.devngho.herewego.remote.Remote
import io.github.devngho.herewego.remote.fetchLatest
import io.github.devngho.herewego.remote.isoToday
import io.github.devngho.herewego.remote.toDto
import io.github.devngho.herewego.remote.toPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.herewego.AppContainer
import io.github.devngho.herewego.data.SamplePlaces
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.data.model.Schedule
import io.github.devngho.herewego.domain.GapPlan
import io.github.devngho.herewego.domain.GapStop
import io.github.devngho.herewego.domain.GapZone
import io.github.devngho.herewego.domain.Geo
import io.github.devngho.herewego.domain.SpeechScripts
import io.github.devngho.herewego.domain.hhmm
import io.github.devngho.herewego.voice.VoiceState
import io.github.devngho.herewego.ui.appViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import kotlin.math.ceil

data class ScheduleUiState(
    val a: Schedule,
    val b: Schedule,
    val actualEnd: LocalTime,
    val plan: GapPlan,
    /** 내비(requestTrip)로 받은 이동 시간. null 이면 직선거리 기반 추정치. */
    val naviTravelMinutes: Int?,
) {
    val endedEarlyBy: Int get() = java.time.Duration.between(actualEnd, a.expectedEnd).toMinutes().toInt()
}

private data class ScheduleInput(
    val a: Schedule = Schedule("판교 미팅", SamplePlaces.softwareDreamCenter, LocalTime.of(14, 0), LocalTime.of(16, 0)),
    val b: Schedule = Schedule("양재 저녁 약속", SamplePlaces.office42dot, LocalTime.of(17, 0), LocalTime.of(19, 0)),
    val actualEnd: LocalTime = LocalTime.of(15, 0),
    val useNaviEta: Boolean = false,
    /** 서버에 저장된 일정의 id. 아직 저장되지 않았으면 null. */
    val aId: String? = null,
    val bId: String? = null,
)

/** Func 3: 앞선 일정이 일찍 끝나 시간이 빌 때. */
class ScheduleViewModel(private val c: AppContainer) : ViewModel() {

    private val input = MutableStateFlow(ScheduleInput())

    val selectablePlaces: List<Place> =
        SamplePlaces.anchors + c.places.filter { it.category == PlaceCategory.RESTAURANT || it.category == PlaceCategory.CAFE }

    private data class RemoteKey(val a: Schedule, val b: Schedule, val actualEnd: LocalTime, val naviMinutes: Int?, val profile: UserProfile)

    private fun naviMinutes(inp: ScheduleInput, trip: TripInfo?): Int? =
        trip?.takeIf { inp.useNaviEta }?.let { ceil(it.eta / 60.0).toInt() }

    /** 서버(실제 장소)의 빈 시간 계획. 서버 주소가 없으면 요청하지 않는다. */
    private val remote: Flow<Remote<GapPlan>> = c.api?.let { api ->
        combine(input, c.profile.profile, c.navi.tripInfo) { inp, profile, trip ->
            RemoteKey(inp.a, inp.b, inp.actualEnd, naviMinutes(inp, trip), profile)
        }.fetchLatest(TAG, c::notifyServerDown, { key -> gapPlan(api, key, RANK_RULES) }, { key -> gapPlan(api, key, rank = null) })
    } ?: flowOf(Remote.Failed)

    private suspend fun gapPlan(api: HereWeGoApi, key: RemoteKey, rank: String?): GapPlan =
            api.gapPlan(
                GapPlanRequest(
                    a = key.a.toDto(), b = key.b.toDto(),
                    actualEnd = isoToday(key.actualEnd),
                    travelMinutesOverride = key.naviMinutes,
                    profile = key.profile.toDto(),
                    rank = rank,
                )
            ).toPlan(perZone = 3).let { plan ->
                // 차가 A 에 있을 때만 A → 들를 곳을 내비로 잴 수 있다.
                val here = c.location.location.value
                val atA = Geo.distanceMeters(here.point, key.a.place.location) <= AT_A_METERS
                if (atA && c.refiner.canRefine(here)) c.refiner.refineGap(key.a, key.b, key.actualEnd, plan) else plan
            }

    /** 일정 장소 검색. 서버가 없으면 null. */
    val searchPlaces = c.searchPlaces

    val uiState: StateFlow<ScheduleUiState> = combine(input, c.profile.profile, c.navi.tripInfo, remote) { inp, profile, trip, remote ->
        val naviMinutes = naviMinutes(inp, trip)
        // 서버 응답이 오기 전과 실패했을 때는 내장 엔진(샘플 데이터)의 계획을 보여 준다.
        (remote as? Remote.Loaded)?.let { return@combine ScheduleUiState(inp.a, inp.b, inp.actualEnd, it.value, naviMinutes) }
        ScheduleUiState(
            a = inp.a,
            b = inp.b,
            actualEnd = inp.actualEnd,
            plan = c.engine.forGap(inp.a, inp.b, inp.actualEnd, profile, travelMinutesOverride = naviMinutes, perZone = 3),
            naviTravelMinutes = naviMinutes,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        input.value.let { ScheduleUiState(it.a, it.b, it.actualEnd, c.engine.forGap(it.a, it.b, it.actualEnd, c.profile.profile.value), null) },
    )

    init {
        // 일정은 서버에 저장한다. 처음 두 개를 A, B 로 쓰고, 없으면 기본 일정을 올려 둔다.
        c.api?.let { api ->
            viewModelScope.launch {
                try {
                    val saved = api.schedules()
                    if (saved.size >= 2) {
                        input.update {
                            it.copy(a = saved[0].toSchedule("schedule_a"), aId = saved[0].id, b = saved[1].toSchedule("schedule_b"), bId = saved[1].id)
                        }
                    } else {
                        val a = api.addSchedule(input.value.a.toDto())
                        val b = api.addSchedule(input.value.b.toDto())
                        input.update { it.copy(aId = a.id, bId = b.id) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "schedules not loaded from server: $e")
                }
            }
        }
    }

    private fun save(id: String?, schedule: Schedule) {
        val api = c.api ?: return
        id ?: return
        viewModelScope.launch {
            try {
                api.updateSchedule(id, schedule.toDto())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "schedule not saved to server: $e")
            }
        }
    }

    fun updateA(schedule: Schedule) {
        input.update { it.copy(a = schedule, useNaviEta = false) }
        save(input.value.aId, schedule)
    }

    fun updateB(schedule: Schedule) {
        input.update { it.copy(b = schedule, useNaviEta = false) }
        save(input.value.bId, schedule)
    }
    fun setActualEnd(time: LocalTime) = input.update { it.copy(actualEnd = time) }

    /** "A 일정이 지금 끝났어요" */
    fun endNow() {
        val now = c.clock.current()
        input.update { it.copy(actualEnd = now) }
        c.showMessage("A 일정이 ${"%02d:%02d".format(now.hour, now.minute)}에 끝난 것으로 기록했어요")
    }

    /** 현재 위치(=A) → B 이동 시간을 내비게이션에 요청한다. */
    fun requestNaviEta() {
        c.navi.clearTrip()
        input.update { it.copy(useNaviEta = true) }
        c.navi.requestTrip(input.value.b.place)
    }

    fun start(stop: GapStop) {
        val b = input.value.b
        // 도착 알림에 보여줄 메모: 얼마나 머물고 언제 떠나야 하는지
        val stayNote = "${stop.stayMinutes}분 머물고 ${stop.leaveAt.hhmm()}에 출발하면 " +
            "${b.title}(${b.start.hhmm()})에 ${stop.arriveBAt.hhmm()} 도착해요"
        if (stop.zone == GapZone.NEAR_B) {
            // B 근처에 먼저 들렀다가 걸어서 B 로 간다.
            c.navi.requestRoute(stop.place, arrivalNote = stayNote)
        } else {
            c.navi.requestRouteWithWaypoints(
                destination = b.place,
                waypoints = listOf(stop.place),
                notes = mapOf(
                    stop.place.id to stayNote,
                    b.place.id to "${b.title}(${b.start.hhmm()}) 장소에 도착했어요",
                ),
            )
        }
    }

    fun goDirectly() = c.navi.requestRoute(input.value.b.place)

    val voice: StateFlow<VoiceState> = c.voice.state

    fun readAloud() {
        val s = uiState.value
        c.voice.speak(SpeechScripts.gap(s.a, s.b, s.plan))
    }

    fun stopReading() = c.voice.stop()

    companion object {
        private const val TAG = "ScheduleViewModel"
        private const val AT_A_METERS = 500.0
        val Factory = appViewModelFactory { ScheduleViewModel(it) }
    }
}
