package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 传输切换协商消息。
 *
 * 用于从蓝牙中继 (A→B→C) 无缝切换到 USB 直连 (A→C) 的流程：
 * 1. A 检测到与 C 的 USB 连接可用
 * 2. A 通过现有蓝牙通道发送 SWITCH_REQUEST：{sessionId, USB_ADB, socketName}
 * 3. C 通过蓝牙通道回复 SWITCH_READY（C 已在 USB 端口监听）
 * 4. A 建立与 C 的 USB 连接
 * 5. A 通过 USB 发送 SWITCH_VERIFY（携带 sessionId）
 * 6. C 验证 sessionId 匹配，通过 USB 回复 SWITCH_CONFIRMED
 * 7. 双方将加密通道切换到新传输层
 * 8. A 通过 USB 发送 SWITCH_COMPLETE
 * 9. 蓝牙中继连接优雅关闭
 *
 * 加密密钥保持不变（会话继续，仅传输层变化）。
 * nonce 计数器继续递增（不重置 = 不重用 nonce）。
 *
 * @property subType 切换子类型
 * @property sessionId 当前会话 ID（用于验证身份）
 * @property newTransport 目标传输类型
 * @property socketName Unix 抽象 socket 名称（USB ADB 时使用）
 * @property reason 拒绝原因（SWITCH_REJECTED 时使用）
 */
data class TransportSwitchMessage(
    val subType: Byte = SUB_SWITCH_REQUEST,
    val sessionId: String = "",
    val newTransport: Byte = 0,
    val socketName: String = "",
    val reason: String = ""
) : Message() {
    override val type = MessageType.TRANSPORT_SWITCH

    override fun serialize(): ByteArray {
        val sessionIdBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        val socketNameBytes = socketName.toByteArray(StandardCharsets.UTF_8)
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)

        val buf = ByteBuffer.allocate(
            1 + // subType
            4 + sessionIdBytes.size + // sessionId length + bytes
            1 + // newTransport
            4 + socketNameBytes.size + // socketName length + bytes
            4 + reasonBytes.size // reason length + bytes
        ).order(ByteOrder.BIG_ENDIAN)

        buf.put(subType)
        buf.putInt(sessionIdBytes.size)
        buf.put(sessionIdBytes)
        buf.put(newTransport)
        buf.putInt(socketNameBytes.size)
        buf.put(socketNameBytes)
        buf.putInt(reasonBytes.size)
        buf.put(reasonBytes)

        return buf.array()
    }

    companion object {
        /** 切换请求（发起方→被控方） */
        const val SUB_SWITCH_REQUEST: Byte = 0
        /** 切换就绪（被控方已在新传输端口监听） */
        const val SUB_SWITCH_READY: Byte = 1
        /** 切换验证（通过新传输发送 sessionId） */
        const val SUB_SWITCH_VERIFY: Byte = 2
        /** 切换确认（被控方验证 sessionId 通过） */
        const val SUB_SWITCH_CONFIRMED: Byte = 3
        /** 切换完成 */
        const val SUB_SWITCH_COMPLETE: Byte = 4
        /** 切换拒绝 */
        const val SUB_SWITCH_REJECTED: Byte = 5

        /** 目标传输：蓝牙 RFCOMM */
        const val TRANSPORT_BT_RFCOMM: Byte = 0
        /** 目标传输：USB ADB */
        const val TRANSPORT_USB_ADB: Byte = 1

        fun deserialize(data: ByteArray): TransportSwitchMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val sub = buf.get()

            val sessionIdLen = buf.int
            val sessionIdBytes = ByteArray(sessionIdLen)
            buf.get(sessionIdBytes)
            val sessionId = String(sessionIdBytes, StandardCharsets.UTF_8)

            val newTransport = buf.get()

            val socketNameLen = buf.int
            val socketNameBytes = ByteArray(socketNameLen)
            buf.get(socketNameBytes)
            val socketName = String(socketNameBytes, StandardCharsets.UTF_8)

            val reasonLen = buf.int
            val reasonBytes = ByteArray(reasonLen)
            buf.get(reasonBytes)
            val reason = String(reasonBytes, StandardCharsets.UTF_8)

            return TransportSwitchMessage(sub, sessionId, newTransport, socketName, reason)
        }
    }
}
