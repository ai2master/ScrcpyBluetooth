package com.scrcpybt.common.protocol.message

import com.scrcpybt.common.protocol.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 文件传输协议消息。
 *
 * 支持大文件分块传输。传输流程：
 * 1. 发送方发送 FILE_BEGIN（携带路径、大小、是否为目录等元信息）
 * 2. 对于文件：发送方连续发送 FILE_CHUNK 消息直到数据发完
 * 3. 发送方发送 FILE_END 标记传输结束
 * 4. 对于目录：FILE_BEGIN(isDir=true) → 递归传输内容 → FILE_END
 *
 * 支持断点续传：FILE_RESUME / FILE_RESUME_ACK 子类型。
 *
 * @property subType 子类型（开始/分块/结束/确认/错误/续传）
 * @property transferId 传输 ID（用于匹配同一次传输的多条消息）
 * @property remotePath 远端文件路径
 * @property fileSize 文件总大小（字节）
 * @property isDirectory 是否为目录
 * @property chunkIndex 当前分块序号
 * @property chunkData 分块数据
 * @property permissions 文件权限（Unix 模式，默认 0644）
 */
data class FileTransferMessage(
    val subType: Byte = SUB_FILE_BEGIN,
    val transferId: Int = 0,
    val remotePath: String = "",
    val fileSize: Long = 0,
    val isDirectory: Boolean = false,
    val chunkIndex: Int = 0,
    val chunkData: ByteArray? = null,
    val permissions: Int = 0x1A4
) : Message() {
    override val type = MessageType.FILE_TRANSFER

    override fun serialize(): ByteArray {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val chunkLen = chunkData?.size ?: 0
        val buf = ByteBuffer.allocate(1 + 4 + 4 + pathBytes.size + 8 + 1 + 4 + 4 + chunkLen + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(subType)
        buf.putInt(transferId)
        buf.putInt(pathBytes.size)
        buf.put(pathBytes)
        buf.putLong(fileSize)
        buf.put(if (isDirectory) 1.toByte() else 0.toByte())
        buf.putInt(chunkIndex)
        buf.putInt(chunkLen)
        chunkData?.let { buf.put(it) }
        buf.putInt(permissions)
        return buf.array()
    }

    companion object {
        /** 传输开始（携带文件元信息） */
        const val SUB_FILE_BEGIN: Byte = 0
        /** 传输分块（携带文件数据） */
        const val SUB_FILE_CHUNK: Byte = 1
        /** 传输结束 */
        const val SUB_FILE_END: Byte = 2
        /** 传输确认 */
        const val SUB_FILE_ACK: Byte = 3
        /** 传输错误 */
        const val SUB_FILE_ERROR: Byte = 4
        /** 断点续传请求 */
        const val SUB_FILE_RESUME: Byte = 5
        /** 断点续传确认 */
        const val SUB_FILE_RESUME_ACK: Byte = 6

        /** 路径字段最大长度：4KB */
        private const val MAX_PATH_SIZE = 4 * 1024

        fun deserialize(data: ByteArray): FileTransferMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val sub = buf.get()
            val tid = buf.int
            val pathLen = buf.int
            if (pathLen < 0 || pathLen > MAX_PATH_SIZE) {
                throw java.io.IOException("文件路径长度非法: $pathLen (上限 $MAX_PATH_SIZE)")
            }
            val pathBytes = ByteArray(pathLen); buf.get(pathBytes)
            val fSize = buf.long
            val isDir = buf.get() != 0.toByte()
            val chunkIdx = buf.int
            val chunkLen = buf.int
            val chunk = if (chunkLen > 0) ByteArray(chunkLen).also { buf.get(it) } else null
            val perms = buf.int
            return FileTransferMessage(sub, tid, String(pathBytes, StandardCharsets.UTF_8), fSize, isDir, chunkIdx, chunk, perms)
        }
    }

    override fun equals(other: Any?) = other is FileTransferMessage && transferId == other.transferId && subType == other.subType && chunkIndex == other.chunkIndex
    override fun hashCode() = transferId * 31 + subType * 31 + chunkIndex
}
