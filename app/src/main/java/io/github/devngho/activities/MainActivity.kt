package io.github.devngho.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.devngho.activities.trip.TripNotifications
import io.github.devngho.activities.ui.ActivitiesAppUi
import io.github.devngho.activities.ui.theme.ActivitiesTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as ActivitiesApp).container

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부해도 앱은 동작 */ }

    /**
     * 차량 디스플레이는 눈과의 거리가 멀어 폰 기준 dp 로는 글자·터치 영역이 너무 작다.
     * (AAOS 가이드: 본문 ≥ 24dp, 주요 텍스트 32dp, 터치 영역 ≥ 76dp / Pleos 가이드: 일반 텍스트 ≥ 3mm)
     *
     * Compose 의 LocalDensity 만 바꾸면 Dialog·BottomSheet 같은 별도 윈도우에는 적용되지 않으므로,
     * Activity 의 densityDpi 자체를 키워 앱 전체(다이얼로그 포함)를 일관되게 확대한다.
     * M3 컴포넌트의 비율은 그대로 유지된다.
     */
    override fun attachBaseContext(newBase: Context) {
        val isCar = newBase.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        if (!isCar) return super.attachBaseContext(newBase)

        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = (densityDpi * CAR_UI_SCALE).toInt()
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        setContent {
            ActivitiesTheme {
                ActivitiesAppUi()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 도착 알림을 눌러 들어오면 "지금 여기" 탭을 연다. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TripNotifications.EXTRA_OPEN_EXPLORE, false) == true) {
            container.requestOpenExplore()
            // 앱이 열렸으니 도착 알림은 더 필요 없다.
            TripNotifications.cancelArrival(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        /** 160dpi 차량 디스플레이 기준 → 280dpi 상당. bodyMedium(14sp) ≈ 24.5px, 48dp 터치 ≈ 84px. */
        const val CAR_UI_SCALE = 1.75f
    }
}
