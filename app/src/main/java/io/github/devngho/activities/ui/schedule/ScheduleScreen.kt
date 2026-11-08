package io.github.devngho.activities.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devngho.activities.data.model.Place
import io.github.devngho.activities.data.model.Schedule
import io.github.devngho.activities.domain.GapZone
import io.github.devngho.activities.domain.hhmm
import io.github.devngho.activities.navi.NaviConnection
import io.github.devngho.activities.ui.components.AdaptiveTwoPane
import io.github.devngho.activities.ui.components.EmptyHint
import io.github.devngho.activities.ui.components.NaviStatusChip
import io.github.devngho.activities.ui.components.PlaceCard
import io.github.devngho.activities.ui.components.PlacePickerDialog
import io.github.devngho.activities.ui.components.ReadAloudButton
import io.github.devngho.activities.ui.components.SectionHeader
import io.github.devngho.activities.ui.components.TimePickerDialog

private enum class EditTarget { A_PLACE, A_START, A_END, B_PLACE, B_START, ACTUAL_END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    snackbarHostState: SnackbarHostState,
    connection: NaviConnection,
    onPlaceClick: (Place) -> Unit,
    viewModel: ScheduleViewModel = viewModel(factory = ScheduleViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<EditTarget?>(null) }
    var zone by rememberSaveable { mutableStateOf(GapZone.NEAR_A) }
    val plan = state.plan

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("빈 시간 활용") },
                actions = {
                    ReadAloudButton(voice, onSpeak = viewModel::readAloud, onStop = viewModel::stopReading)
                    NaviStatusChip(connection)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AdaptiveTwoPane(
            padding = padding,
            left = {
                item(key = "h_schedule") { SectionHeader("오늘 일정") }
                item(key = "a") {
                    ScheduleCard(
                        label = "A",
                        schedule = state.a,
                        showEnd = true,
                        onPlace = { editing = EditTarget.A_PLACE },
                        onStart = { editing = EditTarget.A_START },
                        onEnd = { editing = EditTarget.A_END },
                    )
                }
                item(key = "b") {
                    ScheduleCard(
                        label = "B",
                        schedule = state.b,
                        showEnd = false,
                        onPlace = { editing = EditTarget.B_PLACE },
                        onStart = { editing = EditTarget.B_START },
                        onEnd = {},
                    )
                }
                item(key = "status") {
                    StatusCard(
                        state = state,
                        onEndNow = viewModel::endNow,
                        onPickTime = { editing = EditTarget.ACTUAL_END },
                        onNaviEta = viewModel::requestNaviEta,
                        onGoDirectly = viewModel::goDirectly,
                    )
                }
            },
            right = {
                item(key = "h_rec") {
                    SectionHeader(
                        "이렇게 보내 보세요",
                        if (plan.freeMinutes >= 15) "B 일정에 늦지 않는 범위에서 머물 수 있는 곳" else null,
                    )
                }
                if (plan.freeMinutes < 15) {
                    item(key = "no_time") { EmptyHint("여유 시간이 부족해요. 바로 B로 출발하세요.") }
                    return@AdaptiveTwoPane
                }
                item(key = "zones") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        GapZone.entries.forEachIndexed { index, z ->
                            SegmentedButton(
                                selected = zone == z,
                                onClick = { zone = z },
                                shape = SegmentedButtonDefaults.itemShape(index, GapZone.entries.size),
                                label = { Text("${z.label} ${plan.stops[z].orEmpty().size}") },
                            )
                        }
                    }
                }
                val stops = plan.stops[zone].orEmpty()
                if (stops.isEmpty()) {
                    item(key = "zone_empty") { EmptyHint("${zone.label}에는 시간에 맞는 곳이 없어요") }
                }
                items(stops, key = { "${zone}_${it.place.id}" }) { stop ->
                    PlaceCard(
                        place = stop.place,
                        reasons = stop.reasons,
                        onClick = { onPlaceClick(stop.place) },
                        trailing = "${stop.arriveAt.hhmm()}~${stop.leaveAt.hhmm()}",
                        footer = {
                            FilledTonalButton(onClick = { viewModel.start(stop) }) {
                                Icon(Icons.Outlined.Navigation, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(if (stop.zone == GapZone.NEAR_B) "여기로 안내" else "경유해서 B로 안내")
                            }
                        },
                    )
                }
            },
        )
    }

    // ---- 편집 다이얼로그 ----
    when (val target = editing) {
        EditTarget.A_PLACE, EditTarget.B_PLACE -> PlacePickerDialog(
            title = if (target == EditTarget.A_PLACE) "일정 A 장소" else "일정 B 장소",
            places = viewModel.selectablePlaces,
            onDismiss = { editing = null },
            onSelect = { place ->
                if (target == EditTarget.A_PLACE) viewModel.updateA(state.a.copy(place = place))
                else viewModel.updateB(state.b.copy(place = place))
                editing = null
            },
        )

        EditTarget.A_START, EditTarget.A_END, EditTarget.B_START, EditTarget.ACTUAL_END -> {
            val (title, initial) = when (target) {
                EditTarget.A_START -> "A 시작 시각" to state.a.start
                EditTarget.A_END -> "A 종료 예정 시각" to state.a.expectedEnd
                EditTarget.B_START -> "B 시작 시각" to state.b.start
                else -> "A 실제 종료 시각" to state.actualEnd
            }
            TimePickerDialog(
                title = title,
                initial = initial,
                onDismiss = { editing = null },
                onConfirm = { time ->
                    when (target) {
                        EditTarget.A_START -> viewModel.updateA(state.a.copy(start = time))
                        EditTarget.A_END -> viewModel.updateA(state.a.copy(expectedEnd = time))
                        EditTarget.B_START -> viewModel.updateB(state.b.copy(start = time))
                        else -> viewModel.setActualEnd(time)
                    }
                    editing = null
                },
            )
        }

        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    label: String,
    schedule: Schedule,
    showEnd: Boolean,
    onPlace: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "일정 $label · ${schedule.title}",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onPlace,
                    label = { Text(schedule.place.name) },
                    leadingIcon = { Icon(Icons.Outlined.Place, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
                AssistChip(
                    onClick = onStart,
                    label = { Text(schedule.start.hhmm()) },
                    leadingIcon = { Icon(Icons.Outlined.AccessTime, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
                if (showEnd) {
                    AssistChip(
                        onClick = onEnd,
                        label = { Text("~${schedule.expectedEnd.hhmm()} 예정") },
                        leadingIcon = { Icon(Icons.Outlined.Timer, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }
    }
}

/** A 실제 종료 + 빈 시간 요약 + 관련 액션을 한 카드로. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    state: ScheduleUiState,
    onEndNow: () -> Unit,
    onPickTime: () -> Unit,
    onNaviEta: () -> Unit,
    onGoDirectly: () -> Unit,
) {
    val plan = state.plan
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("A가 ${state.actualEnd.hhmm()}에 끝났어요", style = MaterialTheme.typography.titleLarge, color = onContainer)
            Text(
                when {
                    state.endedEarlyBy > 0 -> "예정보다 ${state.endedEarlyBy}분 일찍"
                    state.endedEarlyBy < 0 -> "예정보다 ${-state.endedEarlyBy}분 늦게"
                    else -> "예정대로"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = onContainer,
            )
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("자유 시간", "${plan.freeMinutes.coerceAtLeast(0)}분", highlight = true)
                Stat(if (state.naviTravelMinutes != null) "이동 (내비)" else "이동", "${plan.travelMinutes}분")
                Stat("늦어도 출발", plan.leaveBy.hhmm())
            }
            Spacer(Modifier.size(16.dp))
            HorizontalDivider(color = onContainer.copy(alpha = 0.2f))
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEndNow) {
                    Icon(Icons.Outlined.DoneAll, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("지금 끝났어요")
                }
                OutlinedButton(onClick = onPickTime) { Text("시각 변경") }
                TextButton(onClick = onNaviEta) {
                    Icon(Icons.Outlined.Route, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("내비 시간으로 보정")
                }
                TextButton(onClick = onGoDirectly) {
                    Icon(Icons.Outlined.Navigation, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("바로 B로")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
