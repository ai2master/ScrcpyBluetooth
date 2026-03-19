package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 握手消息。
 *
 * 连接建立后双方首先交换握手消息，传递设备基本信息。
 * 握手在明文阶段完成（密钥交换之前），用于协商能力。
 *
 * 载荷格式（大端序）：
 * [modelLen:4][model:N][versionLen:4][version:N][width:4][height:4][density:4][capabilities:1]
 *
 * @property deviceModel 设备型号（如 "Pixel 7 Pro"）
 * @property androidVersion Android 版本号（如 "14"）
 * @property screenWidth 屏幕宽度（像素）
 * @property screenHeight 屏幕高度（像素）
 * @property density 屏幕密度（DPI）
 * @property capabilities 能力位掩码（触摸、键盘、剪贴板、文件传输）
 */
data class HandshakeMessage(
    val deviceModel: String = "",
    val androidVersion: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val density: Int = 0,
    val capabilities: Byte = 0
) : Message() {
    override val type = MessageType.HANDSHAKE

    override fun serialize(): ByteArray {
        val modelBytes = deviceModel.toByteArray(StandardCharsets.UTF_8)
        val versionBytes = androidVersion.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + modelBytes.size + 4 + versionBytes.size + 4 + 4 + 4 + 1)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(modelBytes.size)
        buffer.put(modelBytes)
        buffer.putInt(versionBytes.size)
        buffer.put(versionBytes)
        buffer.putInt(screenWidth)
        buffer.putInt(screenHeight)
        buffer.putInt(density)
        buffer.put(capabilities)
        return buffer.array()
    }

    companion object {
        /** 能力标志：支持触摸输入 */
        const val CAP_TOUCH: Byte = 0x01
        /** 能力标志：支持键盘输入 */
        const val CAP_KEYBOARD: Byte = 0x02
        /** 能力标志：支持剪贴板同步 */
        const val CAP_CLIPBOARD: Byte = 0x04
        /** 能力标志：支持文件传输 */
        const val CAP_FILE_TRANSFER: Byte = 0x08

        fun deserialize(data: ByteArray): HandshakeMessage {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)
            val modelLen = buffer.int
            val modelBytes = ByteArray(modelLen); buffer.get(modelBytes)
            val versionLen = buffer.int
            val versionBytes = ByteArray(versionLen); buffer.get(versionBytes)
            return HandshakeMessage(
                String(modelBytes, StandardCharsets.UTF_8),
                String(versionBytes, StandardCharsets.UTF_8),
                buffer.int, buffer.int, buffer.int, buffer.get()
            )
        }
    }
}
