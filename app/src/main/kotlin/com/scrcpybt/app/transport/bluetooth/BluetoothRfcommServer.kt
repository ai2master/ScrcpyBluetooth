package com.scrcpybt.app.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.util.UUID

/**
 * 蓝牙 RFCOMM 服务端：用于中继端角色，监听来自控制端的连接
 *
 * 工作流程：
 * 1. 使用 BluetoothAdapter 创建 RFCOMM ServerSocket
 * 2. 监听指定 SERVICE_UUID 的连接请求
 * 3. 接受连接并返回封装的 Connection 对象
 *
 * 典型使用场景：
 * - 中继端设备等待控制端连接
 * - 被控端设备等待控制端或中继端连接
 */
class BluetoothRfcommServer {
    companion object {
        private const val TAG = "BTRfcommServer"
        /** ScrcpyBT 服务的 UUID */
        private val SERVICE_UUID = UUID.fromString(ProtocolConstants.SERVICE_UUID)
    }

    /** 蓝牙服务端 Socket */
    private var serverSocket: BluetoothServerSocket? = null

    /**
     * 监听并接受一个传入的 RFCOMM 连接
     *
     * 此方法会阻塞，直到有客户端连接或发生错误
     *
     * @return 已接受的连接对象
     * @throws IOException 蓝牙不可用或连接失败时抛出
     */
    fun accept(): Connection {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Bluetooth not available")

        Logger.i(TAG, "Listening for RFCOMM connections...")
        serverSocket = try {
            adapter.listenUsingRfcommWithServiceRecord("ScrcpyBT", SERVICE_UUID)
        } catch (e: SecurityException) {
            throw IOException("缺少蓝牙权限 (BLUETOOTH_CONNECT/ADVERTISE)", e)
        }

        val socket = serverSocket?.accept()
            ?: throw IOException("Failed to accept connection")
        serverSocket?.close()

        // 安全检查：只接受已配对设备的连接
        try {
            val remoteDevice = socket.remoteDevice
            if (remoteDevice != null && remoteDevice.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                Logger.w(TAG, "拒绝未配对设备的连接")
                socket.close()
                throw IOException("拒绝未配对设备的连接")
            }
        } catch (e: SecurityException) {
            // 无法检查配对状态时继续（RFCOMM 本身要求配对）
            Logger.w(TAG, "无法检查设备配对状态", e)
        }

        val remoteName = try {
            socket.remoteDevice?.name ?: "unknown"
        } catch (e: SecurityException) {
            "unknown"
        }
        Logger.i(TAG, "Accepted connection from $remoteName")
        return BluetoothConnection(socket)
    }

    /**
     * 关闭服务端 Socket
     */
    fun close() {
        serverSocket?.let {
            try { it.close() } catch (ignored: IOException) {}
        }
    }
}
