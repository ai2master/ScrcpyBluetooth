package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import com.scrcpybt.common.sync.BlockInfo
import com.scrcpybt.common.sync.FileInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 文件夹同步消息，支持块级增量同步（类 Syncthing 设计）。
 *
 * 同步流程：
 * 1. SYNC_REQUEST：发起方发送文件夹配置（路径、类型等）
 * 2. SYNC_MANIFEST：交换完整文件列表（含块级 SHA-256 哈希）
 * 3. SYNC_DIFF：计算需要传输的差异
 * 4. SYNC_BLOCK_REQUEST：请求特定文件的特定块
 * 5. SYNC_BLOCK_DATA：传输块数据
 * 6. SYNC_DELETE：传播删除操作
 * 7. SYNC_CONFLICT：通知冲突
 * 8. SYNC_COMPLETE：同步完成
 * 9. SYNC_ERROR：发生错误
 *
 * @property subType 同步子类型
 * @property syncId 同步会话 ID
 * @property localPath 本地文件夹路径
 * @property remotePath 远端文件夹路径
 * @property direction 同步方向（推送/拉取/双向）
 * @property folderType 文件夹类型（仅发送/仅接收/双向同步）
 * @property sequence 序列号（基于序列的变更跟踪）
 * @property manifestEntries 文件清单条目列表
 * @property blockRequests 块请求列表
 * @property blockData 块数据载荷
 * @property errorMessage 错误信息
 */
