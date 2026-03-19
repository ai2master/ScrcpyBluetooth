package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 剪贴板传输消息。
 *
 * 方向由发送方决定：
 * - 控制端 → 服务端：将文本粘贴到被控设备
 * - 服务端 → 控制端：从被控设备复制文本
 *
 * 载荷格式（大端序）：[direction:1][textLen:4][text:N]
 *
 * @property text 剪贴板文本内容
 * @property direction 传输方向（推送/拉取）
 */
data class ClipboardMessage(
    val text: String = "",
    val direction: Byte = DIR_PUSH
) : Message() {
    override val type = MessageType.CLIPBOARD

    override fun serialize(): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 4 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(direction)
        buf.putInt(textBytes.size)
        buf.put(textBytes)
        return buf.array()
    }

    companion object {
        /** 推送：将本地剪贴板发送到远端 */
        const val DIR_PUSH: Byte = 0
        /** 拉取：请求远端剪贴板内容 */
        const val DIR_PULL: Byte = 1

        /** 剪贴板文本最大长度：1MB */
        private const val MAX_TEXT_SIZE = 1 * 1024 * 1024

        fun deserialize(data: ByteArray): ClipboardMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val dir = buf.get()
            val textLen = buf.int
            if (textLen < 0 || textLen > MAX_TEXT_SIZE) {
                throw java.io.IOException("剪贴板文本长度非法: $textLen (上限 $MAX_TEXT_SIZE)")
            }
            val textBytes = ByteArray(textLen); buf.get(textBytes)
            return ClipboardMessage(String(textBytes, StandardCharsets.UTF_8), dir)
        }
    }
}
