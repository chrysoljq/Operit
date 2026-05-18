package com.ai.assistance.operit.ui.common.markdown

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.widget.Toast
import com.ai.assistance.operit.util.AppLogger
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnit.Companion.Unspecified
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.displays.LatexCache
import com.ai.assistance.operit.ui.theme.LocalAiMarkdownTextLayoutSettings
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import com.ai.assistance.operit.util.stream.Stream
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Typeface
import android.widget.TextView
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CanvasMarkdownRenderer"
private const val MAX_CANVAS_HEIGHT_PX = 250_000f
private const val MAX_COMPOSE_CONSTRAINT_HEIGHT_PX = 262_000f
private const val TYPEWRITER_WINDOW_MS = 200
private const val DEFAULT_CANVAS_LINE_SPACING_MULTIPLIER = 1.3f
private const val DEFAULT_PARAGRAPH_BREAK_DP = 4f

private const val FALLBACK_MAX_TEXT_CHARS = 20_000

/**
 * 通用性能优化 Modifier：仅在组件进入屏幕可见区域时才进行绘制。
 * 
 * 实现原理：
 * 1.  使用 `onGloballyPositioned` 监听组件的布局位置和大小。
 * 2.  获取 `LocalView.current.getGlobalVisibleRect()` 来确定当前窗口的可见区域。
 * 3.  通过 `layoutCoordinates.boundsInWindow()` 获取组件在窗口中的边界。
 * 4.  比较组件边界和窗口可见边界，判断组件是否应该被渲染。
 * 5.  使用 `drawWithContent`，如果组件不可见，则跳过其内容的绘制阶段，但其空间占用不变。
 *
 * @return 返回一个配置好的 Modifier。
 */
@Composable
private fun Modifier.drawOnlyWhenVisible(): Modifier {
    var isVisible by remember { mutableStateOf(false) }
    val view = LocalView.current

    return this
            .onGloballyPositioned { layoutCoordinates ->
                val windowRect = android.graphics.Rect()
                // getGlobalVisibleRect 提供了视图在全局坐标系中的可见部分。
                // 对于根视图，这给了我们应用窗口的可见部分。
                view.getGlobalVisibleRect(windowRect)

                val componentBounds = layoutCoordinates.boundsInWindow()

                // 检查组件的垂直边界是否与窗口的可见垂直边界重叠。
                // 这是检查可滚动列表中可见性的一个简单而有效的方法。
                val newVisibility = componentBounds.top < windowRect.bottom && componentBounds.bottom > windowRect.top

                if (newVisibility != isVisible) {
                    isVisible = newVisibility
                }
            }
            .drawWithContent {
                // 仅当可组合项可见时才绘制内容。
                if (isVisible) {
                    drawContent()
                }
            }
}

/** 扩展函数：去除字符串首尾的所有空白字符 */
private fun String.trimAll(): String {
    return this.trim { it.isWhitespace() }
}

private fun CharSequence.trimAllPreservingSpans(): CharSequence {
    var start = 0
    var end = length

    while (start < end && this[start].isWhitespace()) {
        start++
    }

    while (end > start && this[end - 1].isWhitespace()) {
        end--
    }

    return if (start == 0 && end == length) this else subSequence(start, end)
}

private fun splitPlainTextParagraphs(content: CharSequence): List<CharSequence> {
    if (content.isEmpty()) return emptyList()

    val rawText = content.toString()
    val paragraphs = mutableListOf<CharSequence>()
    var paragraphStart = 0
    var index = 0

    while (index < rawText.length) {
        if (rawText[index] != '\n') {
            index++
            continue
        }

        val newlineRunStart = index
        while (index < rawText.length && rawText[index] == '\n') {
            index++
        }

        if (index - newlineRunStart >= 2) {
            if (newlineRunStart > paragraphStart) {
                paragraphs += content.subSequence(paragraphStart, newlineRunStart)
            }
            paragraphStart = index
        }
    }

    if (paragraphStart < content.length) {
        paragraphs += content.subSequence(paragraphStart, content.length)
    }

    return paragraphs.filter { it.isNotEmpty() }
}

/**
 * Paint 对象池 - 避免重复创建相同样式的 Paint
 */
private object PaintCache {
    private data class PaintKey(
        val colorArgb: Int,
        val textSize: Float,
        val typeface: Typeface,
        val letterSpacingEm: Float = 0f
    )

    private val paintCache = ConcurrentHashMap<PaintKey, android.graphics.Paint>()
    private val textPaintCache = ConcurrentHashMap<PaintKey, TextPaint>()

    fun getPaint(color: Color, textSize: Float, typeface: Typeface): android.graphics.Paint {
        val key = PaintKey(color.toArgb(), textSize, typeface)
        return paintCache.getOrPut(key) {
            android.graphics.Paint().apply {
                this.color = key.colorArgb
                this.textSize = textSize
                this.isAntiAlias = true
                this.typeface = key.typeface
            }
        }
    }

    fun getTextPaint(
        color: Color,
        textSize: Float,
        typeface: Typeface,
        letterSpacingEm: Float
    ): TextPaint {
        val key = PaintKey(color.toArgb(), textSize, typeface, letterSpacingEm)
        return textPaintCache.getOrPut(key) {
            TextPaint().apply {
                this.color = key.colorArgb
                this.textSize = textSize
                this.isAntiAlias = true
                this.typeface = key.typeface
                this.letterSpacing = key.letterSpacingEm
            }
        }
    }

    fun clear() {
        paintCache.clear()
        textPaintCache.clear()
    }
}

/**
 * StaticLayout 缓存 - 避免重复创建相同的 StaticLayout
 * 使用 LRU 策略，最多缓存 100 个
 */
private fun safeLayoutWidth(width: Int): Int = width.coerceAtLeast(1)

private fun calculateCanvasLetterSpacingEm(fontSize: TextUnit, letterSpacingSp: Float): Float {
    val fontSizeSp = fontSize.value
    if (!java.lang.Float.isFinite(fontSizeSp) || fontSizeSp <= 0f) {
        return 0f
    }
    return letterSpacingSp / fontSizeSp
}

private fun calculateCanvasLineSpacingMultiplier(lineHeightMultiplier: Float): Float {
    return DEFAULT_CANVAS_LINE_SPACING_MULTIPLIER * lineHeightMultiplier
}

private fun createSafeInlineStaticLayout(
    children: List<MarkdownNodeStable>,
    fallbackText: CharSequence,
    textColor: Color,
    primaryColor: Color,
    density: Density?,
    fontSize: TextUnit?,
    textPaint: TextPaint,
    width: Int,
    lineSpacingMultiplier: Float,
    contextLabel: String
): StaticLayout {
    return try {
        val spannable = buildSpannableFromChildren(
            children = children,
            textColor = textColor,
            primaryColor = primaryColor,
            density = density,
            fontSize = fontSize
        )
        createStaticLayout(spannable, textPaint, width, lineSpacingMultiplier)
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Inline markdown layout failed in $contextLabel, fallback to plain text", t)
        createStaticLayout(fallbackText, textPaint, width, lineSpacingMultiplier)
    }
}

private object LayoutCache {
    private data class LayoutKey(
        val text: String,
        val colorArgb: Int,
        val textSize: Float,
        val width: Int,
        val typeface: Typeface,
        val letterSpacing: Float,
        val lineSpacingMultiplier: Float
    )

    private val cache = LruCache<LayoutKey, StaticLayout>(100)

    fun getLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        color: Color,
        typeface: Typeface,
        lineSpacingMultiplier: Float
    ): StaticLayout {
        val safeWidth = safeLayoutWidth(width)
        val key = LayoutKey(
            text = text,
            colorArgb = color.toArgb(),
            textSize = paint.textSize,
            width = safeWidth,
            typeface = paint.typeface,
            letterSpacing = paint.letterSpacing,
            lineSpacingMultiplier = lineSpacingMultiplier
        )

        return cache.get(key) ?: createStaticLayout(text, paint, safeWidth, lineSpacingMultiplier).also {
            cache.put(key, it)
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun createStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        lineSpacingMultiplier: Float
    ): StaticLayout {
        val safeWidth = safeLayoutWidth(width)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                android.text.Layout.Alignment.ALIGN_NORMAL,
                lineSpacingMultiplier,
                0f,
                false
            )
        }
    }
}

/**
 * 绘制指令 - 用于描述如何在 Canvas 上绘制内容
 */
internal sealed class DrawInstruction {
    data class Text(
        val text: String,
        val x: Float,
        val y: Float,
        val paint: android.graphics.Paint,
        val copySeparatorBefore: TextCopySeparator = TextCopySeparator.AUTO,
        val selectionHitBounds: android.graphics.RectF? = null,
    ) : DrawInstruction()
    
    data class Line(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val paint: android.graphics.Paint
    ) : DrawInstruction()
    
    data class TextLayout(
        val layout: StaticLayout,
        val x: Float,
        val y: Float,
        val text: CharSequence? = null, // 添加字段以存储Spannable
        val copySeparatorBefore: TextCopySeparator = TextCopySeparator.AUTO,
        val selectionHitBounds: android.graphics.RectF? = null,
        val tableCellInfo: MarkdownTableCellInfo? = null,
    ) : DrawInstruction()
}

internal data class MarkdownTableCellInfo(
    val rowIndex: Int,
    val columnIndex: Int,
    val columnCount: Int,
    val hasHeader: Boolean,
)

internal enum class TextCopySeparator {
    AUTO,
    NONE,
    SPACE,
    TAB,
    NEWLINE,
}

/**
 * 布局计算结果 - 包含高度、实际使用的宽度和绘制指令
 */
private data class LayoutResult(
    val height: Float,
    val actualWidth: Float,  // 实际使用的最大宽度
    val instructions: List<DrawInstruction>
)

internal data class ActiveTextSelection(
    val start: TextSelectionPoint,
    val end: TextSelectionPoint,
)

internal data class TextSelectionPoint(
    val nodeIndex: Int,
    val instructionIndex: Int,
    val offset: Int,
)

internal data class TextSelectionRange(
    val start: Int,
    val end: Int,
)

internal data class TextSelectionNodeSnapshot(
    val start: TextSelectionPoint,
    val end: TextSelectionPoint,
    val startHandle: TextSelectionPoint?,
    val endHandle: TextSelectionPoint?,
)

private data class TextSelectionNodeLayout(
    val boundsInWindow: Rect,
    val instructions: List<DrawInstruction>,
)

internal data class MarkdownTextSelectionAutoScrollController(
    val scrollByEdge: (positionInWindow: Offset) -> Boolean,
    val reset: () -> Unit,
)

internal val LocalMarkdownTextSelectionAutoScrollController =
    staticCompositionLocalOf<MarkdownTextSelectionAutoScrollController?> { null }

internal class MarkdownCanvasTextSelectionState {
    var selection by mutableStateOf<ActiveTextSelection?>(null)
    var handledRequestId by mutableStateOf<Long?>(null)
    var dragMagnifier by mutableStateOf<TextSelectionMagnifier?>(null)
    private val nodeLayouts = mutableStateMapOf<Int, TextSelectionNodeLayout>()
    private var nodeLayoutVersion = 0
    private var orderedNodeLayoutVersion = -1
    private var orderedNodeLayoutCache: List<Pair<Int, TextSelectionNodeLayout>> = emptyList()

    fun updateNodeLayout(
        nodeIndex: Int,
        boundsInWindow: Rect,
        instructions: List<DrawInstruction>,
    ) {
        val nextLayout = TextSelectionNodeLayout(boundsInWindow, instructions)
        if (nodeLayouts[nodeIndex] != nextLayout) {
            nodeLayouts[nodeIndex] = nextLayout
            nodeLayoutVersion++
        }
    }

    fun retainNodeIndices(nodeIndices: Set<Int>) {
        var removedLayout = false
        nodeLayouts.keys.toList().forEach { nodeIndex ->
            if (nodeIndex !in nodeIndices) {
                nodeLayouts.remove(nodeIndex)
                removedLayout = true
            }
        }
        if (removedLayout) {
            nodeLayoutVersion++
        }
        val magnifierHostNodeIndex = dragMagnifier?.hostNodeIndex
        if (magnifierHostNodeIndex != null && magnifierHostNodeIndex !in nodeIndices) {
            dragMagnifier = null
        }
    }

    fun clear() {
        selection = null
        handledRequestId = null
        dragMagnifier = null
    }

    fun dismissSelection() {
        selection = null
        dragMagnifier = null
    }

