package com.ai.assistance.operit.ui.common.composedsl

import android.graphics.Color as AndroidColor
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.ToolPkgComposeDslNode
import com.ai.assistance.operit.core.tools.packTool.ToolPkgComposeDslParser
import com.ai.assistance.operit.core.tools.packTool.ToolPkgComposeDslRenderResult
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.main.LocalTopBarTitleContent
import com.ai.assistance.operit.ui.main.TopBarTitleContent
import com.ai.assistance.operit.ui.main.components.LocalIsCurrentScreen
import com.ai.assistance.operit.ui.main.components.LocalSetScreenSoftInputMode
import com.ai.assistance.operit.ui.main.components.LocalSetUseScreenImePadding
import com.ai.assistance.operit.ui.features.token.webview.WebViewConfig
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

private const val TAG = "ToolPkgComposeDslScreen"

private fun ToolPkgComposeDslNode.containsNodeType(typeToken: String): Boolean {
    if (normalizeToken(type) == typeToken) {
        return true
    }
    if (children.any { child -> child.containsNodeType(typeToken) }) {
        return true
    }
    return slots.values.any { slotChildren ->
        slotChildren.any { child -> child.containsNodeType(typeToken) }
    }
}

private fun buildComposeDslExecutionContextKey(
    containerPackageName: String,
    uiModuleId: String,
    routeInstanceId: String
): String =
    "toolpkg_compose_dsl:${containerPackageName.trim().ifBlank { "default" }}:${uiModuleId.trim().ifBlank { "default" }}:${routeInstanceId.trim().ifBlank { "default" }}"

internal fun normalizeToken(raw: String): String =
    raw.lowercase(Locale.ROOT)
        .replace("-", "")
        .replace("_", "")
        .trim()

private fun buildZeroArgGetterByToken(
    ownerClass: Class<*>,
    returnTypeMatcher: (Class<*>) -> Boolean
): Map<String, java.lang.reflect.Method> =
    ownerClass.methods
        .asSequence()
        .filter { method ->
            method.name.startsWith("get") &&
                method.parameterCount == 0 &&
                returnTypeMatcher(method.returnType)
        }
        .onEach { method -> method.isAccessible = true }
        .associateBy { method -> normalizeToken(method.name.removePrefix("get")) }

private val typographyGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(androidx.compose.material3.Typography::class.java) { returnType ->
        returnType == androidx.compose.ui.text.TextStyle::class.java
    }
}
private val horizontalAlignmentGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(Alignment::class.java) { returnType ->
        Alignment.Horizontal::class.java.isAssignableFrom(returnType)
    }
}
private val verticalAlignmentGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(Alignment::class.java) { returnType ->
        Alignment.Vertical::class.java.isAssignableFrom(returnType)
    }
}
private val boxAlignmentGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(Alignment::class.java) { returnType ->
        returnType == Alignment::class.java
    }
}
private val horizontalArrangementGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(Arrangement::class.java) { returnType ->
        Arrangement.Horizontal::class.java.isAssignableFrom(returnType)
    }
}
private val verticalArrangementGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(Arrangement::class.java) { returnType ->
        Arrangement.Vertical::class.java.isAssignableFrom(returnType)
    }
}
private val fontWeightGetterByToken: Map<String, java.lang.reflect.Method> by lazy {
    buildZeroArgGetterByToken(FontWeight.Companion::class.java) { returnType ->
        FontWeight::class.java.isAssignableFrom(returnType)
    }
}

