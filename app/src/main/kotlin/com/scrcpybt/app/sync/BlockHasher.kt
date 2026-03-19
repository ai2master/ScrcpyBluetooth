package com.scrcpybt.app.sync

import com.scrcpybt.common.sync.BlockInfo
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Computes block-level SHA-256 hashes for files.
 * Block size configurable (default 128KB).
 *
 * This is the core of block-level synchronization, enabling:
 * - Transfer only changed blocks, not entire files
 * - Block deduplication across files
 * - Efficient incremental updates
 */
class BlockHasher(private val blockSize: Int = 128 * 1024) {

    /** 复用的 MessageDigest 实例，避免每个块重新创建（重量级对象） */
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        require(blockSize > 0) { "Block size must be positive" }
    }

    /**
     * Hash an entire file into blocks.
     * Returns list of BlockInfo with offset, size, and SHA-256 hash.
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
     * Hash a single block of data.
     * Returns SHA-256 hash as hex string (64 characters).
     */
    fun hashBlock(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        digest.reset()
        digest.update(data, offset, length)
        return digest.digest().toHex()
    }

    /**
     * Hash data directly (convenience method).
     */
    fun hashData(data: ByteArray): String {
        return hashBlock(data, 0, data.size)
    }

    /**
     * Verify that a block matches its expected hash.
     */
    fun verifyBlock(data: ByteArray, offset: Int, length: Int, expectedHash: String): Boolean {
        val actualHash = hashBlock(data, offset, length)
        return actualHash.equals(expectedHash, ignoreCase = true)
    }

    /**
     * Compare two files at block level and return which blocks differ.
     * Returns list of block indices that are different.
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
     * Calculate which blocks need to be transferred from local to match remote.
     * @param localBlocks Current local file blocks
     * @param remoteBlocks Desired remote file blocks
     * @return Indices of blocks that need to be sent
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
