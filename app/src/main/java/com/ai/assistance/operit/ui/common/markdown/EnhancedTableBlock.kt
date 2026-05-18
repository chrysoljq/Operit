package com.ai.assistance.operit.ui.common.markdown

import android.graphics.Typeface
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.theme.LocalAiMarkdownTextLayoutSettings
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

private val TABLE_MIN_COLUMN_WIDTH = 80.dp
private val TABLE_MAX_COLUMN_WIDTH = 320.dp
private val TABLE_CELL_HORIZONTAL_PADDING = 8.dp
private val TABLE_CELL_VERTICAL_PADDING = 8.dp
private val TABLE_OUTER_VERTICAL_PADDING = 8.dp
private val TABLE_CORNER_RADIUS = 4.dp
private val TABLE_BORDER_WIDTH = 1.dp
private val TABLE_GRID_WIDTH = 0.5.dp
private const val TABLE_MAX_MEASURE_LINE_CHARS = 512
private const val TABLE_LINE_SPACING_MULTIPLIER = 1.3f
private const val TABLE_FLING_DECAY_RATE = 4.5f
private const val TABLE_MIN_FLING_VELOCITY = 120f
private const val TABLE_MAX_FLING_VELOCITY = 9000f

private data class TableData(
    val rows: List<List<String>>,
    val hasHeader: Boolean
)

private data class TableCellRenderData(
    val layout: StaticLayout,
    val isHeader: Boolean
)

private data class TableRenderLayout(
    val columnWidthsPx: List<Int>,
    val rowHeightsPx: List<Int>,
    val cells: List<List<TableCellRenderData>>,
    val hasHeader: Boolean,
    val totalWidthPx: Int,
    val totalHeightPx: Int,
    val rowCount: Int,
    val columnCount: Int,
    val cellCount: Int,
    val measuredLineCount: Int,
    val overlongCellCount: Int,
)

