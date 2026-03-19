package com.scrcpybt.app.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.scrcpybt.app.transport.bluetooth.BluetoothManager
import com.scrcpybt.app.transport.bluetooth.BluetoothRfcommClient
import com.scrcpybt.app.transport.bluetooth.BluetoothRfcommServer
import com.scrcpybt.app.util.NotificationHelper
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 中继端前台服务：透明转发控制端和被控端之间的加密数据
 *
 * 角色定位（三方架构中的 B）：
 * - A（控制端）<--蓝牙--> B（中继端）<--USB--> C（被控端）
 *
 * 核心职责：
 * 1. 连接管理：
 *    - 作为服务端接受控制端的蓝牙连接
 *    - 作为客户端连接到被控端（可能是蓝牙或 USB）
 *    - 建立两条独立的数据通道
 *
 * 2. 数据转发：
 *    - 从 A 读取数据，原样写入 C
 *    - 从 C 读取数据，原样写入 A
 *    - 不解密、不解码，纯粹的字节流转发
 *    - 双向异步转发，互不阻塞
 *
 * 3. 统计监控：
 *    - 统计 A->C 和 C->A 的传输字节数
 *    - 实时更新统计信息到 UI
 *
 * 设计特点：
 * - 零信任架构：中继端无法看到明文数据（端到端加密）
 * - 低延迟：直接内存拷贝，无需序列化/反序列化
 * - 高可靠：异常自动断开，不会挂起
 */
class RelayService : Service() {
    companion object {
        private const val TAG = "RelayService"
        /** 前台服务通知 ID */
        private const val NOTIFICATION_ID = 1002
        /** WakeLock 超时时长（毫秒） */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        /** WakeLock 续期间隔（秒）= 25 分钟 */
        private const val WAKELOCK_RENEW_SECONDS = 25 * 60
    }

    /** 本地 Binder，供 Activity 绑定 */
    private val binder = LocalBinder()
    /** 状态文本 LiveData */
    private val statusLiveData = MutableLiveData<String>()
    /** 统计信息 LiveData */
    private val statsLiveData = MutableLiveData<String>()

    /** 控制端连接（A） */
    private var connectionA: Connection? = null
    /** 被控端连接（C） */
    private var connectionC: Connection? = null
    /** 线程池（用于双向转发线程和统计线程） */
    private var executor: ExecutorService? = null
    /** 运行标志 */
    @Volatile
    private var running = false

    /** WakeLock：转发期间保持 CPU 运行 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** A->C 方向转发的字节数 */
    private val bytesForwardedAtoC = AtomicLong(0)
    /** C->A 方向转发的字节数 */
    private val bytesForwardedCtoA = AtomicLong(0)

    /**
     * 本地 Binder 实现
     */
    inner class LocalBinder : Binder() {
        fun getService(): RelayService = this@RelayService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationHelper.createServiceNotification(
                this, "Relay active", RelayService::class.java.name
            )
        )

        executor = Executors.newFixedThreadPool(3)
        executor?.submit(::startRelay)

