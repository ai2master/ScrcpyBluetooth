package com.scrcpybt.common.codec.delta

/**
 * XOR 帧差编码器。
 *
 * 实现两级优化策略减少帧数据量：
 *
 * 1. **16×16 块级脏区检测**：将画面划分为 16×16 像素的块，
 *    只有与前帧不同的块才标记为"脏"。这快速排除了大面积未变化区域。
 *
 * 2. **XOR 帧差编码**：对当前帧和前帧逐字节做 XOR 运算。
 *    未变化的像素 XOR 结果为 0，大量连续 0 字节对 LZ4 压缩非常友好，
 *    压缩后的数据量会大幅减少。
 *
 * @param width 帧宽度（像素）
 * @param height 帧高度（像素）
 */
class DeltaEncoder(private val width: Int, private val height: Int) {
    companion object {
        /** 脏区检测的块大小：16×16 像素 */
        const val BLOCK_SIZE = 16
    }

    /**
     * 检测当前帧与前帧之间的脏区。
     *
     * 将画面划分为 16×16 的块，逐块比较。
     * 如果块内任何一个像素不同，则该块标记为脏。
     *
     * @param current 当前帧的调色板索引数组
     * @param previous 前帧的调色板索引数组
     * @return 脏区列表，包含所有发生变化的块的矩形坐标
     */
    fun detectDirty(current: ByteArray, previous: ByteArray): DirtyRegion {
        val blocks = mutableListOf<DirtyRegion.Rect>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                // 处理边缘块（可能不足 16×16）
                val bw = minOf(BLOCK_SIZE, width - x); val bh = minOf(BLOCK_SIZE, height - y)
                if (!blockEquals(current, previous, x, y, bw, bh)) {
                    blocks.add(DirtyRegion.Rect(x, y, bw, bh))
                }
                x += BLOCK_SIZE
            }
            y += BLOCK_SIZE
        }
        return DirtyRegion(blocks)
    }

    /**
     * XOR 帧差编码。
     *
     * 对当前帧和前帧逐字节 XOR，结果中：
     * - 未变化像素：0x00（LZ4 压缩效率极高）
     * - 变化像素：非零值
     *
     * @param current 当前帧索引数据
     * @param previous 前帧索引数据
     * @param dirty 脏区信息（当前实现为全帧 XOR，脏区用于上层判断）
     * @return XOR 差异数据
     */
    fun encode(current: ByteArray, previous: ByteArray, dirty: DirtyRegion): ByteArray {
        val delta = ByteArray(current.size)
        for (i in current.indices) delta[i] = (current[i].toInt() xor previous[i].toInt()).toByte()
        return delta
    }

    /**
     * 比较两帧中同一个块的像素是否完全相同。
     * 发现任何不同的像素立即返回 false（提前退出优化）。
     */
    private fun blockEquals(a: ByteArray, b: ByteArray, x: Int, y: Int, bw: Int, bh: Int): Boolean {
        for (row in y until y + bh) {
            val offset = row * width + x
            for (col in 0 until bw) {
                if (a[offset + col] != b[offset + col]) return false
            }
        }
        return true
    }
}
