package io.github.devngho.herewego.trip

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차가 "앱 화면을 쓸 수 있는 상태"(주차)인지 알려 준다.
 *
 * 이 앱은 출발 전·도착 후에 쓰는 앱이라 주행 중 사용(distractionOptimized)을 선언하지 않는다.
 * 주행 제한이 걸린 동안에는 시스템이 앱 화면을 "주행 중에는 사용할 수 없습니다"로 막으므로,
 * 도착 알림은 제한이 풀린 뒤(P단)에 보내야 눌렀을 때 바로 열린다.
 *
 * 기어 값을 직접 읽지 않고 CarUxRestrictions 의 "DO 필요 여부"를 본다.
 * 권한이 필요 없고, 정차 중 허용 여부 같은 지역별 규정도 플랫폼이 반영해 준다.
 * (Pleos 에뮬레이터 기준: P단만 제한 없음, D단 정차·주행은 제한)
 *
 * 차량이 아닌 기기(폰·태블릿)에서는 항상 주차 상태로 본다.
 */
class ParkingMonitor(context: Context) {

    private val _parked = MutableStateFlow(true)
    val parked: StateFlow<Boolean> = _parked.asStateFlow()

    init {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            connect(context.applicationContext)
        }
    }

    private fun connect(context: Context) {
        try {
            // 핸들러 null → 메인 스레드 콜백. car service 가 재시작되면 ready=true 로 다시 불린다.
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready ->
                if (!ready) return@createCar
                val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    ?: return@createCar
                update(manager.currentCarUxRestrictions)
                manager.registerListener { update(it) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Car service unavailable; treating as parked", e)
        }
    }

    private fun update(restrictions: CarUxRestrictions?) {
        val parked = restrictions?.isRequiresDistractionOptimization != true
        if (_parked.value != parked) {
            Log.i(TAG, "parked=$parked (uxr=0x${Integer.toHexString(restrictions?.activeRestrictions ?: 0)})")
        }
        _parked.value = parked
    }

    private companion object {
        const val TAG = "ParkingMonitor"
    }
}
