package io.github.devngho.activities.data

import io.github.devngho.activities.data.model.GeoPoint
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.domain.Geo
import io.github.devngho.activities.navi.NaviRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class LocationSource {
    /** NaviHelper.getCurrentLocationInfo() 결과 */
    NAVI,

    /** 사용자가 고른 데모 장소 */
    DEMO,

    /** 내비 위치를 받았지만 주변에 샘플 POI 가 없어 데모 장소로 대체 */
    DEMO_FALLBACK,

    /** 이 앱이 요청한 내비 안내로 목적지에 도착 (onDestinationArrived) */
    ARRIVED,
}

data class EffectiveLocation(
    val point: GeoPoint,
    val address: String,
    /** 현재 위치와 일치하는(150m 이내) POI. 없으면 null. */
    val place: Place?,
    val source: LocationSource,
)

/** 내비 현재 위치와 데모 위치를 하나로 합친다. 모든 추천은 이 위치를 기준으로 한다. */
class LocationRepository(
    navi: NaviRepository,
    private val places: List<Place>,
    scope: CoroutineScope,
) {
    private val _demoPlace = MutableStateFlow(SamplePlaces.yangjaeRestaurant)
    val demoPlace: StateFlow<Place> = _demoPlace.asStateFlow()

    private val _preferNavi = MutableStateFlow(true)
    val preferNavi: StateFlow<Boolean> = _preferNavi.asStateFlow()

    /** 내비 안내로 도착한 장소. 도착 좌표는 도로에 스냅돼 POI 와 어긋날 수 있어 요청한 Place 를 그대로 쓴다. */
    private val _arrivedAt = MutableStateFlow<Place?>(null)

    val location: StateFlow<EffectiveLocation> =
        combine(navi.currentLocation, _demoPlace, _preferNavi, _arrivedAt) { naviLoc, demo, preferNavi, arrived ->
            if (arrived != null) return@combine EffectiveLocation(arrived.location, arrived.address, arrived, LocationSource.ARRIVED)
            val demoLocation = EffectiveLocation(demo.location, demo.address, demo, LocationSource.DEMO)
            if (!preferNavi || naviLoc == null) return@combine demoLocation

            val p = GeoPoint(naviLoc.latitude, naviLoc.longitude)
            val hasSampleData = places.any { Geo.distanceMeters(p, it.location) < 3_000 }
            if (!hasSampleData) {
                demoLocation.copy(source = LocationSource.DEMO_FALLBACK)
            } else {
                val nearest = places.minByOrNull { Geo.distanceMeters(p, it.location) }
                    ?.takeIf { Geo.distanceMeters(p, it.location) < 150 }
                EffectiveLocation(p, naviLoc.address, nearest, LocationSource.NAVI)
            }
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            EffectiveLocation(_demoPlace.value.location, _demoPlace.value.address, _demoPlace.value, LocationSource.DEMO),
        )

    /** 데모 장소로 '이동'한다. 내비 위치 사용은 끈다. */
    fun moveTo(place: Place) {
        _arrivedAt.value = null
        _demoPlace.value = place
        _preferNavi.value = false
    }

    fun setPreferNavi(value: Boolean) {
        _arrivedAt.value = null
        _preferNavi.value = value
    }

    fun arrivedAt(place: Place) {
        _arrivedAt.value = place
    }
}
