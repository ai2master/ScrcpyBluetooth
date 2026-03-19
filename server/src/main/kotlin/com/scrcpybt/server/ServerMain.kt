package com.scrcpybt.server

import android.os.Looper
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import com.scrcpybt.server.control.ScreenUnlocker
import com.scrcpybt.server.util.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * ScrcpyBluetooth 服务端入口程序
 *
 * 本类是通过 app_process 启动的服务端主入口，负责解析命令行参数并启动服务器。
 *
 * ### 启动方式
 * ```bash
 * CLASSPATH=/path/to/server.jar app_process / com.scrcpybt.server.ServerMain [args]
 * ```
 *
 * ### 命令行参数
 * - `--transport=bluetooth|usb` - 传输类型（蓝牙 RFCOMM 或 USB ADB）
 * - `--socket-name=NAME` - Abstract socket 名称（用于 USB ADB 模式）
 * - `--max-fps=N` - 最大帧率限制
 * - `--virtual-display=true|false` - 是否使用虚拟显示器模式（Android 10+，类似 RDP 的隐私模式）
 * - `--start-app=COMPONENT` - 在虚拟显示器上启动的应用组件（防止黑屏）
 * - `--bypass-secure-flag=true|false` - 绕过 FLAG_SECURE 以捕获受保护应用（需要 root）
 * - `--force-unlock=true|false` - 启用强制解锁功能（Android <10 的降级方案）
 * - `--credential-type=none|pin|password|pattern` - 锁屏凭据类型
 * - `--credential=VALUE` - 锁屏凭据值（仅本地存储）
 * - `--share-target=COMPONENT` - 分享转发目标组件（包名/Activity）
 *
 * @see Server
 */
object ServerMain {

    private const val TAG = "ServerMain"

    /**
     * 服务端主入口函数
     *
     * 解析命令行参数，配置服务器选项，然后启动主服务循环。
     * 如果发生致命错误，将以退出码 1 终止进程。
     *
     * @param args 命令行参数数组
     */
    @JvmStatic
    fun main(args: Array<String>) {
        Logger.i(TAG, "ScrcpyBluetooth Server starting...")

        // Bypass hidden API restrictions
        HiddenApiBypass.exempt()

        // Parse arguments
        var transport = TransportType.BLUETOOTH_RFCOMM
        var socketName: String? = null
        var maxFps = 30
        var virtualDisplay = false
        var startApp: String? = null
        var bypassSecureFlag = false
        var forceUnlock = false
        var credentialType = "none"
        var credential: String? = null
        var shareTarget: String? = null

        for (arg in args) {
            when {
                arg.startsWith("--transport=") -> {
                    transport = TransportType.fromCliName(arg.substring("--transport=".length))
                }
                arg.startsWith("--socket-name=") -> {
                    socketName = arg.substring("--socket-name=".length)
                }
                arg.startsWith("--max-fps=") -> {
                    maxFps = arg.substring("--max-fps=".length).toInt()
                }
                arg.startsWith("--virtual-display=") -> {
                    virtualDisplay = arg.substring("--virtual-display=".length).toBoolean()
                }
                arg.startsWith("--start-app=") -> {
                    startApp = arg.substring("--start-app=".length)
                }
                arg.startsWith("--bypass-secure-flag=") -> {
                    bypassSecureFlag = arg.substring("--bypass-secure-flag=".length).toBoolean()
                }
                arg.startsWith("--force-unlock=") -> {
                    forceUnlock = arg.substring("--force-unlock=".length).toBoolean()
                }
                arg.startsWith("--credential-type=") -> {
                    credentialType = arg.substring("--credential-type=".length)
                }
                arg.startsWith("--credential=") -> {
                    credential = arg.substring("--credential=".length)
                }
                arg.startsWith("--share-target=") -> {
                    shareTarget = arg.substring("--share-target=".length)
                }
            }
        }

        Logger.i(TAG, "Transport: $transport, maxFps: $maxFps, " +
                "virtualDisplay: $virtualDisplay, forceUnlock: $forceUnlock")
        if (shareTarget != null) {
            Logger.i(TAG, "Share target: $shareTarget")
        }

        try {
            // 准备主 Looper（Handler 使用所必需）
            Looper.prepareMainLooper()

            val server = Server(transport, socketName, maxFps)

            // 配置虚拟显示器模式（仅 Android 10+ 支持）
            if (virtualDisplay) {
                server.useVirtualDisplay = true
                server.virtualDisplayStartApp = startApp
                server.bypassSecureFlag = bypassSecureFlag
                Logger.i(TAG, "Virtual display mode enabled (RDP-like privacy)")
                if (startApp != null) {
                    Logger.i(TAG, "Start app on virtual display: $startApp")
                }
                if (bypassSecureFlag) {
                    Logger.i(TAG, "FLAG_SECURE bypass enabled")
                }
            }

            // 配置强制解锁功能（Android <10 的降级方案）
            if (forceUnlock) {
                val type = when (credentialType) {
                    "pin" -> ScreenUnlocker.CredentialType.PIN
                    "password" -> ScreenUnlocker.CredentialType.PASSWORD
                    "pattern" -> ScreenUnlocker.CredentialType.PATTERN
                    else -> ScreenUnlocker.CredentialType.NONE
                }
                server.enableForceUnlock(type, credential)
                Logger.i(TAG, "Force unlock configured: type=$credentialType")
            }

            // 配置分享转发目标
            if (shareTarget != null) {
                server.setShareTarget(shareTarget)
            }

            server.run()
        } catch (e: Exception) {
            Logger.e(TAG, "Server fatal error", e)
            exitProcess(1)
        }
    }
}
