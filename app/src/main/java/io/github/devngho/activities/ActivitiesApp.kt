package io.github.devngho.activities

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.devngho.activities.data.ClockRepository
import io.github.devngho.activities.data.LocationRepository
import io.github.devngho.activities.data.SamplePlaces
import io.github.devngho.activities.data.UserProfileRepository
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.domain.RecommendationEngine
import io.github.devngho.activities.domain.SpeechScripts
import io.github.devngho.activities.navi.NaviRepository
import io.github.devngho.activities.navi.TripEvent
import io.github.devngho.activities.trip.ParkingMonitor
import io.github.devngho.activities.trip.TripNotifications
import io.github.devngho.activities.trip.TripService
import io.github.devngho.activities.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 내비의 도착 안내 음성("목적지에 도착했습니다")이 끝날 때까지 기다리는 시간. */
private val NAVI_ARRIVAL_VOICE = 3.seconds

/** 도착 후 이 시간 안에 주차(P)하지 않으면(잠깐 내려 주고 떠나는 경우 등) 알림을 보내지 않는다. */
private val PARK_WAIT_DESTINATION = 10.minutes
private val PARK_WAIT_WAYPOINT = 5.minutes

private const val TAG = "AppContainer"

class ActivitiesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 간단한 수동 DI 컨테이너. */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = MainScope()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages
    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    /** 도착 알림을 눌렀을 때 등, "지금 여기" 탭으로 이동해야 할 때 신호. */
    private val _openExplore = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openExplore: SharedFlow<Unit> get() = _openExplore
    fun requestOpenExplore() {
        _openExplore.tryEmit(Unit)
    }

    val places = SamplePlaces.all
    val navi = NaviRepository(appContext, _messages)
    val profile = UserProfileRepository()
    val clock = ClockRepository()
    val location = LocationRepository(navi, places, appScope)
    val engine = RecommendationEngine(places)
    val voice = VoiceGuide(appContext)
    val parking = ParkingMonitor(appContext)

    /** 도착 후 주차를 기다리는 중인 알림. 새 안내를 시작하면 취소된다. */
    private var pendingArrival: Job? = null

    init {
        TripNotifications.createChannels(appContext)
        voice.initialize()

        // 사용자 분석: 내비 최근 목적지가 들어올 때마다 취향을 학습한다.
        navi.recentDestinations
            .drop(1)
            .onEach { recent ->
                val learned = profile.learnFrom(recent)
                if (learned > 0) showMessage("최근 목적지 ${learned}곳에서 취향을 학습했어요")
            }
            .launchIn(appScope)

        // 안내 요청 직후(앱이 아직 화면에 있을 때) 포그라운드 서비스를 시작해 동결을 막는다.
        navi.onTripRequested = {
            pendingArrival?.cancel()
            TripService.start(appContext)
        }

        navi.tripEvents
            .onEach { event ->
                when (event) {
                    is TripEvent.WaypointArrived -> {
                        val place = event.place
                        val note = event.trip.notes[place.id]
                        whenParked(place, PARK_WAIT_WAYPOINT) {
                            TripNotifications.notifyWaypoint(appContext, place, note)
                            voice.announce(SpeechScripts.waypoint(place, note?.let(SpeechScripts::spokenNote)))
                        }
                    }

                    is TripEvent.Arrived -> {
                        val dest = event.trip.destination
                        val note = event.trip.notes[dest.id]
                        // Func 1 로 이어진다: 도착한 장소 기준으로 "지금 여기" 추천이 갱신됨.
                        location.arrivedAt(dest)
                        requestOpenExplore()
                        whenParked(dest, PARK_WAIT_DESTINATION) {
                            TripNotifications.notifyArrived(appContext, dest, note)
                            showMessage("${dest.name}에 도착했어요")
                            voice.announce(arrivalBriefing(dest, note))
                        }
                    }

                    is TripEvent.Ended -> TripService.stop(appContext)
                }
            }
            .launchIn(appScope)
    }

    /**
     * 주행 중에는 앱 화면이 열리지 않으므로, 도착 알림·음성 안내는 주차(P)한 뒤에 보낸다.
     * 이미 주차 중이면 내비의 도착 음성이 끝난 뒤 바로 보낸다.
     * 기다리는 동안 프로세스가 동결되지 않도록 포그라운드 서비스는 끝난 뒤에 멈춘다.
     */
    private fun whenParked(place: Place, timeout: Duration, action: () -> Unit) {
        pendingArrival?.cancel()
        // 목적지 도착(안내 종료)이면 진행 중 알림을 "주차하면 알려 드릴게요"로 바꾼다.
        if (!parking.parked.value && navi.activeTrip.value == null) TripNotifications.waitingForPark(appContext, place)
        pendingArrival = appScope.launch {
            delay(NAVI_ARRIVAL_VOICE)
            val parked = withTimeoutOrNull(timeout) { parking.parked.first { it } }
            if (parked != null) action() else Log.i(TAG, "Not parked within $timeout at ${place.name}; skipping")
            // 경유지 이후에도 안내가 이어지면 서비스는 계속 필요하다.
            if (navi.activeTrip.value == null) TripService.stop(appContext)
        }
    }

    private fun arrivalBriefing(dest: Place, note: String?): String {
        val next = engine.nearby(dest, dest.location, profile.profile.value, clock.current(), personalWeight = 0.15, limit = 1)
        return SpeechScripts.arrival(dest, next, note?.let(SpeechScripts::spokenNote))
    }
}
