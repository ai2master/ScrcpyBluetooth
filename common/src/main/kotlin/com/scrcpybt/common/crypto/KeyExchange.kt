package com.scrcpybt.common.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * ECDH (椭圆曲线 Diffie-Hellman) 密钥交换。
 *
 * 使用 P-256 (secp256r1) 曲线实现密钥协商。
 *
 * 流程：
 * 1. 双方各自调用 [generateKeyPair] 生成 EC 密钥对
 * 2. 交换公钥（通过 KEY_EXCHANGE 消息）
 * 3. 各自调用 [computeSharedSecret] 计算共享密钥
 * 4. 共享密钥再通过 HKDF 派生出 AES-256 加密密钥
 *
 * 安全性：
 * - P-256 曲线提供约 128 位安全强度
 * - 即使公钥被截获，攻击者也无法计算出共享密钥
 * - 每次会话生成新的密钥对，提供前向保密性
 */
class KeyExchange {
    /** 本端的 EC 密钥对 */
    private lateinit var keyPair: KeyPair

    /** ECDH 计算得到的共享密钥 */
    private lateinit var _sharedSecret: ByteArray

    /** 获取共享密钥（需要先调用 computeSharedSecret） */
    val sharedSecret: ByteArray get() = _sharedSecret

    /**
     * 生成 EC 密钥对并返回公钥的 X.509 编码。
     * 该编码通过 KEY_EXCHANGE 消息发送给对端。
     *
     * @return 公钥的 X.509 DER 编码字节数组
     */
    fun generateKeyPair(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CryptoConstants.EC_CURVE))
        keyPair = kpg.generateKeyPair()
        return keyPair.public.encoded
    }

    /**
     * 使用对端公钥计算 ECDH 共享密钥。
     *
     * @param peerPublicKeyEncoded 对端公钥的 X.509 DER 编码
     */
    fun computeSharedSecret(peerPublicKeyEncoded: ByteArray) {
        val keyFactory = KeyFactory.getInstance("EC")
        val peerKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyEncoded))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerKey, true)
        _sharedSecret = ka.generateSecret()
    }
}
