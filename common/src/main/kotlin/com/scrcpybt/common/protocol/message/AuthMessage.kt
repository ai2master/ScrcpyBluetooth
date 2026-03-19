package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 认证消息，实现逐功能授权（TOFU 模式）。
 *
 * 认证流程：
 * 1. 客户端发送 AUTH_REQUEST，携带功能 ID 和设备指纹
 * 2. 服务端检查该设备是否已被信任执行该功能：
 *    - 已信任：直接发送 AUTH_GRANTED
 *    - 未知设备：弹窗提示用户确认，然后发送 AUTH_GRANTED 或 AUTH_DENIED
 * 3. 客户端接收响应
 *
 * 设备指纹 = ECDH 公钥的 SHA-256 哈希，绑定到密钥交换过程，
 * 无法被伪造。
 *
 * @property subType 认证子类型（请求/授权/拒绝/撤销）
 * @property featureId 功能 ID（屏幕镜像/剪贴板/文件传输/文件夹同步/分享转发）
 * @property deviceFingerprint 设备指纹（ECDH 公钥 SHA-256 哈希）
 * @property deviceName 设备名称（用于 UI 显示）
 * @property reason 拒绝/撤销原因
 */
data class AuthMessage(
    val subType: Byte,
    val featureId: Byte,
    val deviceFingerprint: String,
    val deviceName: String,
    val reason: String = ""
) : Message() {
    override val type = MessageType.AUTH

    override fun serialize(): ByteArray {
        val fingerprintBytes = deviceFingerprint.toByteArray(StandardCharsets.UTF_8)
        val deviceNameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)

        val buf = ByteBuffer.allocate(
            1 + 1 + // subType + featureId
            4 + fingerprintBytes.size +
            4 + deviceNameBytes.size +
            4 + reasonBytes.size
        ).order(ByteOrder.BIG_ENDIAN)

        buf.put(subType)
        buf.put(featureId)
        buf.putInt(fingerprintBytes.size)
        buf.put(fingerprintBytes)
        buf.putInt(deviceNameBytes.size)
        buf.put(deviceNameBytes)
        buf.putInt(reasonBytes.size)
        buf.put(reasonBytes)

        return buf.array()
    }

    companion object {
        /** 认证请求 */
        const val SUB_AUTH_REQUEST: Byte = 0
        /** 认证通过 */
        const val SUB_AUTH_GRANTED: Byte = 1
        /** 认证拒绝 */
        const val SUB_AUTH_DENIED: Byte = 2
        /** 撤销认证 */
        const val SUB_AUTH_REVOKE: Byte = 3

        /** 功能：屏幕镜像 */
        const val FEATURE_SCREEN_MIRROR: Byte = 0
        /** 功能：剪贴板同步 */
        const val FEATURE_CLIPBOARD: Byte = 1
        /** 功能：文件传输 */
        const val FEATURE_FILE_TRANSFER: Byte = 2
        /** 功能：文件夹同步 */
        const val FEATURE_FOLDER_SYNC: Byte = 3
        /** 功能：分享转发 */
        const val FEATURE_SHARE_FORWARD: Byte = 4

        fun deserialize(data: ByteArray): AuthMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val subType = buf.get()
            val featureId = buf.get()

            val fingerprintLen = buf.int
            val fingerprintBytes = ByteArray(fingerprintLen)
            buf.get(fingerprintBytes)
            val deviceFingerprint = String(fingerprintBytes, StandardCharsets.UTF_8)

            val deviceNameLen = buf.int
            val deviceNameBytes = ByteArray(deviceNameLen)
            buf.get(deviceNameBytes)
            val deviceName = String(deviceNameBytes, StandardCharsets.UTF_8)

            val reasonLen = buf.int
            val reasonBytes = ByteArray(reasonLen)
            buf.get(reasonBytes)
            val reason = String(reasonBytes, StandardCharsets.UTF_8)

            return AuthMessage(subType, featureId, deviceFingerprint, deviceName, reason)
        }

        fun getFeatureName(featureId: Byte): String = when (featureId) {
            FEATURE_SCREEN_MIRROR -> "Screen Mirror"
            FEATURE_CLIPBOARD -> "Clipboard"
            FEATURE_FILE_TRANSFER -> "File Transfer"
            FEATURE_FOLDER_SYNC -> "Folder Sync"
            FEATURE_SHARE_FORWARD -> "Share Forward"
            else -> "Unknown"
        }
    }
}