private val colorSchemeFieldByToken: Map<String, java.lang.reflect.Field> by lazy {
    androidx.compose.material3.ColorScheme::class.java.declaredFields
        .onEach { it.isAccessible = true }
        .associateBy { field ->
            normalizeToken(field.name)
        }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun ToolPkgComposeDslToolScreen(
    navController: NavController,
    routeInstanceId: String,
    containerPackageName: String,
    uiModuleId: String,
    fallbackTitle: String
) {
    val context = LocalContext.current
    val isCurrentScreen = LocalIsCurrentScreen.current
    val setTopBarTitleContent = LocalTopBarTitleContent.current
    val setScreenSoftInputMode = LocalSetScreenSoftInputMode.current
    val setUseScreenImePadding = LocalSetUseScreenImePadding.current
    val scope = rememberCoroutineScope()
    val renderMutex = remember { Mutex() }
    val currentLanguage =
        (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            }
        )?.toLanguageTag()
            ?.trim()
            ?.ifBlank { null }
            ?: "en"

    val packageManager = remember {
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    }
    val executionContextKey = remember(routeInstanceId, containerPackageName, uiModuleId) {
        buildComposeDslExecutionContextKey(
            containerPackageName = containerPackageName,
            uiModuleId = uiModuleId,
            routeInstanceId = routeInstanceId
        )
    }
    val jsEngine = remember(packageManager, executionContextKey) {
        packageManager.getToolPkgExecutionEngine(executionContextKey)
    }

    var script by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var scriptScreenPath by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var renderResult by remember(containerPackageName, uiModuleId) {
        mutableStateOf<ToolPkgComposeDslRenderResult?>(null)
    }
    var errorMessage by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var isLoading by remember(containerPackageName, uiModuleId) { mutableStateOf(true) }
    var isDispatching by remember(containerPackageName, uiModuleId) { mutableStateOf(false) }
    var dispatchingCount by remember(containerPackageName, uiModuleId) { mutableStateOf(0) }
    var hasDispatchedInitialOnLoad by
        rememberSaveable(routeInstanceId, containerPackageName, uiModuleId) {
            mutableStateOf(false)
        }
    var nextDispatchTicket by remember(containerPackageName, uiModuleId) { mutableStateOf(1L) }
    val settledDispatchTickets = remember(containerPackageName, uiModuleId) { mutableSetOf<Long>() }
    val requiresWebViewImeResize =
        remember(renderResult?.tree) {
            renderResult?.tree?.containsNodeType("webview") == true
        }
    val topBarTitleNodes =
        remember(renderResult?.tree) {
            renderResult?.tree?.slots?.get("topBarTitle").orEmpty()
        }

    fun buildModuleSpec(screenPath: String?): Map<String, Any?> =
        mapOf(
            "id" to uiModuleId,
            "runtime" to "compose_dsl",
            "screen" to (screenPath ?: ""),
            "title" to fallbackTitle,
            "toolPkgId" to containerPackageName
        )

    fun buildActionRuntimeOptions(): Map<String, Any?> {
        val runtimeOptions =
            mutableMapOf<String, Any?>(
                "packageName" to containerPackageName,
                "containerPackageName" to containerPackageName,
                "toolPkgId" to containerPackageName,
                "__operit_ui_package_name" to containerPackageName,
                "__operit_ui_toolpkg_id" to containerPackageName,
                "uiModuleId" to uiModuleId,
                "__operit_ui_module_id" to uiModuleId,
                "__operit_package_lang" to currentLanguage
            )
        val currentScreenPath = scriptScreenPath?.trim().orEmpty()
        if (currentScreenPath.isNotEmpty()) {
            runtimeOptions["__operit_script_screen"] = currentScreenPath
        }
        return runtimeOptions
    }

    fun updateDebugSnapshot(
        phase: String,
        rawRenderResult: Any? = null,
        parsedRenderResult: ToolPkgComposeDslRenderResult? = renderResult,
        error: String? = errorMessage
    ) {
        ToolPkgComposeDslDebugSnapshotStore.update(
            ToolPkgComposeDslDebugSnapshot(
                routeInstanceId = routeInstanceId,
                containerPackageName = containerPackageName,
                uiModuleId = uiModuleId,
                fallbackTitle = fallbackTitle,
                scriptScreenPath = scriptScreenPath,
                scriptSource = script,
                phase = phase,
                rawRenderResultText = rawRenderResult?.toString(),
                renderResult = parsedRenderResult,
                errorMessage = error,
                isLoading = isLoading,
                isDispatching = isDispatching,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    fun dispatchAction(actionId: String, payload: Any? = null) {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            return
        }
        AppLogger.d(
            TAG,
            "compose_dsl dispatchAction: routeInstanceId=$routeInstanceId, package=$containerPackageName, uiModuleId=$uiModuleId, actionId=$normalizedActionId, payload=$payload"
        )
        val dispatchTicket = nextDispatchTicket
        nextDispatchTicket += 1

        dispatchingCount += 1
        isDispatching = dispatchingCount > 0

        val dispatched =
            jsEngine.dispatchComposeDslActionAsync(
                actionId = normalizedActionId,
                payload = payload,
                runtimeOptions = buildActionRuntimeOptions(),
                onIntermediateResult = { intermediateResult ->
                    if (settledDispatchTickets.contains(dispatchTicket)) {
                        return@dispatchComposeDslActionAsync
                    }
                    val parsedIntermediate =
                        ToolPkgComposeDslParser.parseRenderResult(intermediateResult)
                    if (parsedIntermediate != null) {
                        renderResult = parsedIntermediate
                        errorMessage = null
                        updateDebugSnapshot(
                            phase = "dispatch_intermediate",
                            rawRenderResult = intermediateResult,
                            parsedRenderResult = parsedIntermediate,
                            error = null
                        )
                    }
                },
                onFinalResult = { finalResult ->
                    if (settledDispatchTickets.contains(dispatchTicket)) {
                        return@dispatchComposeDslActionAsync
                    }
                    val parsedFinal =
                        ToolPkgComposeDslParser.parseRenderResult(finalResult)
                    if (parsedFinal != null) {
                        renderResult = parsedFinal
                        errorMessage = null
                        updateDebugSnapshot(
                            phase = "dispatch_final",
                            rawRenderResult = finalResult,
                            parsedRenderResult = parsedFinal,
                            error = null
                        )
                    }
                },
                onComplete = {
                    dispatchingCount = (dispatchingCount - 1).coerceAtLeast(0)
                    isDispatching = dispatchingCount > 0
                    updateDebugSnapshot(
                        phase = "dispatch_complete",
                        parsedRenderResult = renderResult,
                        error = errorMessage
                    )
                    settledDispatchTickets.add(dispatchTicket)
                    if (settledDispatchTickets.size > 64) {
                        val latestTickets = settledDispatchTickets.toList().sortedDescending().take(32).toSet()
                        settledDispatchTickets.retainAll(latestTickets)
                    }
                },
                onError = { error ->
                    errorMessage = "compose_dsl runtime error: $error"
                    updateDebugSnapshot(
                        phase = "dispatch_error",
                        parsedRenderResult = renderResult,
                        error = errorMessage
                    )
                    AppLogger.e(
                        TAG,
                        "compose_dsl async action failed: actionId=$normalizedActionId, error=$error"
                    )
                }
            )

        if (!dispatched) {
            dispatchingCount = (dispatchingCount - 1).coerceAtLeast(0)
            isDispatching = dispatchingCount > 0
            updateDebugSnapshot(
                phase = "dispatch_not_started",
                parsedRenderResult = renderResult,
                error = errorMessage
            )
            settledDispatchTickets.add(dispatchTicket)
        }
    }

    SideEffect {
        if (!isCurrentScreen) {
            setTopBarTitleContent(null)
            return@SideEffect
        }

        if (topBarTitleNodes.isNotEmpty()) {
            setTopBarTitleContent(
                TopBarTitleContent {
                    CompositionLocalProvider(
                        LocalComposeDslActionHandler provides ::dispatchAction,
                        LocalComposeDslRouteInstanceId provides routeInstanceId
                    ) {
                        renderComposeDslNodes(
                            nodes = topBarTitleNodes,
                            onAction = ::dispatchAction,
                            nodePath = "0:topBarTitle"
                        )
                    }
                }
            )
        } else {
            setTopBarTitleContent(null)
        }

        if (requiresWebViewImeResize) {
            setScreenSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setUseScreenImePadding(true)
        } else {
            setScreenSoftInputMode(null)
            setUseScreenImePadding(false)
        }
    }

    suspend fun render() {
        var followUpActionId: String? = null
        var snapshotPhase = "render_start"
        var snapshotRawResult: Any? = null
        var snapshotParsedResult: ToolPkgComposeDslRenderResult? = null
        var snapshotError: String? = null
        renderMutex.withLock {
            try {
                isLoading = true
                dispatchingCount = 0
                isDispatching = false
                errorMessage = null

                val scriptText: String? =
                    if (script == null) {
                        val loaded =
                            withContext(Dispatchers.IO) {
                                Pair(
                                    packageManager.getToolPkgComposeDslScript(
                                        containerPackageName = containerPackageName,
                                        uiModuleId = uiModuleId
                                    ),
                                    packageManager.getToolPkgComposeDslScreenPath(
                                        containerPackageName = containerPackageName,
                                        uiModuleId = uiModuleId
                                    )
                                )
                            }
                        if (scriptScreenPath.isNullOrBlank() && !loaded.second.isNullOrBlank()) {
                            scriptScreenPath = loaded.second
                        }
                        loaded.first
                    } else {
                        script
                    }

                if (scriptText.isNullOrBlank()) {
                    renderResult = null
                    errorMessage =
                        "compose_dsl script not found: package=$containerPackageName, module=$uiModuleId"
                    snapshotPhase = "render_missing_script"
                    snapshotParsedResult = null
                    snapshotError = errorMessage
                    return
                }
                if (script == null) {
                    script = scriptText
                }

                val rawResult =
                    withContext(Dispatchers.IO) {
                        jsEngine.executeComposeDslScript(
                            script = scriptText,
                            runtimeOptions =
                                mapOf(
                                    "packageName" to containerPackageName,
                                    "toolPkgId" to containerPackageName,
                                    "uiModuleId" to uiModuleId,
                                    "__operit_package_lang" to currentLanguage,
                                    "__operit_script_screen" to (scriptScreenPath ?: ""),
                                    "moduleSpec" to buildModuleSpec(scriptScreenPath),
                                    "state" to (renderResult?.state ?: emptyMap<String, Any?>()),
                                    "memo" to (renderResult?.memo ?: emptyMap<String, Any?>())
                                )
                        )
                    }
                snapshotRawResult = rawResult

                val rawText = rawResult?.toString()?.trim().orEmpty()
                val parsed = ToolPkgComposeDslParser.parseRenderResult(rawResult)
                if (parsed == null) {
                    val normalizedError =
                        when {
                            rawText.startsWith("Error:", ignoreCase = true) -> rawText
                            rawText.isNotBlank() -> "Invalid compose_dsl result: $rawText"
                            else -> "Invalid compose_dsl result"
                        }
                    renderResult = null
                    errorMessage = normalizedError
                    snapshotPhase = "render_invalid_result"
                    snapshotParsedResult = null
                    snapshotError = normalizedError
                    AppLogger.e(TAG, normalizedError)
                    return
                }

                renderResult = parsed
                errorMessage = null
                snapshotPhase = "render_success"
                snapshotParsedResult = parsed
                snapshotError = null

                followUpActionId =
                    ToolPkgComposeDslParser.extractActionId(parsed.tree.props["onLoad"])
                Unit
            } catch (e: Exception) {
                renderResult = null
                errorMessage = "compose_dsl runtime error: ${e.message}"
                snapshotPhase = "render_exception"
                snapshotParsedResult = null
                snapshotError = errorMessage
                AppLogger.e(TAG, "compose_dsl render failed", e)
            } finally {
                isLoading = false
                updateDebugSnapshot(
                    phase = snapshotPhase,
                    rawRenderResult = snapshotRawResult,
                    parsedRenderResult = snapshotParsedResult,
                    error = snapshotError
                )
            }
        }

        val onLoadActionId = followUpActionId
        if (!onLoadActionId.isNullOrBlank() && !hasDispatchedInitialOnLoad) {
            hasDispatchedInitialOnLoad = true
            dispatchAction(actionId = onLoadActionId, payload = null)
        }
    }

    LaunchedEffect(routeInstanceId, containerPackageName, uiModuleId) {
        scope.launch {
            render()
        }
    }

    DisposableEffect(executionContextKey) {
        onDispose {
            setTopBarTitleContent(null)
            ToolPkgComposeDslDebugSnapshotStore.clear(routeInstanceId)
            packageManager.releaseToolPkgExecutionEngine(executionContextKey)
        }
    }

    CustomScaffold { paddingValues ->
        val rootNode = renderResult?.tree
        val contentModifier =
            Modifier
                .padding(paddingValues)
                .fillMaxSize()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Column(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    hasDispatchedInitialOnLoad = false
                                    render()
                                }
                            }
                        ) {
                            Text("Retry")
                        }
                    }
                }
                rootNode != null -> {
                    CompositionLocalProvider(
                        LocalComposeDslActionHandler provides ::dispatchAction,
                        LocalComposeDslRouteInstanceId provides routeInstanceId
                    ) {
                        // Let compose_dsl content own its own scrolling behavior.
                        // Wrapping the whole screen in an outer verticalScroll changes
                        // root measurement semantics and breaks full-screen layouts.
                        Box(modifier = contentModifier) {
                            renderComposeDslNode(
                                node = rootNode,
                                onAction = ::dispatchAction,
                                nodePath = "0"
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun RenderToolPkgComposeDslNode(
    node: ToolPkgComposeDslNode,
    modifier: Modifier = Modifier,
    onAction: (String, Any?) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(
        LocalComposeDslActionHandler provides onAction,
        LocalComposeDslRouteInstanceId provides ""
    ) {
        Box(modifier = modifier) {
            renderComposeDslNode(
                node = node,
                onAction = onAction,
                nodePath = "0"
            )
        }
    }
}

internal val LocalComposeDslActionHandler = staticCompositionLocalOf<(String, Any?) -> Unit> {
    { _, _ -> }
}
internal val LocalComposeDslRouteInstanceId = staticCompositionLocalOf { "" }

private data class ComposeDslDebugNodeInfo(
    val routeInstanceId: String,
    val nodePath: String,
    val nodeType: String,
    val nodeKey: String?
)

private val LocalComposeDslDebugNodeInfo = staticCompositionLocalOf<ComposeDslDebugNodeInfo?> {
    null
}

internal typealias ComposeDslModifierResolver =
    @Composable (Modifier, Map<String, Any?>) -> Modifier

@Composable
internal fun renderComposeDslNode(
    node: ToolPkgComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver = { base, props ->
        defaultComposeDslModifierResolver(base, props)
    }
) {
    val routeInstanceId = LocalComposeDslRouteInstanceId.current
    val nodeKey = node.props["key"]?.toString()?.trim()?.ifBlank { null }
    CompositionLocalProvider(
        LocalComposeDslDebugNodeInfo provides
            ComposeDslDebugNodeInfo(
                routeInstanceId = routeInstanceId,
                nodePath = nodePath,
                nodeType = node.type,
                nodeKey = nodeKey
            )
    ) {
        val normalizedType = normalizeToken(node.type)
        if (normalizedType == "canvas") {
            renderCanvasNode(node, onAction, modifierResolver)
            return@CompositionLocalProvider
        }
        if (normalizedType == "webview") {
            renderWebViewNode(node, onAction, modifierResolver)
            return@CompositionLocalProvider
        }
        val renderer = composeDslGeneratedNodeRendererRegistry[normalizedType]
        if (renderer != null) {
            renderer(node, onAction, nodePath, modifierResolver)
            return@CompositionLocalProvider
        }
        Text(
            text = "Unsupported node: ${node.type}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun applyComposeDslNodeDebugLayoutModifier(modifier: Modifier): Modifier {
    val nodeInfo = LocalComposeDslDebugNodeInfo.current ?: return modifier
    if (nodeInfo.routeInstanceId.isBlank()) {
        return modifier
    }
    return modifier.onGloballyPositioned { coordinates ->
        val rootBounds = coordinates.boundsInRoot()
        val windowPosition = coordinates.positionInWindow()
        ToolPkgComposeDslDebugSnapshotStore.updateLayout(
            ToolPkgComposeDslLayoutSnapshot(
                routeInstanceId = nodeInfo.routeInstanceId,
                nodePath = nodeInfo.nodePath,
                nodeType = nodeInfo.nodeType,
                nodeKey = nodeInfo.nodeKey,
                rootX = rootBounds.left,
                rootY = rootBounds.top,
                width = rootBounds.width,
                height = rootBounds.height,
                windowX = windowPosition.x,
                windowY = windowPosition.y,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }
}

internal typealias ComposeDslNodeRenderer =
    @Composable (ToolPkgComposeDslNode, (String, Any?) -> Unit, String, ComposeDslModifierResolver) -> Unit

private data class CanvasCommand(
    val type: String,
    val values: Map<String, Any?>,
    val unit: String,
    val color: Color,
    val brush: Brush?,
    val alpha: Float?,
    val strokeWidth: Float
)

private data class ComposeDslWebViewRequest(
    val url: String?,
    val html: String?,
    val baseUrl: String?,
    val mimeType: String,
    val encoding: String,
    val headers: Map<String, String>
)

private data class ComposeDslWebViewCallbackIds(
    val onPageStarted: String?,
    val onPageFinished: String?,
    val onReceivedError: String?,
    val onUrlChanged: String?,
    val onProgressChanged: String?
)

private fun canvasNumberFromValue(value: Any?): Float? {
    return when (value) {
        is Number -> value.toFloat()
        is Map<*, *> -> {
            val raw = value["value"]
            when (raw) {
                is Number -> raw.toFloat()
                else -> raw?.toString()?.toFloatOrNull()
            }
        }
        else -> value?.toString()?.toFloatOrNull()
    }
}

private fun canvasUnitFromValue(value: Any?): String? {
    val map = value as? Map<*, *> ?: return null
    val token =
        map["unit"]?.toString()
            ?: map["__unit"]?.toString()
    return token?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { null }
}

private fun buildComposeDslWebViewRequest(props: Map<String, Any?>): ComposeDslWebViewRequest {
    val url = props.stringOrNull("url")
    val html = props.stringOrNull("html")
    require(url != null || html != null) {
        "WebView requires either 'url' or 'html'."
    }
    val headers =
        (props["headers"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                val normalizedKey = key?.toString()?.trim().orEmpty()
                if (normalizedKey.isBlank() || value == null) {
                    null
                } else {
                    normalizedKey to value.toString()
                }
            }
            ?.toMap()
            .orEmpty()
    return ComposeDslWebViewRequest(
        url = url,
        html = html,
        baseUrl = props.stringOrNull("baseUrl"),
        mimeType = props.string("mimeType", "text/html"),
        encoding = props.string("encoding", "UTF-8"),
        headers = headers
    )
}


@Composable
private fun parseCanvasCommands(raw: Any?): List<CanvasCommand> {
    val list = raw as? List<*> ?: return emptyList()
    @Composable
    fun parseCanvasBrush(value: Any?): Brush? {
        val map = value as? Map<*, *> ?: return null
        val type = map["type"]?.toString()?.trim()?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("canvas brush type is required")
        require(type == "verticalgradient") { "unsupported canvas brush type: $type" }
        val colorsRaw = map["colors"] as? List<*>
            ?: throw IllegalArgumentException("canvas brush colors are required")
        require(colorsRaw.isNotEmpty()) { "canvas brush colors are empty" }
        val colors = colorsRaw.mapIndexed { index, entry ->
            val resolved = resolveColorValue(entry)
                ?: throw IllegalArgumentException("canvas brush color not resolved at $index")
            resolved
        }
        return Brush.verticalGradient(colors)
    }
    return list.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val type = map["type"]?.toString()?.trim().orEmpty()
        if (type.isBlank()) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val values = map.entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>
        val unit =
            canvasUnitFromValue(values["unit"])
                ?: values["unit"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                ?: "fraction"
        val alpha = canvasNumberFromValue(values["alpha"])
        val strokeWidth = canvasNumberFromValue(values["strokeWidth"]) ?: 1f
        val resolvedColor = resolveColorValue(values["color"])
        val color = resolvedColor ?: Color.Unspecified
        val brush = parseCanvasBrush(values["brush"])
        CanvasCommand(
            type = type.lowercase(Locale.ROOT),
            values = values,
            unit = unit,
            color = color,
            brush = brush,
            alpha = alpha,
            strokeWidth = strokeWidth
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun renderCanvasNode(
    node: ToolPkgComposeDslNode,
    onAction: (String, Any?) -> Unit,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val commands = parseCanvasCommands(props["commands"])
    val textMeasurer = rememberTextMeasurer()
    val onTransformActionId = ToolPkgComposeDslParser.extractActionId(props["onTransform"])
    val onSizeChangedActionId = ToolPkgComposeDslParser.extractActionId(props["onSizeChanged"])
    var lastSize by remember { mutableStateOf(IntSize.Zero) }

    val transform = props["transform"] as? Map<*, *>
    val transformScale = (transform?.get("scale") as? Number)?.toFloat()
    val transformOffsetX = (transform?.get("offsetX") as? Number)?.toFloat()
    val transformOffsetY = (transform?.get("offsetY") as? Number)?.toFloat()
    val transformPivotX = (transform?.get("pivotX") as? Number)?.toFloat()
    val transformPivotY = (transform?.get("pivotY") as? Number)?.toFloat()
    var localScale by remember(transformScale, transformOffsetX, transformOffsetY) {
        mutableStateOf(transformScale ?: 1f)
    }
    var localOffset by remember(transformScale, transformOffsetX, transformOffsetY) {
        mutableStateOf(
            androidx.compose.ui.geometry.Offset(
                transformOffsetX ?: 0f,
                transformOffsetY ?: 0f
            )
        )
    }

    var modifier =
        applyScopedCommonModifier(Modifier, props, modifierResolver)
            .onSizeChanged { size ->
                if (onSizeChangedActionId != null && size != lastSize) {
                    lastSize = size
                    onAction(
                        onSizeChangedActionId,
                        mapOf(
                            "width" to size.width,
                            "height" to size.height
                        )
                    )
                }
            }

    if (onTransformActionId != null) {
        modifier =
            modifier.pointerInput(onTransformActionId) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    localScale = (localScale * zoom).coerceIn(0.6f, 2f)
                    localOffset = localOffset + pan
                    onAction(
                        onTransformActionId,
                        mapOf(
                            "__no_render" to true,
                            "centroidX" to centroid.x,
                            "centroidY" to centroid.y,
                            "panX" to pan.x,
                            "panY" to pan.y,
                            "zoom" to zoom,
                            "rotation" to rotation
                        )
                    )
                }
            }
    }

    Canvas(modifier = modifier) {
        val widthPx = size.width
        val heightPx = size.height

        fun resolve(value: Any?, defaultUnit: String, axis: String): Float {
            val unit = canvasUnitFromValue(value) ?: defaultUnit
            val numeric = canvasNumberFromValue(value) ?: 0f
            return when (unit) {
                "fraction" -> if (axis == "x") numeric * widthPx else numeric * heightPx
                "dp" -> numeric.dp.toPx()
                else -> numeric
            }
        }

        fun drawCommands() {
            commands.forEach { command ->
            val values = command.values
            val unit = command.unit
            val strokeWidth = command.strokeWidth
            val color = if (command.alpha != null) command.color.copy(alpha = command.alpha) else command.color
            val brush = command.brush
            val brushAlpha = command.alpha ?: 1f

            fun resolveStyle(): androidx.compose.ui.graphics.drawscope.DrawStyle {
                val token = values["style"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                return if (token == "stroke") Stroke(width = strokeWidth) else Fill
            }

            fun buildPath(raw: Any?): Path? {
                val list = raw as? List<*> ?: return null
                val path = Path()
                list.forEach { opRaw ->
                    val op = opRaw as? Map<*, *> ?: return@forEach
                    val opType = op["type"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    when (opType) {
                        "moveto" -> {
                            val x = resolve(op["x"], unit, "x")
                            val y = resolve(op["y"], unit, "y")
                            path.moveTo(x, y)
                        }
                        "lineto" -> {
                            val x = resolve(op["x"], unit, "x")
                            val y = resolve(op["y"], unit, "y")
                            path.lineTo(x, y)
                        }
                        "cubicto" -> {
                            val x1 = resolve(op["x1"], unit, "x")
                            val y1 = resolve(op["y1"], unit, "y")
                            val x2 = resolve(op["x2"], unit, "x")
                            val y2 = resolve(op["y2"], unit, "y")
                            val x3 = resolve(op["x3"], unit, "x")
                            val y3 = resolve(op["y3"], unit, "y")
                            path.cubicTo(x1, y1, x2, y2, x3, y3)
                        }
                        "quadto" -> {
                            val x1 = resolve(op["x1"], unit, "x")
                            val y1 = resolve(op["y1"], unit, "y")
                            val x2 = resolve(op["x2"], unit, "x")
                            val y2 = resolve(op["y2"], unit, "y")
                            path.quadraticBezierTo(x1, y1, x2, y2)
                        }
                        "close" -> path.close()
                    }
                }
                return path
            }

            when (command.type) {
                "line" -> {
                    val x1 = resolve(values["x1"], unit, "x")
                    val y1 = resolve(values["y1"], unit, "y")
                    val x2 = resolve(values["x2"], unit, "x")
                    val y2 = resolve(values["y2"], unit, "y")
                    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(x1, y1), end = androidx.compose.ui.geometry.Offset(x2, y2), strokeWidth = strokeWidth)
                }
                "rect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val filled = (values["filled"] as? Boolean) ?: true
                    val style = if (filled) Fill else Stroke(width = strokeWidth)
                    if (brush != null) {
                        drawRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            style = style
                        )
                    }
                }
                "roundrect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val radius = canvasNumberFromValue(values["radius"]) ?: 0f
                    val filled = (values["filled"] as? Boolean) ?: true
                    val style = if (filled) Fill else Stroke(width = strokeWidth)
                    if (brush != null) {
                        drawRoundRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRoundRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            style = style
                        )
                    }
                }
                "drawroundrect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val radius = canvasNumberFromValue(values["cornerRadius"])
                        ?: canvasNumberFromValue(values["radius"])
                        ?: 0f
                    val style = resolveStyle()
                    if (brush != null) {
                        drawRoundRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRoundRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            style = style
                        )
                    }
                }
                "circle" -> {
                    val cx = resolve(values["cx"], unit, "x")
                    val cy = resolve(values["cy"], unit, "y")
                    val r = resolve(values["radius"], unit, "x")
                    val filled = (values["filled"] as? Boolean) ?: true
                    drawCircle(
                        color = color,
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = if (filled) Fill else Stroke(width = strokeWidth)
                    )
                }
                "text" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val text = values["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val fontSize = canvasNumberFromValue(values["fontSize"]) ?: 10f
                        val minWidthRaw = values["minWidth"]
                        val maxWidthRaw = values["maxWidth"]
                        val minWidth = canvasNumberFromValue(minWidthRaw)
                        val maxWidth = canvasNumberFromValue(maxWidthRaw)
                        val minHeightRaw = values["minHeight"]
                        val maxHeightRaw = values["maxHeight"]
                        val minHeight = canvasNumberFromValue(minHeightRaw)
                        val maxHeight = canvasNumberFromValue(maxHeightRaw)
                        val maxLines = (values["maxLines"] as? Number)?.toInt() ?: Int.MAX_VALUE
                        val overflowToken = values["overflow"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                        val overflow =
                            if (overflowToken == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = TextStyle(color = color, fontSize = fontSize.sp),
                            maxLines = maxLines,
                            overflow = overflow,
                            constraints = if (minWidth != null || maxWidth != null || minHeight != null || maxHeight != null) {
                                androidx.compose.ui.unit.Constraints(
                                    minWidth = minWidth?.let { resolve(minWidthRaw, unit, "x").toInt() } ?: 0,
                                    maxWidth = maxWidth?.let { resolve(maxWidthRaw, unit, "x").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity,
                                    minHeight = minHeight?.let { resolve(minHeightRaw, unit, "y").toInt() } ?: 0,
                                    maxHeight = maxHeight?.let { resolve(maxHeightRaw, unit, "y").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity
                                )
                            } else {
                                androidx.compose.ui.unit.Constraints()
                            }
                        )
                        drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
                "drawtext" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val text = values["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val fontSize = canvasNumberFromValue(values["fontSize"]) ?: 10f
                        val minWidthRaw = values["minWidth"]
                        val maxWidthRaw = values["maxWidth"]
                        val minWidth = canvasNumberFromValue(minWidthRaw)
                        val maxWidth = canvasNumberFromValue(maxWidthRaw)
                        val minHeightRaw = values["minHeight"]
                        val maxHeightRaw = values["maxHeight"]
                        val minHeight = canvasNumberFromValue(minHeightRaw)
                        val maxHeight = canvasNumberFromValue(maxHeightRaw)
                        val maxLines = (values["maxLines"] as? Number)?.toInt() ?: Int.MAX_VALUE
                        val overflowToken = values["overflow"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                        val overflow =
                            if (overflowToken == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = TextStyle(color = color, fontSize = fontSize.sp),
                            maxLines = maxLines,
                            overflow = overflow,
                            constraints = if (minWidth != null || maxWidth != null || minHeight != null || maxHeight != null) {
                                androidx.compose.ui.unit.Constraints(
                                    minWidth = minWidth?.let { resolve(minWidthRaw, unit, "x").toInt() } ?: 0,
                                    maxWidth = maxWidth?.let { resolve(maxWidthRaw, unit, "x").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity,
                                    minHeight = minHeight?.let { resolve(minHeightRaw, unit, "y").toInt() } ?: 0,
                                    maxHeight = maxHeight?.let { resolve(maxHeightRaw, unit, "y").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity
                                )
                            } else {
                                androidx.compose.ui.unit.Constraints()
                            }
                        )
                        drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
                "drawpath" -> {
                    val path = buildPath(values["path"]) ?: return@forEach
                    drawPath(path = path, color = color, style = resolveStyle())
                }
            }
            }
        }

        val activeScale = if (onTransformActionId != null) localScale else transformScale
        val activeOffset = if (onTransformActionId != null) localOffset else null
        if (activeScale != null || activeOffset != null) {
            val pivot =
                androidx.compose.ui.geometry.Offset(
                    transformPivotX ?: (widthPx / 2f),
                    transformPivotY ?: (heightPx / 2f)
                )
            withTransform(
                {
                    val offsetToUse =
                        activeOffset ?: androidx.compose.ui.geometry.Offset(0f, 0f)
                    translate(offsetToUse.x, offsetToUse.y)
                    if (activeScale != null) {
                        scale(activeScale, activeScale, pivot)
                    }
                }
            ) {
                drawCommands()
            }
        } else {
            drawCommands()
        }
    }
}

@Composable
private fun renderWebViewNode(
    node: ToolPkgComposeDslNode,
    onAction: (String, Any?) -> Unit,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val request = buildComposeDslWebViewRequest(props)
    val context = LocalContext.current
    val nestedScrollInterop = rememberNestedScrollInteropConnection()
    val modifier =
        applyScopedCommonModifier(Modifier, props, modifierResolver).let { base ->
            if (props.bool("nestedScrollInterop", false)) {
                base.nestedScroll(nestedScrollInterop)
            } else {
                base
            }
        }
    val callbackIdsState =
        rememberUpdatedState(
            ComposeDslWebViewCallbackIds(
                onPageStarted = ToolPkgComposeDslParser.extractActionId(props["onPageStarted"]),
                onPageFinished = ToolPkgComposeDslParser.extractActionId(props["onPageFinished"]),
                onReceivedError = ToolPkgComposeDslParser.extractActionId(props["onReceivedError"]),
                onUrlChanged = ToolPkgComposeDslParser.extractActionId(props["onUrlChanged"]),
                onProgressChanged = ToolPkgComposeDslParser.extractActionId(props["onProgressChanged"])
            )
        )
    val onActionState = rememberUpdatedState(onAction)
    var pendingFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooserCallback
            pendingFileChooserCallback = null
            if (callback == null) {
                return@rememberLauncherForActivityResult
            }
            val uris =
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: run {
                        val intent = result.data
                        val directData = intent?.data?.let { arrayOf(it) }
                        val clipData =
                            intent?.clipData?.let { clip ->
                                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                            }
                        directData ?: clipData
                    }
            callback.onReceiveValue(uris)
        }

    val webView = remember(context) {
        WebViewConfig.createWebView(context).apply {
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val callbackIds = callbackIdsState.value
                        val url = request?.url?.toString()
                        val actionId = callbackIds.onUrlChanged
                        if (!actionId.isNullOrBlank() && !url.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "url" to url,
                                    "isMainFrame" to (request?.isForMainFrame ?: true),
                                    "method" to request?.method
                                )
                            )
                        }
                        return false
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        val callbackIds = callbackIdsState.value
                        val actionId = callbackIds.onPageStarted
                        if (!actionId.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "url" to url,
                                    "title" to view?.title,
                                    "canGoBack" to (view?.canGoBack() ?: false),
                                    "canGoForward" to (view?.canGoForward() ?: false)
                                )
                            )
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val callbackIds = callbackIdsState.value
                        val actionId = callbackIds.onPageFinished
                        if (!actionId.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "url" to url,
                                    "title" to view?.title,
                                    "canGoBack" to (view?.canGoBack() ?: false),
                                    "canGoForward" to (view?.canGoForward() ?: false)
                                )
                            )
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        val callbackIds = callbackIdsState.value
                        val actionId = callbackIds.onReceivedError
                        if (!actionId.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "errorCode" to errorCode,
                                    "description" to description,
                                    "url" to failingUrl
                                )
                            )
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        val callbackIds = callbackIdsState.value
                        val actionId = callbackIds.onReceivedError
                        if (!actionId.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "errorCode" to error?.errorCode,
                                    "description" to error?.description?.toString(),
                                    "url" to request?.url?.toString(),
                                    "isMainFrame" to (request?.isForMainFrame ?: true)
                                )
                            )
                        }
                    }
                }

            webChromeClient =
                object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        val message = resultMsg ?: return false
                        val transport = message.obj as? WebView.WebViewTransport ?: return false
                        transport.webView = view
                        message.sendToTarget()
                        return true
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        return super.onConsoleMessage(consoleMessage)
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        val callbackIds = callbackIdsState.value
                        val actionId = callbackIds.onProgressChanged
                        if (!actionId.isNullOrBlank()) {
                            onActionState.value(
                                actionId,
                                mapOf(
                                    "progress" to newProgress,
                                    "url" to view?.url,
                                    "title" to view?.title
                                )
                            )
                        }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        if (filePathCallback == null) {
                            return false
                        }
                        pendingFileChooserCallback?.onReceiveValue(null)
                        pendingFileChooserCallback = filePathCallback
                        return try {
                            val intent =
                                fileChooserParams?.createIntent()
                                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                    }
                            if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (_: Throwable) {
                            pendingFileChooserCallback?.onReceiveValue(null)
                            pendingFileChooserCallback = null
                            false
                        }
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        callback?.invoke(origin.orEmpty(), true, false)
                    }

                    override fun onJsAlert(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?
                    ): Boolean {
                        result?.confirm()
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        result: android.webkit.JsResult?
                    ): Boolean {
                        result?.confirm()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        defaultValue: String?,
                        result: android.webkit.JsPromptResult?
                    ): Boolean {
                        result?.confirm(defaultValue)
                        return true
                    }
                }
        }
    }

    DisposableEffect(webView) {
        webView.post {
            webView.requestFocus()
        }
        onDispose {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    LaunchedEffect(
        webView,
        request.url,
        request.html,
        request.baseUrl,
        request.mimeType,
        request.encoding,
        request.headers,
        props.bool("javaScriptEnabled", true),
        props.bool("domStorageEnabled", true),
        props.bool("allowFileAccess", true),
        props.bool("allowContentAccess", true),
        props.bool("supportZoom", true),
        props.bool("useWideViewPort", true),
        props.bool("loadWithOverviewMode", true),
        props.stringOrNull("userAgent")
    ) {
        webView.settings.apply {
            javaScriptEnabled = props.bool("javaScriptEnabled", true)
            domStorageEnabled = props.bool("domStorageEnabled", true)
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = props.bool("allowFileAccess", true)
            allowContentAccess = props.bool("allowContentAccess", true)
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            val supportZoom = props.bool("supportZoom", true)
            setSupportZoom(supportZoom)
            builtInZoomControls = supportZoom
            displayZoomControls = false
            loadWithOverviewMode = props.bool("loadWithOverviewMode", true)
            useWideViewPort = props.bool("useWideViewPort", true)
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            props.stringOrNull("userAgent")?.let { userAgentString = it }
        }
        val url = request.url
        if (url != null) {
            if (request.headers.isEmpty()) {
                webView.loadUrl(url)
            } else {
                webView.loadUrl(url, request.headers)
            }
        } else {
            webView.loadDataWithBaseURL(
                request.baseUrl,
                request.html.orEmpty(),
                request.mimeType,
                request.encoding,
                null
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView }
    )
}


@Composable
internal fun applyCommonModifier(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    var modifier = base

    val explicitWidth = props.floatOrNull("width")
    if (explicitWidth != null) {
        modifier = modifier.width(explicitWidth.dp)
    }
    val explicitHeight = props.floatOrNull("height")
    if (explicitHeight != null) {
        modifier = modifier.height(explicitHeight.dp)
    }

    if (props.bool("fillMaxSize", false)) {
        modifier = modifier.fillMaxSize()
    } else if (props.bool("fillMaxWidth", false)) {
        modifier = modifier.fillMaxWidth()
    }

    val paddingValue = props["padding"]
    if (paddingValue is Map<*, *>) {
        val horizontal = (paddingValue["horizontal"] as? Number)?.toFloat()
        val vertical = (paddingValue["vertical"] as? Number)?.toFloat()
        if (horizontal != null || vertical != null) {
            modifier = modifier.padding(
                horizontal = (horizontal ?: 0f).dp,
                vertical = (vertical ?: 0f).dp
            )
        }
    } else {
        val allPadding = props.floatOrNull("padding")
        if (allPadding != null) {
            modifier = modifier.padding(allPadding.dp)
        } else {
            val horizontal = props.floatOrNull("paddingHorizontal")
            val vertical = props.floatOrNull("paddingVertical")
            if (horizontal != null || vertical != null) {
                modifier = modifier.padding(
                    horizontal = (horizontal ?: 0f).dp,
                    vertical = (vertical ?: 0f).dp
                )
            }
        }
    }

    val backgroundBrush = props["backgroundBrush"]
    if (backgroundBrush != null) {
        val shape = shapeFromValue(props["backgroundShape"]) ?: shapeFromValue(props["shape"])
        val brush = parseBrush(backgroundBrush)
        if (brush != null) {
            if (shape != null) {
                modifier = modifier.clip(shape).background(brush, shape = shape)
            } else {
                modifier = modifier.background(brush)
            }
        }
    }

    val zIndex = props.floatOrNull("zIndex")
    if (zIndex != null) {
        modifier = modifier.zIndex(zIndex)
    }

    modifier = applyProxyModifierOps(modifier, props["modifier"])

    return modifier
}

@Composable
private fun parseBrush(value: Any?): Brush? {
    val map = value as? Map<*, *> ?: return null
    val type = map["type"]?.toString()?.trim()?.lowercase(Locale.ROOT)
        ?: throw IllegalArgumentException("brush type is required")
    require(type == "verticalgradient") { "unsupported brush type: $type" }
    val colorsRaw = map["colors"] as? List<*>
        ?: throw IllegalArgumentException("brush colors are required")
    require(colorsRaw.isNotEmpty()) { "brush colors are empty" }
    val colors = colorsRaw.mapIndexed { index, entry ->
        resolveColorValue(entry)
            ?: throw IllegalArgumentException("brush color not resolved at $index")
    }
    return Brush.verticalGradient(colors)
}

private data class ComposeDslModifierOp(
    val name: String,
    val args: List<Any?>
)

internal fun Map<String, Any?>.paddingValuesOrNull(key: String): PaddingValues? {
    return when (val raw = this[key]) {
        is Number -> PaddingValues(raw.toFloat().dp)
        is Map<*, *> -> {
            val horizontal = (raw["horizontal"] as? Number)?.toFloat()
            val vertical = (raw["vertical"] as? Number)?.toFloat()
            if (horizontal != null || vertical != null) {
                PaddingValues(
                    horizontal = (horizontal ?: 0f).dp,
                    vertical = (vertical ?: 0f).dp
                )
            } else {
                null
            }
        }
        else -> null
    }
}

@Composable
private fun applyProxyModifierOps(
    base: Modifier,
    rawModifier: Any?
): Modifier {
    val ops = extractModifierOps(rawModifier)
    if (ops.isEmpty()) {
        return base
    }
    var modifier = base
    ops.forEach { op ->
        modifier = applySingleModifierOp(modifier, op)
    }
    return modifier
}

private fun extractModifierOps(rawModifier: Any?): List<ComposeDslModifierOp> {
    val container = rawModifier as? Map<*, *> ?: return emptyList()
    val list = container["__modifierOps"] as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val name = map["name"]?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            return@mapNotNull null
        }
        val args = (map["args"] as? List<*>)?.toList() ?: emptyList()
        ComposeDslModifierOp(name = name, args = args)
    }
}

internal fun Map<String, Any?>.scopeAlignToken(): String? {
    stringOrNull("align")?.let { return it }
    return extractModifierOps(this["modifier"])
        .lastOrNull { op -> normalizeToken(op.name) == "align" }
        ?.args
        ?.firstOrNull()
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

@Composable
private fun applySingleModifierOp(
    modifier: Modifier,
    op: ComposeDslModifierOp
): Modifier {
    val onAction = LocalComposeDslActionHandler.current
    val nodeInfo = LocalComposeDslDebugNodeInfo.current
    val token = normalizeToken(op.name)
    return when (token) {
        "fillmaxsize" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxSize(fraction.coerceAtLeast(0f))
        }
        "fillmaxwidth" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxWidth(fraction.coerceAtLeast(0f))
        }
        "fillmaxheight" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxHeight(fraction.coerceAtLeast(0f))
        }
        "width" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.width(value.dp)
        }
        "height" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.height(value.dp)
        }
        "size" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.size(value.dp)
        }
        "padding" -> applyPaddingModifierOp(modifier, op.args)
        "offset" -> applyOffsetModifierOp(modifier, op.args)
        "aspectratio" -> {
            val ratio = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.aspectRatio(ratio, true)
        }
        "alpha" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.alpha(value)
        }
        "rotate" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.rotate(value)
        }
        "scale" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.scale(value)
        }
        "zindex" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.zIndex(value)
        }
        "background" -> {
            val color = colorFromModifierArg(op.args.getOrNull(0)) ?: return modifier
            val shape = shapeFromModifierArg(op.args.getOrNull(1))
            if (shape != null) {
                modifier.background(color = color, shape = shape)
            } else {
                modifier.background(color = color)
            }
        }
        "border" -> {
            val width = op.args.getOrNull(0).floatArg() ?: 1f
            val color = colorFromModifierArg(op.args.getOrNull(1)) ?: return modifier
            val shape = shapeFromModifierArg(op.args.getOrNull(2))
            if (shape != null) {
                modifier.border(width = width.dp, color = color, shape = shape)
            } else {
                modifier.border(width = width.dp, color = color)
            }
        }
        "clip" -> {
            val shape = shapeFromModifierArg(op.args.getOrNull(0)) ?: return modifier
            modifier.clip(shape)
        }
        "clickable" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(op.args.getOrNull(0))
            if (actionId.isNullOrBlank()) {
                modifier
            } else {
                modifier.clickable {
                    AppLogger.d(
                        TAG,
                        "compose_dsl clickable triggered: routeInstanceId=${nodeInfo?.routeInstanceId.orEmpty()}, nodePath=${nodeInfo?.nodePath.orEmpty()}, nodeType=${nodeInfo?.nodeType.orEmpty()}, nodeKey=${nodeInfo?.nodeKey.orEmpty()}, actionId=$actionId"
                    )
                    onAction(actionId, null)
                }
            }
        }
        else -> modifier
    }
}

