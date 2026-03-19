package com.scrcpybt.server

import android.os.Build
import com.scrcpybt.common.codec.FrameCodec
import com.scrcpybt.common.crypto.EncryptedChannel
import com.scrcpybt.common.crypto.KeyExchange
import com.scrcpybt.common.crypto.SecureRandomWrapper
import com.scrcpybt.common.protocol.MessageType
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.protocol.message.*
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.session.SessionManager
import com.scrcpybt.common.transport.Connection
import com.scrcpybt.common.transport.TransportType
import com.scrcpybt.common.util.Logger
import com.scrcpybt.server.capture.DisplayInfo
import com.scrcpybt.server.capture.FrameProducer
import com.scrcpybt.server.capture.ScreenCapture
import com.scrcpybt.server.capture.SurfaceControlCapture
import com.scrcpybt.server.clipboard.ClipboardHandler
import com.scrcpybt.server.control.ControlHandler
import com.scrcpybt.server.control.ScreenUnlocker
import com.scrcpybt.server.display.SecureFlagBypass
import com.scrcpybt.server.display.VirtualDisplayLauncher
import com.scrcpybt.server.display.VirtualDisplayManager
import com.scrcpybt.server.display.WakeLockManager
import com.scrcpybt.server.file.FileTransferHandler
import com.scrcpybt.server.file.FolderSyncHandler
import com.scrcpybt.server.input.InputInjector
import com.scrcpybt.server.share.ShareForwardHandler
import com.scrcpybt.server.transport.BluetoothServerTransport
import com.scrcpybt.server.transport.ServerTransport
import com.scrcpybt.server.transport.UnixSocketServerTransport
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ScrcpyBluetooth 主服务器类
 *
 * 负责协调屏幕捕获、编码和通信的核心服务端逻辑。
 *
 * ### 工作原理
 * 1. 通过指定的传输层（蓝牙 RFCOMM 或 USB ADB）接受客户端连接
 * 2. 执行握手协议，交换设备信息和能力标志
 * 3. 通过 X25519 ECDH 密钥交换建立 AES-256-GCM 加密通道
 * 4. 初始化屏幕捕获管线（镜像模式或虚拟显示器模式）
 * 5. 在独立线程中运行：帧发送循环、控制命令接收循环、心跳循环
 *
 * ### 两种捕获模式
 *
 * **镜像模式（默认）**
 * - 通过 SurfaceControl 隐藏 API 捕获物理屏幕内容
 * - 远程用户看到的内容与物理屏幕完全一致
 * - 适用于所有 Android 版本
 *
 * **虚拟显示器模式（Android 10+）**
 * - 通过 DisplayManager 创建独立的虚拟显示器（类似 Windows RDP）
 * - 远程用户在虚拟"第二屏幕"上操作，物理屏幕可以保持关闭/锁定状态
 * - 提供更好的隐私保护，适合远程办公场景
 * - 需要配合 WakeLock 防止深度睡眠
 * - 可选择性绕过 FLAG_SECURE 标志以捕获银行类应用（需要 root）
 *
 * ### 线程模型
 * - 主线程：控制消息接收循环
 * - 工作线程 1：帧编码和发送循环
 * - 工作线程 2：心跳发送循环
 * - 工作线程 3：备用传输层监听（用于无缝切换传输方式）
 *
 * @param transportType 传输类型（蓝牙或 USB）
 * @param socketName Abstract socket 名称（仅用于 USB 模式）
 * @param maxFps 最大帧率限制
 *
 * @see SurfaceControlCapture
 * @see VirtualDisplayManager
 * @see FrameProducer
 * @see InputInjector
 * @see ControlHandler
 */