    fun findPointInWindow(positionInWindow: Offset): TextSelectionPoint? {
        val layouts = orderedNodeLayouts()
        if (layouts.isEmpty()) return null

        layouts.forEach { (nodeIndex, nodeLayout) ->
            val bounds = nodeLayout.boundsInWindow
            if (bounds.containsOffset(positionInWindow)) {
                val localPosition = positionInWindow - Offset(bounds.left, bounds.top)
                return findTextSelectionPoint(
                    instructions = nodeLayout.instructions,
                    position = localPosition,
                    nodeIndex = nodeIndex,
                )
            }
        }

        val first = layouts.first()
        val last = layouts.last()
        if (positionInWindow.y < first.second.boundsInWindow.top) {
            return first.second.firstPoint(first.first)
        }
        if (positionInWindow.y > last.second.boundsInWindow.bottom) {
            return last.second.lastPoint(last.first)
        }

        for (i in 0 until layouts.lastIndex) {
            val current = layouts[i]
            val next = layouts[i + 1]
            val currentBottom = current.second.boundsInWindow.bottom
            val nextTop = next.second.boundsInWindow.top
            if (positionInWindow.y > currentBottom && positionInWindow.y < nextTop) {
                val midpoint = (currentBottom + nextTop) / 2f
                return if (positionInWindow.y < midpoint) {
                    current.second.lastPoint(current.first)
                } else {
                    next.second.firstPoint(next.first)
                }
            }
        }

        return null
    }

    fun selectedText(): String {
        val activeSelection = selection ?: return ""
        val (startPoint, endPoint) = orderedSelectionPoints(activeSelection)
        return buildString {
            orderedNodeLayouts()
                .forEach { (nodeIndex, nodeLayout) ->
                    if (nodeIndex >= startPoint.nodeIndex && nodeIndex <= endPoint.nodeIndex) {
                        val part = selectedText(activeSelection, nodeIndex, nodeLayout.instructions)
                        if (part.isNotEmpty()) {
                            if (isNotEmpty()) append('\n')
                            append(part)
                        }
                    }
                }
        }
    }

    fun textLayoutForPoint(point: TextSelectionPoint): DrawInstruction.TextLayout? {
        return nodeLayouts[point.nodeIndex]
            ?.instructions
            ?.getOrNull(point.instructionIndex) as? DrawInstruction.TextLayout
    }

    private fun orderedNodeLayouts(): List<Pair<Int, TextSelectionNodeLayout>> {
        if (orderedNodeLayoutVersion != nodeLayoutVersion) {
            orderedNodeLayoutCache = nodeLayouts.toList().sortedBy { it.first }
            orderedNodeLayoutVersion = nodeLayoutVersion
        }
        return orderedNodeLayoutCache
    }

    private fun TextSelectionNodeLayout.firstPoint(nodeIndex: Int): TextSelectionPoint? {
        for (instructionIndex in instructions.indices) {
            val instruction = instructions[instructionIndex]
            if (instruction.selectableTextLength() > 0) {
                return TextSelectionPoint(nodeIndex, instructionIndex, 0)
            }
        }
        return null
    }

    private fun TextSelectionNodeLayout.lastPoint(nodeIndex: Int): TextSelectionPoint? {
        for (instructionIndex in instructions.indices.reversed()) {
            val instruction = instructions[instructionIndex]
            val textLength = instruction.selectableTextLength()
            if (textLength > 0) {
                return TextSelectionPoint(nodeIndex, instructionIndex, textLength)
            }
        }
        return null
    }
}

internal enum class TextSelectionHandle {
    START,
    END,
}

internal data class TextSelectionMagnifier(
    val hostNodeIndex: Int,
    val handle: TextSelectionHandle,
    val position: Offset,
    val positionInWindow: Offset,
    val point: TextSelectionPoint,
)

/**
 * 从绘制指令中提取文本内容用于无障碍朗读
 */
private fun extractAccessibleText(instructions: List<DrawInstruction>): String {
    return buildString {
        instructions.forEach { instruction ->
            when (instruction) {
                is DrawInstruction.Text -> {
                    if (isNotEmpty()) append(" ")
                    append(instruction.text)
                }
                is DrawInstruction.TextLayout -> {
                    if (isNotEmpty()) append(" ")
                    // 优先使用原始文本，否则从layout中提取
                    if (instruction.text != null) {
                        append(instruction.text.toString())
                    } else {
                        append(instruction.layout.text.toString())
                    }
                }
                is DrawInstruction.Line -> {
                    // 线条不需要朗读
                }
            }
        }
    }.trim()
}

internal fun Rect.containsOffset(offset: Offset): Boolean {
    return offset.x >= left && offset.x <= right && offset.y >= top && offset.y <= bottom
}

internal fun compareSelectionPoints(
    first: TextSelectionPoint,
    second: TextSelectionPoint,
): Int {
    val nodeCompare = first.nodeIndex.compareTo(second.nodeIndex)
    if (nodeCompare != 0) return nodeCompare
    val instructionCompare = first.instructionIndex.compareTo(second.instructionIndex)
    return if (instructionCompare != 0) instructionCompare else first.offset.compareTo(second.offset)
}

internal fun orderedSelectionPoints(selection: ActiveTextSelection): Pair<TextSelectionPoint, TextSelectionPoint> {
    return if (compareSelectionPoints(selection.start, selection.end) <= 0) {
        selection.start to selection.end
    } else {
        selection.end to selection.start
    }
}

private fun selectedRangeForInstruction(
    selection: ActiveTextSelection,
    nodeIndex: Int,
    instructionIndex: Int,
    textLength: Int,
): TextSelectionRange? {
    val (startPoint, endPoint) = orderedSelectionPoints(selection)
    if (nodeIndex < startPoint.nodeIndex || nodeIndex > endPoint.nodeIndex) {
        return null
    }
    if (nodeIndex > startPoint.nodeIndex && nodeIndex < endPoint.nodeIndex) {
        return if (textLength == 0) null else TextSelectionRange(0, textLength)
    }

    val startsHere = nodeIndex == startPoint.nodeIndex
    val endsHere = nodeIndex == endPoint.nodeIndex
    if (startsHere && instructionIndex < startPoint.instructionIndex) return null
    if (endsHere && instructionIndex > endPoint.instructionIndex) return null

    val start =
        if (startsHere && instructionIndex == startPoint.instructionIndex) {
            startPoint.offset
        } else {
            0
        }.coerceIn(0, textLength)
    val end =
        if (endsHere && instructionIndex == endPoint.instructionIndex) {
            endPoint.offset
        } else {
            textLength
        }.coerceIn(0, textLength)

    return if (start == end) null else TextSelectionRange(minOf(start, end), maxOf(start, end))
}

internal fun selectedRangeForInstruction(
    selection: TextSelectionNodeSnapshot,
    instructionIndex: Int,
    textLength: Int,
): TextSelectionRange? {
    if (instructionIndex < selection.start.instructionIndex || instructionIndex > selection.end.instructionIndex) {
        return null
    }

    val start =
        if (instructionIndex == selection.start.instructionIndex) {
            selection.start.offset
        } else {
            0
        }.coerceIn(0, textLength)
    val end =
        if (instructionIndex == selection.end.instructionIndex) {
            selection.end.offset
        } else {
            textLength
        }.coerceIn(0, textLength)

    return if (start == end) null else TextSelectionRange(minOf(start, end), maxOf(start, end))
}

private fun DrawInstruction.selectableTextLength(): Int {
    return when (this) {
        is DrawInstruction.Text -> text.length
        is DrawInstruction.TextLayout -> layout.text.length
        is DrawInstruction.Line -> 0
    }
}

private fun DrawInstruction.selectedText(range: TextSelectionRange): CharSequence {
    return when (this) {
        is DrawInstruction.Text -> text.subSequence(range.start, range.end)
        is DrawInstruction.TextLayout -> layout.text.subSequence(range.start, range.end)
        is DrawInstruction.Line -> ""
    }
}

private fun DrawInstruction.selectionBounds(): android.graphics.RectF? {
    return when (this) {
        is DrawInstruction.Text -> {
            if (text.isEmpty()) {
                null
            } else {
                android.graphics.RectF(
                    x,
                    y + paint.ascent(),
                    x + paint.measureText(text),
                    y + paint.descent(),
                )
            }
        }
        is DrawInstruction.TextLayout -> {
            android.graphics.RectF(
                x,
                y,
                x + layout.width,
                y + layout.height,
            )
        }
        is DrawInstruction.Line -> null
    }
}

private fun DrawInstruction.selectionHitBounds(): android.graphics.RectF? {
    return when (this) {
        is DrawInstruction.Text -> selectionHitBounds ?: selectionBounds()
        is DrawInstruction.TextLayout -> selectionHitBounds ?: selectionBounds()
        is DrawInstruction.Line -> null
    }
}

private fun DrawInstruction.cursorLineRect(offset: Int): android.graphics.RectF? {
    return when (this) {
        is DrawInstruction.Text -> {
            val safeOffset = offset.coerceIn(0, text.length)
            val cursorX = x + paint.measureText(text, 0, safeOffset)
            android.graphics.RectF(
                cursorX,
                y + paint.ascent(),
                cursorX,
                y + paint.descent(),
            )
        }
        is DrawInstruction.TextLayout -> {
            val safeOffset = offset.coerceIn(0, layout.text.length)
            val line = layout.getLineForOffset(safeOffset)
            val cursorX = x + layout.getPrimaryHorizontal(safeOffset)
            val top = y + layout.getLineTop(line)
            val bottom = y + layout.getLineBottom(line)
            android.graphics.RectF(cursorX, top.toFloat(), cursorX, bottom.toFloat())
        }
        is DrawInstruction.Line -> null
    }
}

private fun DrawInstruction.selectedBounds(range: TextSelectionRange): android.graphics.RectF? {
    return when (this) {
        is DrawInstruction.Text -> {
            if (range.start == range.end) {
                null
            } else {
                val left = x + paint.measureText(text, 0, range.start.coerceIn(0, text.length))
                val right = x + paint.measureText(text, 0, range.end.coerceIn(0, text.length))
                android.graphics.RectF(
                    minOf(left, right),
                    y + paint.ascent(),
                    maxOf(left, right),
                    y + paint.descent(),
                )
            }
        }
        is DrawInstruction.TextLayout -> {
            val localBounds = android.graphics.RectF()
            val path = android.graphics.Path()
            layout.getSelectionPath(range.start, range.end, path)
            path.computeBounds(localBounds, true)
            if (localBounds.isEmpty) {
                null
            } else {
                localBounds.offset(x, y)
                localBounds
            }
        }
        is DrawInstruction.Line -> null
    }
}

private fun DrawInstruction.pointForPosition(
    nodeIndex: Int,
    instructionIndex: Int,
    position: Offset,
): TextSelectionPoint {
    return when (this) {
        is DrawInstruction.Text -> {
            val targetX = (position.x - x).coerceAtLeast(0f)
            var offset = 0
            while (offset < text.length) {
                val before = paint.measureText(text, 0, offset)
                val after = paint.measureText(text, 0, offset + 1)
                if (targetX < (before + after) / 2f) {
                    break
                }
                offset++
            }
            TextSelectionPoint(
                nodeIndex = nodeIndex,
                instructionIndex = instructionIndex,
                offset = offset.coerceIn(0, text.length),
            )
        }
        is DrawInstruction.TextLayout -> findPointInInstruction(
            nodeIndex = nodeIndex,
            instructionIndex = instructionIndex,
            instruction = this,
            position = position,
        )
        is DrawInstruction.Line -> TextSelectionPoint(nodeIndex, instructionIndex, 0)
    }
}

private fun DrawInstruction.copySeparatorBefore(): TextCopySeparator {
    return when (this) {
        is DrawInstruction.Text -> copySeparatorBefore
        is DrawInstruction.TextLayout -> copySeparatorBefore
        is DrawInstruction.Line -> TextCopySeparator.AUTO
    }
}

internal fun nodeSelectionSnapshot(
    selection: ActiveTextSelection,
    nodeIndex: Int,
    instructions: List<DrawInstruction>,
): TextSelectionNodeSnapshot? {
    val (orderedStart, orderedEnd) = orderedSelectionPoints(selection)
    if (nodeIndex < orderedStart.nodeIndex || nodeIndex > orderedEnd.nodeIndex) {
        return null
    }

    var firstTextPoint: TextSelectionPoint? = null
    var lastTextPoint: TextSelectionPoint? = null
    instructions.forEachIndexed { instructionIndex, instruction ->
        val textLength = instruction.selectableTextLength()
        if (textLength > 0) {
            if (firstTextPoint == null) {
                firstTextPoint = TextSelectionPoint(nodeIndex, instructionIndex, 0)
            }
            lastTextPoint =
                TextSelectionPoint(
                    nodeIndex = nodeIndex,
                    instructionIndex = instructionIndex,
                    offset = textLength,
                )
        }
    }

    val start =
        if (nodeIndex == orderedStart.nodeIndex) {
            orderedStart
        } else {
            firstTextPoint
        } ?: return null
    val end =
        if (nodeIndex == orderedEnd.nodeIndex) {
            orderedEnd
        } else {
            lastTextPoint
        } ?: return null

    if (compareSelectionPoints(start, end) == 0) {
        return null
    }

    return TextSelectionNodeSnapshot(
        start = start,
        end = end,
        startHandle = selection.start.takeIf { it.nodeIndex == nodeIndex },
        endHandle = selection.end.takeIf { it.nodeIndex == nodeIndex },
    )
}

