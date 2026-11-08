package io.github.devngho.activities.trip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.devngho.activities.MainActivity
import io.github.devngho.activities.R
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.domain.withEuroRo

/** 안내 중(포그라운드 서비스) / 도착 알림. */
object TripNotifications {
    private const val CHANNEL_TRIP = "trip"
    private const val CHANNEL_ARRIVAL = "arrival"
    const val ID_TRIP = 1
    private const val ID_ARRIVAL = 2

    /** 알림을 눌렀을 때 MainActivity 가 "지금 여기" 탭을 열도록 하는 extra. */
    const val EXTRA_OPEN_EXPLORE = "open_explore"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_TRIP, "안내 중", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "내비게이션 안내 중 도착을 감지하기 위한 알림"
                },
                NotificationChannel(CHANNEL_ARRIVAL, "도착 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "목적지·경유지에 도착하면 할 일을 추천해요"
                },
            )
        )
    }

    fun ongoing(context: Context, destination: Place): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("${destination.name.withEuroRo()} 안내 중")
            .setContentText("도착하면 주변에서 할 일을 추천해 드릴게요")
            .setOngoing(true)
            .setContentIntent(openAppIntent(context, explore = false))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()

    /** 도착했지만 아직 주행 제한 중(D단)일 때, 진행 중 알림을 "주차하면 알려 드릴게요"로 바꾼다. */
    fun waitingForPark(context: Context, place: Place) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("${place.name}에 도착했어요")
            .setContentText("주차(P)하면 근처에서 할 일을 알려 드릴게요")
            .setOngoing(true)
            .setContentIntent(openAppIntent(context, explore = true))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        NotificationManagerCompat.from(context).notify(ID_TRIP, n)
    }

    fun notifyArrived(context: Context, place: Place, note: String?) =
        post(
            context,
            title = "${place.name}에 도착했어요",
            text = note ?: "근처에서 할 일을 추천해 드릴게요",
        )

    fun notifyWaypoint(context: Context, place: Place, note: String?) =
        post(
            context,
            title = "경유지 ${place.name}에 도착했어요",
            text = note ?: "목적지 안내는 계속돼요",
        )

    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 차량 OS 는 카테고리가 없는 알림을 헤드업으로 띄우지 않는다(CarHeadsUpNotificationManager).
            // 내비 안내의 도착에 이어지는 알림이라 NAVIGATION 으로 분류한다.
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, explore = true))
            .build()
        NotificationManagerCompat.from(context).notify(ID_ARRIVAL, n)
    }

    fun cancelArrival(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ARRIVAL)
    }

    /** 앱의 기존 태스크를 전면으로 가져오고(있으면 onNewIntent) 필요하면 "지금 여기" 탭을 연다. */
    fun appIntent(context: Context, explore: Boolean): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_EXPLORE, explore)

    private fun openAppIntent(context: Context, explore: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            if (explore) 1 else 0,
            appIntent(context, explore),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