data class FolderSyncMessage(
    val subType: Byte = SUB_SYNC_REQUEST,
    val syncId: Int = 0,
    val localPath: String = "",
    val remotePath: String = "",
    val direction: Byte = DIR_PUSH,
    val folderType: Byte = FOLDER_TYPE_SEND_RECEIVE,
    val sequence: Long = 0,              // for sequence-based sync
    val manifestEntries: List<ManifestEntry> = emptyList(),
    val blockRequests: List<BlockRequest> = emptyList(),
    val blockData: BlockData? = null,
    val errorMessage: String = ""
) : Message() {
    override val type = MessageType.FOLDER_SYNC

    /**
     * 文件清单条目，包含块级哈希信息。
     */
    data class ManifestEntry(
        val relativePath: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
        val isDeleted: Boolean = false,
        val version: Long = 0,
        val blocks: List<BlockInfo> = emptyList()
    ) {
        fun toFileInfo(): FileInfo = FileInfo(
            relativePath = relativePath,
            size = size,
            lastModified = lastModified,
            isDirectory = isDirectory,
            blocks = blocks,
            isDeleted = isDeleted,
            version = version
        )
    }

    /**
     * Request for specific blocks of a file.
     */
    data class BlockRequest(
        val relativePath: String,
        val blockIndices: List<Int>  // which blocks are needed
    )

    /**
     * Block data payload.
     */
    data class BlockData(
        val relativePath: String,
        val blockIndex: Int,
        val offset: Long,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as BlockData
            if (relativePath != other.relativePath) return false
            if (blockIndex != other.blockIndex) return false
            if (offset != other.offset) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = relativePath.hashCode()
            result = 31 * result + blockIndex
            result = 31 * result + offset.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    override fun serialize(): ByteArray {
        val localBytes = localPath.toByteArray(StandardCharsets.UTF_8)
        val remoteBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val errorBytes = errorMessage.toByteArray(StandardCharsets.UTF_8)

        // Estimate size - will use dynamic buffer
        val buffer = mutableListOf<ByteArray>()

        // Header: subType(1) + syncId(4) + direction(1) + folderType(1) + sequence(8)
        val header = ByteBuffer.allocate(15).order(ByteOrder.BIG_ENDIAN)
        header.put(subType)
        header.putInt(syncId)
        header.put(direction)
        header.put(folderType)
        header.putLong(sequence)
        buffer.add(header.array())

        // Paths
        val paths = ByteBuffer.allocate(8 + localBytes.size + remoteBytes.size).order(ByteOrder.BIG_ENDIAN)
        paths.putInt(localBytes.size)
        paths.put(localBytes)
        paths.putInt(remoteBytes.size)
        paths.put(remoteBytes)
        buffer.add(paths.array())

        // Manifest entries
        buffer.add(serializeManifestEntries())

        // Block requests
        buffer.add(serializeBlockRequests())

        // Block data
        buffer.add(serializeBlockData())

        // Error message
        val errorBuf = ByteBuffer.allocate(4 + errorBytes.size).order(ByteOrder.BIG_ENDIAN)
        errorBuf.putInt(errorBytes.size)
        errorBuf.put(errorBytes)
        buffer.add(errorBuf.array())

        // Combine all
        val totalSize = buffer.sumOf { it.size }
        val result = ByteBuffer.allocate(totalSize)
        buffer.forEach { result.put(it) }
        return result.array()
    }

    private fun serializeManifestEntries(): ByteArray {
        val entriesData = mutableListOf<ByteArray>()

        for (e in manifestEntries) {
            val pathBytes = e.relativePath.toByteArray(StandardCharsets.UTF_8)

            // Serialize blocks
            val blocksData = mutableListOf<ByteArray>()
            for (block in e.blocks) {
                val hashBytes = block.hash.toByteArray(StandardCharsets.UTF_8)
                val blockBuf = ByteBuffer.allocate(8 + 4 + 4 + hashBytes.size).order(ByteOrder.BIG_ENDIAN)
                blockBuf.putLong(block.offset)
                blockBuf.putInt(block.size)
                blockBuf.putInt(hashBytes.size)
                blockBuf.put(hashBytes)
                blocksData.add(blockBuf.array())
            }

            val blocksSize = blocksData.sumOf { it.size }
            val entryBuf = ByteBuffer.allocate(
                4 + pathBytes.size + 8 + 8 + 1 + 1 + 8 + 4 + blocksSize
            ).order(ByteOrder.BIG_ENDIAN)

            entryBuf.putInt(pathBytes.size)
            entryBuf.put(pathBytes)
            entryBuf.putLong(e.size)
            entryBuf.putLong(e.lastModified)
            entryBuf.put(if (e.isDirectory) 1.toByte() else 0.toByte())
            entryBuf.put(if (e.isDeleted) 1.toByte() else 0.toByte())
            entryBuf.putLong(e.version)
            entryBuf.putInt(e.blocks.size)
            blocksData.forEach { entryBuf.put(it) }

            entriesData.add(entryBuf.array())
        }

        val totalSize = 4 + entriesData.sumOf { it.size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(manifestEntries.size)
        entriesData.forEach { buf.put(it) }
        return buf.array()
    }

    private fun serializeBlockRequests(): ByteArray {
        val requestsData = mutableListOf<ByteArray>()

        for (req in blockRequests) {
            val pathBytes = req.relativePath.toByteArray(StandardCharsets.UTF_8)
            val reqBuf = ByteBuffer.allocate(
                4 + pathBytes.size + 4 + req.blockIndices.size * 4
            ).order(ByteOrder.BIG_ENDIAN)

            reqBuf.putInt(pathBytes.size)
            reqBuf.put(pathBytes)
            reqBuf.putInt(req.blockIndices.size)
            req.blockIndices.forEach { reqBuf.putInt(it) }

            requestsData.add(reqBuf.array())
        }

        val totalSize = 4 + requestsData.sumOf { it.size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(blockRequests.size)
        requestsData.forEach { buf.put(it) }
        return buf.array()
    }

    private fun serializeBlockData(): ByteArray {
        val bd = blockData ?: return ByteBuffer.allocate(1).put(0.toByte()).array()

        val pathBytes = bd.relativePath.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(
            1 + 4 + pathBytes.size + 4 + 8 + 4 + bd.data.size
        ).order(ByteOrder.BIG_ENDIAN)

        buf.put(1.toByte())  // has block data
        buf.putInt(pathBytes.size)
        buf.put(pathBytes)
        buf.putInt(bd.blockIndex)
        buf.putLong(bd.offset)
        buf.putInt(bd.data.size)
        buf.put(bd.data)

        return buf.array()
    }

    companion object {
        // Sub-types
        const val SUB_SYNC_REQUEST: Byte = 0
        const val SUB_SYNC_MANIFEST: Byte = 1
        const val SUB_SYNC_DIFF: Byte = 2
        const val SUB_SYNC_COMPLETE: Byte = 3
        const val SUB_SYNC_ERROR: Byte = 4
        const val SUB_SYNC_BLOCK_REQUEST: Byte = 5  // request specific blocks
        const val SUB_SYNC_BLOCK_DATA: Byte = 6     // block data response
        const val SUB_SYNC_DELETE: Byte = 7         // deletion propagation
        const val SUB_SYNC_CONFLICT: Byte = 8       // conflict notification

        // Direction
        const val DIR_PUSH: Byte = 0  // local -> remote
        const val DIR_PULL: Byte = 1  // remote -> local
        const val DIR_BIDIRECTIONAL: Byte = 2  // both ways

        // Folder types
        const val FOLDER_TYPE_SEND_ONLY: Byte = 0
        const val FOLDER_TYPE_RECEIVE_ONLY: Byte = 1
        const val FOLDER_TYPE_SEND_RECEIVE: Byte = 2

        fun deserialize(data: ByteArray): FolderSyncMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            // Header
            val sub = buf.get()
            val sid = buf.int
            val dir = buf.get()
            val folderTp = buf.get()
            val seq = buf.long

            // Paths
            val localLen = buf.int
            val localBytes = ByteArray(localLen)
            buf.get(localBytes)
            val remoteLen = buf.int
            val remoteBytes = ByteArray(remoteLen)
            buf.get(remoteBytes)

            // Manifest entries
            val entries = deserializeManifestEntries(buf)

            // Block requests
            val blockReqs = deserializeBlockRequests(buf)

            // Block data
            val blockDt = deserializeBlockData(buf)

            // Error message
            val errorLen = buf.int
            val errorBytes = ByteArray(errorLen)
            buf.get(errorBytes)

            return FolderSyncMessage(
                sub, sid,
                String(localBytes, StandardCharsets.UTF_8),
                String(remoteBytes, StandardCharsets.UTF_8),
                dir, folderTp, seq, entries, blockReqs, blockDt,
                String(errorBytes, StandardCharsets.UTF_8)
            )
        }

        private fun deserializeManifestEntries(buf: ByteBuffer): List<ManifestEntry> {
            val entryCount = buf.int
            return (0 until entryCount).map {
                val pathLen = buf.int
                val pathBytes = ByteArray(pathLen)
                buf.get(pathBytes)
                val size = buf.long
                val lastMod = buf.long
                val isDir = buf.get() != 0.toByte()
                val isDel = buf.get() != 0.toByte()
                val ver = buf.long

                val blockCount = buf.int
                val blocks = (0 until blockCount).map {
                    val offset = buf.long
                    val blkSize = buf.int
                    val hashLen = buf.int
                    val hashBytes = ByteArray(hashLen)
                    buf.get(hashBytes)
                    BlockInfo(offset, blkSize, String(hashBytes, StandardCharsets.UTF_8))
                }

                ManifestEntry(
                    String(pathBytes, StandardCharsets.UTF_8),
                    size, lastMod, isDir, isDel, ver, blocks
                )
            }
        }

        private fun deserializeBlockRequests(buf: ByteBuffer): List<BlockRequest> {
            val reqCount = buf.int
            return (0 until reqCount).map {
                val pathLen = buf.int
                val pathBytes = ByteArray(pathLen)
                buf.get(pathBytes)
                val indicesCount = buf.int
                val indices = (0 until indicesCount).map { buf.int }

                BlockRequest(String(pathBytes, StandardCharsets.UTF_8), indices)
            }
        }

        private fun deserializeBlockData(buf: ByteBuffer): BlockData? {
            val hasData = buf.get() != 0.toByte()
            if (!hasData) return null

            val pathLen = buf.int
            val pathBytes = ByteArray(pathLen)
            buf.get(pathBytes)
            val blockIdx = buf.int
            val offset = buf.long
            val dataLen = buf.int
            val data = ByteArray(dataLen)
            buf.get(data)

            return BlockData(
                String(pathBytes, StandardCharsets.UTF_8),
                blockIdx, offset, data
            )
        }
    }
}