internal fun findTextSelectionHit(
    instructions: List<DrawInstruction>,
    position: Offset,
    nodeIndex: Int,
): TextSelectionPoint? {
    instructions.forEachIndexed { index, instruction ->
        val bounds = instruction.selectionHitBounds()
        if (
            bounds != null &&
                position.x >= bounds.left &&
                position.x <= bounds.right &&
                position.y >= bounds.top &&
                position.y <= bounds.bottom
        ) {
            return instruction.pointForPosition(nodeIndex, index, position)
        }
    }
    return null
}

private fun findPointInInstruction(
    nodeIndex: Int,
    instructionIndex: Int,
    instruction: DrawInstruction.TextLayout,
    position: Offset,
): TextSelectionPoint {
    val layout = instruction.layout
    val relativeX = (position.x - instruction.x).coerceIn(0f, layout.width.toFloat())
    val relativeY = (position.y - instruction.y).coerceIn(0f, layout.height.toFloat())
    val line = layout.getLineForVertical(relativeY.toInt())
    return TextSelectionPoint(
        nodeIndex = nodeIndex,
        instructionIndex = instructionIndex,
        offset = layout.getOffsetForHorizontal(line, relativeX).coerceIn(0, layout.text.length),
    )
}

internal fun findTextSelectionPoint(
    instructions: List<DrawInstruction>,
    position: Offset,
    nodeIndex: Int,
): TextSelectionPoint? {
    val textInstructions =
        instructions.mapIndexedNotNull { index, instruction ->
            instruction.selectionHitBounds()?.let { bounds ->
                if (instruction.selectableTextLength() > 0) {
                    Triple(index, instruction, bounds)
                } else {
                    null
                }
            }
        }
    if (textInstructions.isEmpty()) return null

    val hit = findTextSelectionHit(instructions, position, nodeIndex)
    if (hit != null) return hit

    val sameLineInstructions = textInstructions.filter { (_, _, bounds) ->
        position.y >= bounds.top && position.y <= bounds.bottom
    }
    if (sameLineInstructions.isNotEmpty()) {
        val nearest =
            sameLineInstructions.minBy { (_, _, bounds) ->
                when {
                    position.x < bounds.left -> bounds.left - position.x
                    position.x > bounds.right -> position.x - bounds.right
                    else -> 0f
                }
            }
        return nearest.second.pointForPosition(nodeIndex, nearest.first, position)
    }

    val first = textInstructions.first()
    val last = textInstructions.last()
    if (position.y < first.third.top) {
        return TextSelectionPoint(nodeIndex, first.first, 0)
    }
    if (position.y > last.third.bottom) {
        return TextSelectionPoint(nodeIndex, last.first, last.second.selectableTextLength())
    }

    for (i in 0 until textInstructions.lastIndex) {
        val current = textInstructions[i]
        val next = textInstructions[i + 1]
        val currentBottom = current.third.bottom
        val nextTop = next.third.top
        if (position.y > currentBottom && position.y < nextTop) {
            val midpoint = (currentBottom + nextTop) / 2f
            return if (position.y < midpoint) {
                TextSelectionPoint(
                    nodeIndex = nodeIndex,
                    instructionIndex = current.first,
                    offset = current.second.selectableTextLength(),
                )
            } else {
                TextSelectionPoint(nodeIndex, next.first, 0)
            }
        }
    }

    return first.second.pointForPosition(nodeIndex, first.first, position)
}

internal fun createInitialSelection(
    nodeIndex: Int,
    instructionIndex: Int,
    instruction: DrawInstruction,
    offset: Int,
): ActiveTextSelection {
    val text =
        when (instruction) {
            is DrawInstruction.Text -> instruction.text
            is DrawInstruction.TextLayout -> instruction.layout.text
            is DrawInstruction.Line -> ""
        }
    if (text.isEmpty()) {
        val point = TextSelectionPoint(nodeIndex, instructionIndex, 0)
        return ActiveTextSelection(point, point)
    }

    if (instruction is DrawInstruction.Text) {
        return ActiveTextSelection(
            start = TextSelectionPoint(nodeIndex, instructionIndex, 0),
            end = TextSelectionPoint(nodeIndex, instructionIndex, text.length),
        )
    }

    var anchor = offset.coerceIn(0, text.length)
    if (anchor == text.length) {
        anchor = (text.length - 1).coerceAtLeast(0)
    }
    if (text[anchor].isWhitespace() && anchor > 0) {
        anchor--
    }

    var start = anchor
    while (start > 0 && !text[start - 1].isWhitespace()) {
        start--
    }

    var end = anchor + 1
    while (end < text.length && !text[end].isWhitespace()) {
        end++
    }

    return ActiveTextSelection(
        start = TextSelectionPoint(nodeIndex, instructionIndex, start.coerceIn(0, text.length)),
        end = TextSelectionPoint(nodeIndex, instructionIndex, end.coerceIn(0, text.length)),
    )
}

private fun cursorLineRect(
    instruction: DrawInstruction.TextLayout,
    offset: Int,
): android.graphics.RectF {
    val layout = instruction.layout
    val safeOffset = offset.coerceIn(0, layout.text.length)
    val line = layout.getLineForOffset(safeOffset)
    val x = instruction.x + layout.getPrimaryHorizontal(safeOffset)
    val top = instruction.y + layout.getLineTop(line)
    val bottom = instruction.y + layout.getLineBottom(line)
    return android.graphics.RectF(x, top.toFloat(), x, bottom.toFloat())
}

private fun selectionHandleGapPx(handleRadiusPx: Float): Float = handleRadiusPx * 0.25f

internal fun selectionHandleCenter(
    instruction: DrawInstruction,
    offset: Int,
    handleRadiusPx: Float,
): Offset {
    val rect = instruction.cursorLineRect(offset) ?: android.graphics.RectF()
    val centerY =
        (rect.bottom + handleRadiusPx + selectionHandleGapPx(handleRadiusPx))
            .coerceAtLeast(rect.top + handleRadiusPx)
    return Offset(rect.left, centerY)
}

internal fun selectionHandleAt(
    selection: ActiveTextSelection,
    nodeIndex: Int,
    instructions: List<DrawInstruction>,
    position: Offset,
    radiusPx: Float,
    handleRadiusPx: Float,
): TextSelectionHandle? {
    val startInstruction =
        if (selection.start.nodeIndex == nodeIndex) {
            instructions.getOrNull(selection.start.instructionIndex)
        } else {
            null
        }
    val endInstruction =
        if (selection.end.nodeIndex == nodeIndex) {
            instructions.getOrNull(selection.end.instructionIndex)
        } else {
            null
        }
    val startDistance =
        startInstruction?.let {
            (position - selectionHandleCenter(it, selection.start.offset, handleRadiusPx)).getDistance()
        }
    val endDistance =
        endInstruction?.let {
            (position - selectionHandleCenter(it, selection.end.offset, handleRadiusPx)).getDistance()
        }
    return when {
        startDistance != null &&
            startDistance <= radiusPx &&
            (endDistance == null || startDistance <= endDistance) -> TextSelectionHandle.START
        endDistance != null && endDistance <= radiusPx -> TextSelectionHandle.END
        else -> null
    }
}

internal fun selectionVisualBounds(
    instructions: List<DrawInstruction>,
    selection: TextSelectionNodeSnapshot,
    handleRadiusPx: Float,
): android.graphics.RectF {
    val bounds = android.graphics.RectF()

    instructions.forEachIndexed { instructionIndex, instruction ->
        val textLength = instruction.selectableTextLength()
        if (textLength > 0) {
            val range =
                selectedRangeForInstruction(selection, instructionIndex, textLength)
            if (range != null) {
                val localBounds = instruction.selectedBounds(range)
                if (localBounds != null && !localBounds.isEmpty) {
                    if (bounds.isEmpty) {
                        bounds.set(localBounds)
                    } else {
                        bounds.union(localBounds)
                    }
                }
            }
        }
    }

    if (bounds.isEmpty) {
        val pointInstruction =
            instructions.getOrNull(selection.start.instructionIndex)
        if (pointInstruction != null) {
            pointInstruction.cursorLineRect(selection.start.offset)?.let(bounds::set)
        }
    }

    val startHandle = selection.startHandle
    val startInstruction =
        startHandle?.let { instructions.getOrNull(it.instructionIndex) }
    if (startHandle != null && startInstruction != null) {
        val startCenter = selectionHandleCenter(startInstruction, startHandle.offset, handleRadiusPx)
        bounds.union(
            startCenter.x - handleRadiusPx,
            startCenter.y - handleRadiusPx,
            startCenter.x + handleRadiusPx,
            startCenter.y + handleRadiusPx,
        )
    }
    val endHandle = selection.endHandle
    val endInstruction =
        endHandle?.let { instructions.getOrNull(it.instructionIndex) }
    if (endHandle != null && endInstruction != null) {
        val endCenter = selectionHandleCenter(endInstruction, endHandle.offset, handleRadiusPx)
        bounds.union(
            endCenter.x - handleRadiusPx,
            endCenter.y - handleRadiusPx,
            endCenter.x + handleRadiusPx,
            endCenter.y + handleRadiusPx,
        )
    }
    return bounds
}

private fun appendSelectedText(
    builder: StringBuilder,
    selectedText: CharSequence,
    separator: TextCopySeparator,
    previousBounds: android.graphics.RectF?,
    currentBounds: android.graphics.RectF?,
) {
    if (selectedText.isEmpty()) return

    if (builder.isNotEmpty()) {
        when (separator) {
            TextCopySeparator.NONE -> Unit
            TextCopySeparator.SPACE -> {
                if (!builder.last().isWhitespace() && !selectedText.first().isWhitespace()) {
                    builder.append(' ')
                }
            }
            TextCopySeparator.TAB -> builder.append('\t')
            TextCopySeparator.NEWLINE -> builder.append('\n')
            TextCopySeparator.AUTO -> {
                val isSameLine =
                    previousBounds != null &&
                        currentBounds != null &&
                        maxOf(previousBounds.top, currentBounds.top) <= minOf(previousBounds.bottom, currentBounds.bottom)
                if (isSameLine) {
                    if (!builder.last().isWhitespace() && !selectedText.first().isWhitespace()) {
                        builder.append(' ')
                    }
                } else {
                    builder.append('\n')
                }
            }
        }
    }

    builder.append(selectedText)
}

private data class SelectedInstructionText(
    val instruction: DrawInstruction,
    val range: TextSelectionRange,
    val text: CharSequence,
)

private fun escapeMarkdownTableCell(text: CharSequence): String {
    return text
        .toString()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("|", "\\|")
        .replace("\n", "<br>")
}

private fun markdownTableText(selectedInstructions: List<SelectedInstructionText>): String? {
    val cells =
        selectedInstructions.mapNotNull { selected ->
            val instruction = selected.instruction as? DrawInstruction.TextLayout
            val info = instruction?.tableCellInfo
            if (info == null) {
                null
            } else {
                info to selected.text
            }
        }
    if (cells.size != selectedInstructions.size || cells.isEmpty()) {
        return null
    }

    val rowIndices = cells.map { it.first.rowIndex }
    val columnIndices = cells.map { it.first.columnIndex }
    val minRow = rowIndices.minOrNull() ?: return null
    val maxRow = rowIndices.maxOrNull() ?: return null
    val minColumn = columnIndices.minOrNull() ?: return null
    val maxColumn = columnIndices.maxOrNull() ?: return null
    val selectedByPosition =
        cells.associate { (info, text) ->
            (info.rowIndex to info.columnIndex) to escapeMarkdownTableCell(text)
        }

    fun appendTableRow(builder: StringBuilder, rowIndex: Int) {
        builder.append('|')
        for (columnIndex in minColumn..maxColumn) {
            builder.append(' ')
            builder.append(selectedByPosition[rowIndex to columnIndex] ?: "")
            builder.append(" |")
        }
        builder.append('\n')
    }

    return buildString {
        appendTableRow(this, minRow)
        append('|')
        for (columnIndex in minColumn..maxColumn) {
            append(" --- |")
        }
        append('\n')
        for (rowIndex in (minRow + 1)..maxRow) {
            appendTableRow(this, rowIndex)
        }
    }.trimEnd()
}

