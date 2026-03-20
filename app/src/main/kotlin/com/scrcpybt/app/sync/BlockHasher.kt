package com.scrcpybt.app.sync

import com.scrcpybt.common.sync.BlockInfo
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 文件块级 SHA-256 哈希计算器 | Block-level SHA-256 hash computer for files
 *
 * 块大小可配置（默认 128KB）
 * Block size configurable (default 128KB)
 *
 * 这是块级同步的核心，实现：
 * This is the core of block-level synchronization, enabling:
 * - 仅传输变更的块，而非整个文件 | Transfer only changed blocks, not entire files
 * - 跨文件的块去重 | Block deduplication across files
 * - 高效的增量更新 | Efficient incremental updates
 */
class BlockHasher(private val blockSize: Int = 128 * 1024) {

    /** 复用的 MessageDigest 实例，避免每个块重新创建（重量级对象） | Reusable MessageDigest instance to avoid recreating for each block (heavyweight object) */
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        require(blockSize > 0) { "Block size must be positive" }
    }

    /**
     * 将整个文件分块哈希 | Hash an entire file into blocks
     *
     * 返回包含偏移量、大小和 SHA-256 哈希的 BlockInfo 列表
     * Returns list of BlockInfo with offset, size, and SHA-256 hash
     *
     * @param file 要哈希的文件 | File to hash
     * @return 块信息列表 | List of block information
     */
    fun hashFile(file: File): List<BlockInfo> {
        if (!file.exists() || !file.isFile) {
            return emptyList()
        }

        val blocks = mutableListOf<BlockInfo>()
        val buffer = ByteArray(blockSize)
        var offset = 0L

        FileInputStream(file).use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) break

                val hash = hashBlock(buffer, 0, bytesRead)
                blocks.add(BlockInfo(offset, bytesRead, hash))
                offset += bytesRead
            }
        }

        return blocks
    }

    /**
     * 哈希单个数据块 | Hash a single block of data
     *
     * 返回 SHA-256 哈希的十六进制字符串（64 字符）
     * Returns SHA-256 hash as hex string (64 characters)
     *
     * @param data 数据数组 | Data array
     * @param offset 偏移量 | Offset
     * @param length 长度 | Length
     * @return 哈希值的十六进制表示 | Hex representation of hash
     */
    fun hashBlock(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        digest.reset()
        digest.update(data, offset, length)
        return digest.digest().toHex()
    }

    /**
     * 直接哈希数据（便捷方法） | Hash data directly (convenience method)
     *
     * @param data 要哈希的数据 | Data to hash
     * @return 哈希值 | Hash value
     */
    fun hashData(data: ByteArray): String {
        return hashBlock(data, 0, data.size)
    }

    /**
     * 验证块是否匹配预期的哈希值 | Verify that a block matches its expected hash
     *
     * @param data 数据数组 | Data array
     * @param offset 偏移量 | Offset
     * @param length 长度 | Length
     * @param expectedHash 预期的哈希值 | Expected hash
     * @return 是否匹配 | Whether it matches
     */
    fun verifyBlock(data: ByteArray, offset: Int, length: Int, expectedHash: String): Boolean {
        val actualHash = hashBlock(data, offset, length)
        return actualHash.equals(expectedHash, ignoreCase = true)
    }

    /**
     * 在块级别比较两个文件并返回哪些块不同 | Compare two files at block level and return which blocks differ
     *
     * 返回不同块的索引列表
     * Returns list of block indices that are different
     *
     * @param file1 文件1 | File 1
     * @param file2 文件2 | File 2
     * @return 不同块的索引列表 | List of different block indices
     */
    fun findDifferentBlocks(file1: File, file2: File): List<Int> {
        val blocks1 = hashFile(file1)
        val blocks2 = hashFile(file2)

        val differentBlocks = mutableListOf<Int>()

        val maxBlocks = maxOf(blocks1.size, blocks2.size)
        for (i in 0 until maxBlocks) {
            val block1 = blocks1.getOrNull(i)
            val block2 = blocks2.getOrNull(i)

            if (block1 == null || block2 == null || block1.hash != block2.hash) {
                differentBlocks.add(i)
            }
        }

        return differentBlocks
    }

    /**
     * 计算需要从本地传输哪些块以匹配远端 | Calculate which blocks need to be transferred from local to match remote
     *
     * @param localBlocks 当前本地文件块 | Current local file blocks
     * @param remoteBlocks 目标远端文件块 | Desired remote file blocks
     * @return 需要发送的块索引 | Indices of blocks that need to be sent
     */
    fun calculateNeededBlocks(localBlocks: List<BlockInfo>, remoteBlocks: List<BlockInfo>): List<Int> {
        val needed = mutableListOf<Int>()

        for (i in remoteBlocks.indices) {
            val remoteBlock = remoteBlocks[i]
            val localBlock = localBlocks.getOrNull(i)

            // Need block if local doesn't have it or hash differs
            if (localBlock == null || localBlock.hash != remoteBlock.hash) {
                needed.add(i)
            }
        }

        return needed
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
