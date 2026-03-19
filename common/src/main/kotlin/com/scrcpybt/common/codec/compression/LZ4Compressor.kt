package com.scrcpybt.common.codec.compression

import net.jpountz.lz4.LZ4Factory

/**
 * LZ4 快速压缩器。
 *
 * 使用 lz4-java 库实现 LZ4 快速压缩算法。
 * LZ4 的特点是压缩/解压速度极快（接近内存拷贝速度），
 * 压缩率适中，非常适合实时帧数据传输场景。
 *
 * 在 256 色编码管线中，XOR 帧差数据含大量连续 0x00 字节，
 * LZ4 对此类数据的压缩效率极高，通常可压缩到 5-20% 体积。
 */
class LZ4Compressor : Compressor {
    /** LZ4 工厂实例（自动选择当前平台最快的实现） */
    private val factory = LZ4Factory.fastestInstance()
    /** LZ4 快速压缩器 */
    private val comp = factory.fastCompressor()
    /** LZ4 快速解压器 */
    private val decomp = factory.fastDecompressor()

    /** 压缩类型标识：1 = LZ4 */
    override val typeId: Byte = 1

    /**
     * LZ4 压缩。
     * 先分配最大可能的输出缓冲区，压缩后截断到实际长度。
     */
    override fun compress(data: ByteArray): ByteArray {
        val maxLen = comp.maxCompressedLength(data.size)
        val buf = ByteArray(maxLen)
        val len = comp.compress(data, 0, data.size, buf, 0, maxLen)
        return buf.copyOf(len)
    }

    /**
     * LZ4 解压。
     * 需要预知原始数据长度（通过消息头的 originalSize 字段传递）。
     */
    override fun decompress(compressed: ByteArray, originalSize: Int): ByteArray {
        val buf = ByteArray(originalSize)
        decomp.decompress(compressed, 0, buf, 0, originalSize)
        return buf
    }
}
