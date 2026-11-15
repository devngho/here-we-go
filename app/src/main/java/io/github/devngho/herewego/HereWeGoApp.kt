package io.github.devngho.herewego

import io.github.devngho.herewego.remote.toDto
import io.github.devngho.herewego.remote.toProfile
import kotlinx.coroutines.flow.collectLatest
import android.app.Application
import android.content.Context
import android.util.Log
import io.github.devngho.herewego.data.ClockRepository
import io.github.devngho.herewego.data.ExploreRemoteState
import io.github.devngho.herewego.data.ExploreRepository
import io.github.devngho.herewego.data.LocationRepository
import io.github.devngho.herewego.data.SamplePlaces
import io.github.devngho.herewego.data.UserProfileRepository
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.domain.RecommendationEngine
import io.github.devngho.herewego.domain.SpeechScripts
import io.github.devngho.herewego.navi.NaviRepository
import io.github.devngho.herewego.navi.TripEvent
import io.github.devngho.herewego.navi.TripRefiner
import io.github.devngho.herewego.remote.HereWeGoApi
import io.github.devngho.herewego.remote.toLatLng
import io.github.devngho.herewego.remote.toPlace
import io.github.devngho.herewego.trip.ParkingMonitor
import io.github.devngho.herewego.trip.TripNotifications
import io.github.devngho.herewego.trip.TripService
import io.github.devngho.herewego.voice.VoiceGuide
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

class HereWeGoApp : Application() {
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
    /** 추천 서버. local.properties 에 주소가 없으면 null 이고 앱은 내장 샘플 데이터만 쓴다. */
    val api: HereWeGoApi? = BuildConfig.SERVER_URL.takeIf { it.isNotBlank() }
        ?.let { HereWeGoApi(appContext, it, BuildConfig.SERVER_APP_KEY) }
    val location = LocationRepository(navi, places, appScope, hasRemoteData = api != null)
    val refiner = TripRefiner(navi)
    val explore = ExploreRepository(api, location.location, profile.profile, clock, appScope, refiner)

    private var lastServerDownMessage = 0L

    /** 서버 요청이 실패해 샘플 데이터로 대체했음을 알린다. 화면을 오갈 때마다 반복되지 않게 1분에 한 번만. */
    fun notifyServerDown(cause: Exception? = null) {
        val now = System.currentTimeMillis()
        if (now - lastServerDownMessage < 60_000) return
        lastServerDownMessage = now
        showMessage("서버에 연결하지 못해 샘플 장소를 보여 드려요")
    }

    /** 이름으로 실제 장소를 찾는다 (목적지·일정 장소 선택). 서버가 없으면 null. */
    val searchPlaces: (suspend (String) -> List<Place>)? = api?.let { api ->
        { query -> api.searchPlaces(query, location.location.value.point.toLatLng()).mapNotNull { it.toPlace() } }
    }
    val engine = RecommendationEngine(places)
    val voice = VoiceGuide(appContext)
    val parking = ParkingMonitor(appContext)

    /** 도착 후 주차를 기다리는 중인 알림. 새 안내를 시작하면 취소된다. */
    private var pendingArrival: Job? = null

    init {
        TripNotifications.createChannels(appContext)
        voice.initialize()

        // 프로필은 앱 메모리에만 있어서 앱을 끄면 사라진다. 서버에 저장해 두고 시작할 때 되살린다.
        api?.let { api ->
            appScope.launch {
                try {
                    val saved = api.profile()
                    if (saved.interests.isNotEmpty() || saved.visitCounts.isNotEmpty()) profile.replace(saved.toProfile())
                    else api.saveProfile(profile.profile.value.toDto())
                } catch (e: Exception) {
                    Log.w(TAG, "profile not loaded from server: $e")
                }
                profile.profile.drop(1).collectLatest { changed ->
                    delay(500) // 관심사를 연달아 누를 때는 마지막 상태만 보낸다.
                    try {
                        api.saveProfile(changed.toDto())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "profile not saved to server: $e")
                    }
                }
            }
        }

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
        val next = when (val remote = explore.state.value) {
            // 서버가 도착한 장소 기준으로 이미 답했으면 그 추천을 말한다.
            is ExploreRemoteState.Loaded ->
                if (remote.result.here?.name == dest.name) remote.result.nextCourse.take(1) else emptyList()
            // 아직 받는 중이면 내장 샘플 장소를 말하지 않고 도착 안내만 한다.
            ExploreRemoteState.Loading -> emptyList()
            ExploreRemoteState.Disabled, ExploreRemoteState.Failed ->
                engine.nearby(dest, dest.location, profile.profile.value, clock.current(), personalWeight = 0.15, limit = 1)
        }
        return SpeechScripts.arrival(dest, next, note?.let(SpeechScripts::spokenNote))
    }
}
