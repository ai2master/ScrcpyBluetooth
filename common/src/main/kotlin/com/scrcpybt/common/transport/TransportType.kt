package com.scrcpybt.common.transport

/**
 * 传输类型枚举。
 *
 * 定义两种支持的物理传输方式（禁止 IP 层连接）：
 * - [BLUETOOTH_RFCOMM]：蓝牙 RFCOMM 串行端口，支持无线连接
 * - [USB_ADB]：USB ADB forward 管道，通过 Unix 抽象 socket 通信
 *
 * @property cliName 命令行参数名称（服务端启动时指定传输方式）
 */
enum class TransportType(val cliName: String) {
    /** 蓝牙 RFCOMM 串行端口传输 */
    BLUETOOTH_RFCOMM("bluetooth"),
    /** USB ADB forward 管道传输 */
    USB_ADB("usb");

    companion object {
        /** 根据命令行参数名称解析传输类型 */
        fun fromCliName(name: String): TransportType = entries.first { it.cliName == name }
    }
}
