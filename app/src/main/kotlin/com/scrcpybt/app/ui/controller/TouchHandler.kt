package com.scrcpybt.app.ui.controller

import android.view.MotionEvent
import com.scrcpybt.common.protocol.message.InputMessage
import kotlin.math.max

/**
 * 触摸事件处理器：将控制端 SurfaceView 的 MotionEvent 转换为 InputMessage。
 * 负责触摸事件的完整转换流程，包括动作类型映射、坐标转换、多点触控支持和压力值提取。
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
 *
 * Touch event handler: converts MotionEvent from controller's SurfaceView to InputMessage.
 * Responsible for complete touch event conversion process, including action type mapping,
 * coordinate conversion, multi-touch support, and pressure value extraction.
 *
 * Main responsibilities:
 * - Maps Android touch event types to protocol-defined action types
 * - Converts SurfaceView coordinates to remote device screen coordinates
 * - Handles multi-touch, supports multiple simultaneous finger operations
 * - Extracts touch pressure information for more realistic touch simulation
 *
 * Coordinate conversion formula:
 * - remoteX = (surfaceX - offsetX) * scaleX
 * - remoteY = (surfaceY - offsetY) * scaleY
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class TouchHandler {
    /**
     * 将 MotionEvent 转换为 InputMessage，完成坐标空间转换和动作类型映射。
     *
     * Converts MotionEvent to InputMessage, completing coordinate space conversion and action type mapping.
     *
     * @param event 触摸事件 | Touch event
     * @param scaleX X 轴缩放系数（Surface 坐标 -> 视频帧坐标）| X-axis scale factor (Surface coordinates -> video frame coordinates)
     * @param scaleY Y 轴缩放系数（Surface 坐标 -> 视频帧坐标）| Y-axis scale factor (Surface coordinates -> video frame coordinates)
     * @param offsetX X 轴偏移量（用于处理 letterbox）| X-axis offset (for handling letterbox)
     * @param offsetY Y 轴偏移量（用于处理 pillarbox）| Y-axis offset (for handling pillarbox)
     * @return 可发送到服务端的输入消息 | Input message that can be sent to server
     */
    fun convert(event: MotionEvent, scaleX: Float, scaleY: Float, offsetX: Int, offsetY: Int): InputMessage {
        val msg = InputMessage()
        msg.inputType = InputMessage.INPUT_TYPE_TOUCH

        // 转换触摸动作类型 | Convert touch action type
        msg.action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> InputMessage.ACTION_DOWN
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> InputMessage.ACTION_UP
            MotionEvent.ACTION_MOVE -> InputMessage.ACTION_MOVE
            else -> InputMessage.ACTION_MOVE
        }

        // 转换所有触摸点（支持多点触控）| Convert all touch points (supports multi-touch)
        val pointerCount = event.pointerCount
        val pointers = Array(pointerCount) { i ->
            // 将 Surface 坐标映射为远程屏幕坐标 | Map Surface coordinates to remote screen coordinates
            var x = (event.getX(i) - offsetX) * scaleX
            var y = (event.getY(i) - offsetY) * scaleY

            // 将坐标限制在有效范围内 | Clamp coordinates to valid range
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
