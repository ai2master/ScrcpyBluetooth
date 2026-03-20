package com.scrcpybt.server.auth

import com.scrcpybt.common.protocol.message.AuthMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 认证处理器
 *
 * 处理被控设备上的认证请求。
 *
 * ### 信任设备管理
 * 信任设备存储在 `/sdcard/ScrcpyBluetooth/.trusteddevices` (JSON 格式)
 * 数据结构：`Map<String, Set<Byte>>` = 设备指纹 -> 授权功能 ID 集合
 *
 * ### 认证策略
 * 当未知设备请求功能时：
 * - 记录日志："Device X requesting feature Y - auto-granting (server mode)"
 * - 自动授予权限（因为服务端以 root 身份运行，且由用户配置）
 *
 * ### 功能授权
 * 支持针对不同功能进行细粒度授权：
 * - 文件传输
 * - 文件夹同步
 * - 剪贴板同步
 * - 分享转发
 * - 等等
 *
 * ### 存储位置
 * `/sdcard/ScrcpyBluetooth/.trusteddevices`
 *
 * Authentication handler that manages trusted device permissions on controlled device,
 * auto-granting in server mode with persistent JSON storage.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see AuthMessage
 */
class AuthHandler {
    private val trustedDevicesFile = File("/sdcard/ScrcpyBluetooth/.trusteddevices")
    private val trustedDevices: MutableMap<String, MutableSet<Byte>> = mutableMapOf()

    init {
        // Ensure directory exists
        trustedDevicesFile.parentFile?.mkdirs()

        // Load trusted devices from file
        loadTrustedDevices()
    }

    /**
     * Handle incoming authentication request.
     */
    fun handleAuth(msg: AuthMessage, writer: MessageWriter) {
        when (msg.subType) {
            AuthMessage.SUB_AUTH_REQUEST -> handleAuthRequest(msg, writer)
            AuthMessage.SUB_AUTH_REVOKE -> handleAuthRevoke(msg)
            else -> Logger.w(TAG, "Unknown auth subtype: ${msg.subType}")
        }
    }

    private fun handleAuthRequest(msg: AuthMessage, writer: MessageWriter) {
        val fingerprint = msg.deviceFingerprint
        val featureId = msg.featureId
        val deviceName = msg.deviceName
        val featureName = AuthMessage.getFeatureName(featureId)

        // Check if device is already trusted for this feature
        val isTrusted = trustedDevices[fingerprint]?.contains(featureId) ?: false

        if (isTrusted) {
            Logger.i(TAG, "Device $deviceName requesting $featureName - already trusted")
            sendAuthGranted(msg, writer)
            return
        }

        // Auto-grant in server mode (server is configured by the user who has root)
        Logger.i(TAG, "Device $deviceName requesting $featureName - auto-granting (server mode)")
        trustDevice(fingerprint, featureId, deviceName)
        sendAuthGranted(msg, writer)
    }

    private fun handleAuthRevoke(msg: AuthMessage) {
        val fingerprint = msg.deviceFingerprint
        val featureId = msg.featureId

        trustedDevices[fingerprint]?.remove(featureId)
        if (trustedDevices[fingerprint]?.isEmpty() == true) {
            trustedDevices.remove(fingerprint)
        }

        saveTrustedDevices()
        Logger.i(TAG, "Device trust revoked: $fingerprint for feature $featureId")
    }

    private fun sendAuthGranted(msg: AuthMessage, writer: MessageWriter) {
        try {
            val response = AuthMessage(
                subType = AuthMessage.SUB_AUTH_GRANTED,
                featureId = msg.featureId,
                deviceFingerprint = msg.deviceFingerprint,
                deviceName = msg.deviceName,
                reason = ""
            )
            writer.writeMessage(response)
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to send auth granted", e)
        }
    }

    private fun sendAuthDenied(msg: AuthMessage, writer: MessageWriter, reason: String) {
        try {
            val response = AuthMessage(
                subType = AuthMessage.SUB_AUTH_DENIED,
                featureId = msg.featureId,
                deviceFingerprint = msg.deviceFingerprint,
                deviceName = msg.deviceName,
                reason = reason
            )
            writer.writeMessage(response)
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to send auth denied", e)
        }
    }

    private fun trustDevice(fingerprint: String, featureId: Byte, deviceName: String) {
        trustedDevices.getOrPut(fingerprint) { mutableSetOf() }.add(featureId)
        saveTrustedDevices()
        Logger.i(TAG, "Device trusted: $deviceName (fingerprint: ${fingerprint.take(16)}...) for feature $featureId")
    }

    private fun loadTrustedDevices() {
        if (!trustedDevicesFile.exists()) {
            Logger.i(TAG, "No trusted devices file found, starting fresh")
            return
        }

        try {
            val json = trustedDevicesFile.readText()
            val obj = JSONObject(json)

            for (fingerprint in obj.keys()) {
                val featuresArray = obj.getJSONArray(fingerprint)
                val features = mutableSetOf<Byte>()
                for (i in 0 until featuresArray.length()) {
                    features.add(featuresArray.getInt(i).toByte())
                }
                trustedDevices[fingerprint] = features
            }

            Logger.i(TAG, "Loaded ${trustedDevices.size} trusted devices")
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading trusted devices", e)
        }
    }

    private fun saveTrustedDevices() {
        try {
            val obj = JSONObject()

            for ((fingerprint, features) in trustedDevices) {
                val featuresArray = org.json.JSONArray()
                for (feature in features) {
                    featuresArray.put(feature.toInt())
                }
                obj.put(fingerprint, featuresArray)
            }

            trustedDevicesFile.writeText(obj.toString(2))
            Logger.d(TAG, "Trusted devices saved")
        } catch (e: Exception) {
            Logger.e(TAG, "Error saving trusted devices", e)
        }
    }

    companion object {
        private const val TAG = "AuthHandler"
    }
}
