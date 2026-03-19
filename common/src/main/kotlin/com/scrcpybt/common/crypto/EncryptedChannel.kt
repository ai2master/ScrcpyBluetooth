package com.scrcpybt.common.crypto

import com.scrcpybt.common.protocol.MessageHeader
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.protocol.message.Message
import com.scrcpybt.common.protocol.message.MessageCodec
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加密通道。
 *
 * 在 ECDH 密钥交换完成后，使用 HKDF 派生的密钥建立加密通道。
 * 所有后续消息均通过此通道加密传输。
 *
 * 加密方案：
 * - 算法：AES-256-GCM（认证加密，同时保证机密性和完整性）
 * - 密钥：256 位，由 HKDF-SHA256 从 ECDH 共享密钥派生
 * - Nonce：12 字节 = 4 字节基底 + 8 字节递增计数器
 * - 发送和接收使用独立的 nonce 基底，避免 nonce 重用
 *
 * Nonce 安全性：
 * - 发起方使用 nonceA 发送、nonceB 接收
 * - 被发起方使用 nonceB 发送、nonceA 接收
 * - 计数器单调递增，结合不同的基底确保全局唯一
 *
 * 线程安全：
 * - send 和 receive 通过 synchronized 保护，支持多线程使用
 * - 传输切换 (switchStreams) 也在锁保护下进行
 */
class EncryptedChannel {
    /** AES-256 密钥 */
    private lateinit var aesKey: SecretKeySpec
    /** 发送方向的 nonce 基底（4 字节） */
    private lateinit var sendNonceBase: ByteArray
    /** 接收方向的 nonce 基底（4 字节） */
    private lateinit var recvNonceBase: ByteArray
    /** 发送方向的 nonce 计数器（单调递增） */
    private val sendCounter = AtomicLong(0)
    /** 接收方向的 nonce 计数器（单调递增） */
    private val recvCounter = AtomicLong(0)
    /** 消息读取器 */
    private lateinit var reader: MessageReader
    /** 消息写入器 */
    private lateinit var writer: MessageWriter
    /** 流操作锁，保护并发读写和传输切换 */
    private val streamLock = Any()
    /** 复用的 Cipher 实例（send 和 receive 各自在锁保护下使用） */
    private val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
    /** 复用的 nonce 缓冲区，避免每次加解密分配 12 字节数组 */
    private val sendNonceBuffer = ByteArray(CryptoConstants.GCM_NONCE_SIZE)
    private val recvNonceBuffer = ByteArray(CryptoConstants.GCM_NONCE_SIZE)

    /**
     * 初始化加密通道。
     *
     * 从 ECDH 共享密钥通过 HKDF 派生 AES 密钥和 nonce 基底，
     * 并根据发起方/被发起方角色分配 nonce 方向。
     *
     * @param sharedSecret ECDH 共享密钥
     * @param salt HKDF 盐值（通常为随机字节）
     * @param isInitiator 是否为连接发起方（决定 nonce 方向分配）
     * @param input 输入流
     * @param output 输出流
     */
    fun initialize(sharedSecret: ByteArray, salt: ByteArray, isInitiator: Boolean, input: InputStream, output: OutputStream) {
        // 派生 AES-256 密钥
        val keyMaterial = KeyDerivation.hkdf(sharedSecret, salt, CryptoConstants.HKDF_INFO_AES_KEY.toByteArray(), CryptoConstants.AES_KEY_SIZE)
        aesKey = SecretKeySpec(keyMaterial, "AES")
        // 派生两组 nonce 基底（A 和 B）
        val nonceMaterial = KeyDerivation.hkdf(sharedSecret, salt, CryptoConstants.HKDF_INFO_NONCE.toByteArray(), CryptoConstants.NONCE_BASE_SIZE * 2)
        val nonceA = nonceMaterial.copyOfRange(0, CryptoConstants.NONCE_BASE_SIZE)
        val nonceB = nonceMaterial.copyOfRange(CryptoConstants.NONCE_BASE_SIZE, CryptoConstants.NONCE_BASE_SIZE * 2)
        // 发起方用 A 发送 B 接收，被发起方反之
        if (isInitiator) { sendNonceBase = nonceA; recvNonceBase = nonceB }
        else { sendNonceBase = nonceB; recvNonceBase = nonceA }
        reader = MessageReader(input)
        writer = MessageWriter(output)
    }