@Composable
internal fun EnhancedTableBlock(
    tableContent: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSelectionRequest: MarkdownTextSelectionRequest? = null,
    selectionState: MarkdownCanvasTextSelectionState? = null,
    nodeIndex: Int = -1,
) {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val textLayoutSettings = LocalAiMarkdownTextLayoutSettings.current
    val coroutineScope = rememberCoroutineScope()
    val fontFamily = typography.bodyMedium.fontFamily
    val resolver = LocalFontFamilyResolver.current
    val normalTypeface = remember(resolver, fontFamily) {
        (resolver.resolve(fontFamily = fontFamily, fontWeight = FontWeight.Normal).value as? Typeface)
            ?: Typeface.DEFAULT
    }
    val boldTypeface = remember(resolver, fontFamily) {
        (resolver.resolve(fontFamily = fontFamily, fontWeight = FontWeight.Bold).value as? Typeface)
            ?: Typeface.DEFAULT_BOLD
    }

    val tableData = remember(tableContent) { parseTable(tableContent) }

    if (tableData.rows.isEmpty()) return

    var scrollOffsetPx by remember(tableContent) { mutableStateOf(0f) }
    var dragVelocityPxPerSec by remember(tableContent) { mutableStateOf(0f) }
    var lastDragEventTimeMs by remember(tableContent) { mutableStateOf(0L) }
    var flingJob by remember(tableContent) { mutableStateOf<Job?>(null) }
    val tableBlockDesc = stringResource(R.string.table_block)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val autoScrollController = LocalMarkdownTextSelectionAutoScrollController.current
    val activeSelectionState = selectionState
    val selectionEnabled = activeSelectionState != null && nodeIndex >= 0
    var canvasBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var toolbarSize by remember(tableContent) { mutableStateOf(IntSize.Zero) }
    val selectionPaint = remember(primaryColor) {
        android.graphics.Paint().apply {
            color = primaryColor.copy(alpha = 0.24f).toArgb()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
    }
    val cursorPaint = remember(primaryColor, density) {
        android.graphics.Paint().apply {
            color = primaryColor.toArgb()
            strokeWidth = with(density) { 2.dp.toPx() }
            isAntiAlias = true
        }
    }
    val handlePaint = remember(primaryColor) {
        android.graphics.Paint().apply {
            color = primaryColor.toArgb()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
    }
    val magnifierSurfaceColor = MaterialTheme.colorScheme.surface
    val magnifierTextColor = MaterialTheme.colorScheme.onSurface
    val magnifierBubblePaint = remember(magnifierSurfaceColor) {
        android.graphics.Paint().apply {
            color = magnifierSurfaceColor.copy(alpha = 0.96f).toArgb()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
    }
    val magnifierBorderPaint = remember(primaryColor, density) {
        android.graphics.Paint().apply {
            color = primaryColor.copy(alpha = 0.45f).toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = with(density) { 1.dp.toPx() }
            isAntiAlias = true
        }
    }
    val magnifierTextPaint = remember(magnifierTextColor, density, normalTypeface) {
        android.graphics.Paint().apply {
            color = magnifierTextColor.toArgb()
            textSize = with(density) { 17.sp.toPx() }
            isAntiAlias = true
            typeface = normalTypeface
        }
    }
    val handleRadiusPx = with(density) { 7.dp.toPx() }
    val handleTouchRadiusPx = with(density) { 38.dp.toPx() }
    val magnifierWidthDp = 164.dp
    val magnifierHeightDp = 48.dp
    val magnifierMarginDp = 8.dp
    val magnifierWidthPx = with(density) { magnifierWidthDp.toPx() }
    val magnifierHeightPx = with(density) { magnifierHeightDp.toPx() }
    val magnifierMarginPx = with(density) { magnifierMarginDp.toPx() }
    val toolbarGapPx = with(density) { 6.dp.toPx() }
    val toolbarEdgePaddingPx = with(density) { 6.dp.toPx() }
    val toolbarEstimatedWidthPx = with(density) { 96.dp.toPx() }
    val toolbarEstimatedHeightPx = with(density) { 30.dp.toPx() }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = tableBlockDesc }
    ) {
        val availableWidthPx = with(density) { maxWidth.toPx() }.toInt()
        if (availableWidthPx <= 0) return@BoxWithConstraints

        val renderLayout = remember(
            tableContent,
            availableWidthPx,
            textColor,
            primaryColor,
            typography.bodyMedium,
            density,
            textLayoutSettings.lineHeightMultiplier,
            textLayoutSettings.letterSpacingSp,
            normalTypeface,
            boldTypeface,
        ) {
            measureTableLayout(
                tableData = tableData,
                textColor = textColor,
                density = density,
                bodyTextStyle = typography.bodyMedium,
                normalTypeface = normalTypeface,
                boldTypeface = boldTypeface,
                primaryColor = primaryColor,
                globalLineHeightMultiplier = textLayoutSettings.lineHeightMultiplier,
                globalLetterSpacingSp = textLayoutSettings.letterSpacingSp,
            )
        }

        val totalHeightDp = with(density) { renderLayout.totalHeightPx.toDp() }
        val outerBorderWidthPx = with(density) { TABLE_BORDER_WIDTH.toPx() }
        val gridLineWidthPx = with(density) { TABLE_GRID_WIDTH.toPx() }
        val cornerRadiusPx = with(density) { TABLE_CORNER_RADIUS.toPx() }
        val cellHorizontalPaddingPx = with(density) { TABLE_CELL_HORIZONTAL_PADDING.toPx() }
        val cellVerticalPaddingPx = with(density) { TABLE_CELL_VERTICAL_PADDING.toPx() }
        val maxScrollPx = (renderLayout.totalWidthPx - availableWidthPx).coerceAtLeast(0).toFloat()
        val tableSelectionInstructions =
            remember(renderLayout, scrollOffsetPx, cellHorizontalPaddingPx, cellVerticalPaddingPx) {
                buildTableSelectionInstructions(
                    renderLayout = renderLayout,
                    scrollOffsetPx = scrollOffsetPx,
                    cellHorizontalPaddingPx = cellHorizontalPaddingPx,
                    cellVerticalPaddingPx = cellVerticalPaddingPx,
                )
            }
        val nodeSelectionState =
            remember(activeSelectionState, nodeIndex, tableSelectionInstructions) {
                derivedStateOf {
                    val state = activeSelectionState ?: return@derivedStateOf null
                    state.selection?.let { selection ->
                        nodeSelectionSnapshot(
                            selection = selection,
                            nodeIndex = nodeIndex,
                            instructions = tableSelectionInstructions,
                        )
                    }
                }
            }
        val toolbarSelectionState =
            remember(activeSelectionState, nodeIndex, tableSelectionInstructions) {
                derivedStateOf {
                    val state = activeSelectionState ?: return@derivedStateOf null
                    val selection = state.selection
                    if (selection == null || compareSelectionPoints(selection.start, selection.end) == 0) {
                        null
                    } else {
                        val orderedStart = orderedSelectionPoints(selection).first
                        if (orderedStart.nodeIndex == nodeIndex) {
                            nodeSelectionSnapshot(
                                selection = selection,
                                nodeIndex = nodeIndex,
                                instructions = tableSelectionInstructions,
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        val nodeMagnifierState =
            remember(activeSelectionState, nodeIndex) {
                derivedStateOf {
                    activeSelectionState?.dragMagnifier?.takeIf { it.hostNodeIndex == nodeIndex }
                }
            }
        val nodeSelection = nodeSelectionState.value
        val toolbarNodeSelection = toolbarSelectionState.value
        val nodeMagnifier = nodeMagnifierState.value

        LaunchedEffect(canvasBoundsInWindow, tableSelectionInstructions, selectionEnabled) {
            val state = activeSelectionState ?: return@LaunchedEffect
            if (!selectionEnabled) return@LaunchedEffect
            val bounds = canvasBoundsInWindow ?: return@LaunchedEffect
            state.updateNodeLayout(
                nodeIndex = nodeIndex,
                boundsInWindow = bounds,
                instructions = tableSelectionInstructions,
            )
        }

        LaunchedEffect(textSelectionRequest?.id, canvasBoundsInWindow, tableSelectionInstructions, selectionEnabled) {
            val state = activeSelectionState ?: return@LaunchedEffect
            if (!selectionEnabled) return@LaunchedEffect
            val request = textSelectionRequest
            if (request == null) {
                state.clear()
                return@LaunchedEffect
            }
            if (state.handledRequestId == request.id) {
                return@LaunchedEffect
            }
            val bounds = canvasBoundsInWindow ?: return@LaunchedEffect
            if (!bounds.containsOffset(request.positionInWindow)) {
                state.dragMagnifier = null
                return@LaunchedEffect
            }

            val localPosition = request.positionInWindow - Offset(bounds.left, bounds.top)
            val hit = findTextSelectionHit(tableSelectionInstructions, localPosition, nodeIndex)
            val instruction = hit?.let { tableSelectionInstructions.getOrNull(it.instructionIndex) }
            state.handledRequestId = request.id
            if (hit != null && instruction is DrawInstruction.TextLayout) {
                state.selection =
                    createInitialSelection(
                        nodeIndex = nodeIndex,
                        instructionIndex = hit.instructionIndex,
                        instruction = instruction,
                        offset = hit.offset,
                    )
            } else {
                state.selection = null
                state.dragMagnifier = null
            }
        }

        fun cancelFling() {
            flingJob?.cancel()
            flingJob = null
        }

        fun startFling(initialVelocityPxPerSec: Float) {
            if (maxScrollPx <= 0f) return
            val clampedVelocity =
                initialVelocityPxPerSec
                    .coerceIn(-TABLE_MAX_FLING_VELOCITY, TABLE_MAX_FLING_VELOCITY)
            if (abs(clampedVelocity) < TABLE_MIN_FLING_VELOCITY) return

            cancelFling()
            flingJob =
                coroutineScope.launch {
                    var velocity = clampedVelocity
                    var lastFrameNanos = 0L
                    while (isActive && abs(velocity) >= TABLE_MIN_FLING_VELOCITY) {
                        val frameNanos = withFrameNanos { it }
                        if (lastFrameNanos == 0L) {
                            lastFrameNanos = frameNanos
                            continue
                        }

                        val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
                        lastFrameNanos = frameNanos
                        if (deltaSeconds <= 0f) continue

                        val nextOffset =
                            (scrollOffsetPx + velocity * deltaSeconds).coerceIn(0f, maxScrollPx)
                        val hitEdge = nextOffset <= 0f || nextOffset >= maxScrollPx
                        scrollOffsetPx = nextOffset
                        velocity *= exp(-TABLE_FLING_DECAY_RATE * deltaSeconds)

                        if (hitEdge) {
                            break
                        }
                    }
                    flingJob = null
                }
        }

        SideEffect {
            if (scrollOffsetPx > maxScrollPx) {
                scrollOffsetPx = maxScrollPx
                dragVelocityPxPerSec = 0f
                cancelFling()
            }
        }

        Box {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = TABLE_OUTER_VERTICAL_PADDING)
                        .height(totalHeightDp)
                        .onGloballyPositioned { coordinates ->
                            canvasBoundsInWindow = coordinates.boundsInWindow()
                        }
                        .pointerInput(selectionEnabled, tableSelectionInstructions, activeSelectionState, nodeIndex) {
                            if (!selectionEnabled) return@pointerInput
                            val state = activeSelectionState ?: return@pointerInput
                            awaitEachGesture {
                                val down = awaitPointerEvent(PointerEventPass.Main).changes.first()
                                val downPosition = down.position
                                val selection = state.selection
                                val activeHandle =
                                    if (selection != null) {
                                        selectionHandleAt(
                                            selection = selection,
                                            nodeIndex = nodeIndex,
                                            instructions = tableSelectionInstructions,
                                            position = downPosition,
                                            radiusPx = handleTouchRadiusPx,
                                            handleRadiusPx = handleRadiusPx,
                                        )
                                    } else {
                                        null
                                    }

                                if (activeHandle == null || selection == null) {
                                    return@awaitEachGesture
                                }

                                down.consume()
                                val downPositionInWindow =
                                    canvasBoundsInWindow?.let { bounds ->
                                        Offset(
                                            x = bounds.left + downPosition.x,
                                            y = bounds.top + downPosition.y,
                                        )
                                    } ?: downPosition
                                val initialPoint =
                                    when (activeHandle) {
                                        TextSelectionHandle.START -> selection.start
                                        TextSelectionHandle.END -> selection.end
                                    }
                                state.dragMagnifier =
                                    TextSelectionMagnifier(
                                        hostNodeIndex = nodeIndex,
                                        handle = activeHandle,
                                        position = downPosition,
                                        positionInWindow = downPositionInWindow,
                                        point = initialPoint,
                                    )
                                autoScrollController?.reset()
                                try {
                                    while (true) {
                                        val event =
                                            withTimeoutOrNull(16L) {
                                                awaitPointerEvent(PointerEventPass.Main)
                                            }
                                        if (event != null) {
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) break
                                            val changePositionInWindow =
                                                canvasBoundsInWindow?.let { bounds ->
                                                    Offset(
                                                        x = bounds.left + change.position.x,
                                                        y = bounds.top + change.position.y,
                                                    )
                                                } ?: change.position
                                            val currentMagnifier = state.dragMagnifier
                                            if (currentMagnifier?.hostNodeIndex == nodeIndex) {
                                                state.dragMagnifier =
                                                    currentMagnifier.copy(
                                                        position = change.position,
                                                        positionInWindow = changePositionInWindow,
                                                    )
                                            }
                                            val point = state.findPointInWindow(changePositionInWindow)
                                            if (point != null) {
                                                val currentSelection = state.selection ?: selection
                                                val updatedSelection =
                                                    when (activeHandle) {
                                                        TextSelectionHandle.START -> currentSelection.copy(start = point)
                                                        TextSelectionHandle.END -> currentSelection.copy(end = point)
                                                    }
                                                if (updatedSelection != currentSelection) {
                                                    state.selection = updatedSelection
                                                }
                                                state.dragMagnifier =
                                                    TextSelectionMagnifier(
                                                        hostNodeIndex = nodeIndex,
                                                        handle = activeHandle,
                                                        position = change.position,
                                                        positionInWindow = changePositionInWindow,
                                                        point = point,
                                                    )
                                            }
                                            change.consume()
                                        }

                                        val magnifier = state.dragMagnifier
                                        if (magnifier?.hostNodeIndex != nodeIndex) break
                                        val controller = autoScrollController
                                        if (controller != null && controller.scrollByEdge(magnifier.positionInWindow)) {
                                            val point = state.findPointInWindow(magnifier.positionInWindow)
                                            val currentSelection = state.selection ?: selection
                                            if (point != null) {
                                                val updatedSelection =
                                                    when (magnifier.handle) {
                                                        TextSelectionHandle.START -> currentSelection.copy(start = point)
                                                        TextSelectionHandle.END -> currentSelection.copy(end = point)
                                                    }
                                                if (updatedSelection != currentSelection) {
                                                    state.selection = updatedSelection
                                                }
                                                val bounds = canvasBoundsInWindow
                                                val localPosition =
                                                    if (bounds != null) {
                                                        magnifier.positionInWindow - Offset(bounds.left, bounds.top)
                                                    } else {
                                                        magnifier.position
                                                    }
                                                state.dragMagnifier =
                                                    magnifier.copy(
                                                        position = localPosition,
                                                        point = point,
                                                    )
                                            }
                                        }
                                    }
                                } finally {
                                    autoScrollController?.reset()
                                    state.dragMagnifier = null
                                }
                            }
                        }
                        .pointerInput(maxScrollPx) {
                        if (maxScrollPx <= 0f) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = {
                                cancelFling()
                                dragVelocityPxPerSec = 0f
                                lastDragEventTimeMs = SystemClock.uptimeMillis()
                            },
                            onDragCancel = {
                                dragVelocityPxPerSec = 0f
                                lastDragEventTimeMs = 0L
                            },
                            onDragEnd = {
                                startFling(dragVelocityPxPerSec)
                                dragVelocityPxPerSec = 0f
                                lastDragEventTimeMs = 0L
                            },
                        ) { _, dragAmount ->
                            val nowMs = SystemClock.uptimeMillis()
                            val deltaMs = (nowMs - lastDragEventTimeMs).coerceAtLeast(1L)
                            val instantVelocity = (-dragAmount / deltaMs.toFloat()) * 1000f
                            dragVelocityPxPerSec =
                                if (dragVelocityPxPerSec == 0f) {
                                    instantVelocity
                                } else {
                                    dragVelocityPxPerSec * 0.35f + instantVelocity * 0.65f
                                }
                            lastDragEventTimeMs = nowMs
                            scrollOffsetPx = (scrollOffsetPx - dragAmount).coerceIn(0f, maxScrollPx)
                        }
                    }
            ) {
            val selectionPath = android.graphics.Path()
            translate(left = -scrollOffsetPx, top = 0f) {
                drawRoundRect(
                    color = borderColor,
                    size = Size(renderLayout.totalWidthPx.toFloat(), renderLayout.totalHeightPx.toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = outerBorderWidthPx)
                )

                var currentY = 0f
                var instructionIndex = 0
                renderLayout.rowHeightsPx.forEachIndexed { rowIndex, rowHeightPx ->
                    var currentX = 0f
                    renderLayout.columnWidthsPx.forEachIndexed { colIndex, colWidthPx ->
                        val cell = renderLayout.cells[rowIndex][colIndex]

                        if (cell.isHeader) {
                            drawRect(
                                color = headerBackground,
                                topLeft = Offset(currentX, currentY),
                                size = Size(colWidthPx.toFloat(), rowHeightPx.toFloat())
                            )
                        }

                        if (rowIndex > 0) {
                            drawLine(
                                color = borderColor,
                                start = Offset(currentX, currentY),
                                end = Offset(currentX + colWidthPx, currentY),
                                strokeWidth = gridLineWidthPx,
                                cap = StrokeCap.Square
                            )
                        }

                        if (colIndex > 0) {
                            drawLine(
                                color = borderColor,
                                start = Offset(currentX, currentY),
                                end = Offset(currentX, currentY + rowHeightPx),
                                strokeWidth = gridLineWidthPx,
                                cap = StrokeCap.Square
                            )
                        }

                        drawIntoCanvas { canvas ->
                            val selectionForInstruction = nodeSelection
                            val selectionRange =
                                selectionForInstruction?.let {
                                    selectedRangeForInstruction(
                                        selection = it,
                                        instructionIndex = instructionIndex,
                                        textLength = cell.layout.text.length,
                                    )
                                }
                            val hasSelectionHandle =
                                selectionForInstruction?.let {
                                    it.startHandle?.instructionIndex == instructionIndex ||
                                        it.endHandle?.instructionIndex == instructionIndex
                                } == true
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.translate(
                                currentX + cellHorizontalPaddingPx,
                                currentY + cellVerticalPaddingPx
                            )
                            drawInlineCodeBackgrounds(cell.layout, canvas.nativeCanvas)
                            if (selectionRange != null) {
                                selectionPath.reset()
                                cell.layout.getSelectionPath(
                                    selectionRange.start,
                                    selectionRange.end,
                                    selectionPath,
                                )
                                canvas.nativeCanvas.drawPath(selectionPath, selectionPaint)
                            }
                            cell.layout.draw(canvas.nativeCanvas)
                            if (hasSelectionHandle) {
                                val selectionStartPoint = selectionForInstruction?.startHandle
                                if (
                                    selectionStartPoint != null &&
                                        selectionStartPoint.instructionIndex == instructionIndex
                                ) {
                                    drawCanvasTextSelectionHandle(
                                        canvas = canvas.nativeCanvas,
                                        instruction = DrawInstruction.TextLayout(
                                            layout = cell.layout,
                                            x = 0f,
                                            y = 0f,
                                            text = cell.layout.text,
                                        ),
                                        offset = selectionStartPoint.offset,
                                        cursorPaint = cursorPaint,
                                        handlePaint = handlePaint,
                                        handleRadiusPx = handleRadiusPx,
                                    )
                                }
                                val selectionEndPoint = selectionForInstruction?.endHandle
                                if (
                                    selectionEndPoint != null &&
                                        selectionEndPoint.instructionIndex == instructionIndex
                                ) {
                                    drawCanvasTextSelectionHandle(
                                        canvas = canvas.nativeCanvas,
                                        instruction = DrawInstruction.TextLayout(
                                            layout = cell.layout,
                                            x = 0f,
                                            y = 0f,
                                            text = cell.layout.text,
                                        ),
                                        offset = selectionEndPoint.offset,
                                        cursorPaint = cursorPaint,
                                        handlePaint = handlePaint,
                                        handleRadiusPx = handleRadiusPx,
                                    )
                                }
                            }
                            canvas.nativeCanvas.restore()
                        }

                        currentX += colWidthPx
                        instructionIndex++
                    }
                    currentY += rowHeightPx
                }
            }
        }
            val magnifier = nodeMagnifier
            val magnifierInstruction =
                magnifier?.let {
                    activeSelectionState?.textLayoutForPoint(it.point)
                }
            if (magnifier != null && magnifierInstruction != null) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset =
                        IntOffset(
                            x = (magnifier.position.x - magnifierWidthPx / 2f).roundToInt(),
                            y = (magnifier.position.y - magnifierHeightPx - magnifierMarginPx * 3f).roundToInt(),
                        ),
                    properties =
                        PopupProperties(
                            focusable = false,
                            clippingEnabled = false,
                        ),
                ) {
                    Canvas(
                        modifier = Modifier.size(magnifierWidthDp, magnifierHeightDp)
                    ) {
                        drawIntoCanvas { canvas ->
                            drawCanvasTextSelectionMagnifier(
                                canvas = canvas.nativeCanvas,
                                layout = magnifierInstruction.layout,
                                magnifier = magnifier,
                                bubblePaint = magnifierBubblePaint,
                                borderPaint = magnifierBorderPaint,
                                textPaint = magnifierTextPaint,
                                cursorPaint = cursorPaint,
                                bubbleWidthPx = size.width,
                                bubbleHeightPx = size.height,
                                marginPx = magnifierMarginPx,
                            )
                        }
                    }
                }
            }
            if (toolbarNodeSelection != null) {
                val selectionBounds =
                    selectionVisualBounds(
                        instructions = tableSelectionInstructions,
                        selection = toolbarNodeSelection,
                        handleRadiusPx = handleRadiusPx,
                    )
                Surface(
                    modifier =
                        Modifier
                            .offset {
                                val toolbarWidthPx =
                                    if (toolbarSize.width > 0) toolbarSize.width.toFloat() else toolbarEstimatedWidthPx
                                val toolbarHeightPx =
                                    if (toolbarSize.height > 0) toolbarSize.height.toFloat() else toolbarEstimatedHeightPx
                                placementForToolbar(
                                    selectionBounds = selectionBounds,
                                    toolbarWidthPx = toolbarWidthPx,
                                    toolbarHeightPx = toolbarHeightPx,
                                    canvasWidthPx = availableWidthPx.toFloat(),
                                    canvasHeightPx = renderLayout.totalHeightPx.toFloat(),
                                    gapPx = toolbarGapPx,
                                    edgePaddingPx = toolbarEdgePaddingPx,
                                )
                            }
                            .onGloballyPositioned { coordinates ->
                                toolbarSize = coordinates.size
                            },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .height(30.dp)
                                .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .height(24.dp)
                                    .clickable {
                                        val selectedText = activeSelectionState?.selectedText() ?: ""
                                        clipboardManager.setText(AnnotatedString(selectedText))
                                        activeSelectionState?.dismissSelection()
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.message_copied_to_clipboard),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = context.getString(R.string.copy),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .height(18.dp)
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        )
                        Box(
                            modifier =
                                Modifier
                                    .height(24.dp)
                                    .clickable {
                                        activeSelectionState?.dismissSelection()
                                    }
                                    .padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = context.getString(R.string.done),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildTableSelectionInstructions(
    renderLayout: TableRenderLayout,
    scrollOffsetPx: Float,
    cellHorizontalPaddingPx: Float,
    cellVerticalPaddingPx: Float,
): List<DrawInstruction> {
    val instructions = ArrayList<DrawInstruction>(renderLayout.cellCount)
    var currentY = 0f
    renderLayout.rowHeightsPx.forEachIndexed { rowIndex, rowHeightPx ->
        var currentX = 0f
        renderLayout.columnWidthsPx.forEachIndexed { colIndex, colWidthPx ->
            val cell = renderLayout.cells[rowIndex][colIndex]
            val separator =
                when {
                    rowIndex > 0 && colIndex == 0 -> TextCopySeparator.NEWLINE
                    colIndex > 0 -> TextCopySeparator.TAB
                    else -> TextCopySeparator.NONE
                }
            instructions +=
                DrawInstruction.TextLayout(
                    layout = cell.layout,
                    x = currentX + cellHorizontalPaddingPx - scrollOffsetPx,
                    y = currentY + cellVerticalPaddingPx,
                    text = cell.layout.text,
                    copySeparatorBefore = separator,
                    selectionHitBounds =
                        android.graphics.RectF(
                            currentX - scrollOffsetPx,
                            currentY,
                            currentX + colWidthPx - scrollOffsetPx,
                            currentY + rowHeightPx,
                        ),
                    tableCellInfo =
                        MarkdownTableCellInfo(
                            rowIndex = rowIndex,
                            columnIndex = colIndex,
                            columnCount = renderLayout.columnCount,
                            hasHeader = renderLayout.hasHeader,
                        ),
                )
            currentX += colWidthPx
        }
        currentY += rowHeightPx
    }
    return instructions
}

private fun measureTableLayout(
    tableData: TableData,
    textColor: Color,
    density: androidx.compose.ui.unit.Density,
    bodyTextStyle: androidx.compose.ui.text.TextStyle,
    normalTypeface: Typeface,
    boldTypeface: Typeface,
    primaryColor: Color,
    globalLineHeightMultiplier: Float,
    globalLetterSpacingSp: Float,
): TableRenderLayout {
    val rowCount = tableData.rows.size
    val columnCount = tableData.rows.maxOfOrNull { it.size } ?: 0
    val cellCount = tableData.rows.sumOf { it.size }
    if (rowCount == 0 || columnCount == 0) {
        return TableRenderLayout(emptyList(), emptyList(), emptyList(), false, 0, 0, 0, 0, 0, 0, 0)
    }

    val minColumnWidthPx = with(density) { TABLE_MIN_COLUMN_WIDTH.roundToPx() }
    val maxColumnWidthPx = with(density) { TABLE_MAX_COLUMN_WIDTH.roundToPx() }
    val cellHorizontalPaddingPx = with(density) { TABLE_CELL_HORIZONTAL_PADDING.roundToPx() }
    val cellVerticalPaddingPx = with(density) { TABLE_CELL_VERTICAL_PADDING.roundToPx() }
    val innerMinWidthPx = (minColumnWidthPx - cellHorizontalPaddingPx * 2).coerceAtLeast(1)
    val innerMaxWidthPx = (maxColumnWidthPx - cellHorizontalPaddingPx * 2).coerceAtLeast(innerMinWidthPx)
    val bodyFontSizePx = with(density) { bodyTextStyle.fontSize.toPx() }
    val headerFontSizePx = bodyFontSizePx
    val bodyPaint = TextPaint().apply {
        color = textColor.toArgb()
        textSize = bodyFontSizePx
        isAntiAlias = true
        typeface = normalTypeface
        letterSpacing = calculateLetterSpacingEm(bodyTextStyle.fontSize.value, globalLetterSpacingSp)
    }
    val headerPaint = TextPaint().apply {
        color = textColor.toArgb()
        textSize = headerFontSizePx
        isAntiAlias = true
        typeface = boldTypeface
        letterSpacing = calculateLetterSpacingEm(bodyTextStyle.fontSize.value, globalLetterSpacingSp)
    }
    val lineSpacingMultiplier = TABLE_LINE_SPACING_MULTIPLIER * globalLineHeightMultiplier

    val normalizedRows =
        tableData.rows.map { row ->
            if (row.size >= columnCount) {
                row
            } else {
                row + List(columnCount - row.size) { "" }
            }
        }

    val preparedRows =
        normalizedRows.map { row ->
            row.map { cell ->
                buildMarkdownInlineSpannableFromText(
                    text = cell,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    density = density,
                    fontSize = bodyTextStyle.fontSize
                ) as CharSequence
            }
        }

    var measuredLineCount = 0
    var overlongCellCount = 0
    val columnWidthsPx = MutableList(columnCount) { minColumnWidthPx }

    normalizedRows.forEachIndexed { rowIndex, row ->
        val isHeaderRow = rowIndex == 0 && tableData.hasHeader
        val paint = if (isHeaderRow) headerPaint else bodyPaint
        row.forEachIndexed { colIndex, cell ->
            val preparedCell = preparedRows[rowIndex][colIndex]
            val rawLineWidthPx =
                if (cell.isEmpty()) {
                    0f
                } else {
                    cell.lineSequence().forEach { line ->
                        if (line.length > TABLE_MAX_MEASURE_LINE_CHARS) {
                            overlongCellCount += 1
                        }
                    }
                    measureCellDesiredWidth(
                        text = preparedCell,
                        paint = paint,
                        widthPx = innerMaxWidthPx,
                        lineSpacingMultiplier = lineSpacingMultiplier
                    ).also {
                        measuredLineCount += preparedCell.lineCountHint()
                    }
                }
            val cellWidthPx =
                ceil(rawLineWidthPx).toInt()
                    .plus(cellHorizontalPaddingPx * 2)
                    .coerceIn(minColumnWidthPx, maxColumnWidthPx)
            if (cellWidthPx > columnWidthsPx[colIndex]) {
                columnWidthsPx[colIndex] = cellWidthPx
            }
        }
    }

    val cells = mutableListOf<List<TableCellRenderData>>()
    val rowHeightsPx = MutableList(rowCount) { 0 }

    preparedRows.forEachIndexed { rowIndex, row ->
        val isHeaderRow = rowIndex == 0 && tableData.hasHeader
        val paint = if (isHeaderRow) headerPaint else bodyPaint
        val cellLayouts = mutableListOf<TableCellRenderData>()
        var maxRowHeightPx = 0

        row.forEachIndexed { colIndex, cell ->
            val innerWidthPx =
                (columnWidthsPx[colIndex] - cellHorizontalPaddingPx * 2).coerceAtLeast(innerMinWidthPx)
            val layout = createCellStaticLayout(cell, paint, innerWidthPx, lineSpacingMultiplier)
            val cellHeightPx = layout.height + cellVerticalPaddingPx * 2
            if (cellHeightPx > maxRowHeightPx) {
                maxRowHeightPx = cellHeightPx
            }
            cellLayouts += TableCellRenderData(layout = layout, isHeader = isHeaderRow)
        }

        rowHeightsPx[rowIndex] = maxRowHeightPx.coerceAtLeast(cellVerticalPaddingPx * 2 + layoutLineHeightFallback(paint))
        cells += cellLayouts
    }

    val totalWidthPx = columnWidthsPx.sum().coerceAtLeast(1)
    val totalHeightPx = rowHeightsPx.sum().coerceAtLeast(1)
    return TableRenderLayout(
        columnWidthsPx = columnWidthsPx,
        rowHeightsPx = rowHeightsPx,
        cells = cells,
        hasHeader = tableData.hasHeader,
        totalWidthPx = totalWidthPx,
        totalHeightPx = totalHeightPx,
        rowCount = rowCount,
        columnCount = columnCount,
        cellCount = cellCount,
        measuredLineCount = measuredLineCount,
        overlongCellCount = overlongCellCount,
    )
}

private fun createCellStaticLayout(
    text: CharSequence,
    paint: TextPaint,
    widthPx: Int,
    lineSpacingMultiplier: Float,
): StaticLayout {
    val safeWidth = widthPx.coerceAtLeast(1)
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(
            text,
            paint,
            safeWidth,
            android.text.Layout.Alignment.ALIGN_CENTER,
            lineSpacingMultiplier,
            0f,
            false
        )
    }
}

private fun measureCellDesiredWidth(
    text: CharSequence,
    paint: TextPaint,
    widthPx: Int,
    lineSpacingMultiplier: Float,
): Float {
    if (text.isEmpty()) return 0f

    val layout = createCellStaticLayout(text, paint, widthPx, lineSpacingMultiplier)
    var maxWidth = 0f
    for (lineIndex in 0 until layout.lineCount) {
        maxWidth = maxOf(maxWidth, layout.getLineWidth(lineIndex))
        if (maxWidth >= widthPx) {
            return widthPx.toFloat()
        }
    }
    return maxWidth
}

private fun CharSequence.lineCountHint(): Int {
    if (isEmpty()) return 0
    return count { it == '\n' } + 1
}

private fun layoutLineHeightFallback(paint: TextPaint): Int {
    val metrics = paint.fontMetricsInt
    return (metrics.descent - metrics.ascent).coerceAtLeast(1)
}

private fun calculateLetterSpacingEm(fontSizeSp: Float, letterSpacingSp: Float): Float {
    if (!fontSizeSp.isFinite() || fontSizeSp <= 0f) return 0f
    return letterSpacingSp / fontSizeSp
}

private fun parseTable(content: String): TableData {
    fun isHeaderSeparatorLine(line: String): Boolean {
        return line.trim().matches(
            Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$")
        )
    }

    fun parseCells(line: String): MutableList<String> {
        val trimmed = line.trim()
        var parts = trimmed.split('|').toMutableList()
        if (trimmed.startsWith("|")) {
            parts = parts.drop(1).toMutableList()
        }
        if (trimmed.endsWith("|") && parts.isNotEmpty()) {
            parts.removeAt(parts.lastIndex)
        }
        return parts
            .map { it.trim().replace(Regex("(?i)<br\\s*/?>"), "\n") }
            .toMutableList()
    }

    val lines = content.lines().filter { it.trim().isNotEmpty() && it.contains('|') }
    if (lines.isEmpty()) {
        return TableData(emptyList(), false)
    }

    val hasHeader = lines.size > 1 && isHeaderSeparatorLine(lines[1])
    val rawRows = mutableListOf<MutableList<String>>()
    var maxColumns = 0

    lines.forEachIndexed { index, line ->
        if (index == 1 && hasHeader) return@forEachIndexed
        val cells = parseCells(line)
        maxColumns = maxOf(maxColumns, cells.size)
        rawRows += cells
    }

    val rows =
        rawRows.map { row ->
            while (row.size < maxColumns) {
                row += ""
            }
            row.toList()
        }

    return TableData(rows = rows, hasHeader = hasHeader)
}
