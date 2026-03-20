package com.scrcpybt.server.file

import com.scrcpybt.common.protocol.message.FileTransferMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 文件传输处理器
 *
 * 处理被控设备上的文件传输操作，支持接收和发送文件。
 *
 * ### 功能特性
 * - 分块传输大文件
 * - 支持断点续传
 * - 多个并发传输（通过 transferId 区分）
 * - 自动创建接收目录
 * - 路径遍历攻击防护
 *
 * ### 传输协议
 * 1. **FILE_BEGIN**: 开始新的文件传输，创建目标文件
 * 2. **FILE_CHUNK**: 传输文件数据块
 * 3. **FILE_END**: 结束传输，发送确认
 * 4. **FILE_RESUME**: 请求断点续传，返回已接收的字节数
 * 5. **FILE_ACK**: 确认消息（成功或错误）
 *
 * ### 存储位置
 * 接收的文件默认保存到 `/sdcard/ScrcpyBluetooth/received/`
 *
 * ### 安全性
 * - 文件名清理：移除路径分隔符、空字节、路径遍历序列
 * - 路径解析验证：确保结果仍在接收目录内部
 * - 防止写入沙箱外的文件
 *
 * File transfer handler that manages file reception and transmission with chunked transfer,
 * resume support, and path traversal protection.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 * @see FileTransferMessage
 */
class FileTransferHandler {

    /** 活动的传输状态映射（transferId -> TransferState） */
    private val activeTransfers = mutableMapOf<Int, TransferState>()

    /** 文件接收路径 */
    private val receivePath = File(DEFAULT_RECEIVE_DIR)

    init {
        // 确保接收目录存在
        if (!receivePath.exists()) {
            receivePath.mkdirs()
            Logger.i(TAG, "Created receive directory: ${receivePath.absolutePath}")
        }
    }

