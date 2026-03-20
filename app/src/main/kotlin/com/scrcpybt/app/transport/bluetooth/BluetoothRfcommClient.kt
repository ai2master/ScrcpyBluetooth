package com.scrcpybt.app.transport.bluetooth

import android.bluetooth.BluetoothDevice
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.util.UUID

/**
 * 蓝牙 RFCOMM 客户端：连接到被控设备的服务端 | Bluetooth RFCOMM client: connects to controlled device's server
 *
 * 工作流程：| Workflow:
 * 1. 使用预定义的 SERVICE_UUID 创建 RFCOMM Socket | Create RFCOMM Socket with predefined SERVICE_UUID
 * 2. 连接到目标蓝牙设备 | Connect to target Bluetooth device
 * 3. 返回封装的 Connection 对象供上层使用 | Return wrapped Connection object for upper layer
 *
 * RFCOMM 是蓝牙的串口仿真协议，类似于 TCP Socket
 * RFCOMM is Bluetooth's serial port emulation protocol, similar to TCP Socket
 */
class BluetoothRfcommClient {
    companion object {
        private const val TAG = "BTRfcommClient"
        /** ScrcpyBT 服务的 UUID */
        private val SERVICE_UUID = UUID.fromString(ProtocolConstants.SERVICE_UUID)
    }

    /**
     * 连接到运行 ScrcpyBT 服务端的蓝牙设备 | Connect to Bluetooth device running ScrcpyBT server
     *
     * @param device 目标蓝牙设备 | Target Bluetooth device
     * @return 与服务端的连接对象 | Connection object to server
     * @throws IOException 连接失败时抛出 | Thrown when connection fails
     */
    fun connect(device: BluetoothDevice): Connection {
        val deviceName: String
        val deviceAddr: String
        try {
            // 安全检查：只连接已配对设备，防止未授权连接
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException("目标设备未配对，拒绝连接 (bondState=${device.bondState})")
            }
            deviceName = device.name ?: "unknown"
            deviceAddr = device.address ?: "unknown"
        } catch (e: SecurityException) {
            throw IOException("缺少蓝牙连接权限 (BLUETOOTH_CONNECT)", e)
        }
        Logger.i(TAG, "Connecting to $deviceName [$deviceAddr]")

        val socket = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (e: SecurityException) {
            throw IOException("缺少蓝牙连接权限 (BLUETOOTH_CONNECT)", e)
        }

        try {
            socket.connect()
            Logger.i(TAG, "Connected to $deviceName")
            return BluetoothConnection(socket)
        } catch (e: SecurityException) {
            socket.close()
            throw IOException("缺少蓝牙连接权限 (BLUETOOTH_CONNECT)", e)
        } catch (e: IOException) {
            Logger.e(TAG, "Connection failed", e)
            socket.close()
            throw e
        }
    }
}
