package com.scrcpybt.app.transport.usb

import com.scrcpybt.common.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * USB 连接检测器：检测 ADB 设备连接状态
 *
 * 功能：
 * - 检查是否有 ADB 设备连接
 * - 获取连接设备的序列号
 *
 * 实现方式：
 * - 通过执行 `adb devices` 命令并解析输出
 * - 适用于控制端或中继端检测被控设备的连接状态
 */
class UsbDetector {
    companion object {
        private const val TAG = "UsbDetector"
    }

    /**
     * 检查是否有 ADB 设备连接
     *
     * @return true 如果检测到至少一个设备
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
     * 获取第一个连接设备的序列号
     *
     * @return 设备序列号，如果没有设备则返回 null
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
