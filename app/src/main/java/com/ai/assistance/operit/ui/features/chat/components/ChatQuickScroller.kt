package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.components.lazy.LazyListState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class ChatQuickScrollerSnapshot(
    val totalItemCount: Int,
    val shouldShow: Boolean,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
    val isScrollInProgress: Boolean,
    val viewportHeightPx: Float,
    val contentHeightPx: Float,
    val scrollOffsetPx: Float,
)

@Stable
private class ChatQuickScrollerUiState {
    var totalItemCount by mutableStateOf(0)
    var shouldShow by mutableStateOf(false)
    var canScrollBackward by mutableStateOf(false)
    var canScrollForward by mutableStateOf(false)
    var isScrollInProgress by mutableStateOf(false)
    var viewportHeightPx by mutableStateOf(0f)
    var contentHeightPx by mutableStateOf(0f)
    var scrollOffsetPx by mutableStateOf(0f)

    fun update(snapshot: ChatQuickScrollerSnapshot) {
        totalItemCount = snapshot.totalItemCount
        shouldShow = snapshot.shouldShow
        canScrollBackward = snapshot.canScrollBackward
        canScrollForward = snapshot.canScrollForward
        isScrollInProgress = snapshot.isScrollInProgress
        viewportHeightPx = snapshot.viewportHeightPx
        contentHeightPx = snapshot.contentHeightPx
        scrollOffsetPx = snapshot.scrollOffsetPx
    }
}

@Composable
private fun rememberChatQuickScrollerUiState(
    listState: LazyListState,
    itemCount: Int,
): ChatQuickScrollerUiState {
    val uiState = remember { ChatQuickScrollerUiState() }
    val currentItemCount by rememberUpdatedState(itemCount.coerceAtLeast(0))

    LaunchedEffect(listState, itemCount) {
        snapshotFlow {
            val indicatorState = listState.scrollIndicatorState
            val viewportHeightPx = indicatorState?.viewportSize?.toFloat()?.coerceAtLeast(0f) ?: 0f
            val contentHeightPx =
                indicatorState?.contentSize?.toFloat()?.coerceAtLeast(viewportHeightPx) ?: 0f

            ChatQuickScrollerSnapshot(
                totalItemCount = currentItemCount,
                shouldShow =
                    currentItemCount > 1 &&
                        viewportHeightPx > 0f &&
                        contentHeightPx > viewportHeightPx,
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward,
                isScrollInProgress = listState.isScrollInProgress,
                viewportHeightPx = viewportHeightPx,
                contentHeightPx = contentHeightPx,
                scrollOffsetPx = (indicatorState?.scrollOffset ?: 0).toFloat().coerceAtLeast(0f),
            )
        }.collect { snapshot ->
            uiState.update(snapshot)
        }
    }

    return uiState
}

