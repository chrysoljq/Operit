package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.chat.components.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 滚动到底部按钮组件
 * 
 * @param scrollState 滚动状态
 * @param coroutineScope 协程作用域
 * @param autoScrollToBottom 是否自动滚动到底部
 * @param onAutoScrollToBottomChange 自动滚动状态变化回调
 * @param modifier 修饰符
 */
@Composable
fun ScrollToBottomButton(
    scrollState: LazyListState,
    coroutineScope: CoroutineScope,
    autoScrollToBottom: Boolean,
    onAutoScrollToBottomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showScrollButton by remember { mutableStateOf(false) }
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()

    // 核心滚动逻辑 - 监听用户的手动滚动行为
    LaunchedEffect(scrollState) {
        var lastIndex = scrollState.firstVisibleItemIndex
        var lastOffset = scrollState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                scrollState.firstVisibleItemIndex,
                scrollState.firstVisibleItemScrollOffset,
                scrollState.canScrollForward,
            )
        }
            .distinctUntilChanged()
            .collect { (currentIndex, currentOffset, _) ->
                if (scrollState.isScrollInProgress) {
                    val scrolledUp =
                        currentIndex < lastIndex ||
                            (currentIndex == lastIndex && currentOffset < lastOffset)
                    if (scrolledUp) {
                        if (autoScrollToBottom && isDragged) {
                            onAutoScrollToBottomChange(false)
                            showScrollButton = true
                        }
                    } else {
                        val isAtBottom = scrollState.isAtBottom()
                        if (isAtBottom && !autoScrollToBottom) {
                            onAutoScrollToBottomChange(true)
                            showScrollButton = false
                        }
                    }
                }
                lastIndex = currentIndex
                lastOffset = currentOffset
            }
    }

    AnimatedVisibility(
        visible = showScrollButton,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        IconButton(
            onClick = {
                coroutineScope.launch {
                    val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        scrollState.animateScrollToItem(lastIndex)
                    }
                }
                onAutoScrollToBottomChange(true)
                showScrollButton = false
            },
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(50)
                )
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun LazyListState.isAtBottom(): Boolean {
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount == 0) {
        return true
    }
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return !canScrollForward && lastVisibleItemIndex >= layoutInfo.totalItemsCount - 1
}
