package com.scrcpybt.server.capture

import com.scrcpybt.common.codec.FrameCodec
import com.scrcpybt.common.protocol.message.FrameMessage
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * 帧生产者
 *
 * 连接屏幕捕获和帧编码管线，负责以下功能：
 * 1. 接收来自 ScreenCapture 的原始像素数据
 * 2. 通过 FrameCodec 编码为压缩格式
 * 3. 管理帧率限制以避免占用过多蓝牙带宽
 * 4. 维护有界队列，丢弃旧帧以保留最新帧
 *
 * ### 设计原理
 * - 实现 ScreenCapture.FrameCallback 接口，由捕获模块回调
 * - 使用 ArrayBlockingQueue 缓冲编码后的帧
 * - 通过时间戳控制帧率，避免超过设定的最大 FPS
 * - 队列满时丢弃最旧的帧（优先保留最新画面）
 *
 * @param codec 帧编解码器
 * @see ScreenCapture
 * @see FrameCodec
 */
class FrameProducer(private val codec: FrameCodec) : ScreenCapture.FrameCallback {

    /** 输出队列（缓冲编码后的帧消息） */
    private val outputQueue: BlockingQueue<FrameMessage> = ArrayBlockingQueue(MAX_QUEUED_FRAMES)

    /** 运行状态标志 */
    @Volatile
    private var running = true

    /** 帧之间的最小间隔（毫秒）- 用于带宽节流 */
    private var minFrameIntervalMs: Long = 33 // ~30fps

    /** 上一帧的时间戳 */
    private var lastFrameTime: Long = 0

    /** 专用编码线程：将编码从捕获回调中解耦，避免阻塞捕获 */
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * 接收新帧回调
     *
     * 对帧进行编码并放入输出队列。如果距离上一帧时间过短（帧率限制），则跳过该帧。
     * 如果队列已满，则丢弃最旧的帧以保留最新画面。
     *
     * @param pixels ARGB 格式的像素数组
     * @param width 帧宽度
     * @param height 帧高度
     */
    override fun onFrame(pixels: IntArray, width: Int, height: Int) {
        if (!running) return

        // 帧率限制
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameIntervalMs) {
            return
        }
        lastFrameTime = now

        // 复制像素数据后提交到编码线程，避免阻塞捕获回调
        val pixelsCopy = pixels.copyOf()
        encoderExecutor.submit {
            try {
                val msg = codec.encode(pixelsCopy)
                if (msg != null) {
                    // 如果队列满了就丢弃旧帧（优先保留最新帧）
                    while (!outputQueue.offer(msg)) {
                        outputQueue.poll()
                    }
                }
            } catch (e: IOException) {
                Logger.e(TAG, "Frame encoding error", e)
            }
        }
    }

    /**
     * 捕获错误回调
     *
     * @param e 捕获过程中发生的异常
     */
    override fun onError(e: Exception) {
        Logger.e(TAG, "Capture error", e)
    }

    /**
     * 获取下一个编码后的帧
     *
     * 从输出队列中取出一帧，如果队列为空则阻塞等待。
     *
     * @return 编码后的帧消息，如果已停止则返回 null
     */
    fun takeFrame(): FrameMessage? {
        return outputQueue.poll(100, TimeUnit.MILLISECONDS)
    }

    /**
     * 停止帧生产
     *
     * 停止生产新帧并清空输出队列。
     */
    fun stop() {
        running = false
        encoderExecutor.shutdown()
        try {
            encoderExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            encoderExecutor.shutdownNow()
        }
        outputQueue.clear()
    }

    /**
     * 设置最大帧率
     *
     * @param fps 目标帧率（每秒帧数），必须大于 0
     */
    fun setMaxFrameRate(fps: Int) {
        if (fps > 0) {
            minFrameIntervalMs = 1000L / fps
        }
    }

    companion object {
        private const val TAG = "FrameProducer"

        /** 最大队列帧数（防止内存压力） */
        private const val MAX_QUEUED_FRAMES = 3
    }
}
