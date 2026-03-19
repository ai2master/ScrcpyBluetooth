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
 * Monitors available transports and triggers switching.
 *
 * Detects:
 *   - USB connection/disconnection (via BroadcastReceiver for ACTION_USB_DEVICE_ATTACHED/DETACHED)
 *   - Bluetooth connection state changes
 *   - ADB forward port availability
 *
 * When a "better" transport becomes available while connected:
 *   - USB is preferred over Bluetooth (lower latency, higher bandwidth)
 *   - If currently on BT relay (A->B->C) and USB direct (A->C) becomes available, switch
 *   - If currently on USB and USB disconnects, fall back to BT if available
 *
 * Notifies ControllerService of transport changes.
 */
class TransportMonitor(private val context: Context) {

    interface Listener {
        fun onBetterTransportAvailable(transportType: TransportType, deviceInfo: String)
        fun onCurrentTransportLost()
    }

    private var listener: Listener? = null
    private var currentTransport: TransportType? = null
    private var isRegistered = false

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
     * Set the listener for transport events.
     */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Start monitoring transports.
     */
    fun start(currentTransport: TransportType) {
        this.currentTransport = currentTransport
        registerReceivers()
        Logger.i(TAG, "Started monitoring transports (current: $currentTransport)")
    }

    /**
     * Stop monitoring transports.
     */
    fun stop() {
        unregisterReceivers()
        Logger.i(TAG, "Stopped monitoring transports")
    }

    /**
     * Update the current transport type.
     */
    fun updateCurrentTransport(transport: TransportType) {
        this.currentTransport = transport
        Logger.d(TAG, "Current transport updated to: $transport")
    }

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

    private fun unregisterReceivers() {
        if (isRegistered) {
            context.unregisterReceiver(usbReceiver)
            isRegistered = false
        }
    }

    private fun onUsbAvailable() {
        // Check if USB ADB connection is actually available
        if (isAdbDeviceConnected()) {
            // If currently on Bluetooth, USB is better
            if (currentTransport == TransportType.BLUETOOTH_RFCOMM) {
                Logger.i(TAG, "USB is available while on Bluetooth - switching to USB")
                listener?.onBetterTransportAvailable(TransportType.USB_ADB, "USB")
            }
        }
    }

    private fun onUsbLost() {
        // If currently on USB and USB is lost, need to fall back
        if (currentTransport == TransportType.USB_ADB) {
            Logger.w(TAG, "USB transport lost")
            listener?.onCurrentTransportLost()
        }
    }

    /**
     * Check if an ADB device is connected via USB.
     */
    private fun isAdbDeviceConnected(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("adb devices")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()

            // Parse output: should have at least one device listed
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
     * Manually check if a better transport is available.
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
