package com.scrcpybt.server.encoder

import com.scrcpybt.common.codec.FrameCodec
import com.scrcpybt.common.protocol.message.FrameMessage
import com.scrcpybt.common.util.Logger
import java.io.IOException

/**
 * 服务端帧编码器
 *
 * 封装 FrameCodec，负责将 ARGB 像素数组编码为 256 色帧消息。
 *
 * ### 编码流程
 * 1. 接收 ARGB 格式的原始像素数据
 * 2. 通过自适应调色板进行色彩量化（24位 → 8位）
 * 3. 使用 RLE（行程编码）压缩
 * 4. 周期性发送关键帧（包含完整调色板）
 *
 * ### 自适应调色板
 * - 使用 Median Cut 算法从当前帧提取 256 色调色板
 * - 关键帧包含完整的调色板数据
 * - 普通帧仅包含压缩的像素索引
 *
 * @param width 帧宽度
 * @param height 帧高度
 * @see FrameCodec
 */
class FrameEncoder(private var width: Int, private var height: Int) {

    /** 底层编解码器 */
    private val codec = FrameCodec().apply {
        setDimensions(width, height)
    }

    /**
     * 编码 ARGB 帧为 FrameMessage
     *
     * @param pixels ARGB 像素数组
     * @return 编码后的帧消息，如果编码失败则返回 null
     */
    fun encode(pixels: IntArray): FrameMessage? {
        return codec.encode(pixels)
    }

    /**
     * 处理显示器尺寸变化
     *
     * 用于处理屏幕旋转等导致的分辨率变化。
     * 会重置编码器状态和调色板。
     *
     * @param width 新宽度
     * @param height 新高度
     */
    fun setDimensions(width: Int, height: Int) {
        this.width = width
        this.height = height
        codec.setDimensions(width, height)
        Logger.i(TAG, "Dimensions updated: ${width}x$height")
    }

    /** 获取当前帧宽度 */
    fun getWidth(): Int = width

    /** 获取当前帧高度 */
    fun getHeight(): Int = height

    /** 获取已编码的帧数量 */
    fun getFrameCount(): Int = codec.getFrameCount()

    companion object {
        private const val TAG = "FrameEncoder"
    }
}
