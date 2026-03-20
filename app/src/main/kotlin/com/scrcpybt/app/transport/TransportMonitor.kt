package com.scrcpybt.app.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 传输方式监控器：监控可用传输方式并触发自动切换 | Transport Monitor: Monitors available transports and triggers automatic switching
 *
 * 检测能力 | Detection Capabilities:
 *   - USB 连接/断开（通过 BroadcastReceiver 监听 ACTION_USB_DEVICE_ATTACHED/DETACHED）| USB connection/disconnection via BroadcastReceiver
 *   - 蓝牙连接状态变化 | Bluetooth connection state changes
 *   - ADB forward 端口可用性 | ADB forward port availability
 *
 * 自动切换逻辑 | Automatic Switching Logic:
 *   - USB 优先于蓝牙（更低延迟、更高带宽）| USB is preferred over Bluetooth (lower latency, higher bandwidth)
 *   - 当前使用蓝牙中继（A->B->C）且 USB 直连（A->C）可用时，自动切换到 USB | If on BT relay and USB direct becomes available, switch
 *   - 当前使用 USB 且 USB 断开时，降级到蓝牙（如果可用）| If on USB and USB disconnects, fall back to BT if available
 *
 * 通知机制 | Notification Mechanism:
 *   - 通知 ControllerService 传输方式变化 | Notifies ControllerService of transport changes
 *
 * @property context Android 上下文 | Android context
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class TransportMonitor(private val context: Context) {

    /**
     * 传输事件监听器接口 | Transport event listener interface
     */
    interface Listener {
        /**
         * 发现更优传输方式时触发 | Triggered when a better transport becomes available
         * @param transportType 新的传输类型 | New transport type
         * @param deviceInfo 设备信息 | Device information
         */
        fun onBetterTransportAvailable(transportType: TransportType, deviceInfo: String)

        /**
         * 当前传输方式丢失时触发 | Triggered when current transport is lost
         */
        fun onCurrentTransportLost()
    }

    /** 传输事件监听器 | Transport event listener */
    private var listener: Listener? = null
    /** 当前传输类型 | Current transport type */
    private var currentTransport: TransportType? = null
    /** 广播接收器注册标志 | Broadcast receiver registration flag */
    private var isRegistered = false

    /** USB 事件广播接收器 | USB event broadcast receiver */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Logger.i(TAG, "USB device attached")
                    onUsbAvailable()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Logger.i(TAG, "USB device detached")
                    onUsbLost()
                }
            }
        }
    }

    /**
     * 设置传输事件监听器 | Set the listener for transport events
     * @param listener 监听器实例 | Listener instance
     */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * 开始监控传输方式 | Start monitoring transports
     * @param currentTransport 当前使用的传输类型 | Current transport type in use
     */
    fun start(currentTransport: TransportType) {
        this.currentTransport = currentTransport
        registerReceivers()
        Logger.i(TAG, "Started monitoring transports (current: $currentTransport)")
    }

    /**
     * 停止监控传输方式 | Stop monitoring transports
     */
    fun stop() {
        unregisterReceivers()
        Logger.i(TAG, "Stopped monitoring transports")
    }

    /**
     * 更新当前传输类型 | Update the current transport type
     * @param transport 新的传输类型 | New transport type
     */
    fun updateCurrentTransport(transport: TransportType) {
        this.currentTransport = transport
        Logger.d(TAG, "Current transport updated to: $transport")
    }

    /**
     * 注册系统广播接收器 | Register system broadcast receivers
     */
    private fun registerReceivers() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            context.registerReceiver(usbReceiver, filter)
            isRegistered = true
        }
    }

    /**
     * 注销系统广播接收器 | Unregister system broadcast receivers
     */
    private fun unregisterReceivers() {
        if (isRegistered) {
            context.unregisterReceiver(usbReceiver)
            isRegistered = false
        }
    }

    /**
     * USB 连接可用时的处理逻辑 | Handle USB connection availability
     */
    private fun onUsbAvailable() {
        // 检查 USB ADB 连接是否实际可用 | Check if USB ADB connection is actually available
        if (isAdbDeviceConnected()) {
            // 如果当前使用蓝牙，USB 是更优选择 | If currently on Bluetooth, USB is better
            if (currentTransport == TransportType.BLUETOOTH_RFCOMM) {
                Logger.i(TAG, "USB is available while on Bluetooth - switching to USB")
                listener?.onBetterTransportAvailable(TransportType.USB_ADB, "USB")
            }
        }
    }

    /**
     * USB 连接丢失时的处理逻辑 | Handle USB connection loss
     */
    private fun onUsbLost() {
        // 如果当前使用 USB 且 USB 丢失，需要降级 | If currently on USB and USB is lost, need to fall back
        if (currentTransport == TransportType.USB_ADB) {
            Logger.w(TAG, "USB transport lost")
            listener?.onCurrentTransportLost()
        }
    }

    /**
     * 检查是否有 ADB 设备通过 USB 连接 | Check if an ADB device is connected via USB
     *
     * 执行 `adb devices` 命令并解析输出，判断是否有授权的设备连接
     * Executes `adb devices` command and parses output to check for authorized devices
     *
     * @return true 如果有设备连接且已授权 | true if device is connected and authorized
     */
    private fun isAdbDeviceConnected(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("adb devices")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()

            // 解析输出：至少有一个设备列出 | Parse output: should have at least one device listed
            val lines = output.lines().filter { it.isNotBlank() && !it.startsWith("List of devices") }
            val hasDevice = lines.any { it.contains("device") && !it.contains("unauthorized") }

            Logger.d(TAG, "ADB devices check: $hasDevice")
            hasDevice
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to check ADB devices", e)
            false
        }
    }

    /**
     * 手动检查是否有更优传输方式可用 | Manually check if a better transport is available
     *
     * 供外部调用，主动检查传输升级机会 | Can be called externally to proactively check for transport upgrade opportunities
     */
    fun checkForBetterTransport() {
        if (currentTransport == TransportType.BLUETOOTH_RFCOMM && isAdbDeviceConnected()) {
            Logger.i(TAG, "Manual check: USB is available while on Bluetooth")
            listener?.onBetterTransportAvailable(TransportType.USB_ADB, "USB")
        }
    }

    companion object {
        private const val TAG = "TransportMonitor"
    }
}
