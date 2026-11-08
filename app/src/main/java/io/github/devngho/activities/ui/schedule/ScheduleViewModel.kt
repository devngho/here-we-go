package io.github.devngho.activities.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.devngho.activities.AppContainer
import io.github.devngho.activities.data.SamplePlaces
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.data.model.Schedule
import io.github.devngho.activities.domain.GapPlan
import io.github.devngho.activities.domain.GapStop
import io.github.devngho.activities.domain.GapZone
import io.github.devngho.activities.domain.SpeechScripts
import io.github.devngho.activities.domain.hhmm
import io.github.devngho.activities.voice.VoiceState
import io.github.devngho.activities.ui.appViewModelFactory
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
)

/** Func 3: 앞선 일정이 일찍 끝나 시간이 빌 때. */
class ScheduleViewModel(private val c: AppContainer) : ViewModel() {

    private val input = MutableStateFlow(ScheduleInput())

    val selectablePlaces: List<Place> =
        SamplePlaces.anchors + c.places.filter { it.category == PlaceCategory.RESTAURANT || it.category == PlaceCategory.CAFE }

    val uiState: StateFlow<ScheduleUiState> = combine(input, c.profile.profile, c.navi.tripInfo) { inp, profile, trip ->
        val naviMinutes = trip?.takeIf { inp.useNaviEta }?.let { ceil(it.eta / 60.0).toInt() }
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

    fun updateA(schedule: Schedule) = input.update { it.copy(a = schedule, useNaviEta = false) }
    fun updateB(schedule: Schedule) = input.update { it.copy(b = schedule, useNaviEta = false) }
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
        val Factory = appViewModelFactory { ScheduleViewModel(it) }
    }
}