private fun applyPaddingModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    if (args.isEmpty()) {
        return modifier
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val all = first["all"].floatArg()
        if (all != null) {
            return modifier.padding(all.dp)
        }
        val horizontal = first["horizontal"].floatArg() ?: 0f
        val vertical = first["vertical"].floatArg() ?: 0f
        val start = first["start"].floatArg()
        val top = first["top"].floatArg()
        val end = first["end"].floatArg()
        val bottom = first["bottom"].floatArg()
        return if (start != null || top != null || end != null || bottom != null) {
            modifier.padding(
                start = (start ?: 0f).dp,
                top = (top ?: 0f).dp,
                end = (end ?: 0f).dp,
                bottom = (bottom ?: 0f).dp
            )
        } else {
            modifier.padding(horizontal = horizontal.dp, vertical = vertical.dp)
        }
    }

    val firstNumber = first.floatArg()
    val secondNumber = args.getOrNull(1).floatArg()
    val thirdNumber = args.getOrNull(2).floatArg()
    val fourthNumber = args.getOrNull(3).floatArg()

    return when {
        firstNumber != null && secondNumber == null -> modifier.padding(firstNumber.dp)
        firstNumber != null && secondNumber != null && thirdNumber == null ->
            modifier.padding(horizontal = firstNumber.dp, vertical = secondNumber.dp)
        firstNumber != null && secondNumber != null && thirdNumber != null && fourthNumber != null ->
            modifier.padding(
                start = firstNumber.dp,
                top = secondNumber.dp,
                end = thirdNumber.dp,
                bottom = fourthNumber.dp
            )
        else -> modifier
    }
}

