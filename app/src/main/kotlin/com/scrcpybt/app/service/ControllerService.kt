package com.scrcpybt.app.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.scrcpybt.app.handler.FileTransferController
import com.scrcpybt.app.transport.TransportMonitor
import com.scrcpybt.app.transport.bluetooth.BluetoothManager
import com.scrcpybt.app.transport.bluetooth.BluetoothRfcommClient
import com.scrcpybt.app.transport.usb.AdbConnection
import com.scrcpybt.app.util.NotificationHelper
import com.scrcpybt.app.handler.ClipboardTransferHandler
import com.scrcpybt.common.codec.FrameCodec
import com.scrcpybt.common.crypto.EncryptedChannel
import com.scrcpybt.common.crypto.KeyExchange
import com.scrcpybt.common.crypto.SecureRandomWrapper
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.protocol.message.*
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.session.SessionManager
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 控制端前台服务：管理与被控设备的连接，接收视频帧并发送输入事件
 *
 * 核心职责：
 * 1. 连接管理：
 *    - 支持蓝牙 RFCOMM 和 USB ADB 两种传输方式
 *    - 执行握手协议和密钥交换
 *    - 建立加密通道（端到端加密）
 *
 * 2. 视频流处理：
 *    - 接收并解码视频帧消息
 *    - 通过 LiveData 向 UI 推送 Bitmap
 *    - 帧队列管理，丢弃过时帧保持低延迟
 *
 * 3. 输入事件发送：
 *    - 触摸事件（多点触控支持）
 *    - 控制指令（返回、主页、电源、解锁等）
 *    - 剪贴板同步
 *
 * 4. 扩展功能：
 *    - 文件传输控制器集成
 *    - 会话管理（恢复连接、状态同步）
 *    - 传输监控（带宽、延迟统计）
 *
 * 前台服务设计：
 * - 显示持久通知，防止系统杀死进程
 * - 支持长时间运行的远程控制会话
 */
class ControllerService : Service() {
    companion object {
        private const val TAG = "ControllerService"
        /** 前台服务通知 ID */
        private const val NOTIFICATION_ID = 1001
        /** WakeLock 超时时长（毫秒） */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        /** WakeLock 续期间隔 = 25 分钟 / 5 秒心跳 = 300 次心跳 */
        private const val WAKELOCK_RENEW_HEARTBEATS = 300
    }

    /** 本地 Binder，供 Activity 绑定 */
    private val binder = LocalBinder()
    /** 视频帧 LiveData，推送到 UI 层 */
    private val frameLiveData = MutableLiveData<Bitmap>()
    /** 状态文本 LiveData */
    private val statusLiveData = MutableLiveData<String>()

    /** 底层传输连接（蓝牙或 USB） */
    private var connection: Connection? = null
    /** 加密通道（端到端加密） */
    var encryptedChannel: EncryptedChannel? = null
        private set
    /** 帧编解码器 */
    private var frameCodec: FrameCodec? = null
    /** 线程池（用于连接和接收线程） */
    private var executor: ExecutorService? = null
    /** 运行标志 */
    @Volatile
    private var running = false

    /**
     * 剪贴板请求-响应守卫标志
     *
     * 安全机制：确保控制端只在主动发起 requestClipboard() 后才接受
     * 受控端的剪贴板推送。防止受控端未经允许向控制端写入剪贴板。
     */
    @Volatile
    private var awaitingClipboardResponse = false

    /** WakeLock：连接期间保持 CPU 运行，防止系统休眠断开蓝牙 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 会话管理器（恢复连接、状态同步） */
    private val sessionManager = SessionManager()
    /** 传输监控器（带宽、延迟统计） */
    private val transportMonitor = TransportMonitor(this)
    /** 文件传输控制器 */
    private var fileTransferController: FileTransferController? = null
    /** 当前传输方式 */
    private var currentTransport: TransportType = TransportType.BLUETOOTH_RFCOMM
    /** 可复用的 Bitmap，避免每帧新建导致频繁 GC */
    private var reusableBitmap: Bitmap? = null