    /**
     * 处理来自控制端的文件传输消息
     *
     * 根据子类型分发到不同的处理方法。
     *
     * @param msg 文件传输消息
     * @param writer 消息写入器（用于发送确认）
     */
    fun handleFileTransfer(msg: FileTransferMessage, writer: MessageWriter) {
        try {
            when (msg.subType) {
                FileTransferMessage.SUB_FILE_BEGIN -> handleFileBegin(msg)
                FileTransferMessage.SUB_FILE_CHUNK -> handleFileChunk(msg)
                FileTransferMessage.SUB_FILE_END -> handleFileEnd(msg, writer)
                FileTransferMessage.SUB_FILE_RESUME -> handleFileResume(msg, writer)
                FileTransferMessage.SUB_FILE_ACK -> {
                    // 来自控制端的确认，仅记录日志
                    Logger.d(TAG, "Received ACK for transfer ${msg.transferId}")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling file transfer", e)
            sendErrorAck(msg.transferId, e.message, writer)
        }
    }

    /**
     * 处理文件开始消息
     *
     * 创建目标文件并初始化传输状态。
     *
     * @param msg 文件传输消息
     */
    /**
     * 清理文件名：移除路径分隔符、空字节、路径遍历序列，防止写入沙箱外
     */
    private fun sanitizeFileName(rawPath: String): String {
        var name = rawPath.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace("\u0000", "")  // 移除空字节
        name = name.replace("..", "")       // 移除路径遍历
        name = name.trimStart('/')          // 移除前导斜杠
        if (name.isBlank()) name = "unknown_${System.currentTimeMillis()}"
        return name
    }

    /**
     * 安全路径解析：确保结果仍在 receivePath 内部
     */
    private fun safeResolve(fileName: String): File {
        val sanitized = sanitizeFileName(fileName)
        val file = File(receivePath, sanitized).canonicalFile
        val base = receivePath.canonicalFile
        if (!file.path.startsWith(base.path + File.separator) && file != base) {
            throw SecurityException("路径遍历攻击被阻止: $fileName")
        }
        return file
    }

    private fun handleFileBegin(msg: FileTransferMessage) {
        val file = safeResolve(msg.remotePath)
        val outputStream = FileOutputStream(file)

        activeTransfers[msg.transferId] = TransferState(
            file = file,
            outputStream = outputStream,
            totalSize = msg.fileSize,
            receivedSize = 0,
            resumeOffset = 0
        )

        Logger.i(TAG, "Started receiving file: ${file.name} (${msg.fileSize} bytes)")
    }

    /**
     * 处理断点续传请求
     *
     * 检查文件是否存在以及已接收的字节数，返回续传偏移量。
     *
     * @param msg 文件传输消息
     * @param writer 消息写入器
     */
    private fun handleFileResume(msg: FileTransferMessage, writer: MessageWriter) {
        val file = safeResolve(msg.remotePath)

        if (!file.exists()) {
            Logger.w(TAG, "Resume requested for non-existent file: ${file.name}")
            sendErrorAck(msg.transferId, "File not found for resume", writer)
            return
        }

        val currentSize = file.length()
        if (currentSize >= msg.fileSize) {
            Logger.i(TAG, "File already complete: ${file.name}")
            sendResumeAck(msg.transferId, msg.fileSize, writer)
            return
        }

        // Resume from current file size
        val outputStream = FileOutputStream(file, true) // append mode

        activeTransfers[msg.transferId] = TransferState(
            file = file,
            outputStream = outputStream,
            totalSize = msg.fileSize,
            receivedSize = currentSize,
            resumeOffset = currentSize
        )

        Logger.i(TAG, "Resuming file: ${file.name} from byte $currentSize")
        sendResumeAck(msg.transferId, currentSize, writer)
    }

    private fun handleFileChunk(msg: FileTransferMessage) {
        val transfer = activeTransfers[msg.transferId]
        if (transfer == null) {
            Logger.w(TAG, "Received chunk for unknown transfer ${msg.transferId}")
            return
        }

        msg.chunkData?.let { data ->
            transfer.outputStream.write(data)
            transfer.receivedSize += data.size
            Logger.d(TAG, "Received chunk ${msg.chunkIndex}: ${data.size} bytes (${transfer.receivedSize}/${transfer.totalSize})")
        }
    }

    private fun handleFileEnd(msg: FileTransferMessage, writer: MessageWriter) {
        val transfer = activeTransfers.remove(msg.transferId)
        if (transfer == null) {
            Logger.w(TAG, "Received end for unknown transfer ${msg.transferId}")
            return
        }

        transfer.outputStream.close()
        Logger.i(TAG, "File received successfully: ${transfer.file.name}")

        // Send acknowledgement
        val ack = FileTransferMessage(
            subType = FileTransferMessage.SUB_FILE_ACK,
            transferId = msg.transferId,
            remotePath = transfer.file.name,
            fileSize = transfer.receivedSize
        )
        writer.writeMessage(ack)
    }

    private fun sendResumeAck(transferId: Int, resumeFromByte: Long, writer: MessageWriter) {
        val ack = FileTransferMessage(
            subType = FileTransferMessage.SUB_FILE_RESUME_ACK,
            transferId = transferId,
            fileSize = resumeFromByte
        )
        writer.writeMessage(ack)
        Logger.d(TAG, "Sent resume ACK for transfer $transferId at byte $resumeFromByte")
    }

    private fun sendErrorAck(transferId: Int, error: String?, writer: MessageWriter) {
        val ack = FileTransferMessage(
            subType = FileTransferMessage.SUB_FILE_ERROR,
            transferId = transferId,
            remotePath = error ?: "Unknown error"
        )
        try {
            writer.writeMessage(ack)
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to send error ack", e)
        }
    }

    private data class TransferState(
        val file: File,
        val outputStream: FileOutputStream,
        val totalSize: Long,
        var receivedSize: Long,
        val resumeOffset: Long = 0
    )

    companion object {
        private const val TAG = "FileTransferHandler"
        private const val DEFAULT_RECEIVE_DIR = "/sdcard/ScrcpyBluetooth/received/"
        private const val CHUNK_SIZE = 32 * 1024 // 32KB chunks
    }
}
