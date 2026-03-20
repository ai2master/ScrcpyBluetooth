package com.scrcpybt.app.auth

import android.os.Build
import com.scrcpybt.common.protocol.message.AuthMessage
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.IOException

/**
 * 客户端功能认证管理器：控制端的按功能认证机制 | Feature authentication manager for the controller side
 *
 * 核心功能 | Core Functions:
 * - 在使用任何功能前，向被控端发起认证请求 | Initiate authentication request before using any feature
 * - 基于设备指纹和功能 ID 的双因素认证 | Two-factor authentication based on device fingerprint and feature ID
 * - 会话级认证缓存（连接断开时清空）| Session-level authentication cache (cleared on disconnect)
 *
 * 认证流程 | Authentication Flow:
 * 1. 从 ECDH 公钥计算设备指纹（SHA-256）| Compute device fingerprint from ECDH public key (SHA-256)
 * 2. 发送 AuthMessage(SUB_AUTH_REQUEST, featureId, fingerprint, deviceName) | Send AuthMessage with request type
 * 3. 等待被控端响应 | Wait for server response
 * 4. AUTH_GRANTED: 缓存到本地，返回 true | Cache locally and return true if granted
 * 5. AUTH_DENIED: 显示拒绝原因，返回 false | Show reason and return false if denied
 *
 * 安全特性 | Security Features:
 * - 功能级细粒度授权（剪贴板、文件传输、投屏等独立认证）| Feature-level granular authorization
 * - 设备指纹防伪造（基于密钥对公钥）| Device fingerprint anti-forgery
 * - 会话隔离（每次连接重新认证）| Session isolation (re-authenticate on each connection)
 *
 * 使用场景 | Use Cases:
 * - ControllerService 在启动功能前调用 authenticateFeature() | Call before starting any feature in ControllerService
 * - 被控端可在 DeviceTrustStore 中记住授权设备 | Server can remember trusted devices in DeviceTrustStore
 *
 * @param publicKey 客户端 ECDH 公钥（用于生成设备指纹）| Client ECDH public key for fingerprint generation
 */
class FeatureAuthClient(private val publicKey: ByteArray) {
    // 设备指纹（基于公钥的 SHA-256 哈希）| Device fingerprint (SHA-256 hash of public key)
    private val deviceFingerprint: String
    // 设备名称（来自系统型号）| Device name (from system model)
    private val deviceName: String = Build.MODEL
    // 当前会话已授权的功能集合 | Set of authorized features in current session
    private val authorizedFeatures = mutableSetOf<Byte>()

    init {
        deviceFingerprint = computeFingerprint(publicKey)
    }

    /**
     * 对指定功能发起认证请求 | Authenticate for a specific feature
     *
     * 执行完整的请求-响应认证流程，并缓存认证结果。| Execute complete request-response authentication flow and cache result.
     *
     * @param featureId 功能 ID（见 AuthMessage.FEATURE_*）| Feature ID (see AuthMessage.FEATURE_*)
     * @param writer 消息写入器（用于发送认证请求）| Message writer for sending authentication request
     * @param reader 消息读取器（用于接收认证响应）| Message reader for receiving authentication response
     * @return true 认证通过，false 认证拒绝或失败 | true if granted, false if denied or failed
     * @throws IOException 网络通信异常（已内部捕获，返回 false）| Network communication error (caught internally, returns false)
     */
    fun authenticateFeature(
        featureId: Byte,
        writer: MessageWriter,
        reader: MessageReader
    ): Boolean {
        // 检查当前会话是否已认证 | Check if already authenticated in this session
        if (authorizedFeatures.contains(featureId)) {
            Logger.d(TAG, "Feature ${AuthMessage.getFeatureName(featureId)} already authorized")
            return true
        }

        try {
            // 发送认证请求 | Send auth request
            val request = AuthMessage(
                subType = AuthMessage.SUB_AUTH_REQUEST,
                featureId = featureId,
                deviceFingerprint = deviceFingerprint,
                deviceName = deviceName,
                reason = ""
            )
            writer.writeMessage(request)
            Logger.i(TAG, "Auth request sent for feature: ${AuthMessage.getFeatureName(featureId)}")

            // 等待响应（带超时）| Wait for response with timeout
            val response = reader.readMessage()

            if (response !is AuthMessage) {
                Logger.e(TAG, "Unexpected response type: ${response?.type}")
                return false
            }

            when (response.subType) {
                AuthMessage.SUB_AUTH_GRANTED -> {
                    authorizedFeatures.add(featureId)
                    Logger.i(TAG, "Auth granted for feature: ${AuthMessage.getFeatureName(featureId)}")
                    return true
                }
                AuthMessage.SUB_AUTH_DENIED -> {
                    Logger.w(TAG, "Auth denied for feature: ${AuthMessage.getFeatureName(featureId)}, reason: ${response.reason}")
                    return false
                }
                else -> {
                    Logger.e(TAG, "Unexpected auth response subtype: ${response.subType}")
                    return false
                }
            }
        } catch (e: IOException) {
            Logger.e(TAG, "Auth request failed", e)
            return false
        }
    }

    /**
     * 检查功能是否在当前会话中已授权 | Check if a feature is authorized in the current session
     *
     * @param featureId 功能 ID | Feature ID
     * @return true 已授权，false 未授权 | true if authorized, false otherwise
     */
    fun isFeatureAuthorized(featureId: Byte): Boolean {
        return authorizedFeatures.contains(featureId)
    }

    /**
     * 清空所有会话授权（连接断开时调用）| Clear all session authorizations (call on disconnect)
     *
     * 认证结果仅在当前连接有效，断开后需重新认证。| Auth results are valid only for current connection, re-auth required after disconnect.
     */
    fun clearSession() {
        authorizedFeatures.clear()
        Logger.d(TAG, "Session cleared")
    }

    /**
     * 获取当前设备指纹 | Get the device fingerprint
     *
     * @return 64 字符的十六进制字符串（SHA-256 哈希）| 64-char hex string (SHA-256 hash)
     */
    fun getFingerprint(): String = deviceFingerprint

    /**
     * 计算设备指纹：公钥的 SHA-256 哈希 | Compute device fingerprint: SHA-256 hash of public key
     *
     * @param publicKey ECDH 公钥字节数组 | ECDH public key byte array
     * @return 十六进制字符串 | Hex string
     */
    private fun computeFingerprint(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FeatureAuthClient"
    }
}
