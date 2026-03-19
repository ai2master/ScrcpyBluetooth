package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType

/**
 * 消息编解码器（单例）。
 *
 * 提供统一的消息序列化/反序列化入口。
 * 序列化时委托给各消息类的 [Message.serialize] 方法；
 * 反序列化时根据 [MessageType] 路由到对应消息类的 companion deserialize 方法。
 *
 * 支持的消息类型：握手、密钥交换、帧数据、输入、控制、心跳、
 * 剪贴板、文件传输、文件夹同步、分享转发、认证、传输切换。
 */
object MessageCodec {
    /** 序列化消息为字节数组 */
    fun serialize(message: Message): ByteArray = message.serialize()

    /**
     * 根据消息类型和载荷字节数组反序列化为具体消息对象。
     * @param type 消息类型（从消息头解析）
     * @param data 消息载荷字节数组
     */
    fun deserialize(type: MessageType, data: ByteArray): Message = when (type) {
        MessageType.HANDSHAKE -> HandshakeMessage.deserialize(data)
        MessageType.KEY_EXCHANGE -> KeyExchangeMessage.deserialize(data)
        MessageType.FRAME -> FrameMessage.deserialize(data)
        MessageType.INPUT -> InputMessage.deserialize(data)
        MessageType.CONTROL -> ControlMessage.deserialize(data)
        MessageType.HEARTBEAT -> HeartbeatMessage.deserialize(data)
        MessageType.CLIPBOARD -> ClipboardMessage.deserialize(data)
        MessageType.FILE_TRANSFER -> FileTransferMessage.deserialize(data)
        MessageType.FOLDER_SYNC -> FolderSyncMessage.deserialize(data)
        MessageType.SHARE_FORWARD -> ShareForwardMessage.deserialize(data)
        MessageType.AUTH -> AuthMessage.deserialize(data)
        MessageType.TRANSPORT_SWITCH -> TransportSwitchMessage.deserialize(data)
    }
}