private fun applyOffsetModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    if (args.isEmpty()) {
        return modifier
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val x = first["x"].floatArg() ?: 0f
        val y = first["y"].floatArg() ?: 0f
        return modifier.offset(x.dp, y.dp)
    }
    val x = first.floatArg() ?: 0f
    val y = args.getOrNull(1).floatArg() ?: 0f
    return modifier.offset(x.dp, y.dp)
}

@Composable
private fun colorFromModifierArg(value: Any?): Color? {
    return resolveColorValue(value)
}

private fun shapeFromValue(value: Any?): androidx.compose.ui.graphics.Shape? {
    if (value is Map<*, *>) {
        val cornerRadius = value["cornerRadius"].floatArg()
        if (cornerRadius != null) {
            return RoundedCornerShape(cornerRadius.dp)
        }
    }
    return null
}

private fun shapeFromModifierArg(value: Any?): androidx.compose.ui.graphics.Shape? {
    return shapeFromValue(value)
}

private fun Any?.floatArg(): Float? {
    return canvasNumberFromValue(this)
}

internal fun Map<String, Any?>.string(key: String, defaultValue: String = ""): String {
    return this[key]?.toString().orEmpty().ifBlank { defaultValue }
}

internal fun Map<String, Any?>.stringOrNull(key: String): String? {
    val value = this[key]?.toString()?.trim().orEmpty()
    return if (value.isBlank()) null else value
}

