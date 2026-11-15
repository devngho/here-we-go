package io.github.devngho.herewego.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.domain.withEulReul
import io.github.devngho.herewego.domain.withEuroRo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

enum class NaviConnection { CONNECTING, CONNECTED, UNAVAILABLE }

/** 이 앱이 내비에 요청한 안내. 도착 콜백을 앱의 장소(Place)와 연결하기 위해 보관한다. */
data class ActiveTrip(
    val destination: Place,
    val waypoints: List<Place> = emptyList(),
    /** 장소 id → 도착 시 보여줄 메모 (예: "40분 머물고 15:44 출발") */
    val notes: Map<String, String> = emptyMap(),
    /** 내비가 onRouteStarted 로 경로를 확정했는지 */
    val started: Boolean = false,
)

sealed interface TripEvent {
    val trip: ActiveTrip
    data class WaypointArrived(override val trip: ActiveTrip, val place: Place) : TripEvent
    data class Arrived(override val trip: ActiveTrip) : TripEvent
    /** 취소되었거나 다른 경로로 바뀜 */
    data class Ended(override val trip: ActiveTrip) : TripEvent
}

/**
 * Pleos NaviHelper SDK 래퍼.
 *
 * NaviHelper 는 요청(request/get) → [NaviHelperEventListener] 콜백 구조이므로,
 * 콜백으로 들어오는 값을 StateFlow 로 노출해 Compose 에서 바로 구독할 수 있게 한다.
 * 내비 서비스가 없는 환경(일반 에뮬레이터·폰)에서는 [NaviConnection.UNAVAILABLE] 상태가 되고
 * 모든 요청은 "데모 모드" 메시지로 대체된다.
 */
