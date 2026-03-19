package com.scrcpybt.common.protocol

/**
 * 通信协议消息类型枚举。
 *
 * 定义了控制端与被控端之间所有可能的消息类型。
 * 每种类型对应一个唯一的字节 ID，用于消息头的 type 字段。
 *
 * 消息分为以下几类：
 * - 连接建立：HANDSHAKE, KEY_EXCHANGE, AUTH
 * - 屏幕传输：FRAME, INPUT, CONTROL
 * - 数据传输：CLIPBOARD, FILE_TRANSFER, FOLDER_SYNC, SHARE_FORWARD
 * - 会话管理：HEARTBEAT, TRANSPORT_SWITCH
 */
enum class MessageType(val id: Byte) {
    /** 握手消息：交换设备信息、屏幕分辨率、协议版本 */
    HANDSHAKE(0x01),
    /** 密钥交换：ECDH P-256 公钥交换 */
    KEY_EXCHANGE(0x02),
    /** 帧数据：256 色编码的屏幕帧（关键帧或增量帧） */
    FRAME(0x03),
    /** 输入事件：触摸、按键等用户输入 */
    INPUT(0x04),
    /** 控制命令：返回、主页、电源、虚拟显示切换等 */
    CONTROL(0x05),
    /** 心跳：保持连接活跃，检测断连 */
    HEARTBEAT(0x06),
    /** 剪贴板：双向剪贴板内容传递 */
    CLIPBOARD(0x07),
    /** 文件传输：文件/文件夹的发送和接收 */
    FILE_TRANSFER(0x08),
    /** 文件夹同步：块级增量同步协议消息 */
    FOLDER_SYNC(0x09),
    /** 分享转发：将控制端的 ACTION_SEND 转发到被控端 */
    SHARE_FORWARD(0x0A),
    /** 认证：按功能粒度的 TOFU 设备认证 */
    AUTH(0x0B),
    /** 传输切换：蓝牙↔USB 无缝切换的协调消息 */
    TRANSPORT_SWITCH(0x0C);

    companion object {
        /** 根据字节 ID 查找消息类型，未找到时抛出 IOException（协议错误） */
        fun fromId(id: Byte): MessageType =
            entries.firstOrNull { it.id == id }
                ?: throw java.io.IOException("未知消息类型: 0x${id.toString(16).padStart(2, '0')}")
    }
}
