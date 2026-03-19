package com.scrcpybt.app.codec

import android.graphics.Bitmap
import com.scrcpybt.common.codec.FrameCodec
import com.scrcpybt.common.protocol.message.FrameMessage
import com.scrcpybt.common.util.Logger

/**
 * 客户端帧解码器：将视频帧消息解码为 Android Bitmap
 *
 * 功能：
 * - 封装 FrameCodec（通用编解码器）
 * - 将解码后的像素数组转换为 Bitmap 对象
 * - 复用 Bitmap 对象减少内存分配和 GC 压力
 *
 * 解码流程：
 * 1. 接收 FrameMessage（包含压缩的像素数据）
 * 2. 使用 FrameCodec 解码为 ARGB 像素数组
 * 3. 将像素数组填充到 Bitmap 对象
 * 4. 返回 Bitmap 供渲染器使用
 *
 * 性能优化：
 * - Bitmap 复用：尺寸不变时重复使用同一个 Bitmap
 * - 避免频繁的对象创建和销毁
 */
class FrameDecoder {
    companion object {
        private const val TAG = "FrameDecoder"
    }

    /** 通用帧编解码器 */
    private val codec = FrameCodec()
    /** 可复用的 Bitmap 对象 */
    private var reusableBitmap: Bitmap? = null

    /**
     * 初始化解码器的已知帧尺寸
     *
     * @param width 帧宽度
     * @param height 帧高度
     */
    fun setDimensions(width: Int, height: Int) {
        codec.setDimensions(width, height)
        reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * 将 FrameMessage 解码为 Bitmap
     *
     * @param msg 接收到的帧消息
     * @return 解码后的 Bitmap，解码失败返回 null
     */
    fun decode(msg: FrameMessage): Bitmap? {
        return try {
            val pixels = codec.decode(msg) ?: return null
            if (pixels.isEmpty()) return null

            val width = msg.width.toInt()
            val height = msg.height.toInt()

            // 尺寸匹配时复用 Bitmap | Reuse Bitmap when dimensions match
            val bitmap = reusableBitmap
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            reusableBitmap?.setPixels(pixels, 0, width, 0, 0, width, height)
            reusableBitmap
        } catch (e: Exception) {
            Logger.e(TAG, "Decode error", e)
            null
        }
    }
}