        return START_STICKY
    }

    private fun startRelay() {
        try {
            statusLiveData.postValue("Waiting for controller...")

            // 步骤 1：接受来自控制端的连接（A）
            val server = BluetoothRfcommServer()
            connectionA = server.accept()
            Logger.i(TAG, "Controller connected: ${connectionA?.getRemoteDeviceInfo()}")
            statusLiveData.postValue("Controller connected. Connecting to target...")

            // 步骤 2：连接到被控设备（C）
            val btManager = BluetoothManager(this)
            val paired = btManager.getPairedDevices()
            val client = BluetoothRfcommClient()

            var connected = false
            for (device in paired) {
                try {
                    if (device.address == connectionA?.getRemoteDeviceInfo()) {
                        continue // 跳过控制端设备
                    }
                } catch (e: SecurityException) {
                    // device.address 需要权限，跳过此设备
                    continue
                }
                try {
                    connectionC = client.connect(device)
                    connected = true
                    break
                } catch (e: IOException) {
                    // 权限错误不再尝试其他设备
                    if (e.cause is SecurityException) {
                        throw e
                    }
                    Logger.w(TAG, "Failed to connect to device")
                }
            }

            if (!connected) {
                statusLiveData.postValue("错误：无法连接到被控设备")
                return
            }

            Logger.i(TAG, "Connected to controlled device: ${connectionC?.getRemoteDeviceInfo()}")
            statusLiveData.postValue("Relay active")
            running = true
            acquireWakeLock()

            // Step 3: Start bidirectional forwarding
            executor?.submit(::forwardAtoC)
            executor?.submit(::forwardCtoA)

            // Stats update loop（兼作 WakeLock 续期）
            var statsCount = 0
            while (running) {
                Thread.sleep(1000)
                statsLiveData.postValue(
                    String.format(
                        "A->C: %d KB | C->A: %d KB",
                        bytesForwardedAtoC.get() / 1024,
                        bytesForwardedCtoA.get() / 1024
                    )
                )
                // 每 25 分钟续期 WakeLock（25*60=1500 次 1 秒循环）
                statsCount++
                if (statsCount >= WAKELOCK_RENEW_SECONDS) {
                    statsCount = 0
                    renewWakeLock()
                }
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Relay error", e)
            statusLiveData.postValue("错误：${e.message}")
        }
    }

    /**
     * Forward data from controller (A) to controlled device (C).
     * This carries input/control messages.
     */
    private fun forwardAtoC() {
        try {
            val from = connectionA?.getInputStream() ?: return
            val to = connectionC?.getOutputStream() ?: return
            val buffer = ByteArray(4096)

            while (running) {
                val n = from.read(buffer)
                if (n == -1) break
                to.write(buffer, 0, n)
                // A->C 方向是控制/输入消息，数据量小但延迟敏感，保留每次 flush
                to.flush()
                bytesForwardedAtoC.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            Logger.e(TAG, "Forward A->C error", e)
        } finally {
            running = false
            statusLiveData.postValue("Disconnected")
        }
    }

    /**
     * Forward data from controlled device (C) to controller (A).
     * This carries frame data.
     */
    private fun forwardCtoA() {
        try {
            val from = connectionC?.getInputStream() ?: return
            val rawTo = connectionA?.getOutputStream() ?: return
            // C->A 方向是视频帧数据，数据量大，使用 BufferedOutputStream 减少 flush 次数
            val to = java.io.BufferedOutputStream(rawTo, 16384)
            val buffer = ByteArray(8192)

            while (running) {
                val n = from.read(buffer)
                if (n == -1) break
                to.write(buffer, 0, n)
                // 仅当 InputStream 无更多可读数据时才 flush（批量发送）
                if (from.available() == 0) {
                    to.flush()
                }
                bytesForwardedCtoA.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            Logger.e(TAG, "Forward C->A error", e)
        } finally {
            running = false
            statusLiveData.postValue("Disconnected")
        }
    }

    fun stopRelay() {
        running = false
        cleanup()
    }

    private fun cleanup() {
        releaseWakeLock()
        connectionA?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            }
        }
        connectionC?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            }
        }
    }

    fun getStatusLiveData(): LiveData<String> = statusLiveData
    fun getStatsLiveData(): LiveData<String> = statsLiveData

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 连接活跃时重启服务保持中继转发
        if (running && connectionA != null && connectionC != null) {
            Logger.i(TAG, "Task removed, restarting relay service")
            val restartIntent = Intent(applicationContext, RelayService::class.java)
            startForegroundService(restartIntent)
        }
    }

    override fun onDestroy() {
        running = false
        cleanup()
        executor?.shutdownNow()
        super.onDestroy()
    }

    /**
     * 获取 WakeLock 保持 CPU 运行
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScrcpyBT:RelayService"
        )
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        Logger.i(TAG, "WakeLock acquired")
    }

    /**
     * 续期 WakeLock：在统计循环中定期调用
     */
    private fun renewWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            it.acquire(WAKELOCK_TIMEOUT_MS)
            Logger.d(TAG, "WakeLock renewed")
        }
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Logger.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