@Composable
internal fun ChatQuickScroller(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val uiState = rememberChatQuickScrollerUiState(listState = listState, itemCount = itemCount)
    if (!uiState.shouldShow) {
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minThumbHeightPx = with(density) { 36.dp.toPx() }
    val trackWidth = 12.dp
    var trackHeightPx by remember { mutableStateOf(0f) }
    var isHandlingTouch by remember { mutableStateOf(false) }
    val maxScrollOffsetPx = (uiState.contentHeightPx - uiState.viewportHeightPx).coerceAtLeast(1f)
    val scrollProgress = (uiState.scrollOffsetPx / maxScrollOffsetPx).coerceIn(0f, 1f)
    val visibleFraction =
        if (uiState.contentHeightPx <= 0f) {
            1f
        } else {
            (uiState.viewportHeightPx / uiState.contentHeightPx).coerceIn(0.12f, 1f)
        }
    val thumbHeightPx =
        if (trackHeightPx <= 0f) {
            minThumbHeightPx
        } else {
            (trackHeightPx * visibleFraction).coerceIn(minThumbHeightPx, trackHeightPx)
        }
    val maxThumbOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbOffsetPx = maxThumbOffsetPx * scrollProgress
    val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
    val thumbOffsetDp = with(density) { thumbOffsetPx.toDp() }
    val fastScrollDescription = stringResource(R.string.history_fast_scroll)
    val quickScrollerAlpha = if (uiState.isScrollInProgress || isHandlingTouch) 0.9f else 0.5f
    val hostView = LocalView.current
    val currentTrackHeightPx by rememberUpdatedState(trackHeightPx)
    val currentThumbHeightPx by rememberUpdatedState(thumbHeightPx)
    val currentMaxScrollOffsetPx by rememberUpdatedState(maxScrollOffsetPx)
    val currentScrollOffsetPx by rememberUpdatedState(uiState.scrollOffsetPx)
    val currentTotalItemCount by rememberUpdatedState(uiState.totalItemCount)

    DisposableEffect(Unit) {
        onDispose {
            isHandlingTouch = false
        }
    }

    fun jumpToPointer(pointerY: Float) {
        if (currentTrackHeightPx <= 0f || currentTotalItemCount <= 1) {
            return
        }
        val trackableHeight = (currentTrackHeightPx - currentThumbHeightPx).coerceAtLeast(1f)
        val normalizedOffset = (pointerY - currentThumbHeightPx / 2f).coerceIn(0f, trackableHeight)
        val progress = (normalizedOffset / trackableHeight).coerceIn(0f, 1f)
        val targetScrollOffsetPx = progress * currentMaxScrollOffsetPx
        val scrollDeltaPx = targetScrollOffsetPx - currentScrollOffsetPx
        if (scrollDeltaPx != 0f) {
            listState.dispatchRawDelta(scrollDeltaPx)
        }
    }

    fun scrollByPointerDelta(pointerDeltaY: Float) {
        if (currentTrackHeightPx <= 0f || currentTotalItemCount <= 1) {
            return
        }
        val trackableHeight = (currentTrackHeightPx - currentThumbHeightPx).coerceAtLeast(1f)
        val contentDeltaPx = pointerDeltaY * (currentMaxScrollOffsetPx / trackableHeight)
        if (contentDeltaPx != 0f) {
            listState.dispatchRawDelta(contentDeltaPx)
        }
    }

    fun startHandlingTouch(pointerY: Float) {
        isHandlingTouch = true
        hostView.parent?.requestDisallowInterceptTouchEvent(true)
        jumpToPointer(pointerY)
    }

    fun stopHandlingTouch() {
        isHandlingTouch = false
        hostView.parent?.requestDisallowInterceptTouchEvent(false)
    }

    Column(
        modifier =
            modifier
                .width(20.dp)
                .alpha(quickScrollerAlpha)
                .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatQuickScrollButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.history_scroll_to_top),
            enabled = uiState.canScrollBackward,
            onClick = {
                coroutineScope.launch {
                    listState.animateScrollToItem(0)
                }
            },
        )

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .width(28.dp)
                    .padding(vertical = 4.dp)
                    .onGloballyPositioned { coordinates ->
                        trackHeightPx = coordinates.size.height.toFloat()
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            startHandlingTouch(down.position.y)
                            down.consume()
                            try {
                                var previousPointerY = down.position.y
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val pointerDeltaY = change.position.y - previousPointerY
                                    previousPointerY = change.position.y
                                    if (pointerDeltaY != 0f) {
                                        scrollByPointerDelta(pointerDeltaY)
                                    }
                                    change.consume()
                                }
                            } finally {
                                stopHandlingTouch()
                            }
                        }
                    }
                    .semantics {
                        contentDescription = fastScrollDescription
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .width(trackWidth)
                        .fillMaxHeight(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = thumbOffsetDp)
                            .width(8.dp)
                            .height(thumbHeightDp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                )
            }
        }

        ChatQuickScrollButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.history_scroll_to_bottom),
            enabled = uiState.canScrollForward,
            onClick = {
                coroutineScope.launch {
                    listState.animateScrollToItem((uiState.totalItemCount - 1).coerceAtLeast(0))
                }
            },
        )
    }
}

@Composable
private fun ChatQuickScrollButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color =
            if (enabled) {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f)
            } else {
                Color.Transparent
            },
        shadowElevation = if (enabled) 1.dp else 0.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(22.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                    },
            )
        }
    }
}
