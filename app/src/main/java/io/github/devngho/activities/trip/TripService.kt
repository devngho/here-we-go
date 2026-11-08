package io.github.devngho.activities.trip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.devngho.activities.ActivitiesApp

/**
 * 이 앱이 요청한 내비 안내가 진행되는 동안 프로세스를 살려 두는 포그라운드 서비스.
 *
 * 내비가 전면에 뜨면 이 앱은 백그라운드 캐시 상태가 되고 Android 14 의 cached-apps freezer 에 의해
 * 동결되어 NaviHelper 바인더 콜백(onDestinationArrived 등)을 받지 못한다.
 * 실제 NaviHelper 연결은 AppContainer(프로세스 단위)가 갖고 있으므로, 이 서비스는 알림만 띄운다.
 */
class TripService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trip = (application as ActivitiesApp).container.navi.activeTrip.value
        if (trip == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            TripNotifications.ID_TRIP,
            TripNotifications.ongoing(this, trip.destination),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TripService"

        /** 앱이 화면에 있을 때(안내 요청 직후) 호출해야 백그라운드 FGS 시작 제한에 걸리지 않는다. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start TripService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripService::class.java))
        }
    }
}
