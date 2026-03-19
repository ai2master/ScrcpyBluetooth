package com.scrcpybt.app.util

import java.io.File

/**
 * Root 权限检测器：检查设备是否有 root 访问权限
 *
 * 检测方法：
 * 1. 检查常见 su 二进制文件路径
 * 2. 尝试执行 `su -c id` 命令
 *
 * 被控端必须有 root 权限才能：
 * - 启动 app_process 服务端进程
 * - 访问系统 API（屏幕捕获、输入注入）
 * - 执行强制解锁等系统级操作
 */
object RootChecker {
    /**
     * 检查 root 权限是否可用
     *
     * @return true 如果检测到 su 二进制文件或能成功执行 su 命令
     */
    fun isRootAvailable(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/su/bin/su", "/system/bin/.ext/.su"
        )
        for (path in paths) {
            if (File(path).exists()) {
                return true
            }
        }
        return canExecuteSu()
    }

    private fun canExecuteSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
