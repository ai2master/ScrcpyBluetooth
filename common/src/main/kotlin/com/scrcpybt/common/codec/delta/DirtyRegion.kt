package com.scrcpybt.common.codec.delta

/**
 * 脏区信息，描述帧间发生变化的区域。
 *
 * 由 [DeltaEncoder.detectDirty] 生成，包含一组矩形块坐标。
 * 脏区用于帧差编码的上层决策：
 * - 脏区为空 → 画面无变化，跳过本帧
 * - 脏区覆盖率高 → 可考虑发送关键帧
 * - 脏区覆盖率低 → 增量帧，只传输变化部分（XOR 后大量 0 字节，LZ4 压缩效率极高）
 *
 * @property blocks 脏区矩形列表
 */
data class DirtyRegion(val blocks: List<Rect> = emptyList()) {
    /**
     * 脏区矩形，表示画面中一个 16×16 像素块的位置和大小。
     * @property x 左上角 X 坐标（像素）
     * @property y 左上角 Y 坐标（像素）
     * @property w 宽度（像素）
     * @property h 高度（像素）
     */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 是否无脏区（画面完全未变化） */
    fun isEmpty() = blocks.isEmpty()

    /** 脏区覆盖率（简化计算：脏块数量） */
    fun coverage(): Float = if (blocks.isEmpty()) 0f else blocks.size.toFloat()

    companion object {
        /** 创建全帧脏区（强制关键帧时使用） */
        fun full() = DirtyRegion(listOf(Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)))
    }
}
