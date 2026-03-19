package com.scrcpybt.app.transport.usb

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * USB ADB 连接实现：支持两种连接模式
 *
 * 连接模式：
 * 1. 抽象 Unix Socket（同设备场景）：
 *    - 服务端和客户端在同一设备上
 *    - 直接通过抽象 Socket 通信，无需网络
 *
 * 2. TCP 回环（跨设备场景）：
 *    - 控制端设备通过 ADB forward 设置端口转发
 *    - 控制端连接到 localhost:PORT
 *    - ADB 将数据通过 USB 线缆隧道传输到被控端的抽象 Socket
 *
 * 典型使用场景：
 * - 控制端：使用 TCP 模式连接（通过 ADB forward）
 * - 被控端：使用抽象 Socket 模式监听
 * - 中继端：可能同时使用两种模式
 */
class AdbConnection : Connection {
    companion object {
        private const val TAG = "AdbConnection"
    }

    /** TCP Socket（用于 ADB forward 场景） */
    private var tcpSocket: Socket? = null
    /** 抽象 Unix Socket（用于同设备场景） */
    private var localSocket: LocalSocket? = null
    /** 连接状态 */
    private var connected = false

    /**
     * 通过 TCP 连接到 ADB 转发的端口（控制端侧）
     *
     * 前提条件：
     * - 控制端已设置 `adb forward tcp:PORT localabstract:scrcpy_bt`
     * - 然后连接到 localhost:PORT
     *
     * @param port ADB 转发的本地端口
     * @throws IOException 连接失败时抛出
     */
    fun connectTcp(port: Int) {
        Logger.i(TAG, "Connecting via TCP to localhost:$port")
        tcpSocket = Socket("127.0.0.1", port)
        connected = true
        Logger.i(TAG, "TCP connection established")
    }

    /**
     * 通过抽象 Unix Socket 连接（同设备或服务端侧）
     *
     * @param socketName 抽象 Socket 名称（例如 "scrcpy_bt"）
     * @throws IOException 连接失败时抛出
     */
    fun connectLocal(socketName: String) {
        Logger.i(TAG, "Connecting via abstract socket: $socketName")
        localSocket = LocalSocket().apply {
            connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        }
        connected = true
        Logger.i(TAG, "Local socket connection established")
    }

    override fun getInputStream(): InputStream {
        return tcpSocket?.getInputStream()
            ?: localSocket?.inputStream
            ?: throw IOException("Not connected")
    }

    override fun getOutputStream(): OutputStream {
        return tcpSocket?.getOutputStream()
            ?: localSocket?.outputStream
            ?: throw IOException("Not connected")
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        connected = false
        tcpSocket?.close()
        localSocket?.close()
    }

    override fun getRemoteDeviceInfo(): String = "USB ADB"

    override fun getTransportType(): TransportType = TransportType.USB_ADB
}
