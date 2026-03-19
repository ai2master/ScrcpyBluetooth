package com.scrcpybt.app.ui.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 视频帧渲染器：将解码后的帧绘制到 SurfaceView 上
 *
 * 核心功能：
 * - 保持视频画面的宽高比，避免拉伸变形
 * - 根据 SurfaceView 尺寸计算绘制位置（居中显示，黑边填充）
 * - 计算触摸坐标转换参数（缩放和偏移），供 TouchHandler 使用
 *
 * 坐标转换逻辑：
 * - scaleX/scaleY：从 SurfaceView 坐标系到视频帧坐标系的缩放比例
 * - offsetX/offsetY：黑边偏移量，用于处理 letterbox/pillarbox 场景
 */
class DisplayRenderer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback {
    /** 启用双线性过滤的画笔，提升缩放画质 */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** SurfaceView 宽度 */
    private var surfaceWidth = 0
    /** SurfaceView 高度 */
    private var surfaceHeight = 0

    /** 目标绘制区域（保持宽高比后的矩形） */
    private var destRect: Rect? = null

    /** X 轴触摸坐标缩放系数（SurfaceView -> 视频帧） */
    var scaleX = 1.0f
        private set
    /** Y 轴触摸坐标缩放系数（SurfaceView -> 视频帧） */
    var scaleY = 1.0f
        private set
    /** X 轴黑边偏移量 */
    var offsetX = 0
        private set
    /** Y 轴黑边偏移量 */
    var offsetY = 0
        private set

    init {
        surfaceView.holder.addCallback(this)
    }

    /**
     * 将解码后的视频帧渲染到 SurfaceView
     *
     * @param frame 解码后的帧位图
     */
    fun render(frame: Bitmap) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return

        try {
            // 清空背景为黑色
            canvas.drawColor(0xFF000000.toInt())

            // 计算保持宽高比的目标矩形
            computeDestRect(frame.width, frame.height)

            // 绘制视频帧
            val src = Rect(0, 0, frame.width, frame.height)
            destRect?.let {
                canvas.drawBitmap(frame, src, it, paint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * 计算保持宽高比的目标矩形和触摸坐标转换参数
     *
     * 采用 letterbox/pillarbox 策略：
     * - 如果视频宽高比大于 Surface 宽高比，则左右填充黑边
     * - 如果视频宽高比小于 Surface 宽高比，则上下填充黑边
     *
     * @param frameWidth 视频帧宽度
     * @param frameHeight 视频帧高度
     */
    private fun computeDestRect(frameWidth: Int, frameHeight: Int) {
        if (destRect != null) return // 只在必要时重新计算

        val frameAspect = frameWidth.toFloat() / frameHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight

        val (dstWidth, dstHeight) = if (frameAspect > surfaceAspect) {
            // 视频比 Surface 更宽 - 适应宽度
            surfaceWidth to (surfaceWidth / frameAspect).toInt()
        } else {
            // 视频比 Surface 更高 - 适应高度
            (surfaceHeight * frameAspect).toInt() to surfaceHeight
        }

        offsetX = (surfaceWidth - dstWidth) / 2
        offsetY = (surfaceHeight - dstHeight) / 2

        destRect = Rect(offsetX, offsetY, offsetX + dstWidth, offsetY + dstHeight)

        // 计算触摸坐标转换的缩放系数（Surface 坐标 -> 视频帧坐标）
        scaleX = frameWidth.toFloat() / dstWidth
        scaleY = frameHeight.toFloat() / dstHeight
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        destRect = null // 强制重新计算绘制区域
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
