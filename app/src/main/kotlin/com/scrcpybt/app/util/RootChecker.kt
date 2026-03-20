package com.scrcpybt.app.util

import java.io.File

/**
 * Root 权限检测工具类：检查 Android 设备是否具有 root 访问权限 | Root permission checker for Android devices
 *
 * 核心功能 | Core Functions:
 * - 检测设备是否已 root | Detect if device is rooted
 * - 验证 su 命令是否可执行 | Verify if su command is executable
 * - 支持多种 root 方案检测（Magisk、SuperSU 等）| Support detection of various root solutions (Magisk, SuperSU, etc.)
 *
 * 检测方法 | Detection Methods:
 * 1. 文件系统检测：检查常见 su 二进制文件路径 | File system check: check common su binary paths
 * 2. 命令执行检测：尝试执行 `su -c id` 命令 | Command execution check: try executing `su -c id`
 *
 * 被控端 Root 权限用途 | Root Permission Use Cases on Server Side:
 * - 启动 app_process 服务端进程（需要 root 权限）| Start app_process server process (requires root)
 * - 访问系统 API 实现屏幕捕获（MediaProjection 需要系统权限）| Access system APIs for screen capture
 * - 注入触摸和按键事件（需要 INJECT_EVENTS 权限）| Inject touch and key events (requires INJECT_EVENTS permission)
 * - 执行强制解锁等系统级操作 | Execute system-level operations like force unlock
 *
 * 常见 Root 方案 | Common Root Solutions:
 * - Magisk: /sbin/su, /system/xbin/su | Magisk paths
 * - SuperSU: /system/bin/su, /system/xbin/su | SuperSU paths
 * - LineageOS: /su/bin/su | LineageOS path
 *
 * 注意事项 | Notes:
 * - 此检测方法可能被 root 隐藏工具绕过 | This detection may be bypassed by root hiding tools
 * - 仅用于功能可用性判断，不用于安全验证 | Only for feature availability check, not for security verification
 * - 控制端不需要 root 权限 | Controller side does not require root permission
 *
 * @see com.scrcpybt.server.ServerLauncher
 */
object RootChecker {
    /**
     * 检查设备是否具有 root 权限 | Check if device has root permission
     *
     * 先检查常见 su 路径，若未找到则尝试执行 su 命令验证。| First check common su paths, then try executing su command if not found.
     *
     * @return true 检测到 root 权限，false 未检测到 | true if root detected, false otherwise
     */
    fun isRootAvailable(): Boolean {
        // 常见 su 二进制文件路径列表 | Common su binary file paths
        val paths = arrayOf(
            "/system/bin/su",       // 标准位置 | Standard location
            "/system/xbin/su",      // SuperSU 常用位置 | SuperSU common location
            "/sbin/su",             // Magisk systemless root | Magisk location
            "/data/local/xbin/su",  // 临时安装位置 | Temporary install location
            "/data/local/bin/su",   // 临时安装位置 | Temporary install location
            "/su/bin/su",           // LineageOS root | LineageOS location
            "/system/bin/.ext/.su"  // 隐藏位置 | Hidden location
        )
        // 检查文件是否存在 | Check if files exist
        for (path in paths) {
            if (File(path).exists()) {
                return true
            }
        }
        // 尝试执行命令验证 | Try command execution verification
        return canExecuteSu()
    }

    /**
     * 尝试执行 su 命令验证 root 权限 | Try executing su command to verify root permission
     *
     * 执行 `su -c id` 命令，返回码为 0 表示成功。| Execute `su -c id` command, exit code 0 means success.
     *
     * @return true 成功执行，false 执行失败 | true if executed successfully, false otherwise
     */
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