class Server(
    private val transportType: TransportType,
    private val socketName: String?,
    private val maxFps: Int
) {

    /** 服务器运行状态标志 */
    @Volatile
    private var running = true

    /** 当前活动的传输连接 */
    private var connection: Connection? = null

    /** AES-256-GCM 加密通道 */
    private var encryptedChannel: EncryptedChannel? = null

    /** 屏幕捕获实现（通过 SurfaceControl） */
    private var screenCapture: SurfaceControlCapture? = null

    /** 帧生产者（捕获、编码、缓冲） */
    private var frameProducer: FrameProducer? = null

    /** 输入事件注入器（触摸、按键） */
    private var inputInjector: InputInjector? = null

    /** 设备控制命令处理器 */
    private var controlHandler: ControlHandler? = null

    /** 线程池执行器 */
    private var executor: ExecutorService? = null

    /** 会话管理器（用于传输切换和会话恢复） */
    private val sessionManager = SessionManager()

    /** 备用传输层（用于无缝切换蓝牙/USB） */
    private var additionalTransport: ServerTransport? = null

    /** 虚拟显示器管理器（Android 10+） */
    private var virtualDisplayManager: VirtualDisplayManager? = null

    /** 虚拟显示器应用启动器 */
    private var virtualDisplayLauncher: VirtualDisplayLauncher? = null

    /** 唤醒锁管理器（防止虚拟显示器模式下深度睡眠） */
    private var wakeLockManager: WakeLockManager? = null

    /** 安全标志绕过器（用于捕获受保护应用） */
    private var secureFlagBypass: SecureFlagBypass? = null

    /** 剪贴板处理器 */
    private var clipboardHandler: ClipboardHandler? = null

    /** 文件传输处理器 */
    private var fileTransferHandler: FileTransferHandler? = null

    /** 文件夹同步处理器 */
    private var folderSyncHandler: FolderSyncHandler? = null

    /** 分享转发处理器 */
    private var shareForwardHandler: ShareForwardHandler? = null

    /** 认证处理器 */
    private var authHandler: com.scrcpybt.server.auth.AuthHandler? = null

    /** 强制解锁凭据类型（在 run() 前设置） */
    private var unlockCredentialType: ScreenUnlocker.CredentialType? = null

    /** 强制解锁凭据值 */
    private var unlockCredential: String? = null

    /** 是否启用强制解锁功能 */
    private var forceUnlockEnabled = false

    /** 是否以虚拟显示器模式启动 */
    var useVirtualDisplay = false

    /** 是否绕过 FLAG_SECURE 标志（需要 root 权限） */
    var bypassSecureFlag = false

    /** 在虚拟显示器上启动的应用组件（防止黑屏） */
    var virtualDisplayStartApp: String? = null

    /**
     * 启用强制解锁功能
     *
     * 允许服务端在连接时自动解锁设备屏幕（适用于 Android <10 的降级方案）。
     * 凭据仅存储在内存中，不会持久化。
     *
     * @param type 锁屏凭据类型（PIN、密码、图案或无）
     * @param credential 锁屏凭据值
     */
    fun enableForceUnlock(type: ScreenUnlocker.CredentialType, credential: String?) {
        forceUnlockEnabled = true
        unlockCredentialType = type
        unlockCredential = credential
    }

    /**
     * 设置分享转发目标组件
     *
     * @param component 目标组件字符串（格式：包名/Activity）
     */
    fun setShareTarget(component: String?) {
        shareForwardHandler = ShareForwardHandler(component)
    }

    /**
     * 启动服务器主循环
     *
     * 执行完整的服务器生命周期：
     * 1. 等待并接受客户端连接
     * 2. 执行握手和密钥交换
     * 3. 建立加密通道
     * 4. 初始化屏幕捕获（镜像或虚拟显示器）
     * 5. 启动帧发送、心跳和控制接收循环
     *
     * 此方法会阻塞直到连接断开或发生错误。
     * 退出时会自动清理所有资源。
     */
    fun run() {
        executor = Executors.newFixedThreadPool(5)

        try {
            // 1. 接受客户端连接
            val transport = createTransport()
            Logger.i(TAG, "Waiting for connection...")
            connection = transport.accept()
            Logger.i(TAG, "Connected: ${connection!!.getRemoteDeviceInfo()}")

            // 2. 执行握手协议（未加密阶段）
            performHandshake()

            // 3. 密钥交换 + 建立加密通道
            establishEncryption()

            // 初始化会话管理器
            encryptedChannel?.let { channel ->
                sessionManager.initializeSession(channel, false, connection!!.getRemoteDeviceInfo())
            }

            // 启动备用传输层监听（用于无缝切换蓝牙/USB）
            startAlternateTransportListener()

            // 4. 初始化屏幕捕获（镜像或虚拟显示器）
            initCapture()

            // 5. 初始化功能处理器
            initHandlers()

            // 6. 启动帧发送线程
            executor!!.submit { frameSendLoop() }

            // 7. 启动心跳线程
            executor!!.submit { heartbeatLoop() }

            // 8. 在主线程运行控制消息接收循环
            controlReceiveLoop()

        } catch (e: Exception) {
            Logger.e(TAG, "Server error", e)
            throw e
        } finally {
            cleanup()
        }
    }

    /**
     * 创建对应类型的服务端传输层
     *
     * @return 服务端传输层实例
     * @throws IllegalArgumentException 如果传输类型未知
     */
    private fun createTransport(): ServerTransport {
        return when (transportType) {
            TransportType.BLUETOOTH_RFCOMM -> BluetoothServerTransport()
            TransportType.USB_ADB -> socketName?.let { UnixSocketServerTransport(it) }
                ?: UnixSocketServerTransport()
            else -> throw IllegalArgumentException("Unknown transport: $transportType")
        }
    }

    /**
     * 执行握手协议
     *
     * 在未加密阶段交换设备信息和能力标志。
     * 客户端和服务端互相发送 HandshakeMessage，包含设备型号、Android 版本、屏幕分辨率等信息。
     *
     * @throws IOException 如果握手失败
     */
    private fun performHandshake() {
        val reader = MessageReader(connection!!.getInputStream())
        val writer = MessageWriter(connection!!.getOutputStream())

        val clientMsg = reader.readMessage()
        if (clientMsg !is HandshakeMessage) {
            throw IOException("Expected handshake, got: ${clientMsg.type}")
        }
        Logger.i(TAG, "Client: ${clientMsg.deviceModel} Android ${clientMsg.androidVersion} " +
                "${clientMsg.screenWidth}x${clientMsg.screenHeight}")

        val displayInfo = DisplayInfo()
        val serverHandshake = HandshakeMessage(
            Build.MODEL,
            Build.VERSION.RELEASE,
            displayInfo.getEffectiveWidth(),
            displayInfo.getEffectiveHeight(),
            displayInfo.density,
            (HandshakeMessage.CAP_TOUCH.toInt() or HandshakeMessage.CAP_KEYBOARD.toInt()).toByte()
        )
        writer.writeMessage(serverHandshake)
        Logger.i(TAG, "Handshake complete")
    }

    /**
     * 建立加密通道
     *
     * 通过 X25519 ECDH 算法执行密钥交换，然后派生 AES-256-GCM 加密密钥。
     * 双方交换公钥和随机盐值，计算共享密钥，最终初始化加密通道。
     *
     * @throws IOException 如果密钥交换失败
     */
    private fun establishEncryption() {
        val reader = MessageReader(connection!!.getInputStream())
        val writer = MessageWriter(connection!!.getOutputStream())

        val keyExchange = KeyExchange()
        val ourPublicKey = keyExchange.generateKeyPair()
        val ourSalt = SecureRandomWrapper.generateBytes(32)

        val clientMsg = reader.readMessage()
        if (clientMsg !is KeyExchangeMessage) {
            throw IOException("Expected key exchange, got: ${clientMsg.type}")
        }

        val serverKeyMsg = KeyExchangeMessage(ourPublicKey, ourSalt, System.currentTimeMillis())
        writer.writeMessage(serverKeyMsg)

        keyExchange.computeSharedSecret(clientMsg.publicKey)

        val combinedSalt = ByteArray(64)
        System.arraycopy(clientMsg.salt, 0, combinedSalt, 0, 32)
        System.arraycopy(ourSalt, 0, combinedSalt, 32, 32)

        encryptedChannel = EncryptedChannel().apply {
            initialize(
                keyExchange.getSharedSecret(),
                combinedSalt,
                false,
                connection!!.getInputStream(),
                connection!!.getOutputStream()
            )
        }

        Logger.i(TAG, "Encryption established (AES-256-GCM)")
    }

    /**
     * 初始化屏幕捕获
     *
     * 根据配置选择捕获模式：
     * - 如果 useVirtualDisplay 为 true 且 Android >= 10，创建虚拟显示器并从中捕获
     * - 否则降级到物理屏幕镜像模式
     *
     * 同时初始化输入注入器、控制处理器和编解码器。
     */
    private fun initCapture() {
        val physicalDisplayInfo = DisplayInfo()
        val captureWidth = physicalDisplayInfo.getEffectiveWidth()
        val captureHeight = physicalDisplayInfo.getEffectiveHeight()
        val captureDpi = physicalDisplayInfo.density

        val codec = FrameCodec()
        codec.setDimensions(captureWidth, captureHeight)

        frameProducer = FrameProducer(codec).apply {
            setMaxFrameRate(maxFps)
        }

        inputInjector = InputInjector()
        controlHandler = ControlHandler(inputInjector!!)

        // 配置强制解锁
        if (forceUnlockEnabled) {
            controlHandler!!.getScreenUnlocker().enable(unlockCredentialType!!, unlockCredential)
        }

        // 设置虚拟显示器回调（用于运行时切换模式）
        controlHandler!!.virtualDisplayCallback = object : ControlHandler.VirtualDisplayCallback {
            override fun onEnableVirtualDisplay() {
                switchToVirtualDisplay()
            }
            override fun onDisableVirtualDisplay() {
                switchToMirrorMode()
            }
        }

        screenCapture = SurfaceControlCapture()

        // 如果请求虚拟显示器模式且系统支持，则启动虚拟显示器捕获
        if (useVirtualDisplay && Build.VERSION.SDK_INT >= 29) {
            startVirtualDisplayCapture(captureWidth, captureHeight, captureDpi)
        } else {
            // 标准镜像模式
            screenCapture!!.start(frameProducer!!)
            Logger.i(TAG, "Screen capture started (mirror mode)")
        }
    }

    /**
     * 启动虚拟显示器捕获
     *
     * 在虚拟显示器模式下，物理屏幕可以保持关闭/锁定状态，远程用户在虚拟屏幕上操作。
     *
     * ### 完整生命周期处理
     * 1. 将捕获管线设置为虚拟显示器模式
     * 2. 通过 DisplayManager 创建虚拟显示器
     * 3. 将输入事件路由到虚拟显示器
     * 4. 获取唤醒锁以防止深度睡眠
     * 5. 关闭物理显示器
     * 6. 在虚拟显示器上启动应用（防止黑屏）
     * 7. 可选：绕过 FLAG_SECURE 标志以捕获受保护应用
     *
     * @param width 虚拟显示器宽度
     * @param height 虚拟显示器高度
     * @param dpi 虚拟显示器 DPI
     */
    private fun startVirtualDisplayCapture(width: Int, height: Int, dpi: Int) {
        Logger.i(TAG, "Starting virtual display capture: ${width}x${height}@${dpi}dpi")

        // 将捕获设置为虚拟显示器模式（跳过 SurfaceControl 镜像）
        screenCapture!!.setVirtualDisplayMode(true)
        screenCapture!!.start(frameProducer!!, width, height)

        // 从捕获管线获取 Surface
        val surface = screenCapture!!.getSurface()
        if (surface == null) {
            Logger.e(TAG, "Failed to get capture surface, falling back to mirror mode")
            fallbackToMirrorMode()
            return
        }

        // 创建渲染到该 Surface 的虚拟显示器
        virtualDisplayManager = VirtualDisplayManager()
        val displayId = virtualDisplayManager!!.createVirtualDisplay(
            width, height, dpi, surface,
            showSystemDecorations = true
        )

        if (displayId < 0) {
            Logger.w(TAG, "Virtual display creation failed, falling back to mirror mode")
            virtualDisplayManager = null
            fallbackToMirrorMode()
            return
        }

        // 将输入事件路由到虚拟显示器
        inputInjector!!.targetDisplayId = displayId

        // 获取唤醒锁以防止物理屏幕关闭时进入深度睡眠
        wakeLockManager = WakeLockManager()
        wakeLockManager!!.acquire()

        // 关闭物理显示器并激活虚拟显示器模式
        controlHandler!!.getScreenUnlocker().activateVirtualDisplayMode()

        // 在虚拟显示器上启动应用以防止黑屏/空白屏幕
        virtualDisplayLauncher = VirtualDisplayLauncher()
        executor?.submit {
            // 短暂延迟以确保虚拟显示器完全初始化
            Thread.sleep(500)
            virtualDisplayLauncher!!.launchOnDisplay(displayId, virtualDisplayStartApp)
        }

        // 可选：绕过受保护应用的 FLAG_SECURE 标志
        if (bypassSecureFlag) {
            secureFlagBypass = SecureFlagBypass()
            if (secureFlagBypass!!.enable()) {
                Logger.i(TAG, "FLAG_SECURE bypass active")
            } else {
                Logger.w(TAG, "FLAG_SECURE bypass could not be enabled")
            }
        }

        Logger.i(TAG, "Virtual display active: ID=$displayId, physical screen off")
    }

    /**
     * 降级到镜像模式
     *
     * 当虚拟显示器设置失败时，回退到标准的物理屏幕镜像模式。
     */
    private fun fallbackToMirrorMode() {
        screenCapture?.stop()
        screenCapture = SurfaceControlCapture()
        screenCapture!!.start(frameProducer!!)
        Logger.i(TAG, "Fell back to mirror mode")
    }

    /**
     * 从镜像模式切换到虚拟显示器模式
     *
     * 运行时动态切换捕获模式。需要 Android 10+ 支持。
     */
    private fun switchToVirtualDisplay() {
        if (virtualDisplayManager?.isActive == true) {
            Logger.w(TAG, "Already in virtual display mode")
            return
        }

        if (Build.VERSION.SDK_INT < 29) {
            Logger.w(TAG, "Virtual display requires Android 10+")
            return
        }

        Logger.i(TAG, "Switching to virtual display mode...")

        // Stop current capture
        screenCapture?.stop()

        val displayInfo = DisplayInfo()
        val width = displayInfo.getEffectiveWidth()
        val height = displayInfo.getEffectiveHeight()
        val dpi = displayInfo.density

        screenCapture = SurfaceControlCapture()
        startVirtualDisplayCapture(width, height, dpi)
    }

    /**
     * 从虚拟显示器模式切换回镜像模式
     *
     * 释放虚拟显示器资源，恢复物理显示器，并将输入事件路由回物理屏幕。
     */
    private fun switchToMirrorMode() {
        if (virtualDisplayManager?.isActive != true) {
            Logger.w(TAG, "Already in mirror mode")
            return
        }

        Logger.i(TAG, "Switching to mirror mode...")

        // 禁用 FLAG_SECURE 绕过
        secureFlagBypass?.disable()
        secureFlagBypass = null

        // 释放虚拟显示器
        virtualDisplayManager?.release()
        virtualDisplayManager = null
        virtualDisplayLauncher = null

        // 释放唤醒锁
        wakeLockManager?.release()
        wakeLockManager = null

        // 恢复物理显示器
        controlHandler?.getScreenUnlocker()?.onDisconnect()

        // 将输入重置为物理显示器
        inputInjector?.targetDisplayId = 0

        // 以镜像模式重启捕获
        screenCapture?.stop()
        screenCapture = SurfaceControlCapture()
        screenCapture!!.start(frameProducer!!)

        Logger.i(TAG, "Switched to mirror mode")
    }

    /**
     * 初始化功能处理器
     *
     * 创建剪贴板、文件传输、文件夹同步、分享转发和认证处理器的实例。
     */
    private fun initHandlers() {
        clipboardHandler = ClipboardHandler()
        fileTransferHandler = FileTransferHandler()
        folderSyncHandler = FolderSyncHandler()
        if (shareForwardHandler == null) {
            shareForwardHandler = ShareForwardHandler()
        }
        authHandler = com.scrcpybt.server.auth.AuthHandler()
    }

    /**
     * 帧发送循环
     *
     * 在独立线程中运行，不断从帧生产者取出编码后的帧数据，
     * 通过加密通道发送给客户端。
     *
     * 此循环会一直运行直到连接断开或发生 I/O 错误。
     */
    private fun frameSendLoop() {
        Logger.i(TAG, "Frame send loop started")
        while (running) {
            try {
                val frame = frameProducer?.takeFrame()
                if (frame != null) {
                    encryptedChannel?.send(frame)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: IOException) {
                Logger.e(TAG, "Frame send error", e)
                running = false
            }
        }
        Logger.i(TAG, "Frame send loop ended")
    }

    /**
     * 控制消息接收循环
     *
     * 在主线程中运行，接收并处理来自客户端的各种消息：
     * - InputMessage: 触摸和按键输入
     * - ControlMessage: 设备控制命令（电源、音量、旋转等）
     * - ClipboardMessage: 剪贴板同步
     * - FileTransferMessage: 文件传输
     * - FolderSyncMessage: 文件夹同步
     * - ShareForwardMessage: 分享转发
     * - AuthMessage: 认证消息
     * - TransportSwitchMessage: 传输切换消息
     * - HeartbeatMessage: 心跳消息（保持连接活跃）
     *
     * 此循环会阻塞直到连接断开或发生 I/O 错误。
     */
    private fun controlReceiveLoop() {
        Logger.i(TAG, "Control receive loop started")
        val writer = encryptedChannel?.let { MessageWriter(it.getOutputStream()) }

        while (running) {
            try {
                val msg = encryptedChannel?.receive()
                when (msg) {
                    is InputMessage -> inputInjector?.inject(msg)
                    is ControlMessage -> controlHandler?.handle(msg)
                    is HeartbeatMessage -> {}
                    is AuthMessage -> {
                        writer?.let { authHandler?.handleAuth(msg, it) }
                    }
                    is ClipboardMessage -> {
                        writer?.let { clipboardHandler?.handleClipboardMessage(msg, it) }
                    }
                    is FileTransferMessage -> {
                        writer?.let { fileTransferHandler?.handleFileTransfer(msg, it) }
                    }
                    is FolderSyncMessage -> {
                        writer?.let { folderSyncHandler?.handleFolderSync(msg, it) }
                    }
                    is ShareForwardMessage -> {
                        writer?.let { shareForwardHandler?.handleShareForward(msg, it) }
                    }
                    is TransportSwitchMessage -> {
                        handleTransportSwitch(msg)
                    }
                }
            } catch (e: IOException) {
                Logger.e(TAG, "Control receive error", e)
                running = false
            }
        }
        Logger.i(TAG, "Control receive loop ended")
    }

    /**
     * 启动备用传输层监听
     *
     * 在后台线程中监听另一种传输方式（如当前用蓝牙则监听 USB，反之亦然），
     * 以支持运行时无缝切换传输方式而不中断会话。
     */
    private fun startAlternateTransportListener() {
        executor?.submit {
            try {
                val alternateType = if (transportType == TransportType.BLUETOOTH_RFCOMM) {
                    TransportType.USB_ADB
                } else {
                    TransportType.BLUETOOTH_RFCOMM
                }

                additionalTransport = when (alternateType) {
                    TransportType.USB_ADB -> socketName?.let { UnixSocketServerTransport(it) }
                        ?: UnixSocketServerTransport()
                    TransportType.BLUETOOTH_RFCOMM -> BluetoothServerTransport()
                    else -> null
                }

                Logger.i(TAG, "Listening on alternate transport: $alternateType")
            } catch (e: Exception) {
                Logger.w(TAG, "Could not start alternate transport listener", e)
            }
        }
    }

    /**
     * 处理传输切换消息
     *
     * 实现蓝牙和 USB 之间的无缝切换，通过会话 ID 验证来保持会话连续性。
     *
     * ### 切换流程
     * 1. 客户端发送 SWITCH_REQUEST
     * 2. 服务端验证会话 ID，返回 SWITCH_READY 或 SWITCH_REJECTED
     * 3. 客户端在新传输层上连接并发送 SWITCH_VERIFY
     * 4. 服务端验证后返回 SWITCH_CONFIRMED
     * 5. 客户端确认后发送 SWITCH_COMPLETE
     *
     * @param msg 传输切换消息
     */
    private fun handleTransportSwitch(msg: TransportSwitchMessage) {
        try {
            when (msg.subType) {
                TransportSwitchMessage.SUB_SWITCH_REQUEST -> {
                    Logger.i(TAG, "Received transport switch request: session=${msg.sessionId}")

                    if (!sessionManager.canResumeSession(msg.sessionId)) {
                        Logger.w(TAG, "Session ID mismatch, rejecting switch")
                        val rejectMsg = TransportSwitchMessage(
                            subType = TransportSwitchMessage.SUB_SWITCH_REJECTED,
                            sessionId = msg.sessionId,
                            reason = "Session ID mismatch"
                        )
                        encryptedChannel?.send(rejectMsg)
                        return
                    }

                    val readyMsg = TransportSwitchMessage(
                        subType = TransportSwitchMessage.SUB_SWITCH_READY,
                        sessionId = msg.sessionId,
                        newTransport = msg.newTransport
                    )
                    encryptedChannel?.send(readyMsg)

                    Logger.i(TAG, "Waiting for connection on new transport...")
                }

                TransportSwitchMessage.SUB_SWITCH_VERIFY -> {
                    Logger.i(TAG, "Received switch verify on new transport")

                    if (sessionManager.canResumeSession(msg.sessionId)) {
                        val confirmMsg = TransportSwitchMessage(
                            subType = TransportSwitchMessage.SUB_SWITCH_CONFIRMED,
                            sessionId = msg.sessionId,
                            newTransport = msg.newTransport
                        )
                        encryptedChannel?.send(confirmMsg)
                        Logger.i(TAG, "Transport switch confirmed")
                    } else {
                        Logger.e(TAG, "Session verification failed during switch")
                    }
                }

                TransportSwitchMessage.SUB_SWITCH_COMPLETE -> {
                    Logger.i(TAG, "Transport switch completed successfully")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling transport switch", e)
        }
    }

    /**
     * 心跳循环
     *
     * 在独立线程中定期发送心跳消息，保持连接活跃并检测连接状态。
     * 发送间隔由 ProtocolConstants.HEARTBEAT_INTERVAL_MS 定义。
     */
    private fun heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(ProtocolConstants.HEARTBEAT_INTERVAL_MS)
                encryptedChannel?.send(HeartbeatMessage())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: IOException) {
                Logger.w(TAG, "Heartbeat send failed", e)
                running = false
            }
        }
    }

    /**
     * 清理资源
     *
     * 按正确顺序释放所有资源，确保设备状态恢复正常：
     * 1. 禁用 FLAG_SECURE 绕过（恢复安全性）
     * 2. 释放虚拟显示器（内部会恢复物理显示器）
     * 3. 释放唤醒锁（恢复原始睡眠设置）
     * 4. 断开连接时自动锁屏/恢复屏幕状态
     * 5. 停止捕获和编码管线
     * 6. 关闭加密通道和网络连接
     * 7. 关闭线程池
     * 8. 清理会话管理器
     */
    private fun cleanup() {
        running = false

        // 首先禁用 FLAG_SECURE 绕过（恢复安全性）
        secureFlagBypass?.disable()
        secureFlagBypass = null

        // 释放虚拟显示器（内部会恢复物理显示器）
        virtualDisplayManager?.release()
        virtualDisplayManager = null
        virtualDisplayLauncher = null

        // 释放唤醒锁（恢复原始睡眠设置）
        wakeLockManager?.release()
        wakeLockManager = null

        // 断开连接时自动锁屏/恢复屏幕状态
        controlHandler?.getScreenUnlocker()?.onDisconnect()

        frameProducer?.stop()
        screenCapture?.stop()
        encryptedChannel?.let {
            try { it.close() } catch (ignored: IOException) {}
        }
        connection?.let {
            try { it.close() } catch (ignored: IOException) {}
        }
        additionalTransport?.let {
            try { it.close() } catch (ignored: IOException) {}
        }
        executor?.shutdown()
        try {
            if (executor?.awaitTermination(5, TimeUnit.SECONDS) != true) {
                executor?.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor?.shutdownNow()
        }
        sessionManager.clear()
        Logger.i(TAG, "Server cleaned up")
    }

    companion object {
        private const val TAG = "Server"
    }
}
