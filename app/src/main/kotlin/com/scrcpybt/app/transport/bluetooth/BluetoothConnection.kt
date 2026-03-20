package com.scrcpybt.app.transport.bluetooth

import android.bluetooth.BluetoothSocket
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import java.io.InputStream
import java.io.OutputStream

/**
 * 蓝牙 RFCOMM 连接实现：封装 BluetoothSocket 为统一的 Connection 接口
 * Bluetooth RFCOMM connection implementation: wraps BluetoothSocket into unified Connection interface
 *
 * 实现了 Connection 接口的所有方法：
 * Implements all Connection interface methods:
 * - getInputStream/getOutputStream：获取输入输出流进行数据传输 | Get input/output streams for data transfer
 * - isConnected：检查连接状态 | Check connection state
 * - close：关闭连接 | Close connection
 * - getRemoteDeviceInfo：获取远程设备信息（设备名和 MAC 地址） | Get remote device info (name and MAC address)
 * - getTransportType：返回传输类型（BLUETOOTH_RFCOMM） | Return transport type (BLUETOOTH_RFCOMM)
 */
class BluetoothConnection(private val socket: BluetoothSocket) : Connection {
    override fun getInputStream(): InputStream = socket.inputStream

    override fun getOutputStream(): OutputStream = socket.outputStream

    override fun isConnected(): Boolean = socket.isConnected

    override fun close() = socket.close()

    override fun getRemoteDeviceInfo(): String {
        return "${socket.remoteDevice.name} [${socket.remoteDevice.address}]"
    }

    override fun getTransportType(): TransportType = TransportType.BLUETOOTH_RFCOMM
}
