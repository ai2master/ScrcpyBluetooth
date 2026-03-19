package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 设备控制命令消息。
 *
 * 控制端向被控端发送的设备控制指令，如电源键、返回键、
 * 音量调节、屏幕旋转、虚拟显示器切换等。
 *
 * 载荷格式（大端序）：[command:1][param:4]
 *
 * @property command 控制命令类型
 * @property param 命令参数（不同命令含义不同）
 */
data class ControlMessage(
    var command: Byte = 0,
    var param: Int = 0
) : Message() {
    override val type = MessageType.CONTROL

    override fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        buf.put(command); buf.putInt(param)
        return buf.array()
    }

    companion object {
        /** 电源键 */
        const val CMD_POWER: Byte = 0
        /** 返回键 */
        const val CMD_BACK: Byte = 1
        /** 主页键 */
        const val CMD_HOME: Byte = 2
        /** 最近任务键 */
        const val CMD_RECENTS: Byte = 3
        /** 音量加 */
        const val CMD_VOLUME_UP: Byte = 4
        /** 音量减 */
        const val CMD_VOLUME_DOWN: Byte = 5
        /** 屏幕旋转 */
        const val CMD_ROTATE: Byte = 6
        /** 关闭屏幕 */
        const val CMD_SCREEN_OFF: Byte = 7
        /** 点亮屏幕 */
        const val CMD_SCREEN_ON: Byte = 8
        /** 展开通知栏 */
        const val CMD_EXPAND_NOTIFICATION: Byte = 9
        /** 强制解锁屏幕（需要 Root） */
        const val CMD_FORCE_UNLOCK: Byte = 10
        /** 恢复锁屏状态 */
        const val CMD_RESTORE_LOCK: Byte = 11
        /** 切换到虚拟显示器模式（RDP 模式） */
        const val CMD_ENABLE_VIRTUAL_DISPLAY: Byte = 12
        /** 切换回物理屏幕镜像模式 */
        const val CMD_DISABLE_VIRTUAL_DISPLAY: Byte = 13

        fun deserialize(data: ByteArray): ControlMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return ControlMessage(buf.get(), buf.int)
        }
    }
}
