package com.scrcpybt.common.session

import com.scrcpybt.common.crypto.EncryptedChannel
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话管理器。
 *
 * 管理跨传输切换持久化的会话状态。
 * 会话在首次握手 + 密钥交换时创建，包含：
 * - sessionId：UUID 格式的会话标识
 * - encryptionKey：AES-256 密钥（从 ECDH 派生）
 * - nonce 计数器和基底：发送/接收方向各一组
 * - authenticatedFeatures：已授权的功能集合
 * - deviceFingerprint：设备指纹（ECDH 公钥的 SHA-256 哈希）
 *
 * 传输切换时（如蓝牙→USB）：
 * - 会话状态完整保留
 * - 仅替换底层 InputStream/OutputStream
 * - nonce 计数器继续递增（不重置 = 不重用 nonce）
 */
class SessionManager {
    /** 会话 ID（UUID 格式） */
    var sessionId: String = ""
        private set

    /** AES-256 加密密钥 */
    var encryptionKey: ByteArray? = null
        private set

    /** 发送方向 nonce 基底（4 字节） */
    var sendNonceBase: ByteArray? = null
        private set

    /** 接收方向 nonce 基底（4 字节） */
    var recvNonceBase: ByteArray? = null
        private set

    /** 发送方向 nonce 计数器 */
    var sendNonceCounter: AtomicLong = AtomicLong(0)
        private set

    /** 接收方向 nonce 计数器 */
    var recvNonceCounter: AtomicLong = AtomicLong(0)
        private set

    /** 设备指纹（ECDH 公钥的 SHA-256 哈希） */
    var deviceFingerprint: String = ""

    /** 已授权的功能 ID 集合 */
    var authenticatedFeatures: MutableSet<Byte> = mutableSetOf()

    /** 加密通道实例 */
    var encryptedChannel: EncryptedChannel? = null
        private set

    /** 是否为连接发起方 */
    var isInitiator: Boolean = false
        private set

    /**
     * 初始化新会话。
     *
     * 从加密通道中提取密钥和 nonce 参数，生成唯一会话 ID。
     *
     * @param encChannel 已初始化的加密通道
     * @param initiator 是否为连接发起方
     * @param deviceFp 远端设备指纹
     */
    fun initializeSession(
        encChannel: EncryptedChannel,
        initiator: Boolean,
        deviceFp: String = ""
    ) {
        this.sessionId = UUID.randomUUID().toString()
        this.encryptedChannel = encChannel
        this.isInitiator = initiator
        this.deviceFingerprint = deviceFp

        // 从加密通道提取加密参数
        this.encryptionKey = encChannel.getAesKey()
        this.sendNonceBase = encChannel.getSendNonceBase()
        this.recvNonceBase = encChannel.getRecvNonceBase()
        this.sendNonceCounter = encChannel.getSendCounter()
        this.recvNonceCounter = encChannel.getRecvCounter()
    }

    /**
     * 切换传输层：替换底层流，保留加密状态。
     * nonce 计数器继续递增，确保不会重用 nonce。
     */
    fun switchTransport(newInputStream: InputStream, newOutputStream: OutputStream) {
        encryptedChannel?.switchStreams(newInputStream, newOutputStream)
    }

    /**
     * 检查是否可以恢复指定的会话。
     * 需要会话 ID 匹配且加密参数完整。
     */
    fun canResumeSession(remoteSessionId: String): Boolean {
        return sessionId == remoteSessionId &&
               encryptionKey != null &&
               sendNonceBase != null &&
               recvNonceBase != null
    }

    /** 获取当前会话 ID */
    fun getSessionId(): String = sessionId

    /** 检查会话是否活跃 */
    fun isActive(): Boolean = encryptedChannel != null && encryptionKey != null

    /** 清除所有会话状态（断开连接时调用） */
    fun clear() {
        sessionId = ""
        encryptionKey = null
        sendNonceBase = null
        recvNonceBase = null
        sendNonceCounter = AtomicLong(0)
        recvNonceCounter = AtomicLong(0)
        deviceFingerprint = ""
        authenticatedFeatures.clear()
        encryptedChannel = null
    }
}
