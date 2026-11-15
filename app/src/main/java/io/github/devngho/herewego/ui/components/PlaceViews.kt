package io.github.devngho.herewego.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devngho.herewego.data.model.Place
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.domain.hhmm
import java.time.LocalTime

/** 추천 결과 공통 카드. */
@Composable
fun PlaceCard(
    place: Place,
    reasons: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    selected: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.outlinedCardColors()
        },
    ) {
        ListItem(
            headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(placeMeta(place)) },
            leadingContent = { CategoryAvatar(place.category) },
            trailingContent = trailing?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (reasons.isNotEmpty()) {
            ReasonRow(reasons, Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (footer == null) 16.dp else 8.dp))
        }
        footer?.let {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                it()
            }
        }
    }
}

fun placeMeta(place: Place): String = buildString {
    append(place.category.label)
    if (place.rating > 0) append(" · ★ ${place.rating}")
}

/** 장소 상세 + 내비 연동 액션. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailSheet(
    place: Place,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    onAddWaypoint: () -> Unit,
    onShowInNavi: () -> Unit,
    onMoveHere: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(place.category)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(place.name, style = MaterialTheme.typography.headlineSmall)
                    Text(place.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (place.rating > 0) IconText(Icons.Outlined.Star, "평점 ${place.rating}" + if (place.ratingCount > 0) " (${place.ratingCount}명)" else "")
                if (!place.hoursEstimated) {
                    val allDay = place.openTime == place.closeTime
                    IconText(Icons.Outlined.Schedule, if (allDay) "24시간 영업" else "영업 ${place.openTime.hhmm()}~${place.closeTime.hhmm()}")
                }
                if (place.stayMinutes > 0) IconText(Icons.Outlined.Schedule, "평균 ${place.stayMinutes}분 머묾")
            }
            if (place.highlights.isNotEmpty()) {
                Spacer(Modifier.size(16.dp))
                Text(highlightTitle(place.category), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                place.highlights.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            if (place.reviewSummary.isNotEmpty()) {
                Spacer(Modifier.size(16.dp))
                Text("리뷰", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(place.reviewSummary, style = MaterialTheme.typography.bodyMedium)
            }
            if (place.mapsUrl.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text("평점·영업시간·메뉴·리뷰 출처: Google Maps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(24.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onNavigate) {
                    Icon(Icons.Outlined.Navigation, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("길 안내")
                }
                FilledTonalButton(onClick = onAddWaypoint) {
                    Icon(Icons.Outlined.AddLocationAlt, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("경유지로 추가")
                }
                OutlinedButton(onClick = onShowInNavi) {
                    Icon(Icons.Outlined.Map, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("내비에서 보기")
                }
                TextButton(onClick = onMoveHere) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("여기 도착한 것으로 (데모)")
                }
            }
        }
    }
}

fun highlightTitle(category: PlaceCategory) = when (category) {
    PlaceCategory.RESTAURANT, PlaceCategory.CAFE, PlaceCategory.BAKERY -> "대표 메뉴"
    PlaceCategory.FLORIST, PlaceCategory.MART, PlaceCategory.WINE_SHOP, PlaceCategory.GIFT_SHOP,
    PlaceCategory.CONVENIENCE, PlaceCategory.PHARMACY -> "살 수 있는 것"
    else -> "여기서 할 수 있는 일"
}

@Composable
private fun IconText(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 장소 선택 다이얼로그. */
@Composable
fun PlacePickerDialog(
    title: String,
    places: List<Place>,
    onDismiss: () -> Unit,
    onSelect: (Place) -> Unit,
    /** 이름으로 실제 장소를 찾는 함수. null 이면 검색창 없이 [places] 만 보여 준다. */
    onSearch: (suspend (String) -> List<Place>)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    /** null 이면 아직 검색하지 않은 상태로, 기본 목록을 보여 준다. */
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        val q = query.trim()
        val find = onSearch ?: return
        if (q.isEmpty()) {
            results = null
            status = null
            return
        }
        scope.launch {
            status = "찾는 중…"
            try {
                val found = find(q)
                results = found
                status = if (found.isEmpty()) "'$q' 검색 결과가 없어요" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "검색하지 못했어요. 서버 연결을 확인해 주세요"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
            if (onSearch != null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("장소 이름으로 검색") },
                    trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Outlined.Search, "검색") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                status?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.size(8.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(results ?: places, key = { it.id }) { place ->
                    ListItem(
                        headlineContent = { Text(place.name) },
                        supportingContent = { Text(listOf(placeMeta(place), place.address).filter { it.isNotBlank() }.joinToString(" · ")) },
                        leadingContent = { CategoryAvatar(place.category) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onSelect(place) },
                    )
                    HorizontalDivider()
                }
            }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** Material 3 TimePicker 다이얼로그. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
