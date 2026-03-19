package com.scrcpybt.common.protocol

/**
 * 通信协议常量定义。
 *
 * 包含消息格式、标志位、传输参数和服务标识符等全局常量。
 */
object ProtocolConstants {
    /** 消息魔数，ASCII "SCRB"，用于验证消息头合法性 */
    const val MAGIC = 0x53435242 // "SCRB"

    /** 协议版本号 */
    const val VERSION = 1

    /** 消息头固定大小：4(magic) + 1(type) + 1(flags) + 4(length) + 4(sequence) = 14 字节 */
    const val HEADER_SIZE = 14

    /** 载荷最大大小：16MB，主要用于文件传输场景 */
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB

    /** 心跳间隔：每 5 秒发送一次心跳消息 */
    const val HEARTBEAT_INTERVAL_MS = 5000L

    // ---- 消息标志位 ----

    /** 加密标志：载荷已通过 AES-256-GCM 加密 */
    const val FLAG_ENCRYPTED: Byte = 0x01

    /** 压缩标志：载荷已通过 LZ4 压缩 */
    const val FLAG_COMPRESSED: Byte = 0x02

    /** ACK 标志：接收方需要回复确认 */
    const val FLAG_ACK_REQUIRED: Byte = 0x04

    // ---- 文件传输 ----

    /** 文件传输分块大小：32KB */
    const val FILE_CHUNK_SIZE = 32 * 1024

    // ---- 蓝牙 ----

    /** 蓝牙 RFCOMM 服务 UUID，控制端和被控端使用相同 UUID 建立连接 */
    const val SERVICE_UUID = "fa87c0d0-afac-11de-8a39-0800200c9a66"

    // ---- USB ADB ----

    /** Unix 抽象 socket 名称，用于 ADB forward 隧道连接 */
    const val ADB_SOCKET_NAME = "scrcpy_bt"
}
