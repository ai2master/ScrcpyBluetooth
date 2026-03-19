package com.scrcpybt.server.input

import com.scrcpybt.common.protocol.message.InputMessage
import kotlin.math.max
import kotlin.math.min

/**
 * 事件坐标转换器
 *
 * 将控制端屏幕坐标转换为被控端屏幕坐标。
 * 当控制端和被控端的屏幕分辨率不同时，需要进行坐标缩放。
 *
 * ### 使用场景
 * - 控制端可能使用平板或不同分辨率的手机
 * - 需要将触摸坐标从控制端分辨率映射到被控端分辨率
 * - 通常情况下是 1:1 映射（传输原始分辨率）
 *
 * ### 坐标变换
 * - 使用线性缩放：targetX = sourceX * (targetWidth / sourceWidth)
 * - 应用边界裁剪，确保坐标在有效范围内
 * - 仅处理触摸事件，按键事件无需坐标转换
 *
 * @param sourceWidth 控制端屏幕宽度
 * @param sourceHeight 控制端屏幕高度
 * @param targetWidth 被控端屏幕宽度
 * @param targetHeight 被控端屏幕高度
 */
class EventConverter(
    private var sourceWidth: Int,
    private var sourceHeight: Int,
    private var targetWidth: Int,
    private var targetHeight: Int
) {

    /**
     * 转换触摸坐标
     *
     * 将控制端屏幕空间的触摸坐标转换为被控端屏幕空间。
     * 由于我们传输原始分辨率，通常是 1:1 映射，
     * 但如果控制端显示器分辨率不同则需要缩放。
     *
     * @param msg 原始输入消息
     * @return 坐标转换后的输入消息
     */
    fun convertCoordinates(msg: InputMessage): InputMessage {
        if (msg.inputType != InputMessage.INPUT_TYPE_TOUCH) {
            return msg
        }
        if (msg.pointers == null) return msg

        // 如果源和目标分辨率相同，无需转换
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            return msg
        }

        val scaleX = targetWidth.toFloat() / sourceWidth
        val scaleY = targetHeight.toFloat() / sourceHeight

        val converted = msg.pointers!!.map { src ->
            InputMessage.Pointer(
                min(max(src.x * scaleX, 0f), (targetWidth - 1).toFloat()).toInt().toShort(),
                min(max(src.y * scaleY, 0f), (targetHeight - 1).toFloat()).toInt().toShort(),
                src.pressure,
                src.pointerId
            )
        }.toTypedArray()

        return InputMessage().apply {
            inputType = msg.inputType
            action = msg.action
            pointers = converted
        }
    }

    /**
     * 设置源（控制端）屏幕尺寸
     *
     * @param width 控制端宽度
     * @param height 控制端高度
     */
    fun setSourceDimensions(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
    }

    /**
     * 设置目标（被控端）屏幕尺寸
     *
     * @param width 被控端宽度
     * @param height 被控端高度
     */
    fun setTargetDimensions(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
    }
}
