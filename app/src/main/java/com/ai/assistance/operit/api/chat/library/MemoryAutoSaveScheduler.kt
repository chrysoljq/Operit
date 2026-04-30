package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.MemoryAutoSaveCandidate
import com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.data.repository.MemoryAutoSaveCandidateRepository
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MemoryAutoSaveScheduler(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MemoryAutoSaveScheduler"
        private const val LOOP_TICK_MS = 60 * 1000L
        const val DEFAULT_POLL_INTERVAL_MS =
            MemorySearchSettingsPreferences.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES * 60 * 1000L
        private const val MAX_MESSAGES_PER_CHAT = 48
        private const val MIN_TOTAL_CANDIDATES_TO_EXTRACT = 5

        @Volatile
        private var instance: MemoryAutoSaveScheduler? = null

        fun getInstance(): MemoryAutoSaveScheduler? = instance
    }

    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val nextRunAtMsByProfileId = ConcurrentHashMap<String, Long>()

    fun start() {
        if (loopJob?.isActive == true) return
        instance = this
        loopJob =
            scope.launch(Dispatchers.IO) {
                AppLogger.d(TAG, "长期记忆自动保存轮询器已启动")
                while (isActive) {
                    delay(LOOP_TICK_MS)
                    runOnce()
                }
            }
    }

    fun getMinutesUntilNextRun(
        profileId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val target = getOrInitNextRunAtMs(profileId, nowMs)
        val remainingMs = (target - nowMs).coerceAtLeast(0L)
        return ((remainingMs + 60_000L - 1L) / 60_000L).coerceAtLeast(0L)
    }

    suspend fun runOnce() {
        if (!isRunning.compareAndSet(false, true)) {
            AppLogger.d(TAG, "上一轮长期记忆自动保存仍在运行，跳过本轮")
            return
        }
        try {
            scanAndProcessCandidates()
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun scanAndProcessCandidates() {
        val profileIds = preferencesManager.profileListFlow.first()
        if (profileIds.isEmpty()) return

        val toolHandler = AIToolHandler.getInstance(context)
        val memoryService =
            EnhancedAIService.getAIServiceForFunction(context, FunctionType.MEMORY)
        val messageDao = AppDatabase.getDatabase(context).messageDao()
        val nowMs = System.currentTimeMillis()

        for (profileId in profileIds) {
            val intervalMs = intervalMsForProfile(profileId)
            val nextRunAtMs = getOrInitNextRunAtMs(profileId, nowMs)
            if (nowMs < nextRunAtMs) {
                continue
            }

            val repository = MemoryAutoSaveCandidateRepository(context, profileId)
            val allCandidates = repository.getPendingAndFailedCandidates()
            if (allCandidates.size < MIN_TOTAL_CANDIDATES_TO_EXTRACT) {
                AppLogger.d(
                    TAG,
                    "候选总条数不足，继续累计: profileId=$profileId, totalCandidates=${allCandidates.size}"
                )
                continue
            }
            val groupedCandidates =
                allCandidates
                    .groupBy { it.chatId }
                    .filterKeys { it.isNotBlank() }

            if (groupedCandidates.isEmpty()) {
                scheduleNextRun(profileId, System.currentTimeMillis() + intervalMs)
                continue
            }

            AppLogger.d(
                TAG,
                "开始处理长期记忆候选: profileId=$profileId, chats=${groupedCandidates.size}"
            )

            for ((chatId, candidates) in groupedCandidates) {
                processChatCandidateGroup(
                    profileId = profileId,
                    chatId = chatId,
                    candidates = candidates,
                    repository = repository,
                    messageDao = messageDao,
                    toolHandler = toolHandler,
                    memoryService = memoryService
                )
            }
            scheduleNextRun(profileId, System.currentTimeMillis() + intervalMs)
        }
    }

    private fun intervalMsForProfile(profileId: String): Long {
        val minutes = MemorySearchSettingsPreferences(context, profileId).loadAutoSaveIntervalMinutes()
        return minutes * 60_000L
    }

    private fun getOrInitNextRunAtMs(
        profileId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        nextRunAtMsByProfileId[profileId]?.takeIf { it > 0L }?.let { return it }
        val preferences = MemorySearchSettingsPreferences(context, profileId)
        val persisted = preferences.loadNextAutoSaveRunAtMs().takeIf { it > 0L }
        val target = persisted ?: (nowMs + intervalMsForProfile(profileId))
        scheduleNextRun(profileId, target)
        return target
    }

    private fun scheduleNextRun(profileId: String, nextRunAtMs: Long) {
        val normalized = nextRunAtMs.coerceAtLeast(0L)
        nextRunAtMsByProfileId[profileId] = normalized
        MemorySearchSettingsPreferences(context, profileId).saveNextAutoSaveRunAtMs(normalized)
    }

    private suspend fun processChatCandidateGroup(
        profileId: String,
        chatId: String,
        candidates: List<MemoryAutoSaveCandidate>,
        repository: MemoryAutoSaveCandidateRepository,
        messageDao: com.ai.assistance.operit.data.dao.MessageDao,
        toolHandler: AIToolHandler,
        memoryService: com.ai.assistance.operit.api.chat.llmprovider.AIService
    ) {
        if (candidates.isEmpty()) return

        val candidateIds = candidates.map { it.id }
        val latestTriggerTimestamp = candidates.maxOf { it.triggerMessageTimestamp }
        repository.markProcessing(candidateIds)

        try {
            val messages =
                withContext(Dispatchers.IO) {
                    messageDao.getMessagesForChatBeforeTimestampDesc(
                        chatId = chatId,
                        maxTimestamp = latestTriggerTimestamp,
                        limit = MAX_MESSAGES_PER_CHAT
                    )
                }.asReversed()

            if (messages.isEmpty()) {
                AppLogger.w(TAG, "未找到候选对应消息，直接清理候选: profileId=$profileId, chatId=$chatId")
                repository.deleteCandidates(candidateIds)
                return
            }

            val conversationHistory =
                messages
                    .filter { message -> message.sender == "user" || message.sender == "ai" }
                    .map { message ->
                        val role =
                            if (message.sender == "user") {
                                "user"
                            } else {
                            "assistant"
                            }
                        role to message.content
                    }

            if (conversationHistory.isEmpty() || conversationHistory.none { it.first == "user" }) {
                AppLogger.w(TAG, "候选消息缺少有效用户上下文，直接清理候选: profileId=$profileId, chatId=$chatId")
                repository.deleteCandidates(candidateIds)
                return
            }

            val latestAssistantMessage =
                conversationHistory.lastOrNull { (role, content) ->
                    role == "assistant" && content.isNotBlank()
                }?.second

            if (latestAssistantMessage.isNullOrBlank()) {
                AppLogger.w(TAG, "候选消息缺少有效助手回复，直接清理候选: profileId=$profileId, chatId=$chatId")
                repository.deleteCandidates(candidateIds)
                return
            }

            MemoryLibrary.saveMemoryNow(
                context = context,
                toolHandler = toolHandler,
                conversationHistory = conversationHistory,
                content = latestAssistantMessage,
                aiService = memoryService,
                profileIdOverride = profileId
            )
            repository.deleteCandidates(candidateIds)
            AppLogger.d(
                TAG,
                "长期记忆候选处理成功: profileId=$profileId, chatId=$chatId, candidates=${candidateIds.size}"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "长期记忆候选处理失败: profileId=$profileId, chatId=$chatId", e)
            repository.markFailed(candidateIds, e.message ?: e.javaClass.simpleName)
        }
    }
}
