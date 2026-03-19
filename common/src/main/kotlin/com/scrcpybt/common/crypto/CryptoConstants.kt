package com.scrcpybt.common.crypto

/**
 * 加密常量定义。
 *
 * 集中管理 ECDH 密钥交换和 AES-256-GCM 加密通道使用的所有常量。
 * 修改这些常量会影响协议兼容性，需要双端同步更新。
 */
object CryptoConstants {
    /** AES 密钥长度：32 字节 = 256 位 */
    const val AES_KEY_SIZE = 32
    /** GCM nonce 长度：12 字节（NIST 推荐值） */
    const val GCM_NONCE_SIZE = 12
    /** GCM 认证标签长度：128 位（最大安全强度） */
    const val GCM_TAG_BITS = 128
    /** Nonce 基底长度：4 字节（与 8 字节计数器合成 12 字节 nonce） */
    const val NONCE_BASE_SIZE = 4
    /** HKDF 派生 AES 密钥的 info 字符串 */
    const val HKDF_INFO_AES_KEY = "ScrcpyBT-AES-Key"
    /** HKDF 派生 nonce 基底的 info 字符串 */
    const val HKDF_INFO_NONCE = "ScrcpyBT-Nonce-Base"
    /** ECDH 椭圆曲线名称：P-256（约 128 位安全强度） */
    const val EC_CURVE = "secp256r1"
}
