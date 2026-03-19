package com.scrcpybt.common.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 消息头数据结构。
 *
 * 所有消息共用固定 14 字节头部，格式如下（大端序）：
 * ```
 * [magic:4字节][type:1字节][flags:1字节][payloadLength:4字节][sequence:4字节]
 * ```
 *
 * - magic: 魔数 0x53435242 ("SCRB")，用于校验消息完整性
 * - type: 消息类型 ID，对应 [MessageType] 枚举
 * - flags: 标志位组合（加密、压缩、需要ACK）
 * - payloadLength: 后续载荷的字节数
 * - sequence: 递增序列号，用于排序和去重
 *
 * @property type 消息类型
 * @property flags 标志位
 * @property payloadLength 载荷长度（字节）
 * @property sequence 消息序列号
 */
data class MessageHeader(
    val type: MessageType,
    val flags: Byte,
    val payloadLength: Int,
    val sequence: Int
) {
    /** 检查载荷是否已加密 (AES-256-GCM) */
    fun isEncrypted(): Boolean = (flags.toInt() and ProtocolConstants.FLAG_ENCRYPTED.toInt()) != 0

    /** 检查载荷是否已压缩 (LZ4) */
    fun isCompressed(): Boolean = (flags.toInt() and ProtocolConstants.FLAG_COMPRESSED.toInt()) != 0

    /** 序列化为 14 字节的字节数组（大端序） */
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(ProtocolConstants.MAGIC)
        buffer.put(type.id)
        buffer.put(flags)
        buffer.putInt(payloadLength)
        buffer.putInt(sequence)
        return buffer.array()
    }

    companion object {
        /**
         * 从 14 字节数据反序列化消息头。
         * 会校验魔数，不匹配时抛出 [IllegalArgumentException]。
         */
        fun fromBytes(data: ByteArray): MessageHeader {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.int
            require(magic == ProtocolConstants.MAGIC) { "Invalid magic: 0x${magic.toString(16)}" }
            val type = MessageType.fromId(buffer.get())
            val flags = buffer.get()
            val payloadLength = buffer.int
            val sequence = buffer.int
            return MessageHeader(type, flags, payloadLength, sequence)
        }
    }
}