internal fun Map<String, Any?>.bool(key: String, defaultValue: Boolean): Boolean {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().equals("true", ignoreCase = true)
    }
}

internal fun Map<String, Any?>.int(key: String, defaultValue: Int): Int {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toIntOrNull() ?: defaultValue
    }
}

internal fun Map<String, Any?>.floatOrNull(key: String): Float? {
    val value = this[key] ?: return null
    return canvasNumberFromValue(value)
}

internal fun Map<String, Any?>.dp(key: String, defaultValue: Dp = 0.dp): Dp {
    return (floatOrNull(key) ?: defaultValue.value).dp
}

internal fun popupPropertiesFromValue(value: Any?): PopupProperties {
    val map = value as? Map<*, *> ?: return PopupProperties()
    return PopupProperties(
        focusable = (map["focusable"] as? Boolean) ?: false,
        dismissOnBackPress = (map["dismissOnBackPress"] as? Boolean) ?: true,
        dismissOnClickOutside = (map["dismissOnClickOutside"] as? Boolean) ?: true,
        clippingEnabled = (map["clippingEnabled"] as? Boolean) ?: true,
        usePlatformDefaultWidth = (map["usePlatformDefaultWidth"] as? Boolean) ?: false
    )
}

@Composable
internal fun Map<String, Any?>.textStyle(key: String): androidx.compose.ui.text.TextStyle {
    val typography = MaterialTheme.typography
    val token = normalizeToken(string(key))
    val getter = typographyGetterByToken[token]
    return (getter?.invoke(typography) as? androidx.compose.ui.text.TextStyle) ?: typography.bodyMedium
}

