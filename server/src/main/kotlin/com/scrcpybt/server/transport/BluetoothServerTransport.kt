package com.scrcpybt.server.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 蓝牙 RFCOMM 服务端传输层
 *
 * 监听并接受传入的安全 RFCOMM 连接。
 *
 * ### 工作流程
 * 1. 调用 BluetoothAdapter.listenUsingRfcommWithServiceRecord() 创建服务端 socket
 * 2. 注册 SDP 服务记录，使用预定义的 UUID
 * 3. 阻塞等待客户端连接
 * 4. 接受连接后关闭服务端 socket
 * 5. 返回封装的连接对象
 *
 * ### 技术细节
 * - 使用安全 RFCOMM（加密链路层）
 * - 服务 UUID 在 ProtocolConstants 中定义
 * - 每次只接受一个连接（单会话）
 *
 * @see ServerTransport
 * @see BluetoothAdapter
 */
class BluetoothServerTransport : ServerTransport {

    /** 蓝牙服务端 Socket */
    private var serverSocket: BluetoothServerSocket? = null

    override fun accept(): Connection {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Bluetooth not available")

        Logger.i(TAG, "Listening for RFCOMM connections...")
        serverSocket = adapter.listenUsingRfcommWithServiceRecord("ScrcpyBT", SERVICE_UUID)

        val socket = serverSocket!!.accept()
        serverSocket!!.close()

        val deviceName = socket.remoteDevice.name
        val deviceAddress = socket.remoteDevice.address
        Logger.i(TAG, "Accepted connection from: $deviceName [$deviceAddress]")

        return BluetoothConnectionImpl(socket)
    }

    override fun close() {
        serverSocket?.close()
    }

    /**
     * 蓝牙连接实现
     *
     * 封装 BluetoothSocket，实现 Connection 接口。
     *
     * @param socket 蓝牙 Socket
     */
    private class BluetoothConnectionImpl(private val socket: BluetoothSocket) : Connection {

        override fun getInputStream(): InputStream = socket.inputStream

        override fun getOutputStream(): OutputStream = socket.outputStream

        override fun isConnected(): Boolean = socket.isConnected

        override fun close() {
            socket.close()
        }

        override fun getRemoteDeviceInfo(): String {
            return "${socket.remoteDevice.name} [${socket.remoteDevice.address}]"
        }

        override fun getTransportType(): TransportType = TransportType.BLUETOOTH_RFCOMM
    }

    companion object {
        private const val TAG = "BTServerTransport"

        /** 蓝牙服务 UUID（在 ProtocolConstants 中定义） */
        private val SERVICE_UUID = UUID.fromString(ProtocolConstants.SERVICE_UUID)
    }
}
