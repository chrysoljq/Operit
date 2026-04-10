package com.ai.assistance.operit.ui.features.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.AiReference
import com.ai.assistance.operit.data.model.ChatMessage

import androidx.compose.ui.window.PopupProperties

import androidx.compose.material.icons.filled.AutoFixHigh

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.ui.draw.alpha
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.ui.features.chat.components.lazy.LazyListState
import com.ai.assistance.operit.ui.features.chat.components.lazy.RecyclerLazyColumn
import com.ai.assistance.operit.ui.features.chat.components.lazy.itemsIndexed
import com.ai.assistance.operit.ui.features.chat.components.style.cursor.CursorStyleChatMessage
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleImageStyleConfig
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleStyleChatMessage
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.WaifuMessageProcessor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 清理消息中的XML标签，保留Markdown格式和纯文本内容
 */
private fun cleanXmlTags(content: String): String {
    return content
        // 移除状态标签
        .replace(ChatMarkupRegex.statusTag, "")
        .replace(ChatMarkupRegex.statusSelfClosingTag, "")
        // 移除思考标签（包括 <think> 和 <thinking>）
        .replace(ChatMarkupRegex.thinkTag, "")
        .replace(ChatMarkupRegex.thinkSelfClosingTag, "")
        // 移除搜索来源标签
        .replace(ChatMarkupRegex.searchTag, "")
        .replace(ChatMarkupRegex.searchSelfClosingTag, "")
        // 移除工具标签
        .replace(ChatMarkupRegex.toolTag, "")
        .replace(ChatMarkupRegex.toolSelfClosingTag, "")
        // 移除工具结果标签
        .replace(ChatMarkupRegex.toolResultTag, "")
        .replace(ChatMarkupRegex.toolResultSelfClosingTag, "")
        // 移除emotion标签
        .replace(ChatMarkupRegex.emotionTag, "")
        // 移除附件与工作区上下文
        .replace(ChatMarkupRegex.workspaceAttachmentTag, "")
        .replace(ChatMarkupRegex.attachmentTag, "")
        .replace(ChatMarkupRegex.attachmentSelfClosingTag, "")
        // 移除多媒体链接标签
        .let(MediaLinkParser::removeImageLinks)
        .let(MediaLinkParser::removeMediaLinks)
        // 移除其他常见的XML标签
        // .replace(Regex("<[^>]*>"), "")
        .trim()
}

private data class PaginationWindow(
    val minVisibleIndex: Int,
    val hasMoreMessages: Boolean
)

private fun isPaginationTriggerMessage(message: ChatMessage): Boolean {
    return message.sender == "user" || message.sender == "ai" || message.sender == "summary"
}

private fun calculatePaginationWindow(
    chatHistory: List<ChatMessage>,
    messagesPerPage: Int,
    depth: Int
): PaginationWindow {
    if (chatHistory.isEmpty()) {
        return PaginationWindow(minVisibleIndex = 0, hasMoreMessages = false)
    }

    val safeMessagesPerPage = messagesPerPage.coerceAtLeast(1)
    val safeDepth = depth.coerceAtLeast(1)

    var cursor = chatHistory.lastIndex
    var minVisibleIndex = 0

    repeat(safeDepth) {
        if (cursor < 0) {
            minVisibleIndex = 0
            return@repeat
        }

        var triggerCountInCurrentStep = 0
        var stepStopped = false

        while (cursor >= 0) {
            val message = chatHistory[cursor]
            if (isPaginationTriggerMessage(message)) {
                triggerCountInCurrentStep += 1

                if (message.sender == "summary") {
                    minVisibleIndex = cursor
                    cursor -= 1
                    stepStopped = true
                    break
                }

                if (triggerCountInCurrentStep >= safeMessagesPerPage) {
                    minVisibleIndex = cursor
                    cursor -= 1
                    stepStopped = true
                    break
                }
            }

            cursor -= 1
        }

        if (!stepStopped) {
            minVisibleIndex = 0
            cursor = -1
            return@repeat
        }
    }

    return PaginationWindow(
        minVisibleIndex = minVisibleIndex,
        hasMoreMessages = cursor >= 0
    )
}

enum class ChatStyle {
    CURSOR,
    BUBBLE
}