internal fun Map<String, Any?>.horizontalAlignment(key: String): Alignment.Horizontal {
    return horizontalAlignmentFromToken(stringOrNull(key))
}

internal fun horizontalAlignmentFromToken(raw: String?): Alignment.Horizontal {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center", "centerhorizontally" -> Alignment.CenterHorizontally
        "end", "right" -> Alignment.End
        else -> Alignment.Start
    }
}

internal fun Map<String, Any?>.verticalAlignment(key: String): Alignment.Vertical {
    return verticalAlignmentFromToken(stringOrNull(key))
}

internal fun verticalAlignmentFromToken(raw: String?): Alignment.Vertical {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center", "centervertically" -> Alignment.CenterVertically
        "end", "bottom" -> Alignment.Bottom
        else -> Alignment.Top
    }
}

internal fun Map<String, Any?>.textOverflow(key: String): TextOverflow {
    return when (normalizeToken(string(key))) {
        "ellipsis" -> TextOverflow.Ellipsis
        else -> TextOverflow.Clip
    }
}

internal fun Map<String, Any?>.boxAlignment(key: String): Alignment {
    return boxAlignmentFromToken(stringOrNull(key))
}

internal fun boxAlignmentFromToken(raw: String?): Alignment {
    val token = normalizeToken(raw.orEmpty())
    return when (token) {
        "center" -> Alignment.Center
        "topcenter", "centertop" -> Alignment.TopCenter
        "topend", "endtop", "topright", "righttop" -> Alignment.TopEnd
        "centerstart", "startcenter", "centerleft", "leftcenter" -> Alignment.CenterStart
        "centerend", "endcenter", "centerright", "rightcenter" -> Alignment.CenterEnd
        "bottomstart", "startbottom", "bottomleft", "leftbottom" -> Alignment.BottomStart
        "bottomcenter", "centerbottom" -> Alignment.BottomCenter
        "bottomend", "endbottom", "bottomright", "rightbottom", "end" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }
}