private fun selectedText(
    selection: ActiveTextSelection,
    nodeIndex: Int,
    instructions: List<DrawInstruction>,
): String {
    val selectedInstructions = mutableListOf<SelectedInstructionText>()
    instructions.forEachIndexed { instructionIndex, instruction ->
        val textLength = instruction.selectableTextLength()
        if (textLength > 0) {
            val range =
                selectedRangeForInstruction(selection, nodeIndex, instructionIndex, textLength)
            if (range != null) {
                selectedInstructions +=
                    SelectedInstructionText(
                        instruction = instruction,
                        range = range,
                        text = instruction.selectedText(range),
                    )
            }
        }
    }

    val tableText = markdownTableText(selectedInstructions)
    if (tableText != null) {
        return tableText
    }

    val builder = StringBuilder()
    var previousBounds: android.graphics.RectF? = null
    selectedInstructions.forEach { selected ->
        val currentBounds =
            selected.instruction.selectionHitBounds()
                ?: selected.instruction.selectedBounds(selected.range)
                ?: selected.instruction.selectionBounds()
        appendSelectedText(
            builder = builder,
            selectedText = selected.text,
            separator = selected.instruction.copySeparatorBefore(),
            previousBounds = previousBounds,
            currentBounds = currentBounds,
        )
        previousBounds = currentBounds
    }
    return builder.toString()
}

private fun drawCanvasTextSelectionHandleAtRect(
    canvas: android.graphics.Canvas,
    rect: android.graphics.RectF,
    cursorPaint: android.graphics.Paint,
    handlePaint: android.graphics.Paint,
    handleRadiusPx: Float,
) {
    val handleCenterY =
        (rect.bottom + handleRadiusPx + selectionHandleGapPx(handleRadiusPx))
            .coerceAtLeast(rect.top + handleRadiusPx)
    canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, cursorPaint)
    canvas.drawCircle(rect.left, handleCenterY, handleRadiusPx, handlePaint)
}

internal fun drawCanvasTextSelectionHandle(
    canvas: android.graphics.Canvas,
    instruction: DrawInstruction,
    offset: Int,
    cursorPaint: android.graphics.Paint,
    handlePaint: android.graphics.Paint,
    handleRadiusPx: Float,
) {
    val rect = instruction.cursorLineRect(offset) ?: return
    drawCanvasTextSelectionHandleAtRect(
        canvas = canvas,
        rect = rect,
        cursorPaint = cursorPaint,
        handlePaint = handlePaint,
        handleRadiusPx = handleRadiusPx,
    )
}

private fun drawCanvasTextSelectionHandle(
    canvas: android.graphics.Canvas,
    layout: StaticLayout,
    offset: Int,
    cursorPaint: android.graphics.Paint,
    handlePaint: android.graphics.Paint,
    handleRadiusPx: Float,
) {
    val safeOffset = offset.coerceIn(0, layout.text.length)
    val line = layout.getLineForOffset(safeOffset)
    val x = layout.getPrimaryHorizontal(safeOffset)
    val top = layout.getLineTop(line).toFloat()
    val bottom = layout.getLineBottom(line).toFloat()
    drawCanvasTextSelectionHandleAtRect(
        canvas = canvas,
        rect = android.graphics.RectF(x, top, x, bottom),
        cursorPaint = cursorPaint,
        handlePaint = handlePaint,
        handleRadiusPx = handleRadiusPx,
    )
}

internal fun drawCanvasTextSelectionMagnifier(
    canvas: android.graphics.Canvas,
    layout: StaticLayout,
    magnifier: TextSelectionMagnifier,
    bubblePaint: android.graphics.Paint,
    borderPaint: android.graphics.Paint,
    textPaint: android.graphics.Paint,
    cursorPaint: android.graphics.Paint,
    bubbleWidthPx: Float,
    bubbleHeightPx: Float,
    marginPx: Float,
) {
    val text = layout.text
    if (text.isEmpty()) return

    val safeOffset = magnifier.point.offset.coerceIn(0, text.length)
    val start = (safeOffset - 10).coerceAtLeast(0)
    val end = (safeOffset + 10).coerceAtMost(text.length)
    val preview = text.subSequence(start, end).toString().replace('\n', ' ')
    val cursorInPreview = (safeOffset - start).coerceIn(0, preview.length)

    val rect = android.graphics.RectF(0f, 0f, bubbleWidthPx, bubbleHeightPx)

    canvas.drawRoundRect(rect, bubbleHeightPx / 2f, bubbleHeightPx / 2f, bubblePaint)
    canvas.drawRoundRect(rect, bubbleHeightPx / 2f, bubbleHeightPx / 2f, borderPaint)

    val textX = marginPx
    val baseline = bubbleHeightPx / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.save()
    canvas.clipRect(marginPx, 0f, bubbleWidthPx - marginPx, bubbleHeightPx)
    canvas.drawText(preview, textX, baseline, textPaint)
    val cursorX = textX + textPaint.measureText(preview, 0, cursorInPreview)
    canvas.drawLine(cursorX, marginPx, cursorX, bubbleHeightPx - marginPx, cursorPaint)
    canvas.restore()
}