@Composable
fun ChatArea(
    chatHistory: List<ChatMessage>,
    scrollState: LazyListState,
    aiReferences: List<AiReference> = emptyList(),
    isLoading: Boolean,
    enableDialogs: Boolean = true,
    userMessageColor: Color,
    aiMessageColor: Color,
    userTextColor: Color,
    aiTextColor: Color,
    systemMessageColor: Color,
    systemTextColor: Color,
    thinkingBackgroundColor: Color,
    thinkingTextColor: Color,
    hasBackgroundImage: Boolean = false,
    modifier: Modifier = Modifier,
    onSelectMessageToEdit: ((Int, ChatMessage, String) -> Unit)? = null,
    onCopyMessage: ((ChatMessage) -> Unit)? = null,
    onDeleteMessage: ((Int) -> Unit)? = null,
    onDeleteMessagesFrom: ((Int) -> Unit)? = null,
    onRollbackToMessage: ((Int) -> Unit)? = null, // 回滚到指定消息的回调
    onSpeakMessage: ((String) -> Unit)? = null, // 添加朗读回调参数
    onAutoReadMessage: ((String) -> Unit)? = null, // 添加自动朗读回调参数
    onReplyToMessage: ((ChatMessage) -> Unit)? = null, // 添加回复回调参数
    onCreateBranch: ((Long) -> Unit)? = null, // 添加创建分支回调参数
    onInsertSummary: ((Int, ChatMessage) -> Unit)? = null, // 添加插入总结回调参数
    onMentionRoleFromAvatar: ((String) -> Unit)? = null, // 长按角色头像提及
    messagesPerPage: Int = 10, // 每页显示的消息数量
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    chatStyle: ChatStyle = ChatStyle.CURSOR, // 新增参数，默认为CURSOR风格
    cursorUserBubbleLiquidGlass: Boolean = false,
    cursorUserBubbleWaterGlass: Boolean = false,
    isMultiSelectMode: Boolean = false, // 是否处于多选模式
    selectedMessageIndices: Set<Int> = emptySet(), // 已选中的消息索引集合
    onToggleMultiSelectMode: ((Int?) -> Unit)? = null, // 切换多选模式的回调，可传入要初始选中的消息索引
    onToggleMessageSelection: ((Int) -> Unit)? = null, // 切换消息选中状态的回调
    horizontalPadding: Dp = 16.dp, // 水平内边距，可自定义
    bubbleUserImageStyle: BubbleImageStyleConfig? = null,
    bubbleAiImageStyle: BubbleImageStyleConfig? = null,
    bubbleUserRoundedCornersEnabled: Boolean = true,
    bubbleAiRoundedCornersEnabled: Boolean = true,
    bubbleUserContentPaddingLeft: Float = 12f,
    bubbleUserContentPaddingRight: Float = 12f,
    bubbleAiContentPaddingLeft: Float = 12f,
    bubbleAiContentPaddingRight: Float = 12f,
    showChatFloatingDotsAnimation: Boolean = true,
) {
    // 记住当前深度状态，但当chatHistory发生变化时重置为1
    var currentDepth = remember(chatHistory) { mutableStateOf(1) }
    val messageHeightMemory = rememberChatMessageHeightMemory(chatHistory)
    val lastMessage = chatHistory.lastOrNull()
    // 仅在首个chunk到达前显示加载点，避免父层跟随每个chunk重组。
    var hasLastAiMessageStartedStreaming by remember(lastMessage?.timestamp) {
        mutableStateOf(
            lastMessage?.let { it.sender == "ai" && it.content.isNotBlank() } == true
        )
    }
    LaunchedEffect(lastMessage?.timestamp, lastMessage?.contentStream) {
        val lastAiMessageHasStaticContent =
            lastMessage?.let { it.sender == "ai" && it.content.isNotBlank() } == true
        hasLastAiMessageStartedStreaming = lastAiMessageHasStaticContent

        val shouldAwaitFirstChunk =
            lastMessage?.let {
                it.sender == "ai" && it.content.isBlank() && it.contentStream != null
            } == true
        val stream = lastMessage?.contentStream

        if (!lastAiMessageHasStaticContent && shouldAwaitFirstChunk && stream != null) {
            stream.collect { chunk ->
                if (!hasLastAiMessageStartedStreaming && chunk.isNotEmpty()) {
                    hasLastAiMessageStartedStreaming = true
                }
            }
        }
    }

    val showLoadingIndicator =
        isLoading &&
            (
                lastMessage?.sender == "user" ||
                    lastMessage?.let {
                        it.sender == "ai" &&
                            it.content.isBlank() &&
                            !hasLastAiMessageStartedStreaming
                    } == true
            )
    val shouldHideLastAiMessage =
        showLoadingIndicator && chatStyle == ChatStyle.BUBBLE && lastMessage?.sender == "ai"

    val messagesCount = chatHistory.size
    val paginationWindow =
        calculatePaginationWindow(
            chatHistory = chatHistory,
            messagesPerPage = messagesPerPage,
            depth = currentDepth.value,
        )
    val minVisibleIndex = paginationWindow.minVisibleIndex
    val hasMoreMessages = paginationWindow.hasMoreMessages
    val visibleMessages = chatHistory.subList(minVisibleIndex, messagesCount)
    var wasAtTop by remember(messagesCount, minVisibleIndex, hasMoreMessages) { mutableStateOf(false) }

    LaunchedEffect(scrollState, messagesCount, minVisibleIndex, hasMoreMessages) {
        snapshotFlow {
            scrollState.firstVisibleItemIndex == 0 && scrollState.firstVisibleItemScrollOffset == 0
        }.collect { isAtTop ->
            if (hasMoreMessages && isAtTop && !wasAtTop) {
                currentDepth.value += 1
            }
            wasAtTop = isAtTop
        }
    }

    Box(
        modifier =
            modifier
            .fillMaxWidth()
            .background(Color.Transparent),
    ) {
        RecyclerLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = horizontalPadding,
                    top = topPadding,
                    end = horizontalPadding,
                    bottom = bottomPadding,
                ),
        ) {
            itemsIndexed(
                items = visibleMessages,
                key = { _, message -> message.timestamp },
            ) { relativeIndex, message ->
                val actualIndex = minVisibleIndex + relativeIndex
                val isLastAiMessage = actualIndex == messagesCount - 1 && message.sender == "ai"
                val shouldHide = shouldHideLastAiMessage && isLastAiMessage

                MessageItem(
                    index = actualIndex,
                    message = message,
                    enableDialogs = enableDialogs,
                    userMessageColor = userMessageColor,
                    aiMessageColor = aiMessageColor,
                    userTextColor = userTextColor,
                    aiTextColor = aiTextColor,
                    systemMessageColor = systemMessageColor,
                    systemTextColor = systemTextColor,
                    thinkingBackgroundColor = thinkingBackgroundColor,
                    thinkingTextColor = thinkingTextColor,
                    onSelectMessageToEdit = onSelectMessageToEdit,
                    onCopyMessage = onCopyMessage,
                    onDeleteMessage = onDeleteMessage,
                    onDeleteMessagesFrom = onDeleteMessagesFrom,
                    onRollbackToMessage = onRollbackToMessage,
                    onSpeakMessage = onSpeakMessage,
                    onReplyToMessage = onReplyToMessage,
                    onCreateBranch = onCreateBranch,
                    onInsertSummary = onInsertSummary,
                    onMentionRoleFromAvatar = onMentionRoleFromAvatar,
                    chatStyle = chatStyle,
                    cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                    cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                    isHidden = shouldHide,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = selectedMessageIndices.contains(actualIndex),
                    onToggleSelection = { onToggleMessageSelection?.invoke(actualIndex) },
                    onToggleMultiSelectMode = onToggleMultiSelectMode,
                    messageIndex = actualIndex,
                    bubbleUserImageStyle = bubbleUserImageStyle,
                    bubbleAiImageStyle = bubbleAiImageStyle,
                    bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                    bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                    bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                    bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                    bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                    bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                    messageHeightMemory = messageHeightMemory,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showLoadingIndicator) {
                item(key = "loading_indicator") {
                    when (chatStyle) {
                        ChatStyle.BUBBLE -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 0.dp)
                                        .offset(y = (-24).dp),
                            ) {
                                Box(modifier = Modifier.padding(start = 16.dp)) {
                                    if (showChatFloatingDotsAnimation) {
                                        LoadingDotsIndicator(aiTextColor)
                                    }
                                }
                            }
                        }

                        ChatStyle.CURSOR -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 0.dp),
                            ) {
                                Box(modifier = Modifier.padding(start = 16.dp)) {
                                    if (showChatFloatingDotsAnimation) {
                                        LoadingDotsIndicator(aiTextColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "chat_bottom_spacing") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        ChatQuickScroller(
            listState = scrollState,
            itemCount = visibleMessages.size,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = topPadding + 6.dp, bottom = bottomPadding + 6.dp, end = 2.dp),
        )
    }
}

