package com.scrcpybt.common.codec.color

/**
 * 颜色量化器接口。
 *
 * 将任意 RGB 颜色空间缩减为指定数量的代表色（调色板）。
 * 当前实现为 [MedianCutQuantizer]（中位切割算法）。
 *
 * 在 256 色编码管线中，量化器的职责是从一帧 ARGB 像素中
 * 提取出最具代表性的 256 种颜色，生成 [Palette]。
 */
interface ColorQuantizer {
    /**
     * 从像素数组生成调色板。
     *
     * @param pixels ARGB_8888 格式的像素数组
     * @param maxColors 调色板最大颜色数（默认 256）
     * @return 生成的 [Palette]
     */
    fun generatePalette(pixels: IntArray, maxColors: Int = 256): Palette
}
