package com.scrcpybt.common.codec.compression

/**
 * 压缩器接口。
 *
 * 定义数据压缩和解压的通用接口。当前实现为 [LZ4Compressor]。
 * 在 256 色编码管线中，压缩器是最后一步：
 * XOR 帧差数据含大量连续 0 字节 → LZ4 压缩后体积大幅缩小。
 */
interface Compressor {
    /**
     * 压缩数据。
     * @param data 原始数据
     * @return 压缩后的数据
     */
    fun compress(data: ByteArray): ByteArray

    /**
     * 解压数据。
     * @param compressed 压缩数据
     * @param originalSize 原始数据长度（LZ4 解压需要预知）
     * @return 还原的原始数据
     */
    fun decompress(compressed: ByteArray, originalSize: Int): ByteArray

    /** 压缩算法类型标识（用于协议中标记使用的压缩方式） */
    val typeId: Byte
}
