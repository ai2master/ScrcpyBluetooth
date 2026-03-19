package com.scrcpybt.common.codec

import com.scrcpybt.common.codec.color.*
import com.scrcpybt.common.codec.compression.Compressor
import com.scrcpybt.common.codec.compression.LZ4Compressor
import com.scrcpybt.common.codec.delta.*
import com.scrcpybt.common.protocol.message.FrameMessage
import com.scrcpybt.common.util.ByteBufferPool

/**
 * 帧编解码器，实现 256 色编码管线的完整流程。
 *
 * 编码管线（被控端 → 控制端）：
 * ```
 * ARGB_8888 像素 → 256色量化 → 帧差检测 → XOR增量编码 → LZ4压缩 → FrameMessage
 * ```
 *
 * 解码管线（控制端接收）：
 * ```
 * FrameMessage → LZ4解压 → XOR增量还原 → 256色索引→ARGB像素
 * ```
 *
 * 关键策略：
 * - 调色板每 30 帧通过中位切割算法重新生成
 * - 关键帧每 150 帧强制发送一次
 * - 脏区覆盖超过 60% 时自动升级为关键帧
 * - 画面无变化时发送空帧（零开销）
 */
class FrameCodec {
    private val quantizer: ColorQuantizer = MedianCutQuantizer()
    private val compressor: Compressor = LZ4Compressor()
    private val deltaDecoder = DeltaDecoder()
    private var deltaEncoder: DeltaEncoder? = null
    private var currentPalette: Palette? = null
    private var colorMapper: ColorMapper? = null
    /** 上一帧的索引数据，用于帧差计算 */
    private var previousFrame: ByteArray? = null
    var width = 0; var height = 0; private set
    private var frameCount = 0
    /** 索引缓冲区对象池，减少编码路径中 ByteArray 分配 */
    private var indexBufferPool: ByteBufferPool? = null

    /** 获取已编码/解码的帧数量 */
    fun getFrameCount(): Int = frameCount

    /** 设置帧尺寸，重置编码器状态 */
    fun setDimensions(w: Int, h: Int) { width = w; height = h; deltaEncoder = DeltaEncoder(w, h); previousFrame = null; frameCount = 0; indexBufferPool = ByteBufferPool(w * h) }

    /**
     * 编码一帧 ARGB_8888 像素为 FrameMessage。
     *
     * 流程：
     * 1. 每 30 帧重新生成 256 色调色板
     * 2. 将 RGB 像素映射为调色板索引
     * 3. 与前帧比较，检测 16×16 块级脏区
     * 4. 根据脏区覆盖率决定关键帧或增量帧
     * 5. XOR 帧差编码 + LZ4 压缩
     *
     * @param pixels ARGB_8888 格式的像素数组
     * @return 编码后的帧消息
     */
    fun encode(pixels: IntArray): FrameMessage {
        // 每 90 帧重新生成调色板（适应画面颜色变化，降低开销）
        if (currentPalette == null || frameCount % 90 == 0) {
            currentPalette = quantizer.generatePalette(pixels)
            colorMapper = ColorMapper(currentPalette!!)
        }
        // RGB → 调色板索引
        val indices = colorMapper!!.mapFrame(pixels)
        val prev = previousFrame
        // 决定是否强制关键帧：首帧或每 150 帧
        val forceKey = prev == null || frameCount % 150 == 0
        // 检测脏区（仅增量帧需要）
        val dirty = if (!forceKey) deltaEncoder!!.detectDirty(indices, prev!!) else null
        // 脏区覆盖超过 60% 时升级为关键帧
        val isKey = forceKey || (dirty != null && dirty.coverage() > (width / 16 * height / 16) * 0.6f)

        // 画面无变化：发送空帧
        if (!isKey && dirty != null && dirty.isEmpty()) {
            previousFrame = indices; frameCount++
            return FrameMessage(false, width.toShort(), height.toShort(), compressor.typeId, 0, null, null)
        }
        // 关键帧：发送完整索引数据 + 调色板；增量帧：发送 XOR 差异
        val data = if (isKey) indices else deltaEncoder!!.encode(indices, prev!!, dirty!!)
        val compressed = compressor.compress(data)
        previousFrame = indices; frameCount++
        return FrameMessage(isKey, width.toShort(), height.toShort(), compressor.typeId, data.size, if (isKey) currentPalette!!.toBytes() else null, compressed)
    }

    /**
     * 解码 FrameMessage 为 ARGB_8888 像素数组。
     *
     * @param msg 接收到的帧消息
     * @return ARGB_8888 像素数组
     */
    fun decode(msg: FrameMessage): IntArray {
        // 空帧：返回上一帧内容
        if (msg.compressedData == null || msg.compressedData!!.isEmpty()) {
            return previousFrame?.let { colorMapper?.unmap(it) } ?: IntArray(width * height)
        }
        // LZ4 解压
        val decompressed = compressor.decompress(msg.compressedData!!, msg.originalSize)
        val indices = if (msg.isKeyframe) {
            // 关键帧：更新调色板，直接使用解压数据
            msg.palette?.let { currentPalette = Palette.fromBytes(it); colorMapper = ColorMapper(currentPalette!!) }
            decompressed
        } else {
            // 增量帧：XOR 还原
            deltaDecoder.decode(decompressed, previousFrame!!)
        }
        previousFrame = indices; width = msg.width.toInt(); height = msg.height.toInt()
        // 索引 → ARGB 像素
        return colorMapper!!.unmap(indices)
    }
}
