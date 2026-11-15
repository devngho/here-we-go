package io.github.devngho.herewego.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.devngho.herewego.data.model.PlaceCategory
import io.github.devngho.herewego.navi.NaviConnection

/** 카테고리 아이콘을 원형 컨테이너에 담아 보여준다. */
@Composable
fun CategoryAvatar(
    category: PlaceCategory,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(modifier = modifier.size(40.dp), shape = CircleShape, color = containerColor, contentColor = contentColor) {
        Box(contentAlignment = Alignment.Center) {
            Icon(category.icon, contentDescription = category.label, modifier = Modifier.size(22.dp))
        }
    }
}

/** 내비 연결 상태 표시용 칩 (TopAppBar actions 에 배치). */
@Composable
fun NaviStatusChip(connection: NaviConnection, onClick: () -> Unit = {}) {
    val (label, icon) = when (connection) {
        NaviConnection.CONNECTED -> "내비 연결됨" to Icons.Outlined.Link
        NaviConnection.CONNECTING -> "연결 중" to Icons.Outlined.Sync
        NaviConnection.UNAVAILABLE -> "데모 모드" to Icons.Outlined.LinkOff
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** LazyGrid 안에서 전체 폭을 차지하는 섹션 제목. */
fun LazyGridScope.sectionHeader(
    title: String,
    subtitle: String? = null,
    key: String = "header_$title",
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        SectionHeader(title, subtitle)
    }
}

fun LazyGridScope.fullWidthItem(key: String, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 추천 이유를 작은 텍스트 칩 형태로 나열한다. 한눈에 읽히도록 [max]개까지만 보여준다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasonRow(reasons: List<String>, modifier: Modifier = Modifier, max: Int = 3) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        reasons.take(max).forEach { reason ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    reason,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