class NaviRepository(
    private val context: Context,
    /** 스낵바로 띄울 사용자 메시지 채널 (앱 전역 공유). */
    private val messages: MutableSharedFlow<String>,
) : NaviHelperEventListener {

    private var helper: NaviHelper? = null

    private val _connection = MutableStateFlow(NaviConnection.CONNECTING)
    val connection: StateFlow<NaviConnection> = _connection.asStateFlow()

    private val _currentLocation = MutableStateFlow<CurrentLocationInfo?>(null)
    val currentLocation: StateFlow<CurrentLocationInfo?> = _currentLocation.asStateFlow()

    private val _destination = MutableStateFlow<DestinationInfo?>(null)
    val destination: StateFlow<DestinationInfo?> = _destination.asStateFlow()

    private val _recentDestinations = MutableStateFlow<List<RecentDestinationInfo>>(emptyList())
    val recentDestinations: StateFlow<List<RecentDestinationInfo>> = _recentDestinations.asStateFlow()

    private val _navigationStatus = MutableStateFlow<NavigationStatus?>(null)
    val navigationStatus: StateFlow<NavigationStatus?> = _navigationStatus.asStateFlow()

    private val _tripInfo = MutableStateFlow<TripInfo?>(null)
    val tripInfo: StateFlow<TripInfo?> = _tripInfo.asStateFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    val isConnected: Boolean get() = _connection.value == NaviConnection.CONNECTED

    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    val activeTrip: StateFlow<ActiveTrip?> = _activeTrip.asStateFlow()

    private val _tripEvents = MutableSharedFlow<TripEvent>(extraBufferCapacity = 8)
    val tripEvents: SharedFlow<TripEvent> = _tripEvents.asSharedFlow()

    /**
     * 앱이 안내를 요청한 직후 **동기적으로** 호출된다(호출 시점에 앱이 아직 화면에 있음).
     * 내비가 전면에 뜨면 이 앱은 캐시 상태로 동결(freezer)되어 바인더 콜백을 못 받으므로,
     * 여기서 포그라운드 서비스를 시작해 도착 이벤트를 받을 수 있게 한다.
     */
    var onTripRequested: ((ActiveTrip) -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle

    fun connect() {
        if (helper != null) {
            if (_connection.value != NaviConnection.UNAVAILABLE) return
            release() // 재연결
        }
        _connection.value = NaviConnection.CONNECTING
        log("initialize")
        try {
            val h = NaviHelper(context.applicationContext)
            h.addListener(this)
            h.initialize(
                onConnected = {
                    log("connected")
                    _connection.value = NaviConnection.CONNECTED
                    refresh()
                },
                onFailed = { e ->
                    log("connect failed: ${e.message}")
                    _connection.value = NaviConnection.UNAVAILABLE
                },
            )
            helper = h
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper initialize failed", e)
            log("initialize failed: ${e.javaClass.simpleName}")
            _connection.value = NaviConnection.UNAVAILABLE
        }
    }

    fun release() {
        helper?.let {
            runCatching {
                it.removeListener(this)
                it.release()
            }
        }
        helper = null
    }

    /** 현재 위치·목적지·최근 목적지를 다시 요청한다. 결과는 리스너로 들어온다. */
    fun refresh() = call("refresh") {
        getCurrentLocationInfo()
        getDestinationInfo()
        getRecentDestinationInfo()
    }

    // ------------------------------------------------------------------ requests

    fun requestRoute(place: Place, arrivalNote: String? = null) {
        val ok = call("requestRoute", "${place.name.withEuroRo()} 길안내를 요청했어요") {
            requestRoute(place.toRouteInfo())
        }
        if (ok) beginTrip(ActiveTrip(place, notes = listOfNotNull(arrivalNote?.let { place.id to it }).toMap()))
    }

    /** SDK 는 최대 2개의 경유지(FIRST, SECOND)를 지원한다. */
    fun requestRouteWithWaypoints(
        destination: Place,
        waypoints: List<Place>,
        notes: Map<String, String> = emptyMap(),
    ) {
        if (waypoints.isEmpty()) return requestRoute(destination, notes[destination.id])
        val indices = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
        val request = RouteWithWaypointsRequest(
            destination = destination.toRouteInfo(),
            waypoints = waypoints.take(2).mapIndexed { i, p -> p.toWaypointInfo(indices[i]) },
        )
        val ok = call(
            "requestRouteWithWaypoints",
            "경유지 ${waypoints.size.coerceAtMost(2)}곳을 포함해 ${destination.name.withEuroRo()} 안내를 요청했어요",
        ) { requestRouteWithWaypoints(request) }
        if (ok) beginTrip(ActiveTrip(destination, waypoints.take(2), notes))
    }

    private fun beginTrip(trip: ActiveTrip) {
        _activeTrip.value = trip
        onTripRequested?.invoke(trip)
    }

    private fun endTrip(event: (ActiveTrip) -> TripEvent) {
        val trip = _activeTrip.value ?: return
        _activeTrip.value = null
        _tripEvents.tryEmit(event(trip))
    }

    fun addWaypoint(place: Place) = call("addWaypoint", "${place.name.withEulReul()} 경유지로 추가했어요") {
        addWaypoint(place.toWaypointInfo(WaypointIndex.FIRST))
    }

    /** 내비 앱의 장소 검색 화면을 연다. */
    fun showInNavi(place: Place) = call("requestNavigationScreen", "내비게이션에서 ${place.name.withEulReul()} 검색했어요") {
        requestNavigationScreen(
            if (place.poiId.isNotEmpty()) NavigationScreenRequest.POIDetailPopup(place.poiId)
            else NavigationScreenRequest.SearchPlace(place.name)
        )
    }

    /** 현재 위치 → place 의 예상 소요 시간 요청. 결과는 [tripInfo]. */
    fun requestTrip(place: Place) = call("requestTrip", "내비게이션에 '${place.name}'까지 예상 시간을 요청했어요") {
        requestTrip(
            TripRequest(
                latitude = place.location.latitude,
                longitude = place.location.longitude,
                poiId = place.poiId,
                poiName = place.name,
                poiSubId = "0",
            )
        )
    }

    private val measureLock = Mutex()

    /** [measureTrip] 이 기다리는 응답. TripInfo 에는 요청 ID 가 없어서 한 번에 하나만 기다린다. */
    @Volatile
    private var pendingMeasure: CompletableDeferred<TripInfo?>? = null

    /**
     * 현재 위치 → [place] 의 실제 주행 시간·거리를 내비에 묻는다 (화면 메시지 없이).
     *
     * 요청은 한 번에 하나씩 순서대로 처리한다. 내비가 없거나(데모 모드) 시간 안에 답이 없으면 null.
     * 출발지는 항상 차량의 현재 위치라서 '경유지 → 목적지' 구간은 잴 수 없다.
     */
    suspend fun measureTrip(place: Place, timeoutMillis: Long = 4_000): TripInfo? = measureLock.withLock {
        val waiting = CompletableDeferred<TripInfo?>()
        pendingMeasure = waiting
        val started = System.nanoTime()
        try {
            val sent = call("requestTrip(measure)") {
                requestTrip(TripRequest(place.location.latitude, place.location.longitude, place.poiId, place.name, "0"))
            }
            if (!sent) return@withLock null
            withTimeoutOrNull(timeoutMillis) { waiting.await() }.also {
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.d(TAG, "measureTrip ${place.name}: ${it?.let { t -> "eta=${t.eta}s distance=${t.distance}m" } ?: "no answer"} in ${ms}ms")
            }
        } finally {
            pendingMeasure = null
        }
    }

    fun clearTrip() {
        _tripInfo.value = null
    }

    /** @return 실제로 내비에 요청을 보냈으면 true (데모 모드·실패 시 false) */
    private inline fun call(api: String, successMessage: String? = null, block: NaviHelper.() -> Unit): Boolean {
        val h = helper
        if (h == null || !isConnected) {
            log("$api (demo)")
            successMessage?.let { messages.tryEmit("데모 모드 · $it") }
            return false
        }
        return try {
            h.block()
            log(api)
            successMessage?.let { messages.tryEmit(it) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "$api failed", e)
            log("$api failed: ${e.javaClass.simpleName}")
            messages.tryEmit("내비게이션 요청 실패: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        _eventLog.update { (listOf("${LocalTime.now().withNano(0)}  $line") + it).take(60) }
    }

    // ------------------------------------------------------------------ NaviHelperEventListener

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        log("onCurrentLocationInfo ${currentLocationInfo.address}")
        _currentLocation.value = currentLocationInfo
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        log("onDestinationInfo ${destinationInfo.name}")
        _destination.value = destinationInfo
    }

    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
        log("onRecentDestinationInfo (${recentDestinationInfoList.size})")
        _recentDestinations.value = recentDestinationInfoList
    }

    override fun onNavigationStatus(status: NavigationStatus) {
        log("onNavigationStatus $status")
        _navigationStatus.value = status
    }

    override fun onTripInfo(tripInfo: TripInfo) {
        log("onTripInfo eta=${tripInfo.eta}s distance=${tripInfo.distance}m")
        // measureTrip 이 기다리는 중이면 그 요청의 답이다. 화면의 '내비 시간으로 보정' 값은 건드리지 않는다.
        pendingMeasure?.let {
            it.complete(tripInfo)
            return
        }
        _tripInfo.value = tripInfo
    }

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
        log("onRouteStarted ${routeStartInfo.name} ${routeStartInfo.duration / 60}min")
        val trip = _activeTrip.value ?: return
        val ours = (trip.waypoints + trip.destination).any { it.name == routeStartInfo.name }
        if (ours) {
            _activeTrip.value = trip.copy(started = true)
        } else if (trip.started) {
            // 사용자가 내비에서 다른 목적지로 바꿈 → 이 앱의 안내는 끝남
            endTrip { TripEvent.Ended(it) }
        }
    }

    override fun onDestinationChanged() {
        log("onDestinationChanged")
        call("getDestinationInfo") { getDestinationInfo() }
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        log("onDestinationArrived ${destinationArrivedInfo.name}")
        // 도착하면 위치가 바뀌었으므로 다시 조회한다.
        call("getCurrentLocationInfo") { getCurrentLocationInfo() }
        endTrip { TripEvent.Arrived(it) }
    }

    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
        log("onWayPointArrived ${waypointArrivedInfo.index} ${waypointArrivedInfo.name}")
        val trip = _activeTrip.value ?: return
        val place = trip.waypoints.firstOrNull { it.name == waypointArrivedInfo.name }
            ?: trip.waypoints.getOrNull(if (waypointArrivedInfo.index == WaypointIndex.SECOND) 1 else 0)
            ?: return
        _tripEvents.tryEmit(TripEvent.WaypointArrived(trip, place))
    }

    override fun onRouteCancelled() {
        log("onRouteCancelled")
        // 새 경로를 요청하면 내비가 이전 경로를 먼저 취소한다. 확정(started) 전의 취소는 무시.
        if (_activeTrip.value?.started == true) endTrip { TripEvent.Ended(it) }
    }

    override fun onError(errorCode: NaviErrorCode) {
        log("onError $errorCode")
        pendingMeasure?.let {
            it.complete(null)
            return
        }
        messages.tryEmit("내비게이션 오류: $errorCode")
    }

    private companion object {
        const val TAG = "NaviRepository"
    }
}

private fun Place.toRouteInfo() = RouteInfo(
    latitude = location.latitude,
    longitude = location.longitude,
    poiName = name,
    poiId = poiId,
    address = address,
    poiSubId = "0",
)

private fun Place.toWaypointInfo(index: WaypointIndex) = RequestWaypointInfo(
    latitude = location.latitude,
    longitude = location.longitude,
    poiName = name,
    waypointIndex = index,
    poiId = poiId,
    address = address,
    poiSubId = "0",
)
