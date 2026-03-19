package com.scrcpybt.common.crypto

import java.security.SecureRandom

/**
 * 安全随机数生成器封装（单例）。
 *
 * 封装 Java [SecureRandom]，提供生成加密安全随机字节的便捷方法。
 * 用于生成 HKDF 盐值和其他需要密码学安全随机性的场景。
 */
object SecureRandomWrapper {
    /** 底层安全随机数生成器实例 */
    private val random = SecureRandom()

    /**
     * 生成指定长度的加密安全随机字节数组。
     * @param length 随机字节长度
     */
    fun generateBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }
}
