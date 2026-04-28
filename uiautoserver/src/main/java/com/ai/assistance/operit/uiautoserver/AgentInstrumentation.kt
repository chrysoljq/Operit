package com.ai.assistance.operit.uiautoserver

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ai.assistance.operit.provider.IUiAutomationService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AgentInstrumentation : Instrumentation() {
    companion object {
        private const val TAG = "AgentInstrumentation"
        private const val MAX_NODE_DEPTH = 30
    }

    private var device: UiDevice? = null
    private val automation: UiAutomation by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.i(
                TAG,
                "Initializing UiAutomation with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES"
            )
            // 不要压制现有无障碍服务
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        } else {
            Log.i(TAG, "Initializing UiAutomation with default flags")
            uiAutomation
        }
    }

    private val isRunning = AtomicBoolean(true)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "UIAutomation Server 守护进程已启动！")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Configurator.getInstance().uiAutomationFlags =
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            Log.i(
                TAG,
                "Configured UiAutomator with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES"
            )
        }

        device = UiDevice.getInstance(this)

        val binder = object : IUiAutomationService.Stub() {
            override fun getPageInfo(displayIdStr: String?): String {
                return this@AgentInstrumentation.getPageInfo(displayIdStr)
            }

            override fun clickElement(
                resourceId: String?,
                className: String?,
                desc: String?,
                boundsStr: String?,
                displayIdStr: String?,
                partialMatch: Boolean,
                index: Int
            ): Boolean {
                return this@AgentInstrumentation.clickElement(
                    resourceId, className, desc, boundsStr, displayIdStr, partialMatch, index
                )
            }

            override fun tap(x: Int, y: Int, displayIdStr: String?): Boolean {
                return device?.click(x, y) ?: false
            }

            override fun swipe(
                startX: Int, startY: Int, endX: Int, endY: Int,
                durationMs: Int, displayIdStr: String?
            ): Boolean {
                val steps = (durationMs / 5).coerceAtLeast(10)
                return device?.swipe(startX, startY, endX, endY, steps) ?: false
            }

            override fun setInputText(text: String?): Boolean {
                if (text == null) return false
                return try {
                    // 1. 先尝试对当前焦点控件直接注入文字（API 18+，无需 IME）
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    val focusedNode = automation.rootInActiveWindow
                        ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focusedNode != null) {
                        val result = focusedNode.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT, args
                        )
                        focusedNode.recycle()
                        if (result) return true
                    }

                    // 2. 降级：通过 UiDevice 清空并输入（依赖 IME，中文可能乱码）
                    device?.let { d ->
                        d.pressKeyCode(android.view.KeyEvent.KEYCODE_CTRL_LEFT,
                            android.view.KeyEvent.META_CTRL_ON)
                        d.pressKeyCode(android.view.KeyEvent.KEYCODE_A)
                        d.setOrientationNatural() // 确保 IME 状态正常
                        // UiDevice 没有直接 setText，用 shell input 兜底
                        val escaped = text.replace(" ", "%s")
                            .replace("'", "\\'")
                        val result = d.executeShellCommand("input text '$escaped'")
                        return true // executeShellCommand 只要不崩溃即视为提交成功
                    } ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "setInputText error", e)
                    false
                }
            }

            override fun pressKey(keyCodeStr: String?): Boolean {
                val keyCode = keyCodeStr?.toIntOrNull() ?: return false
                return device?.pressKeyCode(keyCode) ?: false
            }

            override fun suicide() {
                isRunning.set(false)
            }
        }

        // 发送 Binder 给主应用
        val intent = Intent("com.ai.assistance.operit.action.UIAUTO_BINDER_READY")
            .setPackage("com.ai.assistance.operit")
        intent.putExtras(Bundle().apply { putBinder("binder", binder) })
        context.sendBroadcast(intent)
        Log.i(TAG, "已发送 UIAUTO_BINDER_READY 广播给主应用")

        try {
            try { automation.rootInActiveWindow?.recycle() } catch (e: Exception) {}

            while (isRunning.get()) {
                Thread.sleep(5000)

                val uiAlive = try {
                    automation.rootInActiveWindow?.recycle()
                    true
                } catch (e: Exception) {
                    Log.i(TAG, "UiAutomation 连接断开，主进程可能已退出")
                    false
                }

                if (!uiAlive) {
                    isRunning.set(false)
                    break
                }

                val pidOutput = try {
                    device?.executeShellCommand("pidof com.ai.assistance.operit")
                } catch (e: Exception) {
                    null
                }
                if (pidOutput.isNullOrBlank()) {
                    Log.i(TAG, "主进程 com.ai.assistance.operit 已退出，Server 自动销毁...")
                    isRunning.set(false)
                }
            }

            Log.d(TAG, "守护进程正常退出...")
            finish(android.app.Activity.RESULT_OK, Bundle())
        } catch (e: InterruptedException) {
            Log.e(TAG, "守护进程被中断", e)
        } finally {
            Log.d(TAG, "守护进程彻底退出")
        }
    }

    private fun getPageInfo(displayIdStr: String?): String {
        // 包名：获取所有窗口并寻找焦点。不限制 TYPE_APPLICATION 以兼容部分 ROM 的 Overlay 窗口。
        val windows = try { automation.windows } catch (e: Exception) { emptyList() }
        
        // 调试用：打印所有窗口状态
        windows.forEach { 
            Log.v(TAG, "Window type=${it.type} isFocused=${it.isFocused} pkg=${it.root?.packageName}")
        }

        val targetWindow = windows.find { it.isFocused } 
            ?: windows.maxByOrNull { it.layer }

        val rootNode = targetWindow?.root
            ?: (if (!displayIdStr.isNullOrEmpty() && Build.VERSION.SDK_INT >= 30) {
                val dId = displayIdStr.toIntOrNull() ?: 0
                windows.find { it.displayId == dId }?.root
            } else null)
            ?: automation.rootInActiveWindow

        if (rootNode == null) return "{}"

        val simplifiedLayout: JSONObject
        val currentPackage: String
        try {
            simplifiedLayout = convertNodeToSimplified(rootNode)
            currentPackage = rootNode.packageName?.toString() ?: "Unknown"
        } finally {
            rootNode.recycle()
        }

        // Activity 名：使用兼容性更好的全量 dumpsys window (内存中 grep)
        val activityName = try {
            val output = device?.executeShellCommand("dumpsys window") ?: ""
            Log.d(TAG, "dumpsys window output length: ${output.length}")
            
            // 使用 lineSequence 延迟处理，在大文本下内存更友好
            output.lineSequence()
                .filter { it.contains("mCurrentFocus") && it.contains("/") }
                .mapNotNull { line ->
                    Regex("([a-zA-Z0-9_.]+\\.[a-zA-Z0-9_.]+)/([^\\s}]+)")
                        .find(line)?.groupValues
                }
                .lastOrNull { it[1] != "null" }
                ?.get(2) ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "获取 Activity 名失败", e)
            "Unknown"
        }

        return JSONObject().apply {
            put("packageName", currentPackage)
            put("activityName", activityName)
            put("uiElements", simplifiedLayout)
        }.toString()
    }

    private fun convertNodeToSimplified(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val boundsString = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

        val children = JSONArray()
        // 超过最大深度时截断，防止 StackOverflowError
        if (depth < MAX_NODE_DEPTH) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    children.put(convertNodeToSimplified(child, depth + 1))
                } finally {
                    child.recycle()
                }
            }
        }

        return JSONObject().apply {
            put("className", node.className?.toString()?.substringAfterLast('.'))
            put("text", node.text?.toString()?.replace("&#10;", "\n"))
            put("contentDesc", node.contentDescription?.toString())
            put("resourceId", node.viewIdResourceName)
            put("bounds", boundsString)
            put("isClickable", node.isClickable)
            put("children", children)
        }
    }

    private fun clickElement(
        resourceId: String?,
        className: String?,
        desc: String?,
        boundsStr: String?,
        displayIdStr: String?,
        partialMatch: Boolean,
        index: Int
    ): Boolean {
        if (device == null) return false

        return try {
            var selector = if (partialMatch) {
                when {
                    resourceId != null -> By.res(
                        java.util.regex.Pattern.compile(
                            ".*${java.util.regex.Pattern.quote(resourceId)}.*"
                        )
                    )
                    desc != null -> By.descContains(desc)
                    className != null -> By.clazz(
                        java.util.regex.Pattern.compile(
                            ".*${java.util.regex.Pattern.quote(className)}.*"
                        )
                    )
                    else -> return false
                }
            } else {
                when {
                    resourceId != null -> By.res(resourceId)
                    desc != null -> By.desc(desc)
                    // 精确匹配用全限定类名的模糊匹配，避免短类名无法命中
                    className != null -> By.clazz(
                        java.util.regex.Pattern.compile(
                            ".*\\.?${java.util.regex.Pattern.quote(className)}$"
                        )
                    )
                    else -> return false
                }
            }

            displayIdStr?.toIntOrNull()?.let { selector = selector.displayId(it) }

            device!!.wait(Until.hasObject(selector), 3000)
            val uiObj = device!!.findObjects(selector).getOrNull(index)
            if (uiObj != null) {
                uiObj.click()
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "clickElement error", e)
            false
        }
    }
}