    /**
     * 加密并发送消息。
     * 序列化 → 加密 → 组装消息头 → 写入流。
     */
    fun send(message: Message) {
        synchronized(streamLock) {
            val plaintext = MessageCodec.serialize(message)
            val ciphertext = encrypt(plaintext, sendNonceBase, sendCounter.getAndIncrement())
            val header = MessageHeader(message.type, ProtocolConstants.FLAG_ENCRYPTED, ciphertext.size, sendCounter.get().toInt() - 1)
            writer.write(header, ciphertext)
        }
    }

    /**
     * 接收并解密消息。
     * 读取消息头 → 读取载荷 → 解密（如有加密标志） → 反序列化。
     */
    fun receive(): Message {
        synchronized(streamLock) {
            val header = reader.readHeader()
            val payload = reader.readPayload(header.payloadLength)
            val plaintext = if (header.isEncrypted()) decrypt(payload, recvNonceBase, recvCounter.getAndIncrement()) else payload
            return MessageCodec.deserialize(header.type, plaintext)
        }
    }

    /** AES-256-GCM 加密（复用 Cipher 实例和 nonce 缓冲区） */
    private fun encrypt(plaintext: ByteArray, nonceBase: ByteArray, counter: Long): ByteArray {
        fillNonce(sendNonceBuffer, nonceBase, counter)
        encryptCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, sendNonceBuffer))
        return encryptCipher.doFinal(plaintext)
    }

    /** AES-256-GCM 解密（复用 Cipher 实例和 nonce 缓冲区） */
    private fun decrypt(ciphertext: ByteArray, nonceBase: ByteArray, counter: Long): ByteArray {
        fillNonce(recvNonceBuffer, nonceBase, counter)
        decryptCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, recvNonceBuffer))
        return decryptCipher.doFinal(ciphertext)
    }

    /**
     * 填充 12 字节 GCM nonce 到已有缓冲区（零分配）。
     * 格式：[基底:4字节][计数器:8字节(大端序)]
     */
    private fun fillNonce(buffer: ByteArray, base: ByteArray, counter: Long) {
        System.arraycopy(base, 0, buffer, 0, CryptoConstants.NONCE_BASE_SIZE)
        ByteBuffer.wrap(buffer, CryptoConstants.NONCE_BASE_SIZE, 8).order(ByteOrder.BIG_ENDIAN).putLong(counter)
    }

    /**
     * 切换底层传输流，保留加密状态。
     *
     * 用于蓝牙↔USB 传输无缝切换：关闭旧流，用新流替换，
     * 加密密钥和 nonce 计数器保持不变，实现会话连续性。
     */
    fun switchStreams(newInput: InputStream, newOutput: OutputStream) {
        synchronized(streamLock) {
            runCatching { reader.close() }
            runCatching { writer.close() }
            reader = MessageReader(newInput)
            writer = MessageWriter(newOutput)
        }
    }

    /** 获取 AES 密钥（用于会话管理/恢复） */
    fun getAesKey(): ByteArray = aesKey.encoded

    /** 获取发送 nonce 基底（用于会话管理/恢复） */
    fun getSendNonceBase(): ByteArray = sendNonceBase.clone()

    /** 获取接收 nonce 基底（用于会话管理/恢复） */
    fun getRecvNonceBase(): ByteArray = recvNonceBase.clone()

    /** 获取发送计数器（用于会话管理/恢复） */
    fun getSendCounter(): AtomicLong = sendCounter

    /** 获取接收计数器（用于会话管理/恢复） */
    fun getRecvCounter(): AtomicLong = recvCounter

    /** 获取底层输出流 */
    fun getOutputStream(): OutputStream = writer.getOutputStream()

    /** 关闭加密通道，释放底层流资源 */
    fun close() { runCatching { reader.close() }; runCatching { writer.close() } }
}
