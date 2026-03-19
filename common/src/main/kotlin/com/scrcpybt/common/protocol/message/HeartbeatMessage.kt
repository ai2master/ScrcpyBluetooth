package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 心跳消息。
 *
 * 定期在加密通道中发送，用于：
 * 1. 检测连接是否存活（超时未收到心跳则判断连接断开）
 * 2. 测量往返延迟（RTT）
 *
 * 载荷格式：[timestamp:8]（大端序毫秒时间戳）
 *
 * @property timestamp 发送时的系统时间（毫秒）
 */
data class HeartbeatMessage(
    val timestamp: Long = System.currentTimeMillis()
) : Message() {
    override val type = MessageType.HEARTBEAT
    override fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(timestamp)
        return buf.array()
    }

    companion object {
        fun deserialize(data: ByteArray): HeartbeatMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return HeartbeatMessage(buf.long)
        }
    }
}
