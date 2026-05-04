package com.ai.assistance.operit.data.model

data class ChatMessageLocatorPreview(
    val timestamp: Long,
    val sender: String,
    val previewContent: String,
    val contentLength: Int,
    val displayMode: String,
) {
    val resolvedDisplayMode: ChatMessageDisplayMode
        get() =
            runCatching { ChatMessageDisplayMode.valueOf(displayMode) }
                .getOrDefault(ChatMessageDisplayMode.NORMAL)
}
