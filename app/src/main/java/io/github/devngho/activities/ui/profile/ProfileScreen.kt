package io.github.devngho.activities.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devngho.activities.data.model.PlaceCategory
import io.github.devngho.activities.domain.hhmm
import io.github.devngho.activities.navi.NaviConnection
import io.github.devngho.activities.ui.components.CategoryAvatar
import io.github.devngho.activities.ui.components.EmptyHint
import io.github.devngho.activities.ui.components.NaviStatusChip
import io.github.devngho.activities.ui.components.TimePickerDialog
import io.github.devngho.activities.ui.components.sectionHeader
import java.time.LocalTime


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val autoAnnounce by viewModel.autoAnnounce.collectAsStateWithLifecycle()
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val profile = state.profile

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("나의 취향") },
                actions = { NaviStatusChip(state.connection, onClick = viewModel::reconnect) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(400.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sectionHeader("관심사", "선택한 카테고리는 추천 점수에 가중치가 붙어요", key = "h_interest")
            item(key = "interests") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    FlowRow(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlaceCategory.entries.filter { it.recommendable }.forEach { category ->
                            val selected = category in profile.interests
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleInterest(category) },
                                label = { Text(category.label) },
                                leadingIcon = {
                                    Icon(
                                        if (selected) Icons.Outlined.Check else category.icon,
                                        null,
                                        Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item(key = "analysis") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("선호도 분석", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "관심사 + 방문 기록(내비 최근 목적지 학습 포함)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        PlaceCategory.entries
                            .filter { it.recommendable }
                            .sortedByDescending { profile.affinity(it) }
                            .take(6)
                            .forEach { category ->
                                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category.label, Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                                    LinearProgressIndicator(
                                        progress = { profile.affinity(category).toFloat() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${profile.visitCounts[category] ?: 0}회",
                                        Modifier.width(48.dp).padding(start = 12.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = viewModel::learnFromNavi) {
                                Icon(Icons.Outlined.Psychology, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("최근 목적지로 학습")
                            }
                            TextButton(onClick = viewModel::resetProfile) { Text("초기화") }
                        }
                    }
                }
            }

            sectionHeader("학습된 장소", "내비게이션 최근 목적지를 이름으로 분류했어요", key = "h_learned")
            item(key = "learned") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    if (profile.learnedPlaces.isEmpty()) {
                        EmptyHint("아직 학습된 장소가 없어요")
                    } else {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            profile.learnedPlaces.forEach {
                                ListItem(
                                    headlineContent = { Text(it.name) },
                                    supportingContent = { Text(it.category.label) },
                                    leadingContent = { CategoryAvatar(it.category) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "voice") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("음성 안내", style = MaterialTheme.typography.titleMedium)
                        ListItem(
                            headlineContent = { Text("도착하면 추천 읽어 주기") },
                            supportingContent = { Text("목적지·경유지에 도착해 주차(P)하면 할 일과 다음 코스를 알림과 소리로 알려 줘요") },
                            trailingContent = { Switch(checked = autoAnnounce, onCheckedChange = viewModel::setAutoAnnounce) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        ListItem(
                            headlineContent = {
                                Text(
                                    when {
                                        voice.initializing -> "음성 엔진 준비 중…"
                                        else -> voice.engine.label
                                    },
                                )
                            },
                            supportingContent = {
                                val detail = listOfNotNull(
                                    voice.lastError?.let { "참고: $it" },
                                    voice.lastSpoken?.let { "마지막 안내: “$it”" },
                                )
                                if (detail.isNotEmpty()) Text(detail.joinToString("\n"))
                            },
                            trailingContent = {
                                Icon(
                                    if (voice.available) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                    contentDescription = if (voice.available) "사용 가능" else "사용 불가",
                                    tint = if (voice.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        OutlinedButton(onClick = viewModel::testVoice, enabled = voice.available) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("테스트 음성 듣기")
                        }
                    }
                }
            }

            item(key = "demo") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("데모 설정", style = MaterialTheme.typography.titleMedium)
                        ListItem(
                            headlineContent = { Text("데모 시각 고정") },
                            supportingContent = {
                                Text(state.clockOverride?.let { "${it.hhmm()}으로 고정 · 탭해서 변경" } ?: "실제 시각 사용 중")
                            },
                            trailingContent = {
                                Switch(
                                    checked = state.clockOverride != null,
                                    onCheckedChange = { on -> viewModel.setClockOverride(if (on) LocalTime.of(13, 10) else null) },
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (state.clockOverride != null) {
                            OutlinedButton(onClick = { pickTime = true }) { Text("시각 변경") }
                        }
                    }
                }
            }

            sectionHeader("Pleos NaviHelper", key = "h_navi")
            item(key = "navi") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (state.connection) {
                                        NaviConnection.CONNECTED -> "내비게이션 서비스에 연결됨"
                                        NaviConnection.CONNECTING -> "연결 중…"
                                        NaviConnection.UNAVAILABLE -> "내비게이션 서비스를 찾을 수 없음 (데모 모드)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    state.naviAddress?.let { "현재 위치: $it" }
                                        ?: "Pleos Connect 에뮬레이터 또는 실차에서 실제 내비 데이터를 사용해요",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            NaviStatusChip(state.connection)
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = viewModel::reconnect) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("다시 연결")
                        }
                    }
                }
            }
            item(key = "log") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SDK 이벤트 로그", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(8.dp))
                        if (state.eventLog.isEmpty()) {
                            Text("-", style = MaterialTheme.typography.bodySmall)
                        }
                        state.eventLog.take(8).forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickTime) {
        TimePickerDialog(
            title = "데모 시각",
            initial = state.clockOverride ?: LocalTime.now(),
            onDismiss = { pickTime = false },
            onConfirm = {
                viewModel.setClockOverride(it)
                pickTime = false
            },
        )
    }
}
