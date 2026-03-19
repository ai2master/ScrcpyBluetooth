package com.scrcpybt.server.transport

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Unix Abstract Socket 服务端传输层
 *
 * 用于 USB ADB 连接模式，监听 abstract socket 上的传入连接。
 *
 * ### ADB 端口转发原理
 * 控制端应用使用以下命令建立 USB 隧道：
 * ```bash
 * adb forward localabstract:scrcpy_bt tcp:PORT
 * ```
 * 然后连接到转发的 TCP 端口。本服务端监听 abstract socket 侧。
 *
 * ### Abstract Socket
 * - Linux 特有的命名空间 socket，不在文件系统中创建文件
 * - 名称以 `\0` 开头（在 Android 中直接使用字符串名称）
 * - 自动清理，无需担心残留文件
 * - 仅限本机通信，适合 ADB 转发场景
 *
 * ### 安全性
 * - Abstract socket 仅允许本机连接
 * - 结合 ADB USB 连接，提供物理隔离的安全通道
 * - 应用层仍使用 AES-256-GCM 加密
 *
 * @param socketName Abstract socket 名称（默认使用 ProtocolConstants.ADB_SOCKET_NAME）
 * @see ServerTransport
 * @see LocalServerSocket
 */
class UnixSocketServerTransport(
    private val socketName: String = ProtocolConstants.ADB_SOCKET_NAME
) : ServerTransport {

    /** Unix 域 socket 服务端 */
    private var serverSocket: LocalServerSocket? = null

    override fun accept(): Connection {
        Logger.i(TAG, "Listening on abstract socket: $socketName")
        serverSocket = LocalServerSocket(socketName)
        val socket = serverSocket!!.accept()
        serverSocket!!.close()
        Logger.i(TAG, "Accepted connection on $socketName")
        return UnixSocketConnection(socket)
    }

    override fun close() {
        serverSocket?.close()
    }

    /**
     * Unix Socket 连接实现
     *
     * 封装 LocalSocket，实现 Connection 接口。
     *
     * @param socket Unix 域 socket
     */
    private class UnixSocketConnection(private val socket: LocalSocket) : Connection {

        override fun getInputStream(): InputStream = socket.inputStream

        override fun getOutputStream(): OutputStream = socket.outputStream

        override fun isConnected(): Boolean = socket.isConnected

        override fun close() {
            socket.close()
        }

        override fun getRemoteDeviceInfo(): String = "USB ADB (abstract socket)"

        override fun getTransportType(): TransportType = TransportType.USB_ADB
    }

    companion object {
        private const val TAG = "UnixSocketTransport"
    }
}
