package com.scrcpybt.app.codec

import android.graphics.Bitmap
import com.scrcpybt.common.protocol.message.FrameMessage
import com.scrcpybt.common.util.Logger
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 后台帧解码线程：异步解码视频帧并通知监听器
 *
 * 工作流程：
 * 1. 接收服务端提交的 FrameMessage（通过队列）
 * 2. 在后台线程中调用 FrameDecoder 进行解码
 * 3. 将解码后的 Bitmap 通过回调传递给监听器（通常是 UI 层）
 *
 * 队列机制：
 * - 限制队列大小为 5，防止内存溢出
 * - 队列满时丢弃最旧的帧（保持低延迟）
 * - 阻塞等待新帧到达（避免空转占用 CPU）
 *
 * 性能优化：
 * - 解码在独立线程执行，不阻塞网络接收线程
 * - 帧丢弃策略确保始终显示最新画面
 */
class DecoderThread : Thread("FrameDecoder") {
    companion object {
        private const val TAG = "DecoderThread"
        /** 最大队列大小（帧缓冲数） */
        private const val MAX_QUEUE_SIZE = 5
    }

    /** 帧解码器 */
    private val decoder = FrameDecoder()
    /** 输入队列（待解码的帧） */
    private val inputQueue: BlockingQueue<FrameMessage> = LinkedBlockingQueue(MAX_QUEUE_SIZE)
    /** 运行标志 */
    @Volatile
    private var running = true
    /** 帧解码监听器 */
    private var listener: FrameListener? = null

    /**
     * 帧解码监听器接口
     */
    interface FrameListener {
        /** 帧解码完成回调 */
        fun onFrameDecoded(bitmap: Bitmap)
    }

    /**
     * 设置帧尺寸（初始化 Bitmap 缓冲区）| Set frame dimensions (initialize Bitmap buffer)
     *
     * @param width 帧宽度 | Frame width
     * @param height 帧高度 | Frame height
     */
    fun setDimensions(width: Int, height: Int) {
        decoder.setDimensions(width, height)
    }

    /**
     * 设置帧解码监听器 | Set frame listener
     *
     * @param listener 监听器实例 | Listener instance
     */
    fun setListener(listener: FrameListener) {
        this.listener = listener
    }

    /**
     * 提交一帧用于解码
     *
     * 队列满时会丢弃最旧的帧（保持低延迟）
     *
     * @param frame 待解码的帧消息
     */
    fun submitFrame(frame: FrameMessage) {
        while (!inputQueue.offer(frame)) {
            inputQueue.poll() // 丢弃最旧的帧
        }
    }

    /**
     * 线程主循环：持续从队列取帧并解码 | Thread main loop: continuously fetch and decode frames from queue
     *
     * 循环逻辑 | Loop Logic:
     * - 每 100ms 轮询一次队列（避免空转）| Poll queue every 100ms (avoid busy wait)
     * - 解码成功后通过回调传递 Bitmap | Pass Bitmap via callback after successful decoding
     * - 捕获中断异常并退出线程 | Catch interrupt exception and exit thread
     */
    override fun run() {
        Logger.i(TAG, "Decoder thread started")
        while (running) {
            try {
                // 阻塞等待新帧（超时 100ms）| Block wait for new frame (timeout 100ms)
                val msg = inputQueue.poll(100, TimeUnit.MILLISECONDS)
                if (msg != null) {
                    val bitmap = decoder.decode(msg)
                    if (bitmap != null) {
                        listener?.onFrameDecoded(bitmap)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Logger.i(TAG, "Decoder thread stopped")
    }

    /**
     * 停止解码线程 | Stop decoding thread
     *
     * 设置停止标志并中断线程（触发 InterruptedException）。| Set stop flag and interrupt thread (trigger InterruptedException).
     */
    fun stopDecoding() {
        running = false
        interrupt()
    }
}
