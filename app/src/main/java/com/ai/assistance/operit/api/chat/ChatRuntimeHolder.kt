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
        // 方案A：FLOATING 共享 MAIN 的 core 实例，实现天然同步
        val effectiveSlot = when (slot) {
            ChatRuntimeSlot.FLOATING -> ChatRuntimeSlot.MAIN
            else -> slot
        }
        return cores.getOrPut(effectiveSlot) {
            ChatServiceCore(
                context = appContext,
                coroutineScope = runtimeScope,
                selectionMode = ChatSelectionMode.FOLLOW_GLOBAL
            )
        }
    }

    private fun observeStats() {
        // 方案A：FLOATING 和 MAIN 共享同一个 core，只需统计一次
        val sharedCore = getCore(ChatRuntimeSlot.MAIN)

        runtimeScope.launch {
            sharedCore.activeStreamingChatIds.collect { activeChatIds ->
                _activeConversationCount.value = activeChatIds.size
            }
        }

        runtimeScope.launch {
            combine(
                sharedCore.activeStreamingChatIds,
                sharedCore.currentTurnToolInvocationCountByChatId
            ) { activeChatIds, counts ->
                countCurrentTurnToolsForActiveChats(activeChatIds, counts)
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
        // 方案A：FLOATING 和 MAIN 共享同一个 core，无需跨实例同步
        // 保留空方法以备将来扩展
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
