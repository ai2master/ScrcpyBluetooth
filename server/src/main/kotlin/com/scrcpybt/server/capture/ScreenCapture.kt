package com.scrcpybt.server.capture

/**
 * 屏幕捕获接口
 *
 * 定义屏幕捕获实现的通用行为。
 * 提供事件驱动的帧捕获机制，通过 FrameCallback 回调传递捕获的像素数据。
 *
 * ### 设计原则
 * - 事件驱动：当新帧可用时触发回调（由 SurfaceTexture.OnFrameAvailableListener 驱动）
 * - 异步处理：回调在后台线程执行，避免阻塞捕获管线
 * - 统一接口：支持多种捕获方式（物理屏幕镜像、虚拟显示器）
 *
 * Interface for screen capture implementations.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see SurfaceControlCapture
 * @see FrameProducer
 */
interface ScreenCapture {

    /**
     * 开始捕获帧
     *
     * 回调将在后台线程中被调用，每当有新帧可用时触发（通过 onFrameAvailable 事件驱动）。
     *
     * Start capturing frames. The callback will be invoked on a background thread
     * each time a new frame is available (event-driven via onFrameAvailable).
     *
     * @param callback 帧回调接口 | Frame callback
     */
    fun start(callback: FrameCallback)

    /**
     * 停止捕获
     *
     * Stop capturing.
     */
    fun stop()

    /**
     * 获取显示器信息
     *
     * Get display information.
     *
     * @return 显示器信息对象 | Display information object
     */
    fun getDisplayInfo(): DisplayInfo

    /**
     * 帧回调接口
     *
     * 接收捕获的帧数据和错误通知。
     *
     * Frame callback interface for receiving captured frame data and error notifications.
     */
    interface FrameCallback {
        /**
         * 当新帧可用时调用
         *
         * Called when a new frame is available.
         *
         * @param pixels ARGB 像素数组（大小为 width * height）| ARGB pixel array (width * height)
         * @param width 帧宽度 | Frame width
         * @param height 帧高度 | Frame height
         */
        fun onFrame(pixels: IntArray, width: Int, height: Int)

        /**
         * 捕获错误时调用
         *
         * Called when a capture error occurs.
         *
         * @param e 捕获异常 | Capture exception
         */
        fun onError(e: Exception)
    }
}
