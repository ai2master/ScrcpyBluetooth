package com.scrcpybt.common.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 传输连接抽象接口。
 *
 * 定义蓝牙 RFCOMM 和 USB ADB 两种传输方式的统一接口。
 * 上层协议栈（消息读写器、加密通道）通过此接口操作底层流，
 * 实现传输方式的可插拔替换。
 *
 * 实现类：
 * - [com.scrcpybt.app.transport.bluetooth.BluetoothConnection]：蓝牙 RFCOMM 连接
 * - [com.scrcpybt.app.transport.usb.AdbConnection]：USB ADB forward 连接
 */
interface Connection : Closeable {
    /** 获取输入流（用于接收数据） */
    fun getInputStream(): InputStream
    /** 获取输出流（用于发送数据） */
    fun getOutputStream(): OutputStream
    /** 连接是否仍然有效 */
    fun isConnected(): Boolean
    /** 获取远端设备信息（用于日志和 UI 显示） */
    fun getRemoteDeviceInfo(): String
    /** 获取当前连接使用的传输类型 */
    fun getTransportType(): TransportType
}
