package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ChatRuntimeHolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()
    private val _activeConversationCount = MutableStateFlow(0)
    val activeConversationCount: StateFlow<Int> = _activeConversationCount.asStateFlow()
    private val _currentSessionToolCount = MutableStateFlow(0)
    val currentSessionToolCount: StateFlow<Int> = _currentSessionToolCount.asStateFlow()

    init {
        ChatRuntimeSlot.values().forEach { slot ->
            getCore(slot)
        }
        setupCrossSessionSync()
        observeStats()
    }

    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore {
        return cores.getOrPut(slot) {
            ChatServiceCore(
                context = appContext,
                coroutineScope = runtimeScope,
                selectionMode = when (slot) {
                    ChatRuntimeSlot.MAIN -> ChatSelectionMode.FOLLOW_GLOBAL
                    ChatRuntimeSlot.FLOATING -> ChatSelectionMode.LOCAL_ONLY
                }
            )
        }
    }

    /**
     * 获取悬浮窗应该使用的 core 实例
     * 如果悬浮窗和主界面看同一个聊天，返回主界面的 core（共享）
     * 如果看不同聊天，返回悬浮窗的独立 core
     */
    fun getFloatingCore(): ChatServiceCore {
        val mainCore = getCore(ChatRuntimeSlot.MAIN)
        val floatingCore = getCore(ChatRuntimeSlot.FLOATING)
        
        val mainChatId = mainCore.currentChatId.value
        val floatingChatId = floatingCore.currentChatId.value
        
        // 如果悬浮窗没有独立聊天，或者和主界面看同一个聊天，返回主界面的 core
        return if (floatingChatId == null || floatingChatId == mainChatId) {
            mainCore
        } else {
            floatingCore
        }
    }

    /**
     * 悬浮窗切换聊天时调用
     * 如果切换到和主界面一样的聊天，销毁独立 core，重新跟随主界面
     * 如果切换到不同聊天，创建独立 core
     */
    fun switchFloatingChat(chatId: String) {
        val mainCore = getCore(ChatRuntimeSlot.MAIN)
        val mainChatId = mainCore.currentChatId.value
        
        if (chatId == mainChatId) {
            // 切换到和主界面一样的聊天，销毁独立 core，重新跟随主界面
            cores.remove(ChatRuntimeSlot.FLOATING)
            AppLogger.d(TAG, "悬浮窗切换到主界面聊天，销毁独立 core，重新跟随主界面")
        } else {
            // 切换到不同聊天，创建独立 core
            val floatingCore = getCore(ChatRuntimeSlot.FLOATING)
            floatingCore.switchChatLocal(chatId)
            AppLogger.d(TAG, "悬浮窗切换到独立聊天，创建独立 core: $chatId")
        }
    }

    private fun observeStats() {
        val mainCore = getCore(ChatRuntimeSlot.MAIN)
        val floatingCore = getCore(ChatRuntimeSlot.FLOATING)

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                floatingCore.activeStreamingChatIds
            ) { mainActiveChatIds, floatingActiveChatIds ->
                (mainActiveChatIds + floatingActiveChatIds).size
            }.collect { count ->
                _activeConversationCount.value = count
            }
        }

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                mainCore.currentTurnToolInvocationCountByChatId,
                floatingCore.activeStreamingChatIds,
                floatingCore.currentTurnToolInvocationCountByChatId
            ) { mainActiveChatIds, mainCounts, floatingActiveChatIds, floatingCounts ->
                countCurrentTurnToolsForActiveChats(mainActiveChatIds, mainCounts) +
                    countCurrentTurnToolsForActiveChats(floatingActiveChatIds, floatingCounts)
            }.collect { count ->
                _currentSessionToolCount.value = count
            }
        }
    }

    private fun countCurrentTurnToolsForActiveChats(
        activeChatIds: Set<String>,
        countMap: Map<String, Int>
    ): Int {
        return activeChatIds.sumOf { chatId -> countMap[chatId] ?: 0 }
    }

    private fun setupCrossSessionSync() {
        registerChatSelectionSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.FLOATING,
            targetSlot = ChatRuntimeSlot.MAIN
        )
    }

    private fun registerTurnSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)
        val targetCore = getCore(targetSlot)

        sourceCore.setAdditionalOnTurnComplete { chatId, inputTokens, outputTokens, windowSize ->
            if (chatId.isNullOrBlank()) {
                return@setAdditionalOnTurnComplete
            }
            if (targetCore.currentChatId.value != chatId) {
                return@setAdditionalOnTurnComplete
            }

            runtimeScope.launch {
                try {
                    targetCore.reloadChatMessagesSmart(chatId)
                    targetCore.getTokenStatisticsDelegate()
                        .setTokenCounts(chatId, inputTokens, outputTokens, windowSize)
                    AppLogger.d(
                        TAG,
                        "跨 Session smart 同步完成: $sourceSlot -> $targetSlot, chatId=$chatId, input=$inputTokens, output=$outputTokens, window=$windowSize"
                    )
                } catch (e: Exception) {
                    AppLogger.e(
                        TAG,
                        "跨 Session smart 同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                        e
                    )
                }
            }
        }
    }

    fun syncMainChatSelectionToFloating(chatId: String) {
        if (chatId.isBlank()) return
        syncChatSelection(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING,
            chatId = chatId
        )
    }

    private fun registerChatSelectionSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)

        runtimeScope.launch {
            sourceCore.currentChatId
                .collect { chatId ->
                    if (chatId.isNullOrBlank()) {
                        return@collect
                    }
                    syncChatSelection(sourceSlot, targetSlot, chatId)
                }
        }
    }

    private fun syncChatSelection(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot,
        chatId: String
    ) {
        val targetCore = getCore(targetSlot)
        if (targetCore.currentChatId.value == chatId) {
            return
        }

        try {
            targetCore.switchChatLocal(chatId)
            AppLogger.d(
                TAG,
                "跨 Session 当前聊天同步: $sourceSlot -> $targetSlot, chatId=$chatId"
            )
        } catch (e: Exception) {
            AppLogger.e(
                TAG,
                "跨 Session 当前聊天同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                e
            )
        }
    }

    companion object {
        private const val TAG = "ChatRuntimeHolder"

        @Volatile
        private var instance: ChatRuntimeHolder? = null

        fun getInstance(context: Context): ChatRuntimeHolder {
            return instance ?: synchronized(this) {
                instance ?: ChatRuntimeHolder(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
