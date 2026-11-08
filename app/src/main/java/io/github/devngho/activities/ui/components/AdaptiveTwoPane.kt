package io.github.devngho.activities.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** M3 window size class 의 Expanded 기준. */
private val EXPANDED_WIDTH = 840.dp

/**
 * 넓은 화면(차량 디스플레이)에서는 왼쪽 = 입력/상황, 오른쪽 = 추천 결과의 2단으로,
 * 좁은 화면에서는 한 줄로 이어 보여준다. 스크롤 없이 한눈에 파악할 수 있게 하기 위함.
 */
@Composable
fun AdaptiveTwoPane(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    bottomExtra: Dp = 16.dp,
    leftWeight: Float = 0.42f,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
) {
    val top = padding.calculateTopPadding()
    val bottom = padding.calculateBottomPadding() + bottomExtra
    val spacing = Arrangement.spacedBy(12.dp)

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= EXPANDED_WIDTH) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LazyColumn(
                    Modifier.weight(leftWeight).fillMaxHeight(),
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                    verticalArrangement = spacing,
                    content = left,
                )
                LazyColumn(
                    Modifier.weight(1f - leftWeight).fillMaxHeight(),
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                    verticalArrangement = spacing,
                    content = right,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
                verticalArrangement = spacing,
            ) {
                left()
                right()
            }
        }
    }
}
