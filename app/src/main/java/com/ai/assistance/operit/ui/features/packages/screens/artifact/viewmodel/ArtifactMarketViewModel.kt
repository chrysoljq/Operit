package com.ai.assistance.operit.ui.features.packages.screens.artifact.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.api.MarketStatsApiService
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.features.packages.market.ArtifactMarketScope
import com.ai.assistance.operit.ui.features.packages.market.collectArtifactPredecessorPublisherLogins
import com.ai.assistance.operit.ui.features.packages.market.ArtifactPublishClusterContext
import com.ai.assistance.operit.ui.features.packages.market.ForgeRepoInfo
import com.ai.assistance.operit.ui.features.packages.market.GitHubForgePublishService
import com.ai.assistance.operit.ui.features.packages.market.GitHubIssueMarketService
import com.ai.assistance.operit.ui.features.packages.market.inspectLocalArtifactAuthorDeclaration
import com.ai.assistance.operit.ui.features.packages.market.LocalPublishableArtifact
import com.ai.assistance.operit.ui.features.packages.market.MarketRegistrationPayload
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactRequest
import com.ai.assistance.operit.ui.features.packages.market.PublishArtifactType
import com.ai.assistance.operit.ui.features.packages.market.PublishAttemptResult
import com.ai.assistance.operit.ui.features.packages.market.PublishProgressStage
import com.ai.assistance.operit.ui.features.packages.market.buildArtifactMarketIssueBody
import com.ai.assistance.operit.ui.features.packages.market.formatSupportedAppVersions
import com.ai.assistance.operit.ui.features.packages.market.normalizeMarketArtifactId
import com.ai.assistance.operit.ui.features.packages.market.normalizeAppVersionOrNull
import com.ai.assistance.operit.ui.features.packages.market.sameArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.validateStandaloneArtifactRuntimePackageId
import com.ai.assistance.operit.ui.features.packages.market.validateSupportedAppVersions
import com.ai.assistance.operit.ui.features.packages.utils.ArtifactIssueParser
import com.ai.assistance.operit.ui.features.github.GitHubOAuthCoordinator
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtifactMarketViewModel(
    private val context: Context,
    private val scope: ArtifactMarketScope
) : ViewModel() {
    data class MarketActionNotice(
        val title: String,
        val message: String
    )

    private val githubApiService = GitHubApiService(context)
    private val marketStatsApiService = MarketStatsApiService()
    private val githubAuth = GitHubAuthPreferences.getInstance(context)
    private val forgePublishService = GitHubForgePublishService(context, githubApiService)
    private val packageManager =
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    private val marketServices =
        PublishArtifactType.entries.associateWith { type ->
            GitHubIssueMarketService(githubApiService, type.marketDefinition())
        }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _publishableArtifacts = MutableStateFlow<List<LocalPublishableArtifact>>(emptyList())
    val publishableArtifacts: StateFlow<List<LocalPublishableArtifact>> = _publishableArtifacts.asStateFlow()

    private val _userPublishedArtifacts = MutableStateFlow<List<GitHubIssue>>(emptyList())
    val userPublishedArtifacts: StateFlow<List<GitHubIssue>> = _userPublishedArtifacts.asStateFlow()
    private val _hasLoadedUserPublishedArtifacts = MutableStateFlow(false)
    val hasLoadedUserPublishedArtifacts: StateFlow<Boolean> =
        _hasLoadedUserPublishedArtifacts.asStateFlow()

    private val _publishProgressStage = MutableStateFlow(PublishProgressStage.IDLE)
    val publishProgressStage: StateFlow<PublishProgressStage> = _publishProgressStage.asStateFlow()

    private val _publishMessage = MutableStateFlow<String?>(null)
    val publishMessage: StateFlow<String?> = _publishMessage.asStateFlow()

    private val _publishErrorMessage = MutableStateFlow<String?>(null)
    val publishErrorMessage: StateFlow<String?> = _publishErrorMessage.asStateFlow()

    private val _publishSuccessMessage = MutableStateFlow<String?>(null)
    val publishSuccessMessage: StateFlow<String?> = _publishSuccessMessage.asStateFlow()

    private val _marketActionNotice = MutableStateFlow<MarketActionNotice?>(null)
    val marketActionNotice: StateFlow<MarketActionNotice?> = _marketActionNotice.asStateFlow()

    private val _requiresForgeInitialization = MutableStateFlow(false)
    val requiresForgeInitialization: StateFlow<Boolean> = _requiresForgeInitialization.asStateFlow()

    private val _registrationRetryAvailable = MutableStateFlow(false)
    val registrationRetryAvailable: StateFlow<Boolean> = _registrationRetryAvailable.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> =
        githubAuth.isLoggedInFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val supportedTypes = scope.supportedTypes()
    private var pendingPublishRequest: PublishArtifactRequest? = null
    private var pendingMarketRegistrationPayload: MarketRegistrationPayload? = null

    init {
        refreshPublishableArtifacts()
    }

    fun initiateGitHubLogin(context: Context) {
        viewModelScope.launch {
            try {
                val authUrl = GitHubOAuthCoordinator(context).createExternalAuthorizationUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to open GitHub login"
                AppLogger.e(TAG, "Failed to initiate GitHub login", e)
            }
        }
    }

    fun logoutFromGitHub() {
        viewModelScope.launch {
            try {
                githubAuth.logout()
                Toast.makeText(context, "Logged out from GitHub", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to logout from GitHub"
                AppLogger.e(TAG, "Failed to logout from GitHub", e)
            }
        }
    }

    fun refreshPublishableArtifacts() {
        viewModelScope.launch {
            val artifacts =
                withContext(Dispatchers.IO) {
                    packageManager.getPublishablePackageSources()
                        .mapNotNull { source ->
                            val type = inferArtifactType(source.isToolPkg, source.fileExtension) ?: return@mapNotNull null
                            if (type !in supportedTypes) return@mapNotNull null
                            val authorDeclaration = inspectLocalArtifactAuthorDeclaration(source)
                            LocalPublishableArtifact(
                                type = type,
                                packageName = source.packageName,
                                displayName = source.displayName,
                                description = source.description,
                                sourceFile = File(source.sourcePath),
                                hasDeclaredAuthorField = authorDeclaration.hasAuthorField,
                                declaredAuthorSlotCount = authorDeclaration.declaredAuthorSlotCount,
                                inferredVersion = source.inferredVersion
                            )
                        }
                        .sortedWith(compareBy<LocalPublishableArtifact> { it.type.ordinal }.thenBy { it.displayName.lowercase() })
            }
            _publishableArtifacts.value = artifacts
        }
    }

    fun loadUserPublishedArtifacts() {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val userInfo = githubAuth.getCurrentUserInfo()
                if (userInfo == null) {
                    _errorMessage.value = "Unable to read GitHub user info"
                    return@launch
                }

                aggregateResults { service ->
                    service.getUserPublishedIssues(
                        creator = userInfo.login,
                        fallbackWithoutLabel = true
                    )
                }.fold(
                    onSuccess = { issues ->
                        _userPublishedArtifacts.value = issues.sortedByDescending { it.updated_at }
                    },
                    onFailure = { error ->
                        _errorMessage.value = error.message ?: "Failed to load published artifacts"
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to load published artifacts"
                AppLogger.e(TAG, "Failed to load user published artifacts", e)
            } finally {
                _hasLoadedUserPublishedArtifacts.value = true
                _isLoading.value = false
            }
        }
    }

    fun removeArtifactFromMarket(issue: GitHubIssue, title: String) {
        updateArtifactIssueState(
            issue = issue,
            state = "closed",
            successMessage = "已提交移除「$title」的请求"
        )
    }

    fun reopenArtifactInMarket(issue: GitHubIssue, title: String) {
        updateArtifactIssueState(
            issue = issue,
            state = "open",
            successMessage = "已提交重新上架「$title」的请求"
        )
    }

    private fun updateArtifactIssueState(issue: GitHubIssue, state: String, successMessage: String) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = "GitHub login required"
                return@launch
            }

            val type = ArtifactIssueParser.parseArtifactInfo(issue).type
            if (type == null) {
                _errorMessage.value = "Invalid artifact metadata"
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null
            try {
                marketServices.getValue(type).updateIssueState(issue.number, state).fold(
                    onSuccess = {
                        _userPublishedArtifacts.value =
                            _userPublishedArtifacts.value.map { existing ->
                                if (existing.id == issue.id) existing.copy(state = state) else existing
                            }
                        _marketActionNotice.value =
                            MarketActionNotice(
                                title =
                                    if (state == "closed") {
                                        "移除请求已提交"
                                    } else {
                                        "重新上架请求已提交"
                                    },
                                message = appendMarketScheduleNotice(successMessage)
                            )
                    },
                    onFailure = { error ->
                        _errorMessage.value = error.message ?: "Failed to update market entry"
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update market entry"
                AppLogger.e(TAG, "Failed to update market entry state", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun requestPublish(
        packageName: String,
        displayName: String,
        description: String,
        version: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?,
        publishContext: ArtifactPublishClusterContext? = null
    ) {
        val localArtifact = _publishableArtifacts.value.firstOrNull { it.packageName == packageName }
        if (localArtifact == null) {
            _publishErrorMessage.value = "Local artifact not found"
            return
        }

        val resolvedDisplayName =
            publishContext?.lockedDisplayName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: displayName

        val request =
            PublishArtifactRequest(
                localArtifact = localArtifact,
                displayName = resolvedDisplayName,
                description = description,
                version = version,
                minSupportedAppVersion = minSupportedAppVersion,
                maxSupportedAppVersion = maxSupportedAppVersion,
                publishContext = publishContext
            )
        executePublish(request, allowCreateForgeRepo = false)
    }

    fun updatePublishedArtifact(
        issue: GitHubIssue,
        displayName: String,
        description: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?
    ) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _publishErrorMessage.value = "GitHub login required"
                return@launch
            }

            _publishErrorMessage.value = null
            _publishSuccessMessage.value = null
            _publishProgressStage.value = PublishProgressStage.VALIDATING
            _publishMessage.value = "正在更新已发布节点"

            try {
                validateSupportedAppVersions(
                    minSupportedAppVersion = minSupportedAppVersion,
                    maxSupportedAppVersion = maxSupportedAppVersion
                )

                val type =
                    ArtifactIssueParser.parseArtifactInfo(issue).type
                        ?: throw IllegalStateException("Invalid artifact metadata")
                val payload =
                    buildUpdatedMarketRegistrationPayload(
                        issue = issue,
                        type = type,
                        displayName = displayName,
                        description = description,
                        minSupportedAppVersion = minSupportedAppVersion,
                        maxSupportedAppVersion = maxSupportedAppVersion
                    )
                ensureArtifactDisplayNameAvailable(
                    displayName = payload.displayName,
                    currentIssueId = issue.id
                )

                marketServices.getValue(type).updateIssueContent(
                    issueNumber = issue.number,
                    title = payload.displayName,
                    body = buildArtifactMarketIssueBody(payload)
                ).fold(
                    onSuccess = { updatedIssue ->
                        _publishProgressStage.value = PublishProgressStage.COMPLETED
                        _publishMessage.value = null
                        _publishSuccessMessage.value =
                            appendMarketScheduleNotice("已更新节点「${payload.displayName}」")
                        _userPublishedArtifacts.value =
                            _userPublishedArtifacts.value.map { existing ->
                                if (existing.id == updatedIssue.id) updatedIssue else existing
                            }
                    },
                    onFailure = { error ->
                        _publishProgressStage.value = PublishProgressStage.IDLE
                        _publishMessage.value = null
                        _publishErrorMessage.value = error.message ?: "Failed to update artifact"
                    }
                )
            } catch (e: Exception) {
                _publishProgressStage.value = PublishProgressStage.IDLE
                _publishMessage.value = null
                _publishErrorMessage.value = e.message ?: "Failed to update artifact"
                AppLogger.e(TAG, "Failed to update published artifact", e)
            }
        }
    }

    fun confirmForgeInitializationAndPublish() {
        val request = pendingPublishRequest ?: return
        _requiresForgeInitialization.value = false
        executePublish(request, allowCreateForgeRepo = true)
    }

    fun dismissForgeInitializationPrompt() {
        pendingPublishRequest = null
        _requiresForgeInitialization.value = false
        _publishProgressStage.value = PublishProgressStage.IDLE
    }

    fun retryPendingMarketRegistration() {
        val payload = pendingMarketRegistrationPayload ?: return
        viewModelScope.launch {
            _publishProgressStage.value = PublishProgressStage.REGISTERING_MARKET
            _publishErrorMessage.value = null
            forgePublishService.retryMarketRegistration(payload).fold(
                onSuccess = {
                    _registrationRetryAvailable.value = false
                    pendingMarketRegistrationPayload = null
                    _publishProgressStage.value = PublishProgressStage.COMPLETED
                    _publishSuccessMessage.value =
                        appendMarketScheduleNotice("市场登记已完成")
                    loadUserPublishedArtifacts()
                },
                onFailure = { error ->
                    _publishErrorMessage.value = error.message ?: "Failed to register market entry"
                    _publishProgressStage.value = PublishProgressStage.IDLE
                }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun resetUserPublishedArtifactsState() {
        _userPublishedArtifacts.value = emptyList()
        _hasLoadedUserPublishedArtifacts.value = false
        _errorMessage.value = null
        _marketActionNotice.value = null
    }

    fun clearPublishMessages() {
        _publishMessage.value = null
        _publishErrorMessage.value = null
        _publishSuccessMessage.value = null
        if (_publishProgressStage.value == PublishProgressStage.COMPLETED) {
            _publishProgressStage.value = PublishProgressStage.IDLE
        }
    }

    fun clearMarketActionNotice() {
        _marketActionNotice.value = null
    }

    private fun executePublish(request: PublishArtifactRequest, allowCreateForgeRepo: Boolean) {
        viewModelScope.launch {
            try {
                if (!githubAuth.isLoggedIn()) {
                    _publishErrorMessage.value = "GitHub login required"
                    return@launch
                }

                val resolvedDisplayName = resolvePublishDisplayName(request)
                val resolvedRequest =
                    if (resolvedDisplayName == request.displayName) {
                        request
                    } else {
                        request.copy(displayName = resolvedDisplayName)
                    }

                _publishErrorMessage.value = null
                _publishSuccessMessage.value = null
                _registrationRetryAvailable.value = false
                pendingMarketRegistrationPayload = null
                pendingPublishRequest = resolvedRequest
                _publishProgressStage.value = PublishProgressStage.VALIDATING
                _publishMessage.value = "正在检查名称和 ID 是否冲突"

                if (resolvedRequest.publishContext == null) {
                    ensureFreshPublishIdentityAvailable(
                        displayName = resolvedRequest.displayName,
                        runtimePackageId = resolvedRequest.localArtifact.packageName
                    )
                }
                validateContinuationAuthorDeclaration(resolvedRequest)

                forgePublishService.publishArtifact(
                    request = resolvedRequest,
                    allowCreateForgeRepo = allowCreateForgeRepo,
                    onProgress = { stage ->
                        _publishProgressStage.value = stage
                        _publishMessage.value = stageMessage(stage)
                    }
                ).fold(
                    onSuccess = { result ->
                        when (result) {
                            is PublishAttemptResult.NeedsForgeInitialization -> {
                                _requiresForgeInitialization.value = true
                                _publishProgressStage.value = PublishProgressStage.IDLE
                                _publishMessage.value = null
                            }

                            is PublishAttemptResult.Success -> {
                                pendingPublishRequest = null
                                _publishProgressStage.value = PublishProgressStage.COMPLETED
                                _publishSuccessMessage.value = buildSuccessMessage(result.forgeRepo, result.payload)
                                loadUserPublishedArtifacts()
                            }

                            is PublishAttemptResult.RegistrationRetryRequired -> {
                                pendingMarketRegistrationPayload = result.payload
                                _registrationRetryAvailable.value = true
                                _publishProgressStage.value = PublishProgressStage.IDLE
                                _publishErrorMessage.value = result.errorMessage
                            }
                        }
                    },
                    onFailure = { error ->
                        _publishProgressStage.value = PublishProgressStage.IDLE
                        _publishMessage.value = null
                        _publishErrorMessage.value = formatPublishErrorMessage(error.message ?: "Publish failed")
                        AppLogger.e(TAG, "Failed to publish artifact", error)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingPublishRequest = null
                _publishProgressStage.value = PublishProgressStage.IDLE
                _publishMessage.value = null
                _publishErrorMessage.value = e.message ?: "Failed to publish artifact"
                AppLogger.e(TAG, "Failed to publish artifact", e)
            }
        }
    }

    private suspend fun validateContinuationAuthorDeclaration(request: PublishArtifactRequest) {
        val publishContext = request.publishContext ?: return
        val parentNodeIds =
            publishContext.parentNodeIds
                .map(String::trim)
                .filter(String::isNotBlank)
        if (parentNodeIds.isEmpty()) {
            return
        }

        if (!request.localArtifact.hasDeclaredAuthorField) {
            return
        }

        val projectDetail =
            marketStatsApiService.getArtifactProject(publishContext.projectId).getOrElse { error ->
                throw error
            }
        val predecessorPublisherCount =
            collectArtifactPredecessorPublisherLogins(
                project = projectDetail,
                parentNodeIds = parentNodeIds
            ).size
        if (predecessorPublisherCount <= 0) {
            return
        }

        if (request.localArtifact.declaredAuthorSlotCount < predecessorPublisherCount) {
            throw IllegalStateException(
                "当前作品已声明 author，但数量不足。当前直接前驱节点的 GitHub 发布者共有 $predecessorPublisherCount 个；author 要么不写，要么至少提供 $predecessorPublisherCount 个位置。"
            )
        }
    }

    private suspend fun searchOpenIssuesByExactTitle(title: String): Result<List<GitHubIssue>> {
        return aggregateResults { service -> service.searchOpenIssuesByExactTitle(title, page = 1) }
    }

    private suspend fun ensureFreshPublishIdentityAvailable(
        displayName: String,
        runtimePackageId: String
    ) {
        validateStandaloneArtifactRuntimePackageId(runtimePackageId)

        val normalizedTitle = normalizePublishTitle(displayName)
        val normalizedRuntimePackageId = normalizeMarketArtifactId(runtimePackageId)
        val titleMatches =
            aggregateResults { service ->
                service.searchIssuesByExactTitle(
                    title = displayName,
                    page = 1,
                    openOnly = false
                )
            }.getOrElse { error ->
                throw IllegalStateException(
                    "检查插件名称是否重复失败：${error.message ?: "GitHub 搜索失败"}"
                )
            }
        val titleConflict =
            titleMatches.firstOrNull { existing ->
                val existingTitle =
                    ArtifactIssueParser.parseArtifactInfo(existing).title.ifBlank { existing.title }
                normalizePublishTitle(existingTitle) == normalizedTitle
            }

        val runtimeIdMatches =
            aggregateResults { service ->
                service.searchIssues(
                    rawQuery = "\"$runtimePackageId\"",
                    page = 1,
                    openOnly = false
                )
            }.getOrElse { error ->
                throw IllegalStateException(
                    "检查插件 ID 是否重复失败：${error.message ?: "GitHub 搜索失败"}"
                )
            }
        val runtimeIdConflict =
            runtimeIdMatches.firstOrNull { existing ->
                val parsed = ArtifactIssueParser.parseArtifactInfo(existing)
                val existingRuntimePackageId =
                    parsed.runtimePackageId.ifBlank { parsed.normalizedId }
                existingRuntimePackageId.isNotBlank() &&
                    sameArtifactRuntimePackageId(existingRuntimePackageId, runtimePackageId)
            }

        val normalizedIdConflict =
            if (normalizedRuntimePackageId.equals(runtimePackageId.trim(), ignoreCase = true)) {
                null
            } else {
                val normalizedIdMatches =
                    aggregateResults { service ->
                        service.searchIssues(
                            rawQuery = "\"$normalizedRuntimePackageId\"",
                            page = 1,
                            openOnly = false
                        )
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "检查插件市场归一化 ID 是否重复失败：${error.message ?: "GitHub 搜索失败"}"
                        )
                    }
                normalizedIdMatches.firstOrNull { existing ->
                    val parsed = ArtifactIssueParser.parseArtifactInfo(existing)
                    val candidateIds =
                        listOf(
                            parsed.projectId,
                            parsed.runtimePackageId,
                            parsed.normalizedId
                        ).map(String::trim).filter(String::isNotBlank)
                    candidateIds.any { normalizeMarketArtifactId(it) == normalizedRuntimePackageId }
                }
            }

        if (titleConflict == null && runtimeIdConflict == null && normalizedIdConflict == null) {
            return
        }

        val conflictReasons = mutableListOf<String>()
        if (titleConflict != null) {
            conflictReasons += "名字「$displayName」已存在"
        }
        if (runtimeIdConflict != null) {
            conflictReasons += "ID「$runtimePackageId」已存在"
        }
        if (normalizedIdConflict != null) {
            conflictReasons += "市场归一化 ID「$normalizedRuntimePackageId」已存在"
        }

        throw IllegalStateException(
            "${conflictReasons.joinToString("，")}。如果这是发布新版本，请在对应的插件页面里点击“发布更新版本”按钮，而不是重新发布一个新的插件。"
        )
    }

    private suspend fun aggregateResults(
        request: suspend (GitHubIssueMarketService) -> Result<List<GitHubIssue>>
    ): Result<List<GitHubIssue>> {
        val results =
            coroutineScope {
                supportedTypes.map { type ->
                    async { request(marketServices.getValue(type)) }
                }.awaitAll()
            }

        val aggregated = mutableListOf<GitHubIssue>()
        results.forEach { result ->
            result.fold(
                onSuccess = { aggregated += it },
                onFailure = { return Result.failure(it) }
            )
        }
        return Result.success(
            aggregated
                .distinctBy { it.id }
                .sortedByDescending { it.updated_at }
        )
    }

    private fun stageMessage(stage: PublishProgressStage): String? {
        return when (stage) {
            PublishProgressStage.IDLE -> null
            PublishProgressStage.VALIDATING -> "正在校验本地条目"
            PublishProgressStage.ENSURING_REPO -> "正在检查 OperitForge 仓库"
            PublishProgressStage.CREATING_RELEASE -> "正在创建或更新 GitHub Release"
            PublishProgressStage.UPLOADING_ASSET -> "正在上传发布资源"
            PublishProgressStage.REGISTERING_MARKET -> "正在登记市场条目"
            PublishProgressStage.COMPLETED -> "发布完成"
        }
    }

    private fun buildSuccessMessage(forgeRepo: ForgeRepoInfo, payload: MarketRegistrationPayload): String {
        return appendMarketScheduleNotice(
            buildString {
                append("已发布「${payload.displayName}」到 ${forgeRepo.repoName}")
                append("\n类型: ${payload.type.wireValue}")
                append("\n项目簇: ${payload.projectId}")
                append("\n节点: ${payload.nodeId}")
                append("\nRelease Tag: ${payload.releaseTag}")
                append("\n资源文件: ${payload.assetName}")
                append("\n支持软件版本: ${formatSupportedAppVersions(payload.minSupportedAppVersion, payload.maxSupportedAppVersion)}")
            }
        )
    }

    private fun buildUpdatedMarketRegistrationPayload(
        issue: GitHubIssue,
        type: PublishArtifactType,
        displayName: String,
        description: String,
        minSupportedAppVersion: String?,
        maxSupportedAppVersion: String?
    ): MarketRegistrationPayload {
        val info = ArtifactIssueParser.parseArtifactInfo(issue)
        val nodeId = info.nodeId.ifBlank { "legacy-${issue.id}" }
        val rootNodeId = info.rootNodeId.ifBlank { nodeId }
        val isRootNode = rootNodeId == nodeId
        val trimmedDisplayName = displayName.trim().ifBlank { info.title }
        val trimmedDescription = description.trim().ifBlank { info.description }

        return MarketRegistrationPayload(
            type = type,
            projectId = info.projectId.ifBlank { info.normalizedId },
            projectDisplayName =
                if (isRootNode) {
                    trimmedDisplayName
                } else {
                    info.projectDisplayName.ifBlank { info.title }
                },
            projectDescription =
                if (isRootNode) {
                    trimmedDescription
                } else {
                    info.projectDescription.ifBlank { info.description }
                },
            runtimePackageId = info.runtimePackageId.ifBlank { info.normalizedId },
            nodeId = nodeId,
            rootNodeId = rootNodeId,
            parentNodeIds = info.parentNodeIds,
            publisherLogin = info.publisherLogin.ifBlank { issue.user.login },
            forgeRepo = info.forgeRepo,
            releaseTag = info.releaseTag,
            assetName = info.assetName,
            downloadUrl = info.downloadUrl,
            sha256 = info.sha256,
            version = info.version.trim().removePrefix("v").removePrefix("V").ifBlank { "1.0.0" },
            displayName = trimmedDisplayName,
            description = trimmedDescription,
            sourceFileName = info.sourceFileName,
            minSupportedAppVersion = normalizeAppVersionOrNull(minSupportedAppVersion),
            maxSupportedAppVersion = normalizeAppVersionOrNull(maxSupportedAppVersion)
        )
    }

    private suspend fun ensureArtifactDisplayNameAvailable(
        displayName: String,
        currentIssueId: Long? = null,
        allowExistingOpenDuplicate: Boolean = false
    ) {
        val trimmedDisplayName = displayName.trim()
        if (trimmedDisplayName.isBlank()) {
            throw IllegalArgumentException("插件名称不能为空")
        }
        if (allowExistingOpenDuplicate) {
            return
        }

        val existingIssues =
            searchOpenIssuesByExactTitle(trimmedDisplayName).getOrElse { error ->
                throw IllegalStateException(
                    "检查插件名称是否重名失败：${error.message ?: "GitHub 搜索失败"}"
                )
            }
        val normalizedTitle = normalizePublishTitle(trimmedDisplayName)
        val conflictingIssue =
            existingIssues.firstOrNull { existing ->
                existing.id != currentIssueId &&
                    normalizePublishTitle(existing.title) == normalizedTitle
            }
        if (conflictingIssue != null) {
            throw IllegalStateException("市场里已经有同名已发布插件「$trimmedDisplayName」，请换一个名称。")
        }
    }

    private fun resolvePublishDisplayName(request: PublishArtifactRequest): String {
        val lockedDisplayName = request.publishContext?.lockedDisplayName?.trim().orEmpty()
        if (request.publishContext != null) {
            if (lockedDisplayName.isBlank()) {
                throw IllegalStateException("继续发布新节点时必须沿用原插件名称。")
            }
            return lockedDisplayName
        }
        return request.displayName.trim()
    }

    private fun normalizePublishTitle(title: String): String {
        return title.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    private fun formatPublishErrorMessage(rawMessage: String): String {
        val message = rawMessage.trim()
        return when {
            message.contains("Repository is empty", ignoreCase = true) ->
                "发布仓库还是空的，GitHub 无法创建 Release。现在新仓库会自动初始化；如果这是旧的空仓库，请重试一次。"

            message.contains("Validation Failed", ignoreCase = true) ->
                "GitHub 拒绝了这次发布请求，请检查仓库状态和发布信息。"

            message.startsWith("HTTP ", ignoreCase = true) ->
                message.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "发布失败" }

            else -> message
        }
    }

    private fun appendMarketScheduleNotice(baseMessage: String): String {
        return buildString {
            append(baseMessage.trim())
            append("\n\n注意：公共市场需要排期发布，不会马上更新。请等待排期完成后再查看。")
        }
    }

    private fun inferArtifactType(isToolPkg: Boolean, fileExtension: String): PublishArtifactType? {
        val normalizedExtension = fileExtension.lowercase()
        return when {
            isToolPkg && normalizedExtension == "toolpkg" -> PublishArtifactType.PACKAGE
            !isToolPkg && normalizedExtension != "toolpkg" -> PublishArtifactType.SCRIPT
            else -> null
        }
    }

    class Factory(
        private val context: Context,
        private val scope: ArtifactMarketScope
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ArtifactMarketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ArtifactMarketViewModel(context, scope) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ArtifactMarketViewModel"
    }
}
