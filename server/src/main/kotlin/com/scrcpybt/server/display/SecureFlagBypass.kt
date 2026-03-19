package com.scrcpybt.server.display

import com.scrcpybt.common.util.Logger

/**
 * FLAG_SECURE 绕过器
 *
 * 可选功能，用于捕获受保护应用的内容（需要 root 权限）。
 *
 * ### 背景
 * 某些应用（银行、流媒体、DRM 保护）在其窗口上设置 FLAG_SECURE 标志，
 * 导致屏幕捕获时这些窗口显示为黑色矩形。这是防止截屏和录屏的安全特性。
 *
 * ### 绕过方法（需要 ROOT 权限）
 * 1. Settings.Global "secure_flag_override"（部分 ROM 支持）
 * 2. `wm` shell 命令禁用安全标志检查
 * 3. 直接操作系统属性
 *
 * ### 安全警告
 * - 禁用 FLAG_SECURE 会移除重要的安全保护
 * - 银行、支付和密码应用依赖此标志保护敏感信息
 * - 仅在特定用例下启用（例如流媒体应用）
 * - 断开连接时会自动禁用绕过
 *
 * ### 注意事项
 * - 本组件**不使用** LSPosed/Xposed 框架钩子
 * - 如需使用 Xposed 方案，用户应安装单独的模块（如 Enable Screenshot）
 *
 * @see PowerControl
 */
class SecureFlagBypass {

    companion object {
        private const val TAG = "SecureFlagBypass"
    }

    /** 绕过是否处于活动状态 */
    private var bypassActive = false

    /** 使用的绕过方法 */
    private var bypassMethod: BypassMethod? = null

    /**
     * 绕过方法枚举
     */
    enum class BypassMethod {
        /** Settings.Global 方式 */
        SETTINGS_GLOBAL,
        /** wm 命令方式 */
        WM_COMMAND,
        /** 系统属性方式 */
        SYSTEM_PROPERTY
    }

    /**
     * 尝试启用 FLAG_SECURE 绕过
     *
     * 按顺序尝试多种方法，使用第一个成功的。
     *
     * @return 如果成功启用绕过则返回 true
     */
    fun enable(): Boolean {
        if (bypassActive) {
            Logger.d(TAG, "FLAG_SECURE bypass already active")
            return true
        }

        Logger.i(TAG, "Attempting to enable FLAG_SECURE bypass")

        // 方法 1: Settings.Global 方式
        if (trySettingsGlobal(true)) {
            bypassMethod = BypassMethod.SETTINGS_GLOBAL
            bypassActive = true
            Logger.i(TAG, "FLAG_SECURE bypass enabled via Settings.Global")
            return true
        }

        // 方法 2: wm disable-secure-flag（部分定制 ROM 可用）
        if (tryWmCommand(true)) {
            bypassMethod = BypassMethod.WM_COMMAND
            bypassActive = true
            Logger.i(TAG, "FLAG_SECURE bypass enabled via wm command")
            return true
        }

        // 方法 3: 系统属性（需要 root）
        if (trySystemProperty(true)) {
            bypassMethod = BypassMethod.SYSTEM_PROPERTY
            bypassActive = true
            Logger.i(TAG, "FLAG_SECURE bypass enabled via system property")
            return true
        }

        Logger.w(TAG, "Failed to enable FLAG_SECURE bypass - " +
                "consider using LSPosed + Enable Screenshot module for per-app bypass")
        return false
    }

    /**
     * 禁用 FLAG_SECURE 绕过并恢复原始状态
     */
    fun disable() {
        if (!bypassActive) return

        Logger.i(TAG, "Disabling FLAG_SECURE bypass")

        when (bypassMethod) {
            BypassMethod.SETTINGS_GLOBAL -> trySettingsGlobal(false)
            BypassMethod.WM_COMMAND -> tryWmCommand(false)
            BypassMethod.SYSTEM_PROPERTY -> trySystemProperty(false)
            null -> {}
        }

        bypassActive = false
        bypassMethod = null
        Logger.i(TAG, "FLAG_SECURE bypass disabled")
    }

    /** 绕过是否处于活动状态 */
    val isActive: Boolean get() = bypassActive

    /**
     * 通过 Settings.Global 切换 FLAG_SECURE 绕过
     *
     * 部分 ROM（LineageOS、定制 AOSP）支持此设置。
     *
     * @param enable 是否启用
     * @return 成功返回 true
     */
    private fun trySettingsGlobal(enable: Boolean): Boolean {
        return try {
            val value = if (enable) "1" else "0"
            // 此设置在部分定制 ROM 上可用
            val result = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "settings put global force_disable_secure_flag $value")
            )
            result.waitFor()
            if (result.exitValue() == 0) {
                // 验证是否实际设置成功
                val verify = Runtime.getRuntime().exec(
                    arrayOf("sh", "-c", "settings get global force_disable_secure_flag")
                )
                val output = verify.inputStream.bufferedReader().readText().trim()
                verify.waitFor()
                output == value
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Settings.Global approach failed: ${e.message}")
            false
        }
    }

    /**
     * 通过 wm shell 命令切换 FLAG_SECURE 绕过
     *
     * @param enable 是否启用
     * @return 成功返回 true
     */
    private fun tryWmCommand(enable: Boolean): Boolean {
        return try {
            val cmd = if (enable) {
                "wm disable-secure-flag 1"
            } else {
                "wm disable-secure-flag 0"
            }
            val result = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            result.waitFor()
            result.exitValue() == 0
        } catch (e: Exception) {
            Logger.d(TAG, "wm command approach failed: ${e.message}")
            false
        }
    }

    /**
     * 通过系统属性切换 FLAG_SECURE 绕过
     *
     * 需要 root 权限。首先尝试使用 su 命令，如果失败则尝试直接执行
     * （我们可能已经通过 app_process 以 root 身份运行）。
     *
     * @param enable 是否启用
     * @return 成功返回 true
     */
    private fun trySystemProperty(enable: Boolean): Boolean {
        return try {
            val value = if (enable) "1" else "0"
            // 尝试使用 su 获取 root 权限
            val result = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "setprop persist.sys.disable_secure_flag $value")
            )
            result.waitFor()
            if (result.exitValue() == 0) {
                Logger.d(TAG, "System property set: persist.sys.disable_secure_flag=$value")
                true
            } else {
                // 尝试不使用 su（我们可能已经通过 app_process 以 root 运行）
                val result2 = Runtime.getRuntime().exec(
                    arrayOf("sh", "-c", "setprop persist.sys.disable_secure_flag $value")
                )
                result2.waitFor()
                result2.exitValue() == 0
            }
        } catch (e: Exception) {
            Logger.d(TAG, "System property approach failed: ${e.message}")
            false
        }
    }
}