internal fun placementForToolbar(
    selectionBounds: android.graphics.RectF,
    toolbarWidthPx: Float,
    toolbarHeightPx: Float,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    gapPx: Float,
    edgePaddingPx: Float,
): IntOffset {
    val maxX = (canvasWidthPx - toolbarWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    val maxY = (canvasHeightPx - toolbarHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    val centeredX = (selectionBounds.centerX() - toolbarWidthPx / 2f).coerceIn(edgePaddingPx, maxX)
    val aboveY = selectionBounds.top - toolbarHeightPx - gapPx
    val belowY = selectionBounds.bottom + gapPx
    val rightX = selectionBounds.right + gapPx
    val leftX = selectionBounds.left - toolbarWidthPx - gapPx
    val sideY = (selectionBounds.centerY() - toolbarHeightPx / 2f).coerceIn(edgePaddingPx, maxY)
    val (x, y) =
        when {
            aboveY >= edgePaddingPx -> centeredX to aboveY
            belowY <= maxY -> centeredX to belowY
            rightX <= maxX -> rightX to sideY
            leftX >= edgePaddingPx -> leftX to sideY
            selectionBounds.centerY() > canvasHeightPx / 2f -> centeredX to edgePaddingPx
            else -> centeredX to maxY
        }

    return IntOffset(x.roundToInt(), y.roundToInt())
}

/**
 * Canvas 版本的 Markdown 节点渲染器
 * 
 * 优化策略：
 * - 使用单个大 Canvas 绘制所有简单文本（标题、段落、列表项）
 * - 复杂组件（代码块、表格、LaTeX）保留原有的 Compose 组件
 * - 最大程度减少组件数量，提高流式渲染性能
 * 
 * 稳定性优化：
 * - 使用 remember 缓存字体大小，避免每次从 MaterialTheme 读取
 * - 稳定化 lambda 参数，减少不必要的 recompose
 */
@Composable
internal fun CanvasMarkdownNodeRenderer(
    nodeKey: String,
    node: MarkdownNodeStable,
    textColor: Color,
    fontSize: TextUnit = Unspecified,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    index: Int,
    xmlRenderer: XmlContentRenderer,
    xmlStream: Stream<String>? = null,
    enableDialogs: Boolean = true,
    fillMaxWidth: Boolean = true,
    textSelectionRequest: MarkdownTextSelectionRequest? = null,
    selectionState: MarkdownCanvasTextSelectionState? = null,
    isLastNode: Boolean = false
) {
    
    val density = LocalDensity.current
    
    // 缓存字体大小 - 避免每次 recompose 都从 MaterialTheme 读取
    // 只有当 MaterialTheme 真正变化时才会重新计算
    val typography = MaterialTheme.typography
    val fontSizes = remember(typography, fontSize) {
        val defaultBodySize = typography.bodyMedium.fontSize
        val scale =
            if (
                fontSize == Unspecified ||
                    !defaultBodySize.value.isFinite() ||
                    defaultBodySize.value <= 0f ||
                    !fontSize.value.isFinite() ||
                    fontSize.value <= 0f
            ) {
                1f
            } else {
                fontSize.value / defaultBodySize.value
            }
        FontSizes(
            bodyMedium = scaleMarkdownTextUnit(typography.bodyMedium.fontSize, scale),
            headlineLarge = scaleMarkdownTextUnit(typography.headlineLarge.fontSize, scale),
            headlineMedium = scaleMarkdownTextUnit(typography.headlineMedium.fontSize, scale),
            headlineSmall = scaleMarkdownTextUnit(typography.headlineSmall.fontSize, scale),
            titleLarge = scaleMarkdownTextUnit(typography.titleLarge.fontSize, scale),
            titleMedium = scaleMarkdownTextUnit(typography.titleMedium.fontSize, scale),
            titleSmall = scaleMarkdownTextUnit(typography.titleSmall.fontSize, scale)
        )
    }
    
    // 【关键优化】稳定化 xmlRenderer 和 onLinkClick
    // 这两个参数虽然每次传入的引用可能不同，但实际功能是相同的
    // 使用 rememberUpdatedState 确保我们总是使用最新的值，但不会因为引用变化而触发不必要的重组
    val currentXmlRenderer = rememberUpdatedState(xmlRenderer)
    val currentOnLinkClick = rememberUpdatedState(onLinkClick)
    
    // 直接从 node 读取内容，不使用外层 key()
    // 让 Compose 根据节点的实际变化自然地触发 recompose，而不是强制重建
    // 这样可以保持 XML 渲染器等组件的内部状态（如折叠/展开状态）
    val content = node.content
    
    // 【不使用 key() 包裹】直接调用 renderNodeContent
    // 让内部的 remember 和组件自己根据 content 的变化来决定是否重组
    // 这样 xmlRenderer 和 onLinkClick 的引用变化不会导致重组
    renderNodeContent(
        nodeKey = nodeKey,
        node = node,
        content = content,
        textColor = textColor,
        fontSizes = fontSizes,
        density = density,
        modifier = modifier,
        onLinkClick = currentOnLinkClick.value,
        xmlRenderer = currentXmlRenderer.value,
        xmlStream = xmlStream,
        index = index,
        enableDialogs = enableDialogs,
        fillMaxWidth = fillMaxWidth,
        textSelectionRequest = textSelectionRequest,
        selectionState = selectionState,
        isLastNode = isLastNode
    )
}

/** 字体大小数据类 */
private data class FontSizes(
    val bodyMedium: TextUnit,
    val headlineLarge: TextUnit,
    val headlineMedium: TextUnit,
    val headlineSmall: TextUnit,
    val titleLarge: TextUnit,
    val titleMedium: TextUnit,
    val titleSmall: TextUnit
)

private fun scaleMarkdownTextUnit(
    base: TextUnit,
    scale: Float
): TextUnit {
    if (!scale.isFinite() || scale <= 0f || scale == 1f) {
        return base
    }
    return (base.value * scale).sp
}

@Composable
private fun renderNodeContent(
    nodeKey: String,
    node: MarkdownNodeStable,
    content: String,
    textColor: Color,
    fontSizes: FontSizes,
    density: Density,
    modifier: Modifier,
    onLinkClick: ((String) -> Unit)?,
    xmlRenderer: XmlContentRenderer,
    xmlStream: Stream<String>?,
    index: Int,
    enableDialogs: Boolean,
    fillMaxWidth: Boolean,
    textSelectionRequest: MarkdownTextSelectionRequest?,
    selectionState: MarkdownCanvasTextSelectionState?,
    isLastNode: Boolean = false
) {
    // 【关键优化】只要节点内容不变，就记住原始节点实例，防止不必要的重组
    val stableNode = remember(content) { node }

    when (stableNode.type) {
        // ========== 简单文本类型：使用单个大 Canvas 绘制 ==========
        MarkdownProcessorType.HEADER,
        MarkdownProcessorType.ORDERED_LIST,
        MarkdownProcessorType.UNORDERED_LIST -> {
            UnifiedCanvasRenderer(
                nodeKey = nodeKey,
                node = stableNode,
                textColor = textColor,
                bodyMediumSize = fontSizes.bodyMedium,
                headlineLargeSize = fontSizes.headlineLarge,
                headlineMediumSize = fontSizes.headlineMedium,
                headlineSmallSize = fontSizes.headlineSmall,
                titleLargeSize = fontSizes.titleLarge,
                titleMediumSize = fontSizes.titleMedium,
                titleSmallSize = fontSizes.titleSmall,
                density = density,
                modifier = modifier,
                onLinkClick = onLinkClick,
                fillMaxWidth = fillMaxWidth,
                textSelectionRequest = textSelectionRequest,
                selectionState = selectionState,
                nodeIndex = index,
                isLastNode = isLastNode
            )
        }

        MarkdownProcessorType.PLAIN_TEXT -> {
            UnifiedCanvasRenderer(
                nodeKey = nodeKey,
                node = stableNode,
                textColor = textColor,
                bodyMediumSize = fontSizes.bodyMedium,
                headlineLargeSize = fontSizes.headlineLarge,
                headlineMediumSize = fontSizes.headlineMedium,
                headlineSmallSize = fontSizes.headlineSmall,
                titleLargeSize = fontSizes.titleLarge,
                titleMediumSize = fontSizes.titleMedium,
                titleSmallSize = fontSizes.titleSmall,
                density = density,
                modifier = modifier,
                onLinkClick = onLinkClick,
                fillMaxWidth = fillMaxWidth,
                textSelectionRequest = textSelectionRequest,
                selectionState = selectionState,
                nodeIndex = index,
                isLastNode = isLastNode
            )
        }

        MarkdownProcessorType.HTML_BREAK -> {
            SingleTextCanvas(
                text = "\n",
                textColor = textColor,
                fontSize = fontSizes.bodyMedium,
                fontWeight = FontWeight.Normal,
                density = density,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // ========== 代码块：保留原组件 ==========
        MarkdownProcessorType.CODE_BLOCK -> {
            val codeLines = content.trimAll().lines()
            val firstLine = codeLines.firstOrNull() ?: ""
            val language = if (firstLine.startsWith("```")) {
                firstLine.removePrefix("```").trim()
            } else ""
            
            val codeContent = codeLines
                .dropWhile { it.startsWith("```") }
                .dropLastWhile { it.endsWith("```") }
                .joinToString("\n")
            
            // 不使用 key()，让 Compose 根据位置自然识别组件
            // 这样可以保留内部状态（如"已复制"提示、Mermaid 渲染状态）
            EnhancedCodeBlock(
                code = codeContent,
                language = language,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // ========== 表格：保留原组件 ==========
        MarkdownProcessorType.TABLE -> {
            // 不使用 key()，让 Compose 根据位置自然识别组件
            EnhancedTableBlock(
                tableContent = content,
                textColor = textColor,
                modifier = Modifier.fillMaxWidth(),
                textSelectionRequest = textSelectionRequest,
                selectionState = selectionState,
                nodeIndex = index,
            )
        }
        
        // ========== 引用块：使用 Canvas 绘制文本 + 边框 ==========
        MarkdownProcessorType.BLOCK_QUOTE -> {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(4.dp)
                ) {
                    val quoteText = content.lines().joinToString("\n") {
                        it.removePrefix("> ").removePrefix(">")
                    }
                    
                    SingleTextCanvas(
                        text = quoteText,
                        textColor = textColor,
                        fontSize = fontSizes.bodyMedium,
                        fontWeight = FontWeight.Normal,
                        density = density,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // ========== 分隔线 ==========
        MarkdownProcessorType.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        }
        
        // ========== XML块：保留原组件 ==========
        MarkdownProcessorType.XML_BLOCK -> {
            xmlRenderer.RenderXmlContent(
                xmlContent = content,
                modifier = Modifier.fillMaxWidth(),
                textColor = textColor,
                xmlStream = xmlStream,
                renderInstanceKey = nodeKey
            )
        }
        
        // ========== 图片：保留原组件 ==========
        MarkdownProcessorType.IMAGE -> {
            val imageContent = content.trimAll()
            if (isCompleteImageMarkdown(imageContent)) {
                // 不使用 key()，让 Compose 根据位置自然识别组件
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                ) {
                    MarkdownImageRenderer(
                        imageMarkdown = imageContent,
                        modifier = Modifier.fillMaxWidth(),
                        maxImageHeight = 140,
                        enableDialogs = enableDialogs
                    )
                }
            } else {
                SingleTextCanvas(
                    text = content,
                    textColor = textColor,
                    fontSize = fontSizes.bodyMedium,
                    fontWeight = FontWeight.Normal,
                    density = density,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)
                )
            }
        }
        
        // ========== 块级 LaTeX：保留原组件 ==========
        MarkdownProcessorType.BLOCK_LATEX -> {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 提取LaTeX内容，移除各种分隔符
                    val latexContent = extractLatexContent(content.trimAll())
                    val horizontalScrollState = rememberScrollState()
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 使用AndroidView和JLatexMath渲染LaTeX公式
                        AndroidView(
                            factory = { context ->
                                TextView(context).apply {
                                    // 设置初始空白状态
                                    text = ""
                                }
                            },
                            update = { textView ->
                                // 在update回调中渲染LaTeX公式
                                try {
                                    val drawable = LatexCache.getDrawable(
                                        latexContent.trim(),
                                        JLatexMathDrawable.builder(latexContent)
                                            .textSize(14f * textView.resources.displayMetrics.density)
                                            .padding(2)
                                            .background(0x00000000)
                                            .align(JLatexMathDrawable.ALIGN_CENTER)
                                            .color(textColor.toArgb())
                                    )
                                    
                                    // 设置边界并添加到TextView
                                    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                                    textView.setCompoundDrawables(null, drawable, null, null)
                                } catch (e: Exception) {
                                    AppLogger.w(TAG, "Block LaTeX render failed, fallback to raw text: $latexContent", e)
                                    // 渲染失败时回退到公式原文显示，避免整页闪退
                                    textView.setCompoundDrawables(null, null, null, null)
                                    textView.text = content.trimAll()
                                    textView.setTextColor(textColor.toArgb())
                                    textView.textSize = 16f
                                    textView.typeface = android.graphics.Typeface.MONOSPACE
                                }
                            },
                            modifier = Modifier.wrapContentWidth(unbounded = true)
                        )
                    }
                }
            }
        }
        
        // ========== 其他：Canvas 绘制 ==========
        else -> {
            if (content.trimAll().isEmpty()) return
            
            SingleTextCanvas(
                text = content.trimAll(),
                textColor = textColor,
                fontSize = fontSizes.bodyMedium,
                fontWeight = FontWeight.Normal,
                density = density,
                modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)
            )
        }
    }
}

/**
 * 统一的 Canvas 渲染器
 * 在一个大 Canvas 中绘制标题、段落、列表等简单文本
 */
@Composable
private fun UnifiedCanvasRenderer(
    nodeKey: String,
    node: MarkdownNodeStable,
    textColor: Color,
    bodyMediumSize: TextUnit,
    headlineLargeSize: TextUnit,
    headlineMediumSize: TextUnit,
    headlineSmallSize: TextUnit,
    titleLargeSize: TextUnit,
    titleMediumSize: TextUnit,
    titleSmallSize: TextUnit,
    density: Density,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)?,
    fillMaxWidth: Boolean = true,
    textSelectionRequest: MarkdownTextSelectionRequest?,
    selectionState: MarkdownCanvasTextSelectionState?,
    nodeIndex: Int,
    isLastNode: Boolean = false
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val textLayoutSettings = LocalAiMarkdownTextLayoutSettings.current

    val fontFamily = MaterialTheme.typography.bodyMedium.fontFamily
    val resolver = LocalFontFamilyResolver.current

    val normalTypeface = remember(resolver, fontFamily) {
        (resolver.resolve(fontFamily = fontFamily, fontWeight = FontWeight.Normal).value as? android.graphics.Typeface)
            ?: android.graphics.Typeface.DEFAULT
    }
    val boldTypeface = remember(resolver, fontFamily) {
        (resolver.resolve(fontFamily = fontFamily, fontWeight = FontWeight.Bold).value as? android.graphics.Typeface)
            ?: android.graphics.Typeface.DEFAULT_BOLD
    }

    BoxWithConstraints(modifier = modifier) {
        val localDensity = LocalDensity.current
        val availableWidthPx = with(localDensity) { maxWidth.toPx() }.toInt()
        if (availableWidthPx <= 0) return@BoxWithConstraints

        val enableTypewriter = !nodeKey.startsWith("static-node-") && isLastNode && (node.content.isNotEmpty() || node.children.isNotEmpty()) &&
                (node.type == MarkdownProcessorType.PLAIN_TEXT ||
                        node.type == MarkdownProcessorType.HEADER ||
                        node.type == MarkdownProcessorType.ORDERED_LIST ||
                        node.type == MarkdownProcessorType.UNORDERED_LIST)

        val contentKey = node.content.length

        // 计算布局和绘制指令（用于稳定高度/宽度）
        val layoutResult = remember(
            contentKey,
            textColor,
            availableWidthPx,
            node.type,
            normalTypeface,
            boldTypeface,
            isLastNode,
            node.children,
            textLayoutSettings.lineHeightMultiplier,
            textLayoutSettings.letterSpacingSp
        ) {
            calculateLayout(
                node = node,
                textColor = textColor,
                primaryColor = primaryColor,
                bodyMediumSize = bodyMediumSize,
                headlineLargeSize = headlineLargeSize,
                headlineMediumSize = headlineMediumSize,
                headlineSmallSize = headlineSmallSize,
                titleLargeSize = titleLargeSize,
                titleMediumSize = titleMediumSize,
                titleSmallSize = titleSmallSize,
                normalTypeface = normalTypeface,
                boldTypeface = boldTypeface,
                density = localDensity,
                availableWidthPx = availableWidthPx,
                isLastNode = isLastNode,
                globalLineHeightMultiplier = textLayoutSettings.lineHeightMultiplier,
                globalLetterSpacingSp = textLayoutSettings.letterSpacingSp,
                globalParagraphSpacingDp = textLayoutSettings.paragraphSpacingDp
            )
        }

        val revealInstruction = layoutResult.instructions.filterIsInstance<DrawInstruction.TextLayout>().singleOrNull()
        val targetLength = revealInstruction?.layout?.text?.length ?: 0
        val revealHasImageSpans =
            (revealInstruction?.text as? Spanned)
                ?.getSpans(0, targetLength, ImageSpan::class.java)
                ?.isNotEmpty() == true
        val shouldAnimateTypewriter = enableTypewriter && !revealHasImageSpans
        val revealAnim = remember(nodeKey) { Animatable(0f) }
        LaunchedEffect(shouldAnimateTypewriter) {
            if (!shouldAnimateTypewriter) {
                revealAnim.snapTo(targetLength.toFloat())
            }
        }
        LaunchedEffect(targetLength, shouldAnimateTypewriter) {
            if (!shouldAnimateTypewriter) {
                return@LaunchedEffect
            }
            if (targetLength <= 0) {
                return@LaunchedEffect
            }
            val current = revealAnim.value
            if (targetLength.toFloat() < current) {
                revealAnim.snapTo(targetLength.toFloat())
            } else {
                val deltaChars = (targetLength - floor(current).toInt()).coerceAtLeast(0)
                if (deltaChars <= 0) {
                    return@LaunchedEffect
                }
                val durationMs = TYPEWRITER_WINDOW_MS
                revealAnim.animateTo(
                    targetValue = targetLength.toFloat(),
                    animationSpec = tween(
                        durationMillis = durationMs,
                        easing = LinearEasing
                    )
                )
            }
        }

        val revealValue = if (shouldAnimateTypewriter) revealAnim.value else targetLength.toFloat()
        val baseLen = floor(revealValue).toInt().coerceIn(0, targetLength)
        val partial = if (shouldAnimateTypewriter) {
            (revealValue - baseLen.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        
        // 提取文本内容用于无障碍朗读
        val accessibleText = remember(layoutResult.instructions) {
            extractAccessibleText(layoutResult.instructions)
        }
        val safeAccessibleText = remember(accessibleText) {
            if (accessibleText.length > FALLBACK_MAX_TEXT_CHARS) {
                accessibleText.substring(0, FALLBACK_MAX_TEXT_CHARS)
            } else {
                accessibleText
            }
        }
        val currentSelectionState = selectionState ?: remember(nodeKey) { MarkdownCanvasTextSelectionState() }
        val maxHeightPx = minOf(MAX_CANVAS_HEIGHT_PX, MAX_COMPOSE_CONSTRAINT_HEIGHT_PX)
        val clampedHeightDp = with(localDensity) {
            layoutResult.height.coerceIn(0f, maxHeightPx).toDp()
        }
        val canvasModifier =
            (if (fillMaxWidth) {
                Modifier.fillMaxWidth()
            } else {
                // bubble模式：使用实际宽度，如果宽度为0则wrapContent
                if (layoutResult.actualWidth > 0f) {
                    Modifier.width(with(localDensity) { layoutResult.actualWidth.toDp() })
                } else {
                    Modifier
                }
            })
                .height(clampedHeightDp)
                .semantics {
                    contentDescription = safeAccessibleText
                }

        val autoScrollController = LocalMarkdownTextSelectionAutoScrollController.current
        var canvasBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
        var toolbarSize by remember(nodeKey) { mutableStateOf(IntSize.Zero) }
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val selectionColor = MaterialTheme.colorScheme.primary
        val selectionPaint = remember(selectionColor) {
            android.graphics.Paint().apply {
                color = selectionColor.copy(alpha = 0.24f).toArgb()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
        }
        val cursorPaint = remember(selectionColor, localDensity) {
            android.graphics.Paint().apply {
                color = selectionColor.toArgb()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(localDensity) { 2.dp.toPx() }
                strokeCap = android.graphics.Paint.Cap.ROUND
                isAntiAlias = true
            }
        }
        val handlePaint = remember(selectionColor) {
            android.graphics.Paint().apply {
                color = selectionColor.toArgb()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
        }
        val handleRadiusPx = with(localDensity) { 7.dp.toPx() }
        val handleTouchRadiusPx = with(localDensity) { 38.dp.toPx() }
        val magnifierSurfaceColor = MaterialTheme.colorScheme.surface
        val magnifierTextColor = MaterialTheme.colorScheme.onSurface
        val magnifierBubblePaint = remember(magnifierSurfaceColor) {
            android.graphics.Paint().apply {
                color = magnifierSurfaceColor.copy(alpha = 0.96f).toArgb()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
        }
        val magnifierBorderPaint = remember(selectionColor, localDensity) {
            android.graphics.Paint().apply {
                color = selectionColor.copy(alpha = 0.45f).toArgb()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(localDensity) { 1.dp.toPx() }
                isAntiAlias = true
            }
        }
        val magnifierTextPaint = remember(magnifierTextColor, localDensity) {
            android.graphics.Paint().apply {
                color = magnifierTextColor.toArgb()
                textSize = with(localDensity) { 17.sp.toPx() }
                isAntiAlias = true
                typeface = normalTypeface
            }
        }
        val magnifierWidthDp = 164.dp
        val magnifierHeightDp = 48.dp
        val magnifierMarginDp = 8.dp
        val magnifierWidthPx = with(localDensity) { magnifierWidthDp.toPx() }
        val magnifierHeightPx = with(localDensity) { magnifierHeightDp.toPx() }
        val magnifierMarginPx = with(localDensity) { magnifierMarginDp.toPx() }
        val toolbarGapPx = with(localDensity) { 6.dp.toPx() }
        val toolbarEdgePaddingPx = with(localDensity) { 6.dp.toPx() }
        val toolbarEstimatedWidthPx = with(localDensity) { 96.dp.toPx() }
        val toolbarEstimatedHeightPx = with(localDensity) { 30.dp.toPx() }
        val nodeSelectionState =
            remember(currentSelectionState, nodeIndex, layoutResult.instructions) {
                derivedStateOf {
                    currentSelectionState.selection?.let { selection ->
                        nodeSelectionSnapshot(
                            selection = selection,
                            nodeIndex = nodeIndex,
                            instructions = layoutResult.instructions,
                        )
                    }
                }
            }
        val toolbarSelectionState =
            remember(currentSelectionState, nodeIndex, layoutResult.instructions) {
                derivedStateOf {
                    val selection = currentSelectionState.selection
                    if (selection == null || compareSelectionPoints(selection.start, selection.end) == 0) {
                        null
                    } else {
                        val orderedStart = orderedSelectionPoints(selection).first
                        if (orderedStart.nodeIndex == nodeIndex) {
                            nodeSelectionSnapshot(
                                selection = selection,
                                nodeIndex = nodeIndex,
                                instructions = layoutResult.instructions,
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        val nodeMagnifierState =
            remember(currentSelectionState, nodeIndex) {
                derivedStateOf {
                    currentSelectionState.dragMagnifier?.takeIf { it.hostNodeIndex == nodeIndex }
                }
            }
        val nodeSelection = nodeSelectionState.value
        val toolbarNodeSelection = toolbarSelectionState.value
        val nodeMagnifier = nodeMagnifierState.value

        LaunchedEffect(canvasBoundsInWindow, layoutResult.instructions) {
            val bounds = canvasBoundsInWindow ?: return@LaunchedEffect
            currentSelectionState.updateNodeLayout(
                nodeIndex = nodeIndex,
                boundsInWindow = bounds,
                instructions = layoutResult.instructions,
            )
        }

        LaunchedEffect(textSelectionRequest?.id, canvasBoundsInWindow) {
            val request = textSelectionRequest
            if (request == null) {
                currentSelectionState.clear()
                return@LaunchedEffect
            }
            if (currentSelectionState.handledRequestId == request.id) {
                return@LaunchedEffect
            }
            val bounds = canvasBoundsInWindow ?: return@LaunchedEffect
            if (!bounds.containsOffset(request.positionInWindow)) {
                currentSelectionState.dragMagnifier = null
                return@LaunchedEffect
            }

            val localPosition =
                request.positionInWindow - Offset(bounds.left, bounds.top)
            val hit = findTextSelectionHit(layoutResult.instructions, localPosition, nodeIndex)
            val instruction = hit?.let { layoutResult.instructions.getOrNull(it.instructionIndex) }
            currentSelectionState.handledRequestId = request.id
            if (hit != null && instruction != null && instruction.selectableTextLength() > 0) {
                currentSelectionState.selection =
                    createInitialSelection(
                        nodeIndex = nodeIndex,
                        instructionIndex = hit.instructionIndex,
                        instruction = instruction,
                        offset = hit.offset,
                    )
            } else {
                currentSelectionState.selection = null
                currentSelectionState.dragMagnifier = null
            }
        }
        
        // 使用单个 Canvas 绘制所有内容
        SafeMeasureOrFallback(
            fallback = {
                Text(
                    text = safeAccessibleText,
                    color = textColor,
                    maxLines = 200,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Box {
                Canvas(
                modifier = canvasModifier
                    .onGloballyPositioned { coordinates ->
                        canvasBoundsInWindow = coordinates.boundsInWindow()
                    }
                    .pointerInput(layoutResult.instructions, onLinkClick, currentSelectionState) {
                    awaitEachGesture {
                        val down = awaitPointerEvent(PointerEventPass.Main).changes.first()
                        val downTime = System.currentTimeMillis()
                        val downPosition = down.position
                        val selection = currentSelectionState.selection
                        val activeHandle =
                            if (selection != null) {
                                selectionHandleAt(
                                    selection = selection,
                                    nodeIndex = nodeIndex,
                                    instructions = layoutResult.instructions,
                                    position = downPosition,
                                    radiusPx = handleTouchRadiusPx,
                                    handleRadiusPx = handleRadiusPx,
                                )
                            } else {
                                null
                            }

                        if (activeHandle != null && selection != null) {
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
                            currentSelectionState.dragMagnifier =
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
                                        val currentMagnifier = currentSelectionState.dragMagnifier
                                        if (currentMagnifier?.hostNodeIndex == nodeIndex) {
                                            currentSelectionState.dragMagnifier =
                                                currentMagnifier.copy(
                                                    position = change.position,
                                                    positionInWindow = changePositionInWindow,
                                                )
                                        }
                                        val point =
                                            currentSelectionState.findPointInWindow(changePositionInWindow)
                                        if (point != null) {
                                            val currentSelection = currentSelectionState.selection ?: selection
                                            val updatedSelection =
                                                when (activeHandle) {
                                                    TextSelectionHandle.START -> currentSelection.copy(start = point)
                                                    TextSelectionHandle.END -> currentSelection.copy(end = point)
                                                }
                                            if (updatedSelection != currentSelection) {
                                                currentSelectionState.selection = updatedSelection
                                            }
                                            currentSelectionState.dragMagnifier =
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

                                    val magnifier = currentSelectionState.dragMagnifier
                                    if (magnifier?.hostNodeIndex != nodeIndex) break
                                    val controller = autoScrollController
                                    if (controller != null && controller.scrollByEdge(magnifier.positionInWindow)) {
                                        val point = currentSelectionState.findPointInWindow(magnifier.positionInWindow)
                                        val currentSelection = currentSelectionState.selection ?: selection
                                        if (point != null) {
                                            val updatedSelection =
                                                when (magnifier.handle) {
                                                    TextSelectionHandle.START -> currentSelection.copy(start = point)
                                                    TextSelectionHandle.END -> currentSelection.copy(end = point)
                                                }
                                            if (updatedSelection != currentSelection) {
                                                currentSelectionState.selection = updatedSelection
                                            }
                                            val bounds = canvasBoundsInWindow
                                            val localPosition =
                                                if (bounds != null) {
                                                    magnifier.positionInWindow - Offset(bounds.left, bounds.top)
                                                } else {
                                                    magnifier.position
                                                }
                                            currentSelectionState.dragMagnifier =
                                                magnifier.copy(
                                                    position = localPosition,
                                                    point = point,
                                                )
                                        }
                                    }
                                }
                            } finally {
                                autoScrollController?.reset()
                                currentSelectionState.dragMagnifier = null
                            }
                            return@awaitEachGesture
                        }

                        val up = awaitPointerEvent(PointerEventPass.Main).changes.first()
                        val upTime = System.currentTimeMillis()
                        val upPosition = up.position

                        val isTap = (upTime - downTime) < 500 &&
                            (upPosition - downPosition).getDistance() < 10f

                        if (isTap) {
                            var clickedLink = false
                            layoutResult.instructions.forEach { instruction ->
                                if (instruction is DrawInstruction.TextLayout) {
                                    val layout = instruction.layout
                                    val text = instruction.text
                                    if (text is Spanned) {
                                        val bounds = android.graphics.RectF(
                                            instruction.x,
                                            instruction.y,
                                            instruction.x + layout.width,
                                            instruction.y + layout.height
                                        )
                                        if (bounds.contains(upPosition.x, upPosition.y)) {
                                            val relativeX = upPosition.x - instruction.x
                                            val relativeY = upPosition.y - instruction.y
                                            val line = layout.getLineForVertical(relativeY.toInt())
                                            val lineOffset = layout.getOffsetForHorizontal(line, relativeX)

                                            val spans = text.getSpans(lineOffset, lineOffset, URLSpan::class.java)
                                            spans.firstOrNull()?.let { span ->
                                                onLinkClick?.invoke(span.url)
                                                clickedLink = true
                                            }
                                        }
                                    }
                                }
                            }

                            if (clickedLink) {
                                up.consume()
                            }
                        }
                    }
                }
            ) {
                drawIntoCanvas { canvas ->
                    // 获取可见区域（屏幕内区域）
                    val clipBounds = android.graphics.Rect()
                    canvas.nativeCanvas.getClipBounds(clipBounds)
                    val selectionPath = android.graphics.Path()

                    // 只绘制在可见区域内的指令
                    layoutResult.instructions.forEachIndexed { instructionIndex, instruction ->
                        when (instruction) {
                            is DrawInstruction.Text -> {
                                // 判断文本是否在可见区域内
                                val textTop = instruction.y - instruction.paint.textSize
                                val textBottom = instruction.y + instruction.paint.descent()

                                if (textBottom >= clipBounds.top && textTop <= clipBounds.bottom) {
                                    val selectionForInstruction = nodeSelection
                                    val selectionRange =
                                        selectionForInstruction?.let {
                                            selectedRangeForInstruction(
                                                selection = it,
                                                instructionIndex = instructionIndex,
                                                textLength = instruction.text.length,
                                            )
                                        }
                                    if (selectionRange != null) {
                                        val selectedBounds = instruction.selectedBounds(selectionRange)
                                        if (selectedBounds != null && !selectedBounds.isEmpty) {
                                            canvas.nativeCanvas.drawRect(selectedBounds, selectionPaint)
                                        }
                                    }
                                    canvas.nativeCanvas.drawText(
                                        instruction.text,
                                        instruction.x,
                                        instruction.y,
                                        instruction.paint
                                    )
                                    val selectionStartPoint = selectionForInstruction?.startHandle
                                    if (
                                        selectionStartPoint != null &&
                                            selectionStartPoint.instructionIndex == instructionIndex
                                    ) {
                                        drawCanvasTextSelectionHandle(
                                            canvas = canvas.nativeCanvas,
                                            instruction = instruction,
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
                                            instruction = instruction,
                                            offset = selectionEndPoint.offset,
                                            cursorPaint = cursorPaint,
                                            handlePaint = handlePaint,
                                            handleRadiusPx = handleRadiusPx,
                                        )
                                    }
                                }
                            }
                            is DrawInstruction.Line -> {
                                // 判断线条是否在可见区域内
                                val lineTop = minOf(instruction.startY, instruction.endY)
                                val lineBottom = maxOf(instruction.startY, instruction.endY)

                                if (lineBottom >= clipBounds.top && lineTop <= clipBounds.bottom) {
                                    canvas.nativeCanvas.drawLine(
                                        instruction.startX,
                                        instruction.startY,
                                        instruction.endX,
                                        instruction.endY,
                                        instruction.paint
                                    )
                                }
                            }
                            is DrawInstruction.TextLayout -> {
                                // 使用 StaticLayout 绘制（自动换行）
                                val layoutTop = instruction.y
                                val layoutBottom = instruction.y + instruction.layout.height

                                if (layoutBottom >= clipBounds.top && layoutTop <= clipBounds.bottom) {
                                    val selectionForInstruction = nodeSelection
                                    val selectionRange =
                                        selectionForInstruction?.let {
                                            selectedRangeForInstruction(
                                                selection = it,
                                                instructionIndex = instructionIndex,
                                                textLength = instruction.layout.text.length,
                                            )
                                        }
                                    val hasSelectionHandle =
                                        selectionForInstruction?.let {
                                            it.startHandle?.instructionIndex == instructionIndex ||
                                                it.endHandle?.instructionIndex == instructionIndex
                                        } == true
                                    canvas.nativeCanvas.save()
                                    canvas.nativeCanvas.translate(instruction.x, instruction.y)

                                    if (selectionRange != null || hasSelectionHandle) {
                                        drawInlineCodeBackgrounds(instruction.layout, canvas.nativeCanvas)
                                        if (selectionRange != null) {
                                            selectionPath.reset()
                                            instruction.layout.getSelectionPath(
                                                selectionRange.start,
                                                selectionRange.end,
                                                selectionPath,
                                            )
                                            canvas.nativeCanvas.drawPath(selectionPath, selectionPaint)
                                        }
                                        instruction.layout.draw(canvas.nativeCanvas)
                                        val selectionStartPoint = selectionForInstruction?.startHandle
                                        if (
                                            selectionStartPoint != null &&
                                                selectionStartPoint.instructionIndex == instructionIndex
                                        ) {
                                            drawCanvasTextSelectionHandle(
                                                canvas = canvas.nativeCanvas,
                                                layout = instruction.layout,
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
                                                layout = instruction.layout,
                                                offset = selectionEndPoint.offset,
                                                cursorPaint = cursorPaint,
                                                handlePaint = handlePaint,
                                                handleRadiusPx = handleRadiusPx,
                                            )
                                        }
                                    } else if (shouldAnimateTypewriter && revealInstruction === instruction && targetLength > 0 && baseLen < targetLength) {
                                        val layout = instruction.layout

                                        val offsetForLine = baseLen.coerceIn(0, (targetLength - 1).coerceAtLeast(0))
                                        val line = layout.getLineForOffset(offsetForLine)
                                        val lineTopPx = layout.getLineTop(line).toFloat()
                                        val lineBottomPx = layout.getLineBottom(line).toFloat()

                                        val safeBaseLen = baseLen.coerceIn(0, targetLength)
                                        val safeNextLen = (baseLen + 1).coerceAtMost(targetLength)
                                        val x0 = layout.getPrimaryHorizontal(safeBaseLen)
                                        
                                        // 检查下一个字符是否换行
                                        val lineOfNext = if (safeNextLen < layout.text.length) layout.getLineForOffset(safeNextLen) else line
                                        val x1 = if (lineOfNext != line) {
                                            // 如果换行了，说明当前字符是该行的最后一个字符（可能是\n，或者是被wrap的字符）
                                            // 这种情况下 getPrimaryHorizontal(safeNextLen) 会返回下一行的坐标（通常是0），导致计算出的 charWidth 巨大且不仅确
                                            // 我们需要手动测量这个字符的宽度，并加在 x0 上 (假设 LTR)
                                            // 注意：如果是 RTL，逻辑需要反过来，这里简单处理常规情况，更严谨可以使用 layout.getParagraphDirection
                                            val charWidthMeasured = layout.paint.measureText(layout.text, safeBaseLen, safeNextLen)
                                            if (layout.getParagraphDirection(line) == android.text.Layout.DIR_RIGHT_TO_LEFT) {
                                                x0 - charWidthMeasured
                                            } else {
                                                x0 + charWidthMeasured
                                            }
                                        } else {
                                            layout.getPrimaryHorizontal(safeNextLen)
                                        }

                                        val charMinX = minOf(x0, x1)
                                        val charMaxX = maxOf(x0, x1)
                                        val charWidth = (charMaxX - charMinX).coerceAtLeast(0f)
                                        // 修正闪烁问题：
                                        // 之前对 charWidth <= 0.01f 的处理（直接显示整行）会导致在换行处如果有零宽字符（如某些空格或换行符处理），
                                        // 会瞬间显示出该行后续的文字，产生闪烁。
                                        // 现在统一使用分段绘制逻辑，并仅在字符宽度有效时才绘制淡入部分。

                                        val visibleRight = (charMinX + charWidth * partial).coerceIn(charMinX, charMaxX)

                                        // 1. 绘制当前行之前的所有行
                                        canvas.nativeCanvas.save()
                                        canvas.nativeCanvas.clipRect(0f, 0f, layout.width.toFloat(), lineTopPx)
                                        drawInlineCodeBackgrounds(layout, canvas.nativeCanvas)
                                        layout.draw(canvas.nativeCanvas)
                                        canvas.nativeCanvas.restore()

                                        // 2. 绘制当前行，直到当前正在显示的字符之前
                                        canvas.nativeCanvas.save()
                                        canvas.nativeCanvas.clipRect(0f, lineTopPx, charMinX, lineBottomPx)
                                        drawInlineCodeBackgrounds(layout, canvas.nativeCanvas)
                                        layout.draw(canvas.nativeCanvas)
                                        canvas.nativeCanvas.restore()

                                        // 3. 绘制当前正在显示的字符（带淡入效果）
                                        if (charWidth > 0.01f) {
                                            val alphaInt = (partial * 255f).toInt().coerceIn(0, 255)
                                            canvas.nativeCanvas.save()
                                            canvas.nativeCanvas.clipRect(charMinX, lineTopPx, visibleRight, lineBottomPx)
                                            // 注意：saveLayerAlpha 在某些情况下可能会对其包含的内容做混合，如果不需要可以简化
                                            canvas.nativeCanvas.saveLayerAlpha(charMinX, lineTopPx, visibleRight, lineBottomPx, alphaInt)
                                            drawInlineCodeBackgrounds(layout, canvas.nativeCanvas)
                                            layout.draw(canvas.nativeCanvas)
                                            canvas.nativeCanvas.restore()
                                            canvas.nativeCanvas.restore()
                                        }
                                    } else {
                                        drawInlineCodeBackgrounds(instruction.layout, canvas.nativeCanvas)
                                        instruction.layout.draw(canvas.nativeCanvas)
                                    }
                                    canvas.nativeCanvas.restore()
                                }
                            }
                        }
                    }
                }
            }
                val magnifier = nodeMagnifier
                val magnifierInstruction =
                    magnifier?.let {
                        currentSelectionState.textLayoutForPoint(it.point)
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
                    val selectionBounds = selectionVisualBounds(
                        instructions = layoutResult.instructions,
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
                                        canvasWidthPx = canvasBoundsInWindow?.width ?: layoutResult.actualWidth,
                                        canvasHeightPx = with(localDensity) { clampedHeightDp.toPx() },
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
                                            clipboardManager.setText(AnnotatedString(currentSelectionState.selectedText()))
                                            currentSelectionState.dismissSelection()
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.message_copied_to_clipboard),
                                                Toast.LENGTH_SHORT,
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
                                            currentSelectionState.dismissSelection()
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
}

/**
 * 计算 StaticLayout 的实际使用宽度（取所有行的最大宽度）
 * @param layout 要计算的 StaticLayout
 * @param offsetX 水平偏移量（用于列表项等有缩进的情况）
 * @param availableWidthPx 最大可用宽度，用于提前终止遍历
 * @return 实际使用的最大宽度
 */
private inline fun calculateActualWidth(
    layout: StaticLayout,
    offsetX: Float = 0f,
    availableWidthPx: Int
): Float {
    var maxWidth = 0f
    for (line in 0 until layout.lineCount) {
        val lineWidth = offsetX + layout.getLineWidth(line)
        maxWidth = maxOf(maxWidth, lineWidth)
        // 如果某行已达到最大可用宽度，无需继续遍历
        if (lineWidth >= availableWidthPx) break
    }
    return maxWidth
}

/**
 * 计算布局和生成绘制指令
 */
private fun calculateLayout(
    node: MarkdownNodeStable,
    textColor: Color,
    primaryColor: Color,
    bodyMediumSize: TextUnit,
    headlineLargeSize: TextUnit,
    headlineMediumSize: TextUnit,
    headlineSmallSize: TextUnit,
    titleLargeSize: TextUnit,
    titleMediumSize: TextUnit,
    titleSmallSize: TextUnit,
    normalTypeface: Typeface,
    boldTypeface: Typeface,
    density: Density,
    availableWidthPx: Int,
    isLastNode: Boolean = false,
    disableLayoutCache: Boolean = false,
    typewriterTailAlpha: Float = 1f,
    globalLineHeightMultiplier: Float = 1f,
    globalLetterSpacingSp: Float = 0f,
    globalParagraphSpacingDp: Float = 0f
): LayoutResult {
    if (availableWidthPx <= 0) return LayoutResult(0f, 0f, emptyList())

    val safeAvailableWidthPx = safeLayoutWidth(availableWidthPx)
    val lineSpacingMultiplier = calculateCanvasLineSpacingMultiplier(globalLineHeightMultiplier)
    val content = node.content
    val instructions = mutableListOf<DrawInstruction>()
    var currentY = 0f
    var maxWidth = 0f  // 追踪实际使用的最大宽度
    
    when (node.type) {
        MarkdownProcessorType.HEADER -> {
            val level = determineHeaderLevel(content)
            val headerText = content.trimStart('#', ' ').trimAll()
            
            // 减小标题字号：使用更小一级的字体
            val fontSize = when (level) {
                1 -> headlineMediumSize  // 原：headlineLargeSize
                2 -> headlineSmallSize   // 原：headlineMediumSize
                3 -> titleLargeSize      // 原：headlineSmallSize
                4 -> titleMediumSize     // 原：titleLargeSize
                5 -> titleSmallSize      // 原：titleMediumSize
                else -> bodyMediumSize   // 原：titleSmallSize
            }
            
            // 增大上下间距，提高可读性
            val topPadding = when (level) {
                1 -> 12f  // 原：8f
                2 -> 10f  // 原：6f
                3 -> 8f   // 原：4f
                else -> 6f // 原：3f
            } * density.density
            
            val bottomPadding = when (level) {
                1, 2 -> 4f  // 原：2f
                else -> 2f  // 原：1f
            }
            val bottomPaddingPx = bottomPadding * density.density
            
            currentY += topPadding
            
            val textSizePx = with(density) { fontSize.toPx() }
            val textPaint = PaintCache.getTextPaint(
                textColor,
                textSizePx,
                boldTypeface,
                calculateCanvasLetterSpacingEm(fontSize, globalLetterSpacingSp)
            )

            val layout = if (node.children.isNotEmpty()) {
                // 处理子节点列表，去除第一个子节点中的标题标记
                val modifiedChildren = node.children.toMutableList()
                val firstChild = modifiedChildren[0]
                val newContent = firstChild.content.trimStart('#', ' ')
                val newFirstChild = MarkdownNodeStable(firstChild.type, content = newContent, children = firstChild.children)
                modifiedChildren[0] = newFirstChild

                createSafeInlineStaticLayout(
                    children = modifiedChildren,
                    fallbackText = headerText,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    density = density,
                    fontSize = fontSize,
                    textPaint = textPaint,
                    width = safeAvailableWidthPx,
                    lineSpacingMultiplier = lineSpacingMultiplier,
                    contextLabel = "header"
                )
            } else {
                LayoutCache.getLayout(
                    headerText,
                    textPaint,
                    safeAvailableWidthPx,
                    textColor,
                    boldTypeface,
                    lineSpacingMultiplier
                )
            }
            
            instructions.add(DrawInstruction.TextLayout(layout, 0f, currentY, layout.text))
            currentY += layout.height
            maxWidth = maxOf(maxWidth, calculateActualWidth(layout, 0f, safeAvailableWidthPx))
            
            // 最后一个节点不添加底部间距
            if (!isLastNode) {
                currentY += bottomPaddingPx
            }
        }
        
        MarkdownProcessorType.ORDERED_LIST -> {
            val itemContent = content.trimAll()
            val numberMatch = Regex("""^(\d+)\.\s*""").find(itemContent)
            val numberStr = numberMatch?.groupValues?.getOrNull(1) ?: ""
            val itemText = numberMatch?.let { 
                val startIndex = (it.range.last + 1).coerceAtMost(itemContent.length)
                itemContent.substring(startIndex)
            } ?: itemContent
            
            val startPadding = 4f * density.density
            val markerEndPadding = 4f * density.density
            
            val textSizePx = with(density) { bodyMediumSize.toPx() }
            val boldPaint = PaintCache.getPaint(textColor, textSizePx, boldTypeface)
            val textPaint = PaintCache.getTextPaint(
                textColor,
                textSizePx,
                normalTypeface,
                calculateCanvasLetterSpacingEm(bodyMediumSize, globalLetterSpacingSp)
            )
            
            // 测量标记宽度
            val markerWidth = boldPaint.measureText("$numberStr.")
            val contentX = startPadding + markerWidth + markerEndPadding
            
            // 绘制标记
            val markerY = currentY + textSizePx
            instructions.add(
                DrawInstruction.Text(
                    text = "$numberStr.",
                    x = startPadding,
                    y = markerY,
                    paint = boldPaint,
                    copySeparatorBefore = TextCopySeparator.NONE,
                )
            )
            
            // 使用 StaticLayout 绘制内容（支持自动换行）
            val contentWidth = (safeAvailableWidthPx - contentX.toInt()).coerceAtLeast(1)
            
            val layout = if (node.children.isNotEmpty()) {
                // 处理子节点列表，去除第一个子节点中的列表标记
                val modifiedChildren = node.children.toMutableList()
                val firstChild = modifiedChildren[0]
                val newContent = numberMatch?.let {
                val startIndex = (it.range.last + 1).coerceAtMost(firstChild.content.length)
                firstChild.content.substring(startIndex)
            } ?: firstChild.content
                val newFirstChild = MarkdownNodeStable(firstChild.type, content = newContent, children = firstChild.children)
                modifiedChildren[0] = newFirstChild

                createSafeInlineStaticLayout(
                    children = modifiedChildren,
                    fallbackText = itemText,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    density = density,
                    fontSize = bodyMediumSize,
                    textPaint = textPaint,
                    width = contentWidth,
                    lineSpacingMultiplier = lineSpacingMultiplier,
                    contextLabel = "ordered-list"
                )
            } else {
                LayoutCache.getLayout(
                    itemText,
                    textPaint,
                    contentWidth,
                    textColor,
                    normalTypeface,
                    lineSpacingMultiplier
                )
            }
            instructions.add(
                DrawInstruction.TextLayout(
                    layout = layout,
                    x = contentX,
                    y = currentY,
                    text = layout.text,
                    copySeparatorBefore = TextCopySeparator.SPACE,
                )
            )
            currentY += layout.height
            maxWidth = maxOf(maxWidth, calculateActualWidth(layout, contentX, safeAvailableWidthPx))
            
            // 最后一个节点不添加底部间距
            if (!isLastNode) {
                currentY += 2f * density.density
            }
        }
        
        MarkdownProcessorType.UNORDERED_LIST -> {
            val itemContent = content.trimAll()
            val markerMatch = Regex("""^[-*+]\s+""").find(itemContent)
            val itemText = markerMatch?.let { 
                val startIndex = (it.range.last + 1).coerceAtMost(itemContent.length)
                itemContent.substring(startIndex)
            } ?: itemContent
            
            val startPadding = 4f * density.density
            val markerEndPadding = 4f * density.density
            
            val textSizePx = with(density) { bodyMediumSize.toPx() }
            val textPaint = PaintCache.getTextPaint(
                textColor,
                textSizePx,
                normalTypeface,
                calculateCanvasLetterSpacingEm(bodyMediumSize, globalLetterSpacingSp)
            )
            val markerPaint = PaintCache.getPaint(textColor, textSizePx, normalTypeface)
            
            // 测量标记宽度
            val markerWidth = markerPaint.measureText("•")
            val contentX = startPadding + markerWidth + markerEndPadding

            // 使用 StaticLayout 绘制内容（支持自动换行）
            val contentWidth = (safeAvailableWidthPx - contentX.toInt()).coerceAtLeast(1)
            
            val layout = if (node.children.isNotEmpty()) {
                // 处理子节点列表，去除第一个子节点中的列表标记
                val modifiedChildren = node.children.toMutableList()
                val firstChild = modifiedChildren[0]
                val newContent = markerMatch?.let {
                val startIndex = (it.range.last + 1).coerceAtMost(firstChild.content.length)
                firstChild.content.substring(startIndex)
            } ?: firstChild.content
                val newFirstChild = MarkdownNodeStable(firstChild.type, content = newContent, children = firstChild.children)
                modifiedChildren[0] = newFirstChild

                createSafeInlineStaticLayout(
                    children = modifiedChildren,
                    fallbackText = itemText,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    density = density,
                    fontSize = bodyMediumSize,
                    textPaint = textPaint,
                    width = contentWidth,
                    lineSpacingMultiplier = lineSpacingMultiplier,
                    contextLabel = "unordered-list"
                )
            } else {
                LayoutCache.getLayout(
                    itemText,
                    textPaint,
                    contentWidth,
                    textColor,
                    normalTypeface,
                    lineSpacingMultiplier
                )
            }

            // 使用首行真实基线定位圆点，避免不同字体/字重下出现垂直漂移
            val markerY = currentY + layout.getLineBaseline(0)
            instructions.add(
                DrawInstruction.Text(
                    text = "•",
                    x = startPadding,
                    y = markerY,
                    paint = markerPaint,
                    copySeparatorBefore = TextCopySeparator.NONE,
                )
            )
            instructions.add(
                DrawInstruction.TextLayout(
                    layout = layout,
                    x = contentX,
                    y = currentY,
                    text = layout.text,
                    copySeparatorBefore = TextCopySeparator.SPACE,
                )
            )
            currentY += layout.height
            maxWidth = maxOf(maxWidth, calculateActualWidth(layout, contentX, safeAvailableWidthPx))
            
            // 最后一个节点不添加底部间距
            if (!isLastNode) {
                currentY += 2f * density.density
            }
        }
        
        MarkdownProcessorType.PLAIN_TEXT -> {
            if (content.trimAll().isEmpty()) return LayoutResult(0f, 0f, emptyList())
            
            val textSizePx = with(density) { bodyMediumSize.toPx() }
            val textPaint = PaintCache.getTextPaint(
                textColor,
                textSizePx,
                normalTypeface,
                calculateCanvasLetterSpacingEm(bodyMediumSize, globalLetterSpacingSp)
            )

            val trimmedContent: CharSequence =
                if (node.children.isNotEmpty()) {
                    try {
                        buildSpannableFromChildren(
                            children = node.children,
                            textColor = textColor,
                            primaryColor = primaryColor,
                            density = density,
                            fontSize = bodyMediumSize
                        ).trimAllPreservingSpans()
                    } catch (t: Throwable) {
                        AppLogger.w(TAG, "Inline markdown layout failed in plain-text, fallback to raw text", t)
                        content.trimAll()
                    }
                } else {
                    content.trimAll()
                }

            val paragraphs = splitPlainTextParagraphs(trimmedContent)
            if (paragraphs.isEmpty()) return LayoutResult(0f, 0f, emptyList())

            val paragraphBreakHeight = with(density) { DEFAULT_PARAGRAPH_BREAK_DP.dp.toPx() }
            val paragraphSpacingPx = with(density) { globalParagraphSpacingDp.dp.toPx() }

            paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                val layout =
                    if (
                        paragraphs.size == 1 &&
                            paragraph is String &&
                            !disableLayoutCache
                    ) {
                        LayoutCache.getLayout(
                            paragraph,
                            textPaint,
                            safeAvailableWidthPx,
                            textColor,
                            normalTypeface,
                            lineSpacingMultiplier
                        )
                    } else if (paragraphs.size == 1 && disableLayoutCache) {
                        val tail = typewriterTailAlpha.coerceIn(0f, 1f)
                        if (tail < 0.999f && paragraph.isNotEmpty()) {
                            val spannable = SpannableStringBuilder(paragraph)
                            val lastIndex = spannable.length - 1
                            val fadedColor = textColor.copy(alpha = textColor.alpha * tail)
                            spannable.setSpan(
                                ForegroundColorSpan(fadedColor.toArgb()),
                                lastIndex,
                                lastIndex + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            createStaticLayout(spannable, textPaint, safeAvailableWidthPx, lineSpacingMultiplier)
                        } else {
                            createStaticLayout(paragraph, textPaint, safeAvailableWidthPx, lineSpacingMultiplier)
                        }
                    } else {
                        createStaticLayout(paragraph, textPaint, safeAvailableWidthPx, lineSpacingMultiplier)
                    }

                instructions.add(DrawInstruction.TextLayout(layout, 0f, currentY, paragraph))
                currentY += layout.height
                maxWidth = maxOf(maxWidth, calculateActualWidth(layout, 0f, safeAvailableWidthPx))

                if (paragraphIndex < paragraphs.lastIndex) {
                    currentY += paragraphBreakHeight + paragraphSpacingPx
                }
            }
            
            // 最后一个节点不添加底部间距
            if (!isLastNode) {
                currentY += 6f * density.density
            }
        }
        
        else -> {
            // 其他类型暂不处理
        }
    }
    
    return LayoutResult(currentY, maxWidth, instructions)
}

private fun buildSpannableFromChildren(
    children: List<MarkdownNodeStable>,
    textColor: Color,
    primaryColor: Color,
    density: Density? = null,
    fontSize: TextUnit? = null
): SpannableStringBuilder {
    return buildMarkdownInlineSpannableFromChildren(
        children = children,
        textColor = textColor,
        primaryColor = primaryColor,
        density = density,
        fontSize = fontSize
    )
}

/**
 * 单个文本的 Canvas 渲染器（用于引用块等简单场景）
 */
@Composable
private fun SingleTextCanvas(
    text: String,
    textColor: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    density: Density,
    modifier: Modifier = Modifier
) {
    if (text.isEmpty()) return

    val textLayoutSettings = LocalAiMarkdownTextLayoutSettings.current
    val fontFamily = MaterialTheme.typography.bodyMedium.fontFamily
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(resolver, fontFamily, fontWeight) {
        (resolver.resolve(fontFamily = fontFamily, fontWeight = fontWeight).value as? android.graphics.Typeface)
            ?: if (fontWeight == FontWeight.Bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    BoxWithConstraints(modifier = modifier) {
        val localDensity = LocalDensity.current
        val availableWidthPxRaw = with(localDensity) { maxWidth.toPx() }.toInt()
        if (availableWidthPxRaw <= 0) return@BoxWithConstraints
        val availableWidthPx = safeLayoutWidth(availableWidthPxRaw)
        val textSizePx = with(localDensity) { fontSize.toPx() }
        val letterSpacingEm = calculateCanvasLetterSpacingEm(fontSize, textLayoutSettings.letterSpacingSp)
        val lineSpacingMultiplier = calculateCanvasLineSpacingMultiplier(textLayoutSettings.lineHeightMultiplier)

        val textPaint = remember(textColor, textSizePx, typeface, letterSpacingEm) {
            PaintCache.getTextPaint(textColor, textSizePx, typeface, letterSpacingEm)
        }

        val layout = remember(text, textPaint, availableWidthPx, textColor, typeface, lineSpacingMultiplier) {
            LayoutCache.getLayout(
                text,
                textPaint,
                availableWidthPx,
                textColor,
                typeface,
                lineSpacingMultiplier
            )
        }
        
        val totalHeight = layout.height.toFloat()
        val maxHeightPx = minOf(MAX_CANVAS_HEIGHT_PX, MAX_COMPOSE_CONSTRAINT_HEIGHT_PX)
        val clampedHeightDp = with(localDensity) {
            totalHeight.coerceIn(0f, maxHeightPx).toDp()
        }

        val safeText = remember(text) {
            if (text.length > FALLBACK_MAX_TEXT_CHARS) {
                text.substring(0, FALLBACK_MAX_TEXT_CHARS)
            } else {
                text
            }
        }

        SafeMeasureOrFallback(
            fallback = {
                Text(
                    text = safeText,
                    color = textColor,
                    maxLines = 200,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(clampedHeightDp)
                    .semantics {
                        contentDescription = text
                    }
            ) {
                drawIntoCanvas { canvas ->
                    // 获取可见区域
                    val clipBounds = android.graphics.Rect()
                    canvas.nativeCanvas.getClipBounds(clipBounds)
                    
                    // 判断是否在可见区域内
                    if (totalHeight >= clipBounds.top && 0f <= clipBounds.bottom) {
                        drawInlineCodeBackgrounds(layout, canvas.nativeCanvas)
                        layout.draw(canvas.nativeCanvas)
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeMeasureOrFallback(
    fallback: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(
        content = {
            Box { content() }
            Box { fallback() }
        }
    ) { measurables, constraints ->
        val primary = measurables[0]
        val fallbackMeasurable = measurables[1]
        try {
            val placeable = primary.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Markdown renderer measure failed, fallback to text", t)
            val placeable = fallbackMeasurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}

/**
 * 判断标题级别
 */
private fun determineHeaderLevel(content: String): Int {
    val headerPrefix = content.takeWhile { it == '#' }
    return headerPrefix.length.coerceIn(1, 6)
}

/**
 * 直接创建 StaticLayout (用于Spannable, 不走缓存)
 */
/** 提取LaTeX内容，移除各种分隔符 */
private fun extractLatexContent(content: String): String {
    return when {
        content.startsWith("$$") && content.endsWith("$$") -> content.removeSurrounding("$$")
        content.startsWith("\\[") && content.endsWith("\\]") -> content.removeSurrounding("\\[", "\\]")
        content.startsWith("$") && content.endsWith("$") -> content.removeSurrounding("$")
        content.startsWith("\\(") && content.endsWith("\\)") -> content.removeSurrounding("\\(", "\\)")
        else -> content
    }
}

private fun createStaticLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Int,
    lineSpacingMultiplier: Float
): StaticLayout {
    val safeWidth = safeLayoutWidth(width)
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(
            text,
            paint,
            safeWidth,
            android.text.Layout.Alignment.ALIGN_NORMAL,
            lineSpacingMultiplier,
            0f,
            false
        )
    }
}
