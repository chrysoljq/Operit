package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 聊天运行时持有者
 *
 * 主界面和悬浮窗是平等的。
 * 同一会话 → 共享同一个 core 实例
 * 不同会话 → 各自独立 core
 *
 * 共享时 core 的 selectionMode 取决于创建者。
 * 分家时两边都会获得 selectionMode 正确的新 core。
 */
class ChatRuntimeHolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()

    // 某个 slot 的 core 被替换时发出通知（分家场景）
    private val _coreReplaced = MutableSharedFlow<ChatRuntimeSlot>(extraBufferCapacity = 4)
    val coreReplaced: SharedFlow<ChatRuntimeSlot> = _coreReplaced.asSharedFlow()

    private val _activeConversationCount = MutableStateFlow(0)
    val activeConversationCount: StateFlow<Int> = _activeConversationCount.asStateFlow()
    private val _currentSessionToolCount = MutableStateFlow(0)
    val currentSessionToolCount: StateFlow<Int> = _currentSessionToolCount.asStateFlow()

    init {
        ChatRuntimeSlot.values().forEach { slot ->
            cores[slot] = createCore(slot)
        }
        observeStats()
    }

    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore = cores[slot]!!

    /**
     * 切换某个 slot 的聊天
     *
     * - 目标 chatId 和另一边相同 → 共享 core
     * - 目标 chatId 不同 → 独立 core
     * - 从共享分家时，两边都会获得 selectionMode 正确的新 core
     */
    fun switchChat(slot: ChatRuntimeSlot, chatId: String) {
        val otherSlot = if (slot == MAIN) FLOATING else MAIN
        val otherCore = cores[otherSlot]!!
        val myCore = cores[slot]!!

        if (otherCore.currentChatId.value == chatId) {
            // 目标和另一边一样 → 共享 core
            if (myCore !== otherCore) {
                myCore.cancelCurrentMessage()
                AppLogger.d(TAG, "$slot 切到 $chatId，与 $otherSlot 共享 core")
            }
            cores[slot] = otherCore
        } else {
            // 目标不同 → 需要独立 core
            if (myCore === otherCore) {
                // 从共享分家：两边都需要新 core（旧 core 的 selectionMode 只适合一方）
                val oldChatId = otherCore.currentChatId.value

                val myNewCore = createCore(slot)
                myNewCore.switchChatLocal(chatId)
                cores[slot] = myNewCore
                _coreReplaced.tryEmit(slot)

                val otherNewCore = createCore(otherSlot)
                if (oldChatId != null) {
                    otherNewCore.switchChatLocal(oldChatId)
                }
                cores[otherSlot] = otherNewCore
                _coreReplaced.tryEmit(otherSlot)

                AppLogger.d(TAG, "共享分家: $slot→$chatId, $otherSlot→$oldChatId")
            } else {
                // 已经独立 → 直接切
                myCore.switchChatLocal(chatId)
                AppLogger.d(TAG, "$slot 切换聊天: $chatId")
            }
        }
    }

    fun isSharingCore(): Boolean = cores[MAIN] === cores[FLOATING]

    private fun createCore(slot: ChatRuntimeSlot): ChatServiceCore {
        return ChatServiceCore(
            context = appContext,
            coroutineScope = runtimeScope,
            selectionMode = when (slot) {
                ChatRuntimeSlot.MAIN -> ChatSelectionMode.FOLLOW_GLOBAL
                ChatRuntimeSlot.FLOATING -> ChatSelectionMode.LOCAL_ONLY
            }
        )
    }

    private fun observeStats() {
        val mainCore = getCore(MAIN)
        val floatingCore = getCore(FLOATING)

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                floatingCore.activeStreamingChatIds
            ) { a, b -> (a + b).size }.collect {
                _activeConversationCount.value = it
            }
        }

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                mainCore.currentTurnToolInvocationCountByChatId,
                floatingCore.activeStreamingChatIds,
                floatingCore.currentTurnToolInvocationCountByChatId
            ) { ma, mc, fa, fc ->
                countTools(ma, mc) + countTools(fa, fc)
            }.collect {
                _currentSessionToolCount.value = it
            }
        }
    }

    private fun countTools(ids: Set<String>, counts: Map<String, Int>): Int {
        return ids.sumOf { counts[it] ?: 0 }
    }

    companion object {
        private const val TAG = "ChatRuntimeHolder"
        val MAIN = ChatRuntimeSlot.MAIN
        val FLOATING = ChatRuntimeSlot.FLOATING

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