/** 单个消息项组件 将消息渲染逻辑提取到单独的组件，减少重组范围 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    index: Int,
    message: ChatMessage,
    enableDialogs: Boolean,
    userMessageColor: Color,
    aiMessageColor: Color,
    userTextColor: Color,
    aiTextColor: Color,
    systemMessageColor: Color,
    systemTextColor: Color,
    thinkingBackgroundColor: Color,
    thinkingTextColor: Color,
    onSelectMessageToEdit: ((Int, ChatMessage, String) -> Unit)?,
    onCopyMessage: ((ChatMessage) -> Unit)?,
    onDeleteMessage: ((Int) -> Unit)?,
    onDeleteMessagesFrom: ((Int) -> Unit)?,
    onRollbackToMessage: ((Int) -> Unit)? = null, // 回滚到指定消息的回调
    onSpeakMessage: ((String) -> Unit)? = null, // 添加朗读回调
    onReplyToMessage: ((ChatMessage) -> Unit)? = null, // 添加回复回调
    onCreateBranch: ((Long) -> Unit)? = null, // 添加创建分支回调
    onInsertSummary: ((Int, ChatMessage) -> Unit)? = null, // 添加插入总结回调
    onMentionRoleFromAvatar: ((String) -> Unit)? = null, // 长按角色头像提及
    chatStyle: ChatStyle, // 新增参数
    cursorUserBubbleLiquidGlass: Boolean = false,
    cursorUserBubbleWaterGlass: Boolean = false,
    isHidden: Boolean = false, // 新增参数控制隐藏
    isMultiSelectMode: Boolean = false, // 是否处于多选模式
    isSelected: Boolean = false, // 是否被选中
    onToggleSelection: (() -> Unit)? = null, // 切换选中状态的回调
    onToggleMultiSelectMode: ((Int?) -> Unit)? = null, // 切换多选模式的回调，可传入要初始选中的消息索引
    messageIndex: Int, // 消息索引，用于进入多选时自动选中
    bubbleUserImageStyle: BubbleImageStyleConfig? = null,
    bubbleAiImageStyle: BubbleImageStyleConfig? = null,
    bubbleUserRoundedCornersEnabled: Boolean = true,
    bubbleAiRoundedCornersEnabled: Boolean = true,
    bubbleUserContentPaddingLeft: Float = 12f,
    bubbleUserContentPaddingRight: Float = 12f,
    bubbleAiContentPaddingLeft: Float = 12f,
    bubbleAiContentPaddingRight: Float = 12f,
    messageHeightMemory: ChatMessageHeightMemory,
) {
    val context = LocalContext.current
    var showContextMenu by remember { mutableStateOf(false) }
    var showMessageInfoDialog by remember { mutableStateOf(false) }


    // 只有用户和AI的消息才能被操作
    val isActionable = message.sender == "user" || message.sender == "ai"

    Box(
        modifier =
        Modifier
            .alpha(if (isHidden) 0f else 1f)
            .then(
                if (isSelected) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode && isActionable) {
                        onToggleSelection?.invoke()
                    }
                },
                onLongClick = { 
                    if (!isMultiSelectMode && isActionable) {
                        showContextMenu = true
                    }
                },
            ),
    ) {
        when (chatStyle) {
            ChatStyle.CURSOR -> {
                CursorStyleChatMessage(
                    message = message,
                    userMessageColor = userMessageColor,
                    userMessageLiquidGlassEnabled = cursorUserBubbleLiquidGlass,
                    userMessageWaterGlassEnabled = cursorUserBubbleWaterGlass,
                    aiMessageColor = aiMessageColor,
                    userTextColor = userTextColor,
                    aiTextColor = aiTextColor,
                    systemMessageColor = systemMessageColor,
                    systemTextColor = systemTextColor,
                    thinkingBackgroundColor = thinkingBackgroundColor,
                    thinkingTextColor = thinkingTextColor,
                    supportToolMarkup = true,
                    initialThinkingExpanded = true,
                    heightMemory = messageHeightMemory,
                    onDeleteMessage = onDeleteMessage,
                    index = index,
                    enableDialogs = enableDialogs,
                    onEditSummary = { summaryMessage ->
                        onSelectMessageToEdit?.invoke(index, summaryMessage, "summary")
                    }
                )
            }

            ChatStyle.BUBBLE -> {
                BubbleStyleChatMessage(
                    message = message,
                    userMessageColor = userMessageColor,
                    aiMessageColor = aiMessageColor,
                    userTextColor = userTextColor,
                    aiTextColor = aiTextColor,
                    systemMessageColor = systemMessageColor,
                    systemTextColor = systemTextColor,
                    userBubbleImageStyle = bubbleUserImageStyle,
                    aiBubbleImageStyle = bubbleAiImageStyle,
                    bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                    bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                    bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                    bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                    bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                    bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                    isHidden = isHidden,
                    heightMemory = messageHeightMemory,
                    onDeleteMessage = onDeleteMessage,
                    index = index,
                    enableDialogs = enableDialogs,
                    onRoleAvatarLongPress = onMentionRoleFromAvatar,
                    onEditSummary = { summaryMessage ->
                        onSelectMessageToEdit?.invoke(index, summaryMessage, "summary")
                    }
                )
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier
                .width(140.dp)
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp)),
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            // 复制选项
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.copy_message),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    val clipboardManager =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val cleanContent = cleanXmlTags(message.content)
                    val clipData =
                        ClipData.newPlainText(
                            context.getString(R.string.chat_clipboard_label_message),
                            cleanContent
                        )
                    clipboardManager.setPrimaryClip(clipData)
                    Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                    onCopyMessage?.invoke(message)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(id = R.string.copy_message),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            // 朗读消息选项
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.read_message),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    onSpeakMessage?.invoke(message.content)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.read_message),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            // 根据消息发送者显示不同的操作
            if (message.sender == "user") {
                // 编辑并重发选项
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.edit_and_resend),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                        )
                    },
                    onClick = {
                        onSelectMessageToEdit?.invoke(index, message, "user")
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(id = R.string.edit_and_resend),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(36.dp)
                )
                // 回滚到此处
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.rollback_to_here),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                        )
                    },
                    onClick = {
                        onRollbackToMessage?.invoke(index)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(id = R.string.rollback_to_here),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(36.dp)
                )
            } else if (message.sender == "ai") {
                // 修改记忆选项
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.modify_memory),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                        )
                    },
                    onClick = {
                        onSelectMessageToEdit?.invoke(index, message, "ai")
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = stringResource(id = R.string.modify_memory),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(36.dp)
                )
            }

            // 删除
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.delete),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    onDeleteMessage?.invoke(index)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            // 回复选项
            if (message.sender == "ai") {
                DropdownMenuItem(
                text = {
                        Text(
                            stringResource(R.string.reply_message),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp
                       )
                },
                onClick = {
                        onReplyToMessage?.invoke(message)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Reply,
                            contentDescription = stringResource(R.string.reply_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(36.dp)
                )
            }

            // 插入总结
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.insert_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    onInsertSummary?.invoke(index, message)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Summarize,
                        contentDescription = stringResource(id = R.string.insert_summary),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            // 创建分支
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.create_branch),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    onCreateBranch?.invoke(message.timestamp)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = stringResource(id = R.string.create_branch),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            // 信息
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.info),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    showContextMenu = false
                    showMessageInfoDialog = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )

            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.multi_select),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                onClick = {
                    onToggleMultiSelectMode?.invoke(messageIndex) // 传入消息索引
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(id = R.string.multi_select),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.height(36.dp)
            )
        }

        if (showMessageInfoDialog) {
            MessageInfoDialog(
                message = message,
                onDismiss = { showMessageInfoDialog = false }
            )
        }
    }
}

@Composable
private fun LoadingDotsIndicator(textColor: Color) {
    val infiniteTransition = rememberInfiniteTransition()

    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val jumpHeight = -5f
        val animationDelay = 160

        (0..2).forEach { index ->
            val offsetY by
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = jumpHeight,
                animationSpec =
                infiniteRepeatable(
                    animation =
                    keyframes {
                        durationMillis = 600
                        0f at 0
                        jumpHeight * 0.4f at 100
                        jumpHeight * 0.8f at 200
                        jumpHeight at 300
                        jumpHeight * 0.8f at 400
                        jumpHeight * 0.4f at 500
                        0f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * animationDelay),
                ),
                label = "",
            )

            Box(
                modifier =
                Modifier
                    .size(6.dp)
                    .offset(y = offsetY.dp)
                    .background(
                        color = textColor.copy(alpha = 0.6f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
