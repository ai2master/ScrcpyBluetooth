package com.scrcpybt.common.codec.color

/**
 * RGB ↔ 调色板索引映射器。
 *
 * 使用 15-bit RGB 查找表 (LUT) 加速颜色映射。
 *
 * 原理：将 24-bit RGB 截断为 15-bit（每通道 5 bit），建立 32768 条目的查找表，
 * 每个条目存储该 15-bit 颜色最接近的调色板索引。这样每个像素的映射只需一次
 * 数组查表操作，避免了逐像素遍历 256 色调色板的开销。
 *
 * 精度损失：每通道从 8-bit 截断到 5-bit，损失低 3 位（最大误差 7/255）。
 * 对于 256 色量化场景，这个精度完全足够。
 *
 * @param palette 当前使用的 256 色调色板
 */
class ColorMapper(private val palette: Palette) {
    /**
     * 15-bit RGB 查找表：32768 个条目。
     * 索引格式：[R5:5bit][G5:5bit][B5:5bit]
     * 值：最近的调色板索引 (0-255)
     */
    private val lut = IntArray(32768)

    init {
        // 预拆分调色板 RGB 分量到独立数组，避免内层循环重复位运算
        val paletteR = IntArray(256); val paletteG = IntArray(256); val paletteB = IntArray(256)
        for (j in 0 until 256) {
            paletteR[j] = palette.colors[j] shr 16 and 0xFF
            paletteG[j] = palette.colors[j] shr 8 and 0xFF
            paletteB[j] = palette.colors[j] and 0xFF
        }
        // 预计算查找表：遍历所有 32768 种 15-bit 颜色，找到最近的调色板索引
        for (i in 0 until 32768) {
            val r5 = (i shr 10) and 0x1F; val g5 = (i shr 5) and 0x1F; val b5 = i and 0x1F
            val r = r5 shl 3; val g = g5 shl 3; val b = b5 shl 3
            var bestIdx = 0; var bestDist = Int.MAX_VALUE
            for (j in 0 until 256) {
                val dr = r - paletteR[j]; val dg = g - paletteG[j]; val db = b - paletteB[j]
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) {
                    bestDist = dist; bestIdx = j
                    if (dist == 0) break  // 精确匹配，提前退出
                }
            }
            lut[i] = bestIdx
        }
    }

    /**
     * 将单个 ARGB 像素映射为调色板索引。
     * 通过截断到 15-bit 后查表实现，时间复杂度 O(1)。
     */
    fun mapPixel(argb: Int): Byte {
        val r5 = (argb shr 19) and 0x1F; val g5 = (argb shr 11) and 0x1F; val b5 = (argb shr 3) and 0x1F
        return lut[(r5 shl 10) or (g5 shl 5) or b5].toByte()
    }

    /**
     * 将整帧 ARGB 像素映射为调色板索引数组。
     * 输出数组大小与输入相同，每个像素对应 1 字节索引。
     */
    fun mapFrame(pixels: IntArray): ByteArray {
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) indices[i] = mapPixel(pixels[i])
        return indices
    }

    /**
     * 逆映射：将调色板索引数组还原为 ARGB 像素数组。
     * 用于控制端解码帧数据。
     */
    fun unmap(indices: ByteArray): IntArray {
        val pixels = IntArray(indices.size)
        for (i in indices.indices) pixels[i] = palette.getColor(indices[i].toInt() and 0xFF)
        return pixels
    }
}
