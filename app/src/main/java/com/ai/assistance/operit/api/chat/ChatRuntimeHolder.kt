package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/**
 * 聊天运行时持有者
 *
 * 主界面和悬浮窗是平等的。
 * 同一会话 → 共享同一个 core 实例
 * 不同会话 → 各自独立 core
 *
 * 共享时 core 的 selectionMode 取决于创建者。
 * 分家时只有切换方获得新 core，另一边保持不变。
 *
 * 共享策略：悬浮窗开启时尝试与主页面共享，之后禁用再共享。
 * 即使双方切到同一会话也不再共享。
 */
class ChatRuntimeHolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()

    // 某个 slot 的 core 被替换时发出通知（分家场景）
    private val _coreReplaced = MutableSharedFlow<ChatRuntimeSlot>(extraBufferCapacity = 4)
    val coreReplaced: SharedFlow<ChatRuntimeSlot> = _coreReplaced.asSharedFlow()
    private val coreReplacedListeners = CopyOnWriteArrayList<(ChatRuntimeSlot) -> Unit>()

    // 共享开关：悬浮窗开启时允许共享一次，之后禁用
    @Volatile
    private var sharingEnabled = true

    private val _activeConversationCount = MutableStateFlow(0)
    val activeConversationCount: StateFlow<Int> = _activeConversationCount.asStateFlow()
    private val _currentSessionToolCount = MutableStateFlow(0)
    val currentSessionToolCount: StateFlow<Int> = _currentSessionToolCount.asStateFlow()

    private var statsJob: Job? = null

    init {
        ChatRuntimeSlot.values().forEach { slot ->
            cores[slot] = createCore(slot)
        }
        observeStats()
        // Re-subscribe stats whenever a core is replaced (fork scenario)
        runtimeScope.launch {
            coreReplaced.collect { observeStats() }
        }
    }

    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore = cores[slot]!!

    fun addCoreReplacedListener(listener: (ChatRuntimeSlot) -> Unit): () -> Unit {
        coreReplacedListeners.add(listener)
        return { coreReplacedListeners.remove(listener) }
    }

    private fun notifyCoreReplaced(slot: ChatRuntimeSlot) {
        _coreReplaced.tryEmit(slot)
        coreReplacedListeners.forEach { listener ->
            try {
                listener(slot)
            } catch (e: Exception) {
                AppLogger.e(TAG, "core 替换监听回调失败: $slot", e)
            }
        }
    }

    /**
     * 仅用于悬浮窗启动时的初始共享：如果另一侧当前就是目标 chatId，则直接共享
     * 另一侧 core。这里不取消消息、不切换会话，只替换 slot 引用。
     */
    fun shareSlotWithOtherIfSameChat(slot: ChatRuntimeSlot, chatId: String): Boolean {
        synchronized(this) {
            if (!sharingEnabled) {
                return false
            }
            val otherSlot = if (slot == MAIN) FLOATING else MAIN
            val otherCore = cores[otherSlot]!!
            if (otherCore.currentChatId.value != chatId) {
                return false
            }

            val myCore = cores[slot]!!
            if (myCore !== otherCore) {
                cores[slot] = otherCore
                AppLogger.d(TAG, "$slot 初始共享 $otherSlot core: chatId=$chatId")
            }
            return true
        }
    }

    fun shareSlotWithOtherCurrentChat(slot: ChatRuntimeSlot, chatId: String): Boolean {
        var replacedSlot: ChatRuntimeSlot? = null
        val shared =
            synchronized(this) {
                val otherSlot = if (slot == MAIN) FLOATING else MAIN
                val otherCore = cores[otherSlot]!!
                if (otherCore.currentChatId.value != chatId) {
                    return@synchronized false
                }

                val myCore = cores[slot]!!
                if (myCore !== otherCore) {
                    cores[slot] = otherCore
                    replacedSlot = slot
                    AppLogger.d(TAG, "$slot 切回 $chatId，与 $otherSlot 重新共享 core")
                }
                true
            }
        replacedSlot?.let(::notifyCoreReplaced)
        return shared
    }

    /**
     * 切换某个 slot 的聊天
     *
     * - 目标 chatId 和另一边相同 → 共享 core
     * - 目标 chatId 不同 → 独立 core
     * - 从共享分家时，只给切换方创建新 core，另一边保持不变
     */
    fun switchChat(slot: ChatRuntimeSlot, chatId: String) {
        var replacedSlot: ChatRuntimeSlot? = null
        synchronized(this) {
            val otherSlot = if (slot == MAIN) FLOATING else MAIN
            val otherCore = cores[otherSlot]!!
            val myCore = cores[slot]!!

            if (otherCore.currentChatId.value == chatId) {
                // 目标和另一边一样 → 共享 core
                if (myCore !== otherCore) {
                    AppLogger.d(TAG, "$slot 切到 $chatId，与 $otherSlot 共享 core")
                    cores[slot] = otherCore
                    replacedSlot = slot
                }
            } else {
                // 目标不同 → 需要独立 core
                if (myCore === otherCore) {
                    // 从共享分家：只给切换方创建新 core，另一边保持不变
                    if (slot == MAIN) {
                        myCore.stopFollowingGlobalCurrentChat()
                    }
                    val myNewCore = createCore(slot, initialLocalChatId = chatId)
                    cores[slot] = myNewCore
                    replacedSlot = slot

                    AppLogger.d(TAG, "共享分家: $slot→$chatId, $otherSlot 保持不变")
                } else {
                    // 已经独立 → 直接切
                    myCore.switchChatLocal(chatId)
                    AppLogger.d(TAG, "$slot 切换聊天: $chatId")
                }
            }
        }
        replacedSlot?.let(::notifyCoreReplaced)
    }

    /**
     * 如果当前 slot 正在和另一侧共享 core，则为当前 slot fork 一个独立 core。
     *
     * 用于“当前窗口主动新建会话”这类尚未有目标 chatId 的操作：先保持当前会话上下文
     * 分家，再让调用方在新 core 上创建新会话。
     */
    fun detachSlotIfShared(slot: ChatRuntimeSlot, initialChatId: String?): Boolean {
        val detached =
            synchronized(this) {
            val otherSlot = if (slot == MAIN) FLOATING else MAIN
            val otherCore = cores[otherSlot]!!
            val myCore = cores[slot]!!
            if (myCore !== otherCore) {
                return@synchronized false
            }

            if (slot == MAIN) {
                myCore.stopFollowingGlobalCurrentChat()
            }
            val myNewCore = createCore(slot, initialLocalChatId = initialChatId)
            cores[slot] = myNewCore

            AppLogger.d(TAG, "共享分家: $slot fork 独立 core, initialChatId=$initialChatId")
            true
        }
        if (detached) {
            notifyCoreReplaced(slot)
        }
        return detached
    }

    /**
     * Home 按钮语义：把悬浮窗当前 core 作为主聊天窗口继续使用。
     *
     * 如果此时双方已经分家，MAIN 会改为引用 FLOATING 的 core。随后关闭悬浮窗时
     * isSharingCore() 为 true，因此不会取消正在流式的会话。
     */
    fun adoptFloatingCoreAsMain(): Boolean {
        val adopted =
            synchronized(this) {
                val floatingCore = cores[FLOATING]!!
                val mainCore = cores[MAIN]!!
                if (mainCore === floatingCore) {
                    return@synchronized false
                }
                cores[MAIN] = floatingCore
                AppLogger.d(
                    TAG,
                    "MAIN 接管 FLOATING core: chatId=${floatingCore.currentChatId.value}"
                )
                true
            }
        if (adopted) {
            notifyCoreReplaced(MAIN)
        }
        return adopted
    }

    /**
     * 设置是否允许共享 core
     *
     * 悬浮窗开启时调用 enable 完成初始共享，之后立即 disable。
     * 悬浮窗销毁时调用 enable 为下次开启做准备。
     */
    fun setSharingEnabled(enabled: Boolean) {
        sharingEnabled = enabled
        AppLogger.d(TAG, "共享 core ${if (enabled) "启用" else "禁用"}")
    }

    fun isSharingCore(): Boolean = cores[MAIN] === cores[FLOATING]

    private fun createCore(
        slot: ChatRuntimeSlot,
        initialLocalChatId: String? = null
    ): ChatServiceCore {
        return ChatServiceCore(
            context = appContext,
            coroutineScope = runtimeScope,
            selectionMode = when (slot) {
                ChatRuntimeSlot.MAIN -> ChatSelectionMode.FOLLOW_GLOBAL
                ChatRuntimeSlot.FLOATING -> ChatSelectionMode.LOCAL_ONLY
            },
            initialLocalChatId = initialLocalChatId.takeIf { slot == ChatRuntimeSlot.FLOATING },
            beforeCurrentChatMutation = { currentChatId ->
                if (slot == MAIN) {
                    detachSlotIfShared(MAIN, currentChatId)
                }
            }
        )
    }

    private fun observeStats() {
        statsJob?.cancel()

        statsJob = runtimeScope.launch {
            val mainCore = getCore(MAIN)
            val floatingCore = getCore(FLOATING)

            launch {
                combine(
                    mainCore.activeStreamingChatIds,
                    floatingCore.activeStreamingChatIds
                ) { a, b -> (a + b).size }.collect {
                    _activeConversationCount.value = it
                }
            }

            launch {
                combine(
                    mainCore.activeStreamingChatIds,
                    mainCore.currentTurnToolInvocationCountByChatId,
                    floatingCore.activeStreamingChatIds,
                    floatingCore.currentTurnToolInvocationCountByChatId
                ) { ma, mc, fa, fc ->
                    val uniqueActiveIds = ma + fa 
                    uniqueActiveIds.sumOf { id ->
                        mc[id] ?: fc[id] ?: 0
                    }
                }.collect {
                    _currentSessionToolCount.value = it
                }
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
