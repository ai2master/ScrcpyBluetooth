package com.scrcpybt.app.transport.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.scrcpybt.common.util.Logger

/**
 * 蓝牙设备管理器：处理设备发现和配对 | Bluetooth device manager: handles device discovery and pairing
 *
 * 主要功能：| Main features:
 * - 获取已配对设备列表 | Get list of paired devices
 * - 扫描发现附近的蓝牙设备 | Scan and discover nearby Bluetooth devices
 * - 管理蓝牙适配器状态 | Manage Bluetooth adapter state
 *
 * 使用场景：| Use cases:
 * - 控制端选择要连接的被控设备 | Controller selects controlled device to connect
 * - 中继端发现附近的设备 | Relay discovers nearby devices
 */
class BluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BTManager"
    }

    /** 蓝牙适配器 */
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    /** 设备发现监听器 */
    private var listener: DeviceDiscoveryListener? = null
    /** 已发现的设备列表（去重） */
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    /**
     * 设备发现监听器接口 | Device discovery listener interface
     */
    interface DeviceDiscoveryListener {
        /** 发现新设备 | Device found */
        fun onDeviceFound(device: BluetoothDevice)
        /** 开始扫描 | Discovery started */
        fun onDiscoveryStarted()
        /** 扫描完成 | Discovery finished */
        fun onDiscoveryFinished()
    }

    /** 蓝牙设备发现广播接收器 */
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            listener?.onDeviceFound(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener?.onDiscoveryFinished()
                }
            }
        }
    }

    /**
     * 获取已配对的设备列表 | Get list of paired devices
     *
     * @return 已配对的蓝牙设备列表 | List of paired Bluetooth devices
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        val result = mutableListOf<BluetoothDevice>()
        adapter?.let {
            try {
                val bonded = it.bondedDevices
                bonded?.let { devices -> result.addAll(devices) }
            } catch (e: SecurityException) {
                Logger.e(TAG, "获取已配对设备失败：缺少 BLUETOOTH_CONNECT 权限", e)
            }
        }
        return result
    }

    /**
     * 开始设备发现（扫描附近的蓝牙设备） | Start device discovery (scan nearby Bluetooth devices)
     *
     * @param listener 设备发现监听器 | Device discovery listener
     */
    fun startDiscovery(listener: DeviceDiscoveryListener) {
        this.listener = listener
        discoveredDevices.clear()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

        adapter?.let {
            if (it.isEnabled) {
                try {
                    it.startDiscovery()
                    listener.onDiscoveryStarted()
                    Logger.i(TAG, "Discovery started")
                } catch (e: SecurityException) {
                    Logger.e(TAG, "启动设备发现失败：缺少 BLUETOOTH_SCAN 权限", e)
                    listener.onDiscoveryFinished()
                }
            }
        }
    }

    /**
     * 停止设备发现 | Stop device discovery
     */
    fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Logger.w(TAG, "停止设备发现失败：缺少权限", e)
        }
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (ignored: IllegalArgumentException) {}
    }

    /**
     * 检查蓝牙是否可用且已启用 | Check if Bluetooth is available and enabled
     *
     * @return true 如果蓝牙可用且已启用 | true if Bluetooth is available and enabled
     */
    fun isBluetoothAvailable(): Boolean = adapter?.isEnabled == true

    fun getAdapter(): BluetoothAdapter? = adapter
}
