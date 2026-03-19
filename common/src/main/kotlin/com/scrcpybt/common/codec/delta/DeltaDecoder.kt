package com.scrcpybt.common.codec.delta

/**
 * XOR 帧差解码器。
 *
 * 与 [DeltaEncoder] 配对使用。接收 XOR 差异数据和前帧数据，
 * 通过再次 XOR 运算还原出当前帧。
 *
 * 原理：A XOR B = C，则 C XOR B = A。
 * 即：差异数据 XOR 前帧 = 当前帧。
 */
class DeltaDecoder {
    /**
     * 解码 XOR 帧差数据，还原当前帧。
     *
     * @param delta XOR 差异数据（由编码端生成）
     * @param previous 前帧的调色板索引数组
     * @return 还原后的当前帧调色板索引数组
     */
    fun decode(delta: ByteArray, previous: ByteArray): ByteArray {
        val result = ByteArray(delta.size)
        for (i in delta.indices) result[i] = (delta[i].toInt() xor previous[i].toInt()).toByte()
        return result
    }
}
