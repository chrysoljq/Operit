package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

object WorkspaceAttachmentProcessor {
    fun generateWorkspaceAttachment(workspaceEnv: String?): String {
        val workspaceTag = workspaceEnv?.trim().orEmpty()
        return """
            <workspace_context>
                <enabled>true</enabled>
                <tag>${escapeXml(workspaceTag)}</tag>
            </workspace_context>
        """.trimIndent()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