internal fun Map<String, Any?>.contentScale(key: String): ContentScale {
    return when (normalizeToken(string(key))) {
        "crop" -> ContentScale.Crop
        "fillbounds", "fill" -> ContentScale.FillBounds
        "fillwidth" -> ContentScale.FillWidth
        "fillheight" -> ContentScale.FillHeight
        "inside" -> ContentScale.Inside
        "none" -> ContentScale.None
        else -> ContentScale.Fit
    }
}

internal fun Map<String, Any?>.imageModelOrNull(): Any? {
    val rawValue =
        stringOrNull("url")
            ?: stringOrNull("uri")
            ?: stringOrNull("path")
            ?: stringOrNull("fileUri")
            ?: stringOrNull("src")
            ?: return null
    val normalized = rawValue.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return when {
        normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true) -> normalized
        normalized.startsWith("content://", ignoreCase = true) ||
            normalized.startsWith("file://", ignoreCase = true) ||
            normalized.startsWith("android.resource://", ignoreCase = true) -> Uri.parse(normalized)
        normalized.startsWith("/") -> File(normalized)
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(normalized) -> File(normalized)
        else -> normalized
    }
}

internal fun Map<String, Any?>.horizontalArrangement(key: String, spacing: Dp): Arrangement.Horizontal {
    val token = normalizeToken(string(key))
    return when (token) {
        "start" -> Arrangement.Start
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

internal fun Map<String, Any?>.verticalArrangement(key: String, spacing: Dp): Arrangement.Vertical {
    val token = normalizeToken(string(key))
    return when (token) {
        "top", "start" -> Arrangement.Top
        "center" -> Arrangement.Center
        "bottom", "end" -> Arrangement.Bottom
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

internal fun Map<String, Any?>.fontWeightOrNull(key: String): FontWeight? {
    val token = normalizeToken(string(key))
    val getter =
        fontWeightGetterByToken[token]
            ?: fontWeightGetterByToken[token.replace("ultra", "extra")]
            ?: fontWeightGetterByToken[token.replace("demi", "semi")]
            ?: fontWeightGetterByToken[if (token == "regular") "normal" else token]
            ?: fontWeightGetterByToken[if (token == "heavy") "black" else token]
    return getter?.invoke(FontWeight.Companion) as? FontWeight
}

@Composable
internal fun Map<String, Any?>.colorOrNull(key: String): Color? {
    val value = this[key] ?: return null
    return resolveColorValue(value)
}

@Composable
internal fun resolveColorToken(raw: String): Color? {
    val token =
        normalizeToken(raw)
    val scheme = MaterialTheme.colorScheme
    val schemeColor =
        colorSchemeFieldByToken[token]?.let { field ->
            when (field.type) {
                java.lang.Long.TYPE -> Color(field.getLong(scheme).toULong())
                java.lang.Long::class.java -> Color((field.get(scheme) as Long).toULong())
                else -> field.get(scheme) as? Color
            }
        }
    if (schemeColor != null) {
        return schemeColor
    }
    return try {
        Color(AndroidColor.parseColor(raw))
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun resolveColorValue(value: Any?): Color? {
    return when (value) {
        is Color -> value
        is Number -> Color(value.toLong().toULong())
        is Map<*, *> -> {
            val token = value["__colorToken"]?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                null
            } else {
                val base = resolveColorToken(token) ?: return null
                val alpha = canvasNumberFromValue(value["alpha"])
                if (alpha != null) base.copy(alpha = alpha) else base
            }
        }
        else -> value?.toString()?.let { raw -> resolveColorToken(raw) }
    }
}

internal fun iconFromName(name: String): ImageVector {
    val iconKey = name.trim()
    require(iconKey.isNotEmpty()) { "icon name is blank" }

    val pascalCaseName =
        iconKey
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString(separator = "") { segment ->
                segment.replaceFirstChar { it.uppercaseChar() }
            }

    require(pascalCaseName.isNotEmpty()) { "icon name is invalid: $name" }

    val iconKtClass = Class.forName("androidx.compose.material.icons.filled.${pascalCaseName}Kt")
    val getterMethod = iconKtClass.getMethod("get$pascalCaseName", Icons.Default::class.java)
    return getterMethod.invoke(null, Icons.Default) as ImageVector
}

@Composable
internal fun Map<String, Any?>.shapeOrNull(): androidx.compose.ui.graphics.Shape? {
    val shapeValue = this["shape"]
    if (shapeValue is Map<*, *>) {
        val cornerRadius = (shapeValue["cornerRadius"] as? Number)?.toFloat()
        if (cornerRadius != null) {
            return RoundedCornerShape(cornerRadius.dp)
        }
    }
    return null
}

@Composable
internal fun Map<String, Any?>.borderOrNull(): BorderStroke? {
    val borderValue = this["border"]
    if (borderValue is Map<*, *>) {
        val width = (borderValue["width"] as? Number)?.toFloat() ?: 1f
        val alpha = (borderValue["alpha"] as? Number)?.toFloat() ?: 1f

        val colorValue = borderValue["color"]
        if (colorValue != null) {
            val color = resolveColorValue(colorValue) ?: MaterialTheme.colorScheme.outline
            return BorderStroke(width.dp, color.copy(alpha = alpha))
        }
    }
    return null
}
