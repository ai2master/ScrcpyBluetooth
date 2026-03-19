package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType

/**
 * 协议消息抽象基类。
 *
 * 所有具体消息类型（握手、帧数据、输入、控制等）均继承此类。
 * 每条消息需指定类型 [type] 并实现 [serialize] 序列化方法。
 *
 * 消息通过 [MessageCodec] 进行统一的序列化/反序列化。
 * 传输时由 [com.scrcpybt.common.protocol.MessageHeader] 头部标识消息类型和长度。
 */
abstract class Message {
    /** 消息类型枚举值 */
    abstract val type: MessageType

    /** 将消息载荷序列化为字节数组（不含消息头） */
    abstract fun serialize(): ByteArray
}
