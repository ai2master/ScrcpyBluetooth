package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ECDH 密钥交换消息。
 *
 * 握手完成后双方交换此消息，携带 ECDH 公钥和 HKDF 盐值。
 * 收到对方公钥后各自计算共享密钥，再通过 HKDF 派生 AES-256 加密密钥。
 *
 * 载荷格式（大端序）：
 * [pubKeyLen:4][publicKey:N][saltLen:4][salt:N][timestamp:8]
 *
 * @property publicKey 本端 EC P-256 公钥的 X.509 DER 编码
 * @property salt HKDF 盐值（随机生成，由发起方提供）
 * @property timestamp 消息时间戳（用于防重放）
 */
data class KeyExchangeMessage(
    val publicKey: ByteArray,
    val salt: ByteArray,
    val timestamp: Long
) : Message() {
    override val type = MessageType.KEY_EXCHANGE

    override fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + publicKey.size + 4 + salt.size + 8)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(publicKey.size)
        buffer.put(publicKey)
        buffer.putInt(salt.size)
        buffer.put(salt)
        buffer.putLong(timestamp)
        return buffer.array()
    }

    companion object {
        fun deserialize(data: ByteArray): KeyExchangeMessage {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)
            val pubLen = buffer.int
            val pub = ByteArray(pubLen); buffer.get(pub)
            val saltLen = buffer.int
            val salt = ByteArray(saltLen); buffer.get(salt)
            return KeyExchangeMessage(pub, salt, buffer.long)
        }
    }

    override fun equals(other: Any?) = other is KeyExchangeMessage && publicKey.contentEquals(other.publicKey) && salt.contentEquals(other.salt) && timestamp == other.timestamp
    override fun hashCode() = publicKey.contentHashCode() * 31 + salt.contentHashCode() * 31 + timestamp.hashCode()
}
