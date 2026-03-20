package com.scrcpybt.app.transport.usb

import com.scrcpybt.common.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * USB 连接检测器：检测 ADB 设备连接状态 | USB connection detector: detects ADB device connection status
 *
 * 功能：| Features:
 * - 检查是否有 ADB 设备连接 | Check if ADB device is connected
 * - 获取连接设备的序列号 | Get serial number of connected device
 *
 * 实现方式：| Implementation:
 * - 通过执行 `adb devices` 命令并解析输出 | Execute `adb devices` command and parse output
 * - 适用于控制端或中继端检测被控设备的连接状态 | Suitable for controller or relay to detect controlled device connection
 */
class UsbDetector {
    companion object {
        private const val TAG = "UsbDetector"
    }

    /**
     * 检查是否有 ADB 设备连接 | Check if ADB device is connected
     *
     * @return true 如果检测到至少一个设备 | true if at least one device detected
     */
    fun isDeviceConnected(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("adb devices")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var deviceCount = 0

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.endsWith("device") && !trimmed.startsWith("List")) {
                    deviceCount++
                }
            }
            process.waitFor()
            deviceCount > 0
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to check ADB devices", e)
            false
        }
    }

    /**
     * 获取第一个连接设备的序列号 | Get serial number of first connected device
     *
     * @return 设备序列号，如果没有设备则返回 null | Device serial number, or null if no device
     */
    fun getDeviceSerial(): String? {
        return try {
            val process = Runtime.getRuntime().exec("adb devices")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.endsWith("device") && !trimmed.startsWith("List")) {
                    return trimmed.split("\\s+".toRegex())[0]
                }
            }
            process.waitFor()
            null
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to get device serial", e)
            null
        }
    }
}
