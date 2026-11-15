package io.github.devngho.herewego.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devngho.herewego.ui.components.PlaceDetailSheet
import io.github.devngho.herewego.ui.explore.ExploreScreen
import io.github.devngho.herewego.ui.profile.ProfileScreen
import io.github.devngho.herewego.ui.purpose.PurposeScreen
import io.github.devngho.herewego.ui.schedule.ScheduleScreen

private enum class Destination(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    EXPLORE("지금 여기", Icons.Filled.Explore, Icons.Outlined.Explore),
    PURPOSE("가는 길", Icons.AutoMirrored.Filled.AltRoute, Icons.AutoMirrored.Outlined.AltRoute),
    SCHEDULE("빈 시간", Icons.Filled.EventAvailable, Icons.Outlined.EventAvailable),
    PROFILE("나", Icons.Filled.Person, Icons.Outlined.Person),
}

@Composable
fun HereWeGoAppUi(mainViewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    var current by rememberSaveable { mutableStateOf(Destination.EXPLORE) }
    val snackbarHostState = remember { SnackbarHostState() }
    val connection by mainViewModel.connection.collectAsStateWithLifecycle()
    val selectedPlace by mainViewModel.selectedPlace.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        mainViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    // 내비 안내로 도착했거나 도착 알림을 눌렀을 때 → Func 1 화면으로
    LaunchedEffect(Unit) {
        mainViewModel.openExplore.collect { current = Destination.EXPLORE }
    }

    // 폰(세로)에서는 하단 내비게이션 바, 차량 디스플레이처럼 넓은 화면에서는 내비게이션 레일.
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { dest ->
                val selected = dest == current
                item(
                    selected = selected,
                    onClick = { current = dest },
                    icon = { Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null) },
                    label = { Text(dest.label) },
                )
            }
        },
    ) {
        when (current) {
            Destination.EXPLORE -> ExploreScreen(snackbarHostState, onPlaceClick = mainViewModel::select)
            Destination.PURPOSE -> PurposeScreen(snackbarHostState, connection, onPlaceClick = mainViewModel::select)
            Destination.SCHEDULE -> ScheduleScreen(snackbarHostState, connection, onPlaceClick = mainViewModel::select)
            Destination.PROFILE -> ProfileScreen(snackbarHostState)
        }
    }

    selectedPlace?.let { place ->
        PlaceDetailSheet(
            place = place,
            onDismiss = { mainViewModel.select(null) },
            onNavigate = {
                mainViewModel.navigate(place)
                mainViewModel.select(null)
            },
            onAddWaypoint = {
                mainViewModel.addWaypoint(place)
                mainViewModel.select(null)
            },
            onShowInNavi = {
                mainViewModel.showInNavi(place)
                mainViewModel.select(null)
            },
            onMoveHere = {
                mainViewModel.moveHere(place)
                mainViewModel.select(null)
                current = Destination.EXPLORE
            },
        )
    }
}
