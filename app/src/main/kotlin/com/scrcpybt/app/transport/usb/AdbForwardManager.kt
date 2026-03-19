package com.scrcpybt.app.transport.usb

import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADB 端口转发管理器：管理 USB 传输的 ADB forward/reverse 隧道
 *
 * 工作原理：
 * - 设置端口转发：`adb forward tcp:PORT localabstract:scrcpy_bt`
 * - 控制端通过 localhost:PORT 连接，数据通过 USB 线缆隧道传输到被控端的抽象 Unix Socket
 * - 无需 IP 网络，纯粹基于 USB 和 ADB 协议
 *
 * 抽象 Unix Socket 说明：
 * - Android 特有的 IPC 机制，不依赖文件系统
 * - 名称以 '@' 开头（如 @scrcpy_bt）
 * - 自动清理，无需手动删除文件
 *
 * 使用场景：
 * - 控制端和被控端通过 USB 线缆直连
 * - 中继端设备同时连接控制端（蓝牙）和被控端（USB）
 */
class AdbForwardManager {
    companion object {
        private const val TAG = "AdbForward"
        /** 默认转发端口 */
        private const val DEFAULT_PORT = 27183
    }

    /** 本地转发端口 */
    private var localPort = DEFAULT_PORT
    /** 转发是否已激活 */
    private var forwardActive = false

    /**
     * 设置 ADB 端口转发（从 TCP 端口到抽象 Socket）
     *
     * 必须在能够通过 ADB 访问被控设备的设备上运行
     * （通常是控制端或中继端设备）
     *
     * @return true 如果转发设置成功
     */
    fun setupForward(): Boolean {
        return setupForward(ProtocolConstants.ADB_SOCKET_NAME, localPort)
    }

    fun setupForward(socketName: String, port: Int): Boolean {
        this.localPort = port
        return try {
            val command = "adb forward tcp:$port localabstract:$socketName"
            val process = Runtime.getRuntime().exec(command.split(" ").toTypedArray())
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                forwardActive = true
                Logger.i(TAG, "Forward set up: tcp:$port -> localabstract:$socketName")
                true
            } else {
                val error = readStream(process)
                Logger.e(TAG, "Forward failed: $error")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to set up forward", e)
            false
        }
    }

    /**
     * 移除 ADB 端口转发
     */
    fun removeForward() {
        if (!forwardActive) return
        try {
            val command = "adb forward --remove tcp:$localPort"
            val process = Runtime.getRuntime().exec(command.split(" ").toTypedArray())
            process.waitFor()
            forwardActive = false
            Logger.i(TAG, "Forward removed")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to remove forward", e)
        }
    }

    fun getLocalPort(): Int = localPort

    fun isForwardActive(): Boolean = forwardActive

    private fun readStream(process: Process): String {
        val reader = BufferedReader(InputStreamReader(process.errorStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line).append("\n")
        }
        return sb.toString().trim()
    }
}
