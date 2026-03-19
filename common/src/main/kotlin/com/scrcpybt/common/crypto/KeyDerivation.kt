package com.scrcpybt.common.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 密钥派生（单例）。
 *
 * 实现 RFC 5869 定义的 HKDF（HMAC-based Key Derivation Function），
 * 使用 SHA-256 作为底层哈希函数。
 *
 * 在本项目中，HKDF 用于从 ECDH 共享密钥派生：
 * 1. AES-256 加密密钥（32 字节）
 * 2. 两组 nonce 基底（各 4 字节，共 8 字节）
 *
 * HKDF 分两步：
 * - Extract：将可变长度的 IKM（输入密钥材料）和盐值压缩为固定长度的 PRK
 * - Expand：将 PRK 扩展为任意长度的输出密钥材料 OKM
 */
object KeyDerivation {
    /**
     * HKDF-SHA256 密钥派生。
     *
     * @param ikm 输入密钥材料（ECDH 共享密钥）
     * @param salt 盐值（随机字节，增加抗预计算攻击能力）
     * @param info 上下文信息（区分不同用途的派生密钥）
     * @param length 输出密钥长度（字节）
     * @return 派生的密钥材料
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // HKDF-Extract：PRK = HMAC-SHA256(salt, IKM)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // HKDF-Expand：OKM = T(1) || T(2) || ... 其中 T(i) = HMAC(PRK, T(i-1) || info || i)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = byteArrayOf()
        val iterations = (length + 31) / 32
        for (i in 1..iterations) {
            mac.update(t)           // T(i-1)，首轮为空
            mac.update(info)        // 上下文信息
            mac.update(i.toByte())  // 轮次计数器
            t = mac.doFinal()
            val copyLen = minOf(32, length - (i - 1) * 32)
            System.arraycopy(t, 0, okm, (i - 1) * 32, copyLen)
        }
        return okm
    }
}
