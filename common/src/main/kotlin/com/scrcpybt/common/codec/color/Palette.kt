package com.scrcpybt.common.codec.color

/**
 * 256 色调色板。
 *
 * 存储 256 个 ARGB 颜色值，用于 256 色编码方案。
 * 每帧的像素通过 [ColorMapper] 映射为调色板索引（0-255），
 * 传输时只需传输 1 字节索引而非 4 字节 ARGB，大幅降低带宽。
 *
 * 序列化格式：768 字节 = 256 色 × 3 字节 (R, G, B)，无 alpha 通道。
 * 网络传输时以此格式嵌入关键帧的 [FrameMessage] 中。
 *
 * @property colors 256 个 ARGB 颜色值数组（高位 alpha 固定为 0xFF）
 */
class Palette(val colors: IntArray) {
    init { require(colors.size == 256) { "Palette must have 256 colors" } }

    /**
     * 根据索引获取 ARGB 颜色值。
     * @param index 调色板索引（0-255，自动掩码低 8 位）
     */
    fun getColor(index: Int): Int = colors[index and 0xFF]

    /**
     * 将调色板序列化为 768 字节的 RGB 字节数组。
     * 格式：[R0,G0,B0, R1,G1,B1, ..., R255,G255,B255]
     * 用于嵌入关键帧消息，随帧数据一起传输给解码端。
     */
    fun toBytes(): ByteArray {
        val bytes = ByteArray(768) // 256 × 3
        for (i in 0 until 256) {
            bytes[i * 3] = (colors[i] shr 16 and 0xFF).toByte()     // R
            bytes[i * 3 + 1] = (colors[i] shr 8 and 0xFF).toByte()  // G
            bytes[i * 3 + 2] = (colors[i] and 0xFF).toByte()         // B
        }
        return bytes
    }

    companion object {
        /**
         * 从 768 字节 RGB 数据反序列化为调色板。
         * 每 3 字节恢复一个 ARGB 颜色（alpha 固定为 0xFF）。
         */
        fun fromBytes(bytes: ByteArray): Palette {
            val colors = IntArray(256)
            for (i in 0 until 256) {
                val r = bytes[i * 3].toInt() and 0xFF
                val g = bytes[i * 3 + 1].toInt() and 0xFF
                val b = bytes[i * 3 + 2].toInt() and 0xFF
                colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Palette(colors)
        }
    }
}
