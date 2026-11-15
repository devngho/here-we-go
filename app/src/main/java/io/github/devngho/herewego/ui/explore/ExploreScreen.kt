package io.github.devngho.herewego.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devngho.herewego.data.ExploreRemoteState
import io.github.devngho.herewego.data.LocationSource
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.domain.formatDistance
import io.github.devngho.herewego.domain.hhmm
import io.github.devngho.herewego.domain.withEulReul
import io.github.devngho.herewego.navi.NaviConnection
import io.github.devngho.herewego.ui.components.CategoryAvatar
import io.github.devngho.herewego.ui.components.EmptyHint
import io.github.devngho.herewego.ui.components.NaviStatusChip
import io.github.devngho.herewego.ui.components.PlaceCard
import io.github.devngho.herewego.ui.components.PlacePickerDialog
import io.github.devngho.herewego.ui.components.ReadAloudButton
import io.github.devngho.herewego.ui.components.fullWidthItem
import io.github.devngho.herewego.ui.components.highlightTitle
import io.github.devngho.herewego.ui.components.placeMeta
import io.github.devngho.herewego.ui.components.sectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    snackbarHostState: SnackbarHostState,
    onPlaceClick: (Place) -> Unit,
    viewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("지금 여기서") },
                actions = {
                    ReadAloudButton(voice, onSpeak = viewModel::readAloud, onStop = viewModel::stopReading)
                    NaviStatusChip(state.connection, onClick = viewModel::refreshLocation)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fullWidthItem("location") {
                CurrentLocationCard(
                    state = state,
                    onChangePlace = { showPicker = true },
                    onPreferNaviChange = viewModel::setPreferNavi,
                )
            }

            state.location.place?.let { place ->
                if (state.thingsToDo.isNotEmpty()) {
                    sectionHeader("${place.name}에서", highlightTitle(place.category))
                    fullWidthItem("todo") { ThingsToDoCard(state.thingsToDo) }
                }
            }

            sectionHeader(
                "이어서 가기 좋은 곳",
                "지금 장소와 시간대(${state.now.hhmm()})에 어울리는 다음 코스" + when (val remote = state.remote) {
                    ExploreRemoteState.Loading -> " · 주변 장소를 찾는 중…"
                    ExploreRemoteState.Failed -> " · 서버에 연결하지 못해 샘플 장소를 보여 드려요"
                    is ExploreRemoteState.Loaded -> if (remote.result.byLlm) " · AI 추천" else ""
                    else -> ""
                },
            )
            if (state.nextCourse.isEmpty()) {
                fullWidthItem("next_empty") { EmptyHint("주변에 추천할 장소가 없어요") }
            }
            items(state.nextCourse, key = { "next_${it.place.id}" }) { rec ->
                PlaceCard(rec.place, rec.reasons, onClick = { onPlaceClick(rec.place) }, trailing = formatDistance(rec.distanceMeters))
            }

            sectionHeader(
                "취향 맞춤 추천",
                "방문 기록 분석 결과: ${state.topInterests.joinToString(" · ") { it.label }.withEulReul()} 즐겨 찾으시네요",
            )
            items(state.forYou, key = { "you_${it.place.id}" }) { rec ->
                PlaceCard(rec.place, rec.reasons, onClick = { onPlaceClick(rec.place) }, trailing = formatDistance(rec.distanceMeters))
            }
        }
    }

    if (showPicker) {
        PlacePickerDialog(
            title = "지금 어디에 있나요?",
            places = viewModel.selectablePlaces,
            onDismiss = { showPicker = false },
            onSelect = {
                viewModel.moveTo(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun CurrentLocationCard(
    state: ExploreUiState,
    onChangePlace: () -> Unit,
    onPreferNaviChange: (Boolean) -> Unit,
) {
    val loc = state.location
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                when (loc.source) {
                    LocationSource.NAVI -> "내비게이션 현재 위치"
                    LocationSource.DEMO -> "데모 위치"
                    // 서버가 연결돼 있으면 이 상태는 생기지 않는다.
                    LocationSource.DEMO_FALLBACK -> "데모 위치 · 내비 위치 주변에 샘플 데이터가 없어요"
                    LocationSource.ARRIVED -> "내비 안내로 도착했어요"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                loc.place?.let {
                    CategoryAvatar(
                        it.category,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        loc.place?.name ?: loc.address.ifEmpty { "알 수 없는 장소" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        listOfNotNull(loc.place?.let(::placeMeta), "${state.now.hhmm()} 기준").joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onChangePlace) {
                    Icon(Icons.Outlined.EditLocationAlt, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("장소 바꾸기")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "내비 위치 사용",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(8.dp))
                Switch(
                    checked = state.preferNavi && state.connection == NaviConnection.CONNECTED,
                    onCheckedChange = onPreferNaviChange,
                    enabled = state.connection == NaviConnection.CONNECTED,
                )
            }
        }
    }
}

@Composable
private fun ThingsToDoCard(items: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEach {
                ListItem(
                    headlineContent = { Text(it) },
                    leadingContent = { Icon(Icons.Outlined.TipsAndUpdates, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
