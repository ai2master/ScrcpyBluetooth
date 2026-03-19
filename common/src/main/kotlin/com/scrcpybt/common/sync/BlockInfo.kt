package com.scrcpybt.common.sync

/**
 * 文件块信息，用于块级增量同步。
 *
 * 文件被划分为固定大小的块（默认 128KB），每个块通过 SHA-256 哈希标识。
 * 这使得同步引擎可以：
 * - 只传输变化的块，而非整个文件
 * - 跨文件的块去重（相同内容的块不重复传输）
 * - 支持断点续传
 *
 * @property offset 块在文件中的字节偏移量
 * @property size 块大小（通常 128KB，最后一块可能较小）
 * @property hash 块数据的 SHA-256 哈希（64 个十六进制字符）
 */
data class BlockInfo(
    val offset: Long,
    val size: Int,
    val hash: String
) {
    init {
        require(offset >= 0) { "Block offset must be non-negative" }
        require(size > 0) { "Block size must be positive" }
        require(hash.length == 64) { "SHA-256 hash must be 64 hex characters" }
    }
}
