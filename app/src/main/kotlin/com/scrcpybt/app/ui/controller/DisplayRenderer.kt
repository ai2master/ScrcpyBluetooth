package com.scrcpybt.app.ui.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 视频帧渲染器：将解码后的帧绘制到 SurfaceView 上，保持正确的宽高比和居中显示。
 * 负责视频帧的高质量渲染，并计算触摸坐标转换所需的参数。
 *
 * 核心功能：
 * - 保持视频画面的宽高比，避免拉伸变形
 * - 根据 SurfaceView 尺寸计算绘制位置（居中显示，黑边填充）
 * - 计算触摸坐标转换参数（缩放和偏移），供 TouchHandler 使用
 *
 * 坐标转换逻辑：
 * - scaleX/scaleY：从 SurfaceView 坐标系到视频帧坐标系的缩放比例
 * - offsetX/offsetY：黑边偏移量，用于处理 letterbox/pillarbox 场景
 *
 * Video frame renderer: draws decoded frames to SurfaceView with correct aspect ratio and centered display.
 * Responsible for high-quality video frame rendering and calculating parameters needed for touch coordinate conversion.
 *
 * Core features:
 * - Maintains video aspect ratio, prevents stretching distortion
 * - Calculates drawing position based on SurfaceView dimensions (centered display with black borders)
 * - Calculates touch coordinate conversion parameters (scale and offset) for TouchHandler use
 *
 * Coordinate conversion logic:
 * - scaleX/scaleY: Scaling ratio from SurfaceView coordinate system to video frame coordinate system
 * - offsetX/offsetY: Black border offset, handles letterbox/pillarbox scenarios
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class DisplayRenderer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback {
    /** 启用双线性过滤的画笔，提升缩放画质 | Paint with bilinear filtering enabled, improves scaling quality */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** SurfaceView 宽度 | SurfaceView width */
    private var surfaceWidth = 0
    /** SurfaceView 高度 | SurfaceView height */
    private var surfaceHeight = 0

    /** 目标绘制区域（保持宽高比后的矩形）| Target drawing area (rectangle after maintaining aspect ratio) */
    private var destRect: Rect? = null

    /** X 轴触摸坐标缩放系数（SurfaceView -> 视频帧）| X-axis touch coordinate scale factor (SurfaceView -> video frame) */
    var scaleX = 1.0f
        private set
    /** Y 轴触摸坐标缩放系数（SurfaceView -> 视频帧）| Y-axis touch coordinate scale factor (SurfaceView -> video frame) */
    var scaleY = 1.0f
        private set
    /** X 轴黑边偏移量 | X-axis black border offset */
    var offsetX = 0
        private set
    /** Y 轴黑边偏移量 | Y-axis black border offset */
    var offsetY = 0
        private set

    init {
        surfaceView.holder.addCallback(this)
    }

    /**
     * 将解码后的视频帧渲染到 SurfaceView，保持宽高比并居中显示。
     *
     * Renders decoded video frame to SurfaceView with aspect ratio maintained and centered display.
     *
     * @param frame 解码后的帧位图 | Decoded frame bitmap
     */
    fun render(frame: Bitmap) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return

        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return

        try {
            // 清空背景为黑色 | Clear background to black
            canvas.drawColor(0xFF000000.toInt())

            // 计算保持宽高比的目标矩形 | Calculate target rectangle maintaining aspect ratio
            computeDestRect(frame.width, frame.height)

            // 绘制视频帧 | Draw video frame
            val src = Rect(0, 0, frame.width, frame.height)
            destRect?.let {
                canvas.drawBitmap(frame, src, it, paint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * 计算保持宽高比的目标矩形和触摸坐标转换参数。
     * 采用 letterbox/pillarbox 策略，根据视频和 Surface 的宽高比关系决定填充方向：
     * - 如果视频宽高比大于 Surface 宽高比，则左右填充黑边
     * - 如果视频宽高比小于 Surface 宽高比，则上下填充黑边
     *
     * Calculates target rectangle maintaining aspect ratio and touch coordinate conversion parameters.
     * Uses letterbox/pillarbox strategy, determining fill direction based on aspect ratio relationship:
     * - If video aspect ratio > Surface aspect ratio, add black borders on left/right
     * - If video aspect ratio < Surface aspect ratio, add black borders on top/bottom
     *
     * @param frameWidth 视频帧宽度 | Video frame width
     * @param frameHeight 视频帧高度 | Video frame height
     */
    private fun computeDestRect(frameWidth: Int, frameHeight: Int) {
        if (destRect != null) return // 只在必要时重新计算 | Only recalculate when necessary

        val frameAspect = frameWidth.toFloat() / frameHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight

        val (dstWidth, dstHeight) = if (frameAspect > surfaceAspect) {
            // 视频比 Surface 更宽 - 适应宽度 | Video wider than Surface - fit to width
            surfaceWidth to (surfaceWidth / frameAspect).toInt()
        } else {
            // 视频比 Surface 更高 - 适应高度 | Video taller than Surface - fit to height
            (surfaceHeight * frameAspect).toInt() to surfaceHeight
        }

        offsetX = (surfaceWidth - dstWidth) / 2
        offsetY = (surfaceHeight - dstHeight) / 2

        destRect = Rect(offsetX, offsetY, offsetX + dstWidth, offsetY + dstHeight)

        // 计算触摸坐标转换的缩放系数（Surface 坐标 -> 视频帧坐标）| Calculate scale factors for touch coordinate conversion (Surface coordinates -> video frame coordinates)
        scaleX = frameWidth.toFloat() / dstWidth
        scaleY = frameHeight.toFloat() / dstHeight
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        destRect = null // 强制重新计算绘制区域 | Force recalculation of drawing area
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
