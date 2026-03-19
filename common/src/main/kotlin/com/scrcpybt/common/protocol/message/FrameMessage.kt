package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 帧数据消息。
 *
 * 携带一帧 256 色编码后的画面数据。分为关键帧和增量帧：
 * - **关键帧**：包含完整调色板 (768 字节) + 全帧压缩数据，解码端可独立还原
 * - **增量帧**：不含调色板，载荷为 XOR 帧差的 LZ4 压缩数据
 *
 * 载荷格式（大端序）：
 * [isKeyframe:1][width:2][height:2][compressionType:1][originalSize:4]
 * [paletteLen:4][palette:N][dataLen:4][compressedData:N]
 *
 * @property isKeyframe 是否为关键帧
 * @property width 帧宽度（像素）
 * @property height 帧高度（像素）
 * @property compressionType 压缩算法类型（1=LZ4）
 * @property originalSize 压缩前原始数据大小（解压需要）
 * @property palette 调色板数据（关键帧时为 768 字节 RGB，增量帧时为 null）
 * @property compressedData LZ4 压缩后的帧数据
 */
data class FrameMessage(
    var isKeyframe: Boolean = false,
    var width: Short = 0,
    var height: Short = 0,
    var compressionType: Byte = 1,
    var originalSize: Int = 0,
    var palette: ByteArray? = null,
    var compressedData: ByteArray? = null
) : Message() {
    override val type = MessageType.FRAME

    override fun serialize(): ByteArray {
        val paletteLen = palette?.size ?: 0
        val dataLen = compressedData?.size ?: 0
        val buffer = ByteBuffer.allocate(1 + 2 + 2 + 1 + 4 + 4 + paletteLen + 4 + dataLen)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(if (isKeyframe) 1.toByte() else 0.toByte())
        buffer.putShort(width)
        buffer.putShort(height)
        buffer.put(compressionType)
        buffer.putInt(originalSize)
        buffer.putInt(paletteLen)
        palette?.let { buffer.put(it) }
        buffer.putInt(dataLen)
        compressedData?.let { buffer.put(it) }
        return buffer.array()
    }

    companion object {
        fun deserialize(data: ByteArray): FrameMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val isKey = buf.get() != 0.toByte()
            val w = buf.short; val h = buf.short
            val comp = buf.get(); val origSize = buf.int
            val palLen = buf.int
            val pal = if (palLen > 0) ByteArray(palLen).also { buf.get(it) } else null
            val dataLen = buf.int
            val compressed = if (dataLen > 0) ByteArray(dataLen).also { buf.get(it) } else null
            return FrameMessage(isKey, w, h, comp, origSize, pal, compressed)
        }
    }

    override fun equals(other: Any?) = other is FrameMessage && isKeyframe == other.isKeyframe && width == other.width && height == other.height
    override fun hashCode() = (isKeyframe.hashCode() * 31 + width) * 31 + height
}
