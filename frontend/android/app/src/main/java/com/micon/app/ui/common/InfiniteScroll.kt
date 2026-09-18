package com.micon.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * 무한 스크롤 트리거. 목록의 마지막 [prefetch] 개 안으로 스크롤이 들어오면 [onLoadMore] 를 **한 번** 부른다.
 * 다음 페이지가 붙어 [itemCount] 가 바뀌거나 [key](보통 현재 페이지 번호)가 바뀌면 다시 무장된다.
 * 목록이 화면보다 짧으면 바로 발동해 화면을 채운다. [enabled] 가 false(더 없음·불러오는 중·오류)면 기다리지 않는다.
 *
 * 서버 `GET /songs` 는 페이지 최대 100 이라 첫 페이지만 보이던 것을, 스크롤로 전체 카탈로그를 이어 보게 한다.
 */
@Composable
fun LoadMoreOnScrollEnd(
    listState: LazyListState,
    enabled: Boolean,
    itemCount: Int,
    key: Any? = null,
    prefetch: Int = 8,
    onLoadMore: () -> Unit,
) {
    val latest by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled, itemCount, key) {
        if (!enabled || itemCount == 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .filter { it >= itemCount - 1 - prefetch }
            .first()
        latest()
    }
}

/** 목록 꼬리: 다음 페이지를 불러오는 중이면 작은 진행 표시, 실패했으면 메시지와 "다시 시도". 둘 다 아니면 여백만. */
@Composable
fun LoadMoreFooter(loading: Boolean, error: String?, onRetry: () -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Purple, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        error != null -> Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(error, fontSize = 12.sp, color = Grey)
            Text("다시 시도", fontSize = 13.sp, color = Purple, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        else -> Spacer(Modifier.height(24.dp))
    }
}