    /**
     * 剪贴板接收回调接口
     *
     * 当从受控端收到剪贴板内容时触发。
     * 由于控制端可能在后台，需要由上层决定如何写入本地剪贴板。
     */
    interface ClipboardCallback {
        /** 收到远端剪贴板内容 */
        fun onClipboardReceived(text: String)
    }

    /** 剪贴板接收回调 */
    var clipboardCallback: ClipboardCallback? = null

    /**
     * 本地 Binder 实现
     */
    inner class LocalBinder : Binder() {
        fun getService(): ControllerService = this@ControllerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            NotificationHelper.createServiceNotification(
                this, "Controller active", ControllerService::class.java.name
            )
        )

        val transportStr = intent?.getStringExtra("transport")
        val transport = transportStr?.let { TransportType.valueOf(it) } ?: TransportType.BLUETOOTH_RFCOMM

        executor = Executors.newFixedThreadPool(4)
        executor?.submit { connectAndRun(transport) }

        return START_STICKY
    }

    private fun connectAndRun(transport: TransportType) {
        try {
            statusLiveData.postValue("Connecting...")
            currentTransport = transport

            // 根据传输类型建立连接
            if (transport == TransportType.BLUETOOTH_RFCOMM) {
                connectBluetooth()
            } else {
                connectUsb()
            }

            // 执行握手协议
            performHandshake()

            // 密钥交换
            establishEncryption()

            // 使用远端连接信息作为设备指纹（而非硬编码占位符）
            val remoteFingerprint = connection?.getRemoteDeviceInfo() ?: "unknown"

            // 初始化会话管理器
            encryptedChannel?.let { channel ->
                sessionManager.initializeSession(channel, true, remoteFingerprint)
            }

            // Initialize file transfer controller
            fileTransferController = FileTransferController(this)
            fileTransferController?.initializeAutoResume(remoteFingerprint, encryptedChannel!!)

            // Start transport monitoring
            transportMonitor.setListener(object : TransportMonitor.Listener {
                override fun onBetterTransportAvailable(transportType: TransportType, deviceInfo: String) {
                    Logger.i(TAG, "Better transport available: $transportType")
                    executor?.submit {
                        try {
                            switchTransport(transportType)
                        } catch (e: Exception) {
                            Logger.e(TAG, "Transport switch failed", e)
                        }
                    }
                }

                override fun onCurrentTransportLost() {
                    Logger.w(TAG, "Current transport lost")
                    // Mark transfers as interrupted
                    fileTransferController?.markActiveTransfersAsInterrupted()
                }
            })
            transportMonitor.start(currentTransport)

            statusLiveData.postValue("Connected")
            running = true
            acquireWakeLock()

            // Start heartbeat sender
            executor?.submit(::heartbeatLoop)

            // Frame receive loop (on this thread)
            frameReceiveLoop()

        } catch (e: Exception) {
            Logger.e(TAG, "Connection error", e)
            statusLiveData.postValue("Error: ${e.message}")
            fileTransferController?.markActiveTransfersAsInterrupted()
        }
    }

    private fun connectBluetooth() {
        val btManager = BluetoothManager(this)
        val paired = btManager.getPairedDevices()

        if (paired.isEmpty()) {
            throw IOException("未找到已配对的蓝牙设备，请先在系统设置中配对")
        }

        // Try to connect to the first paired device
        // In production, user would select from a list
        val client = BluetoothRfcommClient()
        for (device in paired) {
            try {
                connection = client.connect(device)
                return
            } catch (e: IOException) {
                // 权限错误不再尝试其他设备
                if (e.cause is SecurityException) {
                    throw e
                }
                Logger.w(TAG, "Failed to connect to device: ${e.message}")
            }
        }
        throw IOException("无法连接到任何已配对设备")
    }

    private fun connectUsb() {
        val adbConn = AdbConnection()
        adbConn.connectTcp(27183)
        connection = adbConn
    }

    private fun performHandshake() {
        val conn = connection ?: throw IOException("Not connected")
        val reader = MessageReader(conn.getInputStream())
        val writer = MessageWriter(conn.getOutputStream())

        // Send client handshake
        val handshake = HandshakeMessage(
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE,
            0, 0, 0, // Controller doesn't send its screen dimensions
            (HandshakeMessage.CAP_TOUCH.toInt() or HandshakeMessage.CAP_KEYBOARD.toInt()).toByte()
        )
        writer.writeMessage(handshake)

        // Receive server handshake
        val serverMsg = reader.readMessage()
        if (serverMsg !is HandshakeMessage) {
            throw IOException("Expected handshake, got: ${serverMsg.type}")
        }
        val serverHandshake = serverMsg
        Logger.i(
            TAG, "Server: ${serverHandshake.deviceModel} " +
                    "${serverHandshake.screenWidth}x${serverHandshake.screenHeight}"
        )

        // Initialize codec with server dimensions
        frameCodec = FrameCodec().apply {
            setDimensions(serverHandshake.screenWidth, serverHandshake.screenHeight)
        }
    }

    private fun establishEncryption() {
        val conn = connection ?: throw IOException("Not connected")
        val reader = MessageReader(conn.getInputStream())
        val writer = MessageWriter(conn.getOutputStream())

        // Generate our key pair
        val keyExchange = KeyExchange()
        val ourPublicKey = keyExchange.generateKeyPair()
        val ourSalt = SecureRandomWrapper.generateBytes(32)

        // Send our public key
        val keyMsg = KeyExchangeMessage(ourPublicKey, ourSalt, System.currentTimeMillis())
        writer.writeMessage(keyMsg)

        // Receive server's public key
        val serverMsg = reader.readMessage()
        if (serverMsg !is KeyExchangeMessage) {
            throw IOException("Expected key exchange, got: ${serverMsg.type}")
        }
        val serverKeyMsg = serverMsg

        // Compute shared secret
        keyExchange.computeSharedSecret(serverKeyMsg.publicKey)

        // Combine salts
        val combinedSalt = ByteArray(64)
        System.arraycopy(ourSalt, 0, combinedSalt, 0, 32)
        System.arraycopy(serverKeyMsg.salt, 0, combinedSalt, 32, 32)

        // Initialize encrypted channel (controller is the initiator)
        encryptedChannel = EncryptedChannel().apply {
            initialize(
                keyExchange.sharedSecret!!,
                combinedSalt,
                true, // controller is initiator
                conn.getInputStream(),
                conn.getOutputStream()
            )
        }

        Logger.i(TAG, "Encryption established")
    }

    private fun frameReceiveLoop() {
        while (running) {
            try {
                val msg = encryptedChannel?.receive()
                when (msg) {
                    is FrameMessage -> {
                        val pixels = frameCodec?.decode(msg)
                        if (pixels != null && pixels.isNotEmpty()) {
                            val w = msg.width.toInt(); val h = msg.height.toInt()
                            // 复用 Bitmap：尺寸不变时不重新创建
                            val bmp = reusableBitmap
                            if (bmp == null || bmp.width != w || bmp.height != h) {
                                reusableBitmap?.recycle()
                                reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            }
                            reusableBitmap?.setPixels(pixels, 0, w, 0, 0, w, h)
                            frameLiveData.postValue(reusableBitmap)
                        }
                    }
                    is ClipboardMessage -> {
                        // 安全检查 1：只接受 PUSH 方向（受控端不应向控制端发送 PULL 请求）
                        if (msg.direction != ClipboardMessage.DIR_PUSH) {
                            Logger.w(TAG, "Security: ignoring clipboard message with direction=${msg.direction}")
                        }
                        // 安全检查 2：只在控制端主动请求后才接受剪贴板推送
                        // 防止受控端未经允许向控制端写入剪贴板
                        else if (!awaitingClipboardResponse) {
                            Logger.w(TAG, "Security: ignoring unsolicited clipboard push from remote")
                        }
                        // 通过安全检查，处理剪贴板内容
                        else {
                            awaitingClipboardResponse = false
                            if (msg.text.isNotEmpty()) {
                                clipboardCallback?.onClipboardReceived(msg.text)
                                    ?: handleClipboardFallback(msg.text)
                            }
                        }
                    }
                    is HeartbeatMessage -> {
                        // Connection alive
                    }
                }
            } catch (e: IOException) {
                Logger.e(TAG, "Frame receive error", e)
                running = false
                statusLiveData.postValue("Disconnected")
            }
        }
    }

    /**
     * 剪贴板接收的默认处理：显示通知，用户点击后通过跳板 Activity 写入本地剪贴板
     *
     * 当没有设置 clipboardCallback 时使用此降级方案。
     * 注意：此方法只在通过安全检查后才被调用（必须是控制端主动 requestClipboard 的响应）。
     */
    private fun handleClipboardFallback(text: String) {
        Logger.i(TAG, "Clipboard received (${text.length} chars), showing notification")
        NotificationHelper.showClipboardNotification(this, text)
    }

    private fun heartbeatLoop() {
        var heartbeatCount = 0
        while (running) {
            try {
                Thread.sleep(ProtocolConstants.HEARTBEAT_INTERVAL_MS)
                encryptedChannel?.send(HeartbeatMessage())
                // 每 25 分钟续期 WakeLock（30 分钟超时前），防止超时后 CPU 休眠断连
                heartbeatCount++
                if (heartbeatCount >= WAKELOCK_RENEW_HEARTBEATS) {
                    heartbeatCount = 0
                    renewWakeLock()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: IOException) {
                Logger.w(TAG, "Heartbeat failed", e)
            }
        }
    }

    /**
     * Send an input message to the server.
     */
    fun sendInput(msg: InputMessage) {
        if (!running || encryptedChannel == null) return
        executor?.submit {
            try {
                encryptedChannel?.send(msg)
            } catch (e: IOException) {
                Logger.e(TAG, "Send input failed", e)
            }
        }
    }

    /**
     * Send a control message to the server.
     */
    fun sendControl(msg: ControlMessage) {
        if (!running || encryptedChannel == null) return
        executor?.submit {
            try {
                encryptedChannel?.send(msg)
            } catch (e: IOException) {
                Logger.e(TAG, "Send control failed", e)
            }
        }
    }

    /**
     * 发送剪贴板文本到受控端
     *
     * 将文本包装为 ClipboardMessage(DIR_PUSH) 发送。
     * 可从任意线程调用，内部会切换到工作线程。
     *
     * @param text 要发送的文本
     */
    fun sendClipboard(text: String) {
        if (!running || encryptedChannel == null) return
        executor?.submit {
            try {
                val msg = ClipboardMessage(text, ClipboardMessage.DIR_PUSH)
                encryptedChannel?.send(msg)
                Logger.i(TAG, "Clipboard sent to remote: ${text.length} chars")
            } catch (e: IOException) {
                Logger.e(TAG, "Send clipboard failed", e)
            }
        }
    }

    /**
     * 请求受控端的剪贴板内容
     *
     * 发送 ClipboardMessage(DIR_PULL)，受控端会回复其剪贴板内容。
     * 收到回复后通过 [clipboardCallback] 通知上层。
     */
    fun requestClipboard() {
        if (!running || encryptedChannel == null) return
        awaitingClipboardResponse = true
        executor?.submit {
            try {
                val msg = ClipboardMessage("", ClipboardMessage.DIR_PULL)
                encryptedChannel?.send(msg)
                Logger.i(TAG, "Clipboard request sent")
            } catch (e: IOException) {
                Logger.e(TAG, "Request clipboard failed", e)
                awaitingClipboardResponse = false
            }
        }
    }

    /**
     * 发送通用消息到受控端
     *
     * @param msg 要发送的消息
     */
    fun sendMessage(msg: Message) {
        if (!running || encryptedChannel == null) return
        executor?.submit {
            try {
                encryptedChannel?.send(msg)
            } catch (e: IOException) {
                Logger.e(TAG, "Send message failed", e)
            }
        }
    }

    /**
     * Switch to a different transport while maintaining the session.
     */
    private fun switchTransport(newTransport: TransportType) {
        Logger.i(TAG, "Initiating transport switch from $currentTransport to $newTransport")

        try {
            // Send switch request to server
            val switchRequest = TransportSwitchMessage(
                subType = TransportSwitchMessage.SUB_SWITCH_REQUEST,
                sessionId = sessionManager.sessionId,
                newTransport = when (newTransport) {
                    TransportType.USB_ADB -> TransportSwitchMessage.TRANSPORT_USB_ADB
                    TransportType.BLUETOOTH_RFCOMM -> TransportSwitchMessage.TRANSPORT_BT_RFCOMM
                    else -> TransportSwitchMessage.TRANSPORT_USB_ADB
                },
                socketName = "scrcpybt_27183"
            )
            encryptedChannel?.send(switchRequest)

            // Wait for SWITCH_READY response
            val response = encryptedChannel?.receive()
            if (response is TransportSwitchMessage && response.subType == TransportSwitchMessage.SUB_SWITCH_READY) {
                Logger.i(TAG, "Server ready for transport switch")

                // Establish new connection
                val newConnection = if (newTransport == TransportType.USB_ADB) {
                    val adb = AdbConnection()
                    adb.connectTcp(27183)
                    adb
                } else {
                    val btManager = BluetoothManager(this)
                    val client = BluetoothRfcommClient()
                    val paired = btManager.getPairedDevices()
                    client.connect(paired.first())
                }

                // Send SWITCH_VERIFY with session ID
                val verifyMsg = TransportSwitchMessage(
                    subType = TransportSwitchMessage.SUB_SWITCH_VERIFY,
                    sessionId = sessionManager.sessionId,
                    newTransport = switchRequest.newTransport
                )

                val tempWriter = MessageWriter(newConnection.getOutputStream())
                tempWriter.writeMessage(verifyMsg)

                // Wait for SWITCH_CONFIRMED
                val tempReader = MessageReader(newConnection.getInputStream())
                val confirmMsg = tempReader.readMessage()

                if (confirmMsg is TransportSwitchMessage && confirmMsg.subType == TransportSwitchMessage.SUB_SWITCH_CONFIRMED) {
                    Logger.i(TAG, "Switch confirmed, replacing streams")

                    // Switch streams in the encrypted channel
                    sessionManager.switchTransport(newConnection.getInputStream(), newConnection.getOutputStream())

                    // Send SWITCH_COMPLETE
                    val completeMsg = TransportSwitchMessage(
                        subType = TransportSwitchMessage.SUB_SWITCH_COMPLETE,
                        sessionId = sessionManager.sessionId,
                        newTransport = switchRequest.newTransport
                    )
                    encryptedChannel?.send(completeMsg)

                    // Close old connection
                    connection?.close()
                    connection = newConnection

                    currentTransport = newTransport
                    transportMonitor.updateCurrentTransport(newTransport)

                    Logger.i(TAG, "Transport switched successfully to $newTransport")
                    statusLiveData.postValue("Switched to $newTransport")
                } else {
                    throw IOException("Switch not confirmed by server")
                }
            } else {
                throw IOException("Server rejected transport switch")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Transport switch failed", e)
            statusLiveData.postValue("Transport switch failed: ${e.message}")
        }
    }

    fun getFrameLiveData(): LiveData<Bitmap> = frameLiveData
    fun getStatusLiveData(): LiveData<String> = statusLiveData

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务移除应用时，如果连接仍然活跃则重启服务保持连接
        if (running && connection != null) {
            Logger.i(TAG, "Task removed, restarting service to maintain connection")
            val restartIntent = Intent(applicationContext, ControllerService::class.java).apply {
                putExtra("transport", currentTransport.name)
            }
            startForegroundService(restartIntent)
        }
    }

    override fun onDestroy() {
        running = false
        releaseWakeLock()
        transportMonitor.stop()
        fileTransferController?.markActiveTransfersAsInterrupted()
        encryptedChannel?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            }
        }
        connection?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            }
        }
        executor?.shutdownNow()
        sessionManager.clear()
        super.onDestroy()
    }

    /**
     * 获取 WakeLock 保持 CPU 运行，防止屏幕关闭后蓝牙连接中断
     *
     * 仅在连接建立后调用，30 分钟超时保护防止泄漏。
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScrcpyBT:ControllerService"
        )
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        Logger.i(TAG, "WakeLock acquired")
    }

    /**
     * 续期 WakeLock：在心跳循环中定期调用，防止超时后 CPU 休眠断连
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
