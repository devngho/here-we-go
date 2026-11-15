package io.github.devngho.herewego.ui.purpose

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.Purpose
import io.github.devngho.herewego.domain.formatDistance
import io.github.devngho.herewego.domain.withIGa
import io.github.devngho.herewego.navi.NaviConnection
import io.github.devngho.herewego.ui.components.CategoryAvatar
import io.github.devngho.herewego.ui.components.EmptyHint
import io.github.devngho.herewego.ui.components.NaviStatusChip
import io.github.devngho.herewego.ui.components.PlaceCard
import io.github.devngho.herewego.ui.components.PlacePickerDialog
import io.github.devngho.herewego.ui.components.ReadAloudButton
import io.github.devngho.herewego.ui.components.AdaptiveTwoPane
import io.github.devngho.herewego.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PurposeScreen(
    snackbarHostState: SnackbarHostState,
    connection: NaviConnection,
    onPlaceClick: (Place) -> Unit,
    viewModel: PurposeViewModel = viewModel(factory = PurposeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selectedIds = state.selected.map { it.place.id }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("가는 길에 들르기") },
                actions = {
                    ReadAloudButton(voice, onSpeak = viewModel::readAloud, onStop = viewModel::stopReading)
                    NaviStatusChip(connection)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startGuidance,
                icon = { Icon(Icons.Outlined.Navigation, null) },
                text = {
                    Text(if (state.selected.isEmpty()) "바로 안내 시작" else "경유지 ${state.selected.size}곳 포함 안내")
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AdaptiveTwoPane(
            padding = padding,
            bottomExtra = 96.dp, // FAB
            left = {
            item(key = "h_dest") { SectionHeader("어디로 가세요?") }
            item(key = "destination") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        overlineContent = {
                            val from = state.origin?.let { it.place?.name ?: it.address } ?: "현재 위치"
                            Text("$from 출발 · 직선 ${formatDistance(state.directMeters)}")
                        },
                        headlineContent = { Text(state.destination.name) },
                        supportingContent = { Text(state.destination.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { CategoryAvatar(state.destination.category) },
                        trailingContent = { TextButton(onClick = { showPicker = true }) { Text("변경") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item(key = "h_purpose") { SectionHeader("무엇을 하러 가세요?", "목적에 맞춰 경로 위에서 들를 곳을 찾아 드려요") }
            item(key = "purposes") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Purpose.entries.forEach { purpose ->
                        val selected = purpose == state.purpose && state.customPurpose == null
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectPurpose(purpose) },
                            label = { Text(purpose.label) },
                            leadingIcon = {
                                Icon(
                                    if (selected) Icons.Outlined.Check else purpose.icon,
                                    null,
                                    Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
            if (viewModel.canEnterPurpose) {
                item(key = "custom_purpose") {
                    var text by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
                        label = { Text("직접 입력") },
                        placeholder = { Text("예: 친구 결혼식 가는 길") },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.setCustomPurpose(text) }) { Icon(Icons.Outlined.Check, "이 목적으로 찾기") }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.setCustomPurpose(text) }),
                    )
                }
            }

            },
            right = {
            state.customPurpose?.let { custom ->
                item(key = "custom_status") {
                    SectionHeader(
                        state.customLabel ?: custom,
                        when (state.customPending) {
                            true -> "무엇을 챙기면 좋을지 찾는 중…"
                            false -> "이 목적은 이해하지 못했어요. 다르게 적거나 목록에서 골라 주세요"
                            null -> "입력하신 목적에 맞춰 챙길 것을 골랐어요"
                        },
                    )
                }
            }
            state.suggestions.forEach { suggestion ->
                val need = suggestion.need
                item(key = "h_${need.category}_${need.item}") { SectionHeader("${need.item} · ${need.category.label}", need.reason) }
                if (suggestion.stops.isEmpty()) {
                    item(key = "empty_${need.category}_${need.item}") {
                        EmptyHint("경로 주변에 지금 영업 중인 ${need.category.label.withIGa()} 없어요")
                    }
                }
                items(suggestion.stops, key = { "${need.item}_${it.place.id}" }) { stop ->
                    val selected = stop.place.id in selectedIds
                    PlaceCard(
                        place = stop.place,
                        reasons = stop.reasons,
                        onClick = { onPlaceClick(stop.place) },
                        trailing = if (stop.detourMeters < 300) "경로상" else "+${stop.detourMinutes}분",
                        selected = selected,
                        footer = {
                            if (selected) {
                                OutlinedButton(onClick = { viewModel.toggleStop(stop) }) {
                                    Icon(Icons.Outlined.Check, null, Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text("경유지에서 빼기")
                                }
                            } else {
                                FilledTonalButton(onClick = { viewModel.toggleStop(stop) }) {
                                    Icon(Icons.Outlined.AddLocationAlt, null, Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text("경유지로 선택")
                                }
                            }
                        },
                    )
                }
            }
            },
        )
    }

    if (showPicker) {
        PlacePickerDialog(
            title = "목적지 선택",
            places = state.destinations,
            onSearch = viewModel.searchPlaces,
            onDismiss = { showPicker = false },
            onSelect = {
                viewModel.selectDestination(it)
                showPicker = false
            },
        )
    }
}
