package com.scrcpybt.server.display

import com.scrcpybt.common.util.Logger

/**
 * 虚拟显示器应用启动器
 *
 * 负责在虚拟显示器上启动 Activity，防止"黑屏"问题。
 *
 * ### 问题背景
 * 当创建虚拟显示器时，它没有配置默认的 Launcher，导致屏幕空白/黑屏。
 * 大多数 Android 系统只为主显示器（display 0）分配启动器。
 * 如果不显式启动一个 Activity，虚拟显示器就没有内容可以渲染。
 *
 * ### 解决方案
 * 创建虚拟显示器后，使用 `am start --display <id>` 命令在虚拟显示器上启动
 * 主屏幕或降级 Activity。我们按优先级顺序尝试多种策略：
 *
 * 1. 用户指定的应用（如果配置了）
 * 2. 系统启动器（HOME intent）
 * 3. 已知的厂商启动器组件
 * 4. 系统设置作为可靠的降级方案（所有设备都有）
 *
 * ### 技术实现
 * - 通过 shell 命令执行（因为以 shell UID 运行）
 * - 使用 `am start --display <id>` 指定目标显示器
 * - 解析命令输出以验证启动是否成功
 *
 * Virtual display launcher that starts activities on virtual displays to prevent
 * black screen issues by trying multiple launcher strategies with fallback options.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see VirtualDisplayManager
 */
class VirtualDisplayLauncher {

    companion object {
        private const val TAG = "VirtualDisplayLauncher"

        /** 常见的启动器组件列表（按流行度排序） */
        private val LAUNCHER_CANDIDATES = listOf(
            // AOSP/Pixel 启动器
            "com.google.android.apps.nexuslauncher/.NexusLauncherActivity",
            // 通用 Android 启动器
            "com.android.launcher3/.Launcher",
            // 三星 One UI
            "com.sec.android.app.launcher/.activities.LauncherActivity",
            // 小米 MIUI
            "com.miui.home/.launcher.Launcher",
            // OPPO/Realme
            "com.oppo.launcher/.Launcher",
            // Vivo
            "com.bbk.launcher2/.Launcher",
            // 华为
            "com.huawei.android.launcher/.Launcher",
            // 一加
            "net.oneplus.launcher/.Launcher"
        )

        /** 可靠的降级 Activity（所有 Android 设备都有） */
        private val FALLBACK_ACTIVITIES = listOf(
            "com.android.settings/.Settings",
            "com.android.settings/.homepage.SettingsHomepageActivity"
        )
    }

    /**
     * 在指定虚拟显示器上启动 Activity
     *
     * 应该在虚拟显示器创建后立即调用，以避免黑屏问题。
     *
     * ### 启动策略（按优先级）
     * 1. 用户指定的应用（如果配置了）
     * 2. 系统主屏幕（HOME intent）
     * 3. 已知的厂商启动器组件
     * 4. 系统设置作为降级方案
     *
     * @param displayId 虚拟显示器 ID
     * @param preferredApp 可选的用户指定应用组件（格式："com.example/.MainActivity"）
     * @return 如果成功启动了 Activity 则返回 true
     */
    fun launchOnDisplay(displayId: Int, preferredApp: String? = null): Boolean {
        if (displayId < 0) {
            Logger.e(TAG, "Invalid display ID: $displayId")
            return false
        }

        Logger.i(TAG, "Launching activity on virtual display $displayId")

        // 1. 优先尝试用户指定的应用
        if (preferredApp != null) {
            if (launchComponent(displayId, preferredApp)) {
                Logger.i(TAG, "Launched preferred app on display $displayId: $preferredApp")
                return true
            }
            Logger.w(TAG, "Failed to launch preferred app: $preferredApp")
        }

        // 2. 尝试启动主屏幕 Intent (ACTION_MAIN + CATEGORY_HOME)
        if (launchHomeIntent(displayId)) {
            Logger.i(TAG, "Launched home intent on display $displayId")
            return true
        }

        // 3. 尝试已知的启动器组件
        for (launcher in LAUNCHER_CANDIDATES) {
            if (launchComponent(displayId, launcher)) {
                Logger.i(TAG, "Launched launcher on display $displayId: $launcher")
                return true
            }
        }

        // 4. 尝试降级 Activity
        for (fallback in FALLBACK_ACTIVITIES) {
            if (launchComponent(displayId, fallback)) {
                Logger.i(TAG, "Launched fallback activity on display $displayId: $fallback")
                return true
            }
        }

        Logger.w(TAG, "Failed to launch any activity on display $displayId")
        return false
    }

    /**
     * 在指定显示器上启动特定组件
     *
     * 使用 `am start --display <id> -n <component>` 命令。
     *
     * @param displayId 显示器 ID
     * @param component 组件名称（格式："包名/Activity"）
     * @return 启动成功返回 true
     */
    private fun launchComponent(displayId: Int, component: String): Boolean {
        return try {
            val cmd = "am start --display $displayId -n $component"
            Logger.d(TAG, "Executing: $cmd")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                // am start 即使找不到组件也返回 0，需要检查输出
                if (output.contains("Error") || output.contains("Exception")) {
                    Logger.d(TAG, "am start returned error output: $output")
                    false
                } else {
                    true
                }
            } else {
                val error = process.errorStream.bufferedReader().readText().trim()
                Logger.d(TAG, "am start failed ($exitCode): $error")
                false
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Failed to launch $component: ${e.message}")
            false
        }
    }

    /**
     * 在指定显示器上启动主屏幕
     *
     * 使用隐式 HOME Intent (ACTION_MAIN + CATEGORY_HOME)。
     *
     * @param displayId 显示器 ID
     * @return 启动成功返回 true
     */
    private fun launchHomeIntent(displayId: Int): Boolean {
        return try {
            val cmd = "am start --display $displayId " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.HOME"
            Logger.d(TAG, "Executing: $cmd")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.contains("Error") || output.contains("Warning")) {
                    Logger.d(TAG, "Home intent had issues: $output")
                    false
                } else {
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Failed to launch home intent: ${e.message}")
            false
        }
    }

    /**
     * 查询系统的默认启动器包
     *
     * 使用 pm resolve-activity 命令查找默认的主屏幕处理器。
     *
     * @return 组件名称（如果找到），否则返回 null
     */
    fun getDefaultLauncher(): String? {
        return try {
            // 使用 pm resolve-activity 查找默认主屏幕处理器
            val cmd = "pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // 从输出中解析 "name=com.example/.LauncherActivity"
            val nameRegex = Regex("name=([\\w.]+/[\\w.]+)")
            val match = nameRegex.find(output)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Logger.d(TAG, "Failed to query default launcher: ${e.message}")
            null
        }
    }
}
