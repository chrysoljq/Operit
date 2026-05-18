package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.ui.geometry.Offset

data class MarkdownTextSelectionRequest(
    val id: Long,
    val positionInWindow: Offset,
)
