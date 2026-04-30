package com.ai.assistance.operit.ui.features.packages.market

sealed interface PluginCreationIntent {
    val requirement: String

    fun toPrompt(): String

    data class Fresh(
        override val requirement: String
    ) : PluginCreationIntent {
        override fun toPrompt(): String {
            return buildString {
                appendLine("请根据沙盒包开发的dev工具包以及operit editor工具包，创作一个符合以下需求的工具并导入。需求：")
                append(requirement.trim())
            }
        }
    }

    data class Continue(
        val projectId: String,
        val runtimePackageId: String,
        val parentNodeIds: List<String>,
        override val requirement: String
    ) : PluginCreationIntent {
        override fun toPrompt(): String {
            return buildString {
                appendLine("请你激活operit editor，然后查找沙盒包${runtimePackageId}的所在位置。在手机下载/Operit/dev_package/${runtimePackageId}/里面二次开发该文件，根据需求进行修改，然后进行安装测试。示范里面最好做两个，第二个用自定义布局。")
                appendLine("需求:")
                append(requirement.trim())
            }
        }
    }

    data class Merge(
        val projectId: String,
        val runtimePackageId: String,
        val parentNodeIds: List<String>,
        override val requirement: String
    ) : PluginCreationIntent {
        override fun toPrompt(): String {
            val parentLabel = parentNodeIds.joinToString(", ").ifBlank { "-" }
            return buildString {
                appendLine("请你激活operit editor，然后查找沙盒包${runtimePackageId}的所在位置。在手机下载/Operit/dev_package/${runtimePackageId}/里面进行合并开发，根据需求完成修改，然后进行安装测试。示范里面最好做两个，第二个用自定义布局。")
                appendLine("本次合并父节点 IDs: $parentLabel")
                appendLine("需求:")
                append(requirement.trim())
            }
        }
    }
}
