package com.scrcpybt.app.ui.controller

import android.view.MotionEvent
import com.scrcpybt.common.protocol.message.InputMessage
import kotlin.math.max

/**
 * 触摸事件处理器：将控制端 SurfaceView 的 MotionEvent 转换为 InputMessage
 *
 * 主要职责：
 * - 将 Android 触摸事件类型映射为协议定义的动作类型
 * - 将 SurfaceView 坐标转换为远程设备的屏幕坐标
 * - 处理多点触控（multi-touch），支持多个手指同时操作
 * - 提取触摸压力信息，实现更真实的触摸模拟
 *
 * 坐标转换公式：
 * - remoteX = (surfaceX - offsetX) * scaleX
 * - remoteY = (surfaceY - offsetY) * scaleY
 */
class TouchHandler {
    /**
     * 将 MotionEvent 转换为 InputMessage
     *
     * @param event 触摸事件
     * @param scaleX X 轴缩放系数（Surface 坐标 -> 视频帧坐标）
     * @param scaleY Y 轴缩放系数（Surface 坐标 -> 视频帧坐标）
     * @param offsetX X 轴偏移量（用于处理 letterbox）
     * @param offsetY Y 轴偏移量（用于处理 pillarbox）
     * @return 可发送到服务端的输入消息
     */
    fun convert(event: MotionEvent, scaleX: Float, scaleY: Float, offsetX: Int, offsetY: Int): InputMessage {
        val msg = InputMessage()
        msg.inputType = InputMessage.INPUT_TYPE_TOUCH

        // 转换触摸动作类型
        msg.action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> InputMessage.ACTION_DOWN
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> InputMessage.ACTION_UP
            MotionEvent.ACTION_MOVE -> InputMessage.ACTION_MOVE
            else -> InputMessage.ACTION_MOVE
        }

        // 转换所有触摸点（支持多点触控）
        val pointerCount = event.pointerCount
        val pointers = Array(pointerCount) { i ->
            // 将 Surface 坐标映射为远程屏幕坐标
            var x = (event.getX(i) - offsetX) * scaleX
            var y = (event.getY(i) - offsetY) * scaleY

            // 将坐标限制在有效范围内
            x = max(0f, x)
            y = max(0f, y)

            val pressure = event.getPressure(i)
            val pointerId = event.getPointerId(i)

            InputMessage.Pointer(
                x.toInt().toShort(),
                y.toInt().toShort(),
                (pressure * 65535).toInt().toShort(),
                pointerId.toShort()
            )
        }

        msg.pointers = pointers
        return msg
    }
}
