package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 输入事件消息。
 *
 * 控制端捕获触摸/按键事件后发送给被控端，被控端通过
 * InputManager 注入系统输入事件，实现远程控制。
 *
 * 支持两种输入类型：
 * - 触摸：携带多点触控指针坐标和压力
 * - 按键：携带 Android KeyEvent keyCode 和 metaState
 *
 * 载荷格式（大端序）：
 * [inputType:1][action:1][pointerCount:1][keyCode:4][metaState:4]
 * [pointer0.x:2][pointer0.y:2][pointer0.pressure:2][pointer0.id:2]...
 *
 * @property inputType 输入类型（0=触摸, 1=按键）
 * @property action 动作（0=按下, 1=抬起, 2=移动）
 * @property pointerCount 触摸指针数量
 * @property keyCode Android 按键码（仅按键类型时使用）
 * @property metaState 修饰键状态（Shift/Ctrl/Alt 等）
 * @property pointers 触摸指针数组
 */
data class InputMessage(
    var inputType: Byte = INPUT_TYPE_TOUCH,
    var action: Byte = 0,
    var pointerCount: Byte = 1,
    var keyCode: Int = 0,
    var metaState: Int = 0,
    var pointers: Array<Pointer> = arrayOf(Pointer())
) : Message() {
    override val type = MessageType.INPUT

    /**
     * 触摸指针数据。
     * @property x X 坐标（屏幕像素）
     * @property y Y 坐标（屏幕像素）
     * @property pressure 压力值
     * @property pointerId 指针 ID（区分多点触控）
     */
    data class Pointer(
        var x: Short = 0,
        var y: Short = 0,
        var pressure: Short = 0,
        var pointerId: Short = 0
    )

    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + 4 + pointers.size * 8)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(inputType)
        buffer.put(action)
        buffer.put(pointerCount)
        buffer.putInt(keyCode)
        buffer.putInt(metaState)
        for (p in pointers) {
            buffer.putShort(p.x); buffer.putShort(p.y)
            buffer.putShort(p.pressure); buffer.putShort(p.pointerId)
        }
        return buffer.array()
    }

    companion object {
        /** 输入类型：触摸事件 */
        const val INPUT_TYPE_TOUCH: Byte = 0
        /** 输入类型：按键事件 */
        const val INPUT_TYPE_KEY: Byte = 1
        /** 动作：按下 */
        const val ACTION_DOWN: Byte = 0
        /** 动作：抬起 */
        const val ACTION_UP: Byte = 1
        /** 动作：移动 */
        const val ACTION_MOVE: Byte = 2

        fun deserialize(data: ByteArray): InputMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val inputType = buf.get(); val action = buf.get(); val ptrCount = buf.get()
            val keyCode = buf.int; val metaState = buf.int
            val pointers = Array(ptrCount.toInt()) {
                Pointer(buf.short, buf.short, buf.short, buf.short)
            }
            return InputMessage(inputType, action, ptrCount, keyCode, metaState, pointers)
        }
    }

    override fun equals(other: Any?) = other is InputMessage && inputType == other.inputType && action == other.action
    override fun hashCode() = inputType * 31 + action
}
