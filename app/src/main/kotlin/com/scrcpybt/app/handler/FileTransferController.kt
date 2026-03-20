package com.scrcpybt.app.handler

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.scrcpybt.app.history.HistoryDatabase
import com.scrcpybt.app.history.HistoryEntry
import com.scrcpybt.app.transfer.TransferSettings
import com.scrcpybt.app.transfer.TransferState
import com.scrcpybt.app.transfer.TransferStateDatabase
import com.scrcpybt.common.crypto.EncryptedChannel
import com.scrcpybt.common.protocol.ProtocolConstants
import com.scrcpybt.common.protocol.message.FileTransferMessage
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文件传输控制器：管理控制端侧的文件传输 | File Transfer Controller: Manages file transfer from the controller side
 *
 * 核心功能 | Core Features:
 * - 发送和接收文件通过加密通道 | Handles sending and receiving files through encrypted channel
 * - 支持断点续传能力 | Supports resume/checkpoint capability
 * - 队列管理和进度追踪 | Queue management and progress tracking
 * - 历史记录集成 | History recording integration
 * - 传输状态持久化 | Transfer state persistence
 *
 * 传输流程 | Transfer Flow:
 * 1. 发送端：BEGIN -> CHUNK(0..N) -> END | Sender: BEGIN -> CHUNK(0..N) -> END
 * 2. 接收端：接收 CHUNK 并写入文件 | Receiver: Receive CHUNK and write to file
 * 3. 中断恢复：RESUME -> 续传未完成的块 | Interruption recovery: RESUME -> Continue incomplete blocks
 *
 * 性能优化 | Performance Optimizations:
 * - 可配置块大小（默认 8KB）| Configurable chunk size (default 8KB)
 * - 周期性检查点保存 | Periodic checkpoint saving
 * - 连接丢失自动标记中断 | Auto-mark interrupted on connection loss
 *
 * @property context Android 上下文 | Android context
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class FileTransferController(private val context: Context) {
    companion object {
        private const val TAG = "FileTransferController"
        /** 接收文件的本地存储目录 | Local storage directory for received files */
        private val RECEIVE_DIR = File("/sdcard/ScrcpyBluetooth/received/")
    }

    /** 待传输文件队列（线程安全）| Transfer queue (thread-safe) */
    private val transferQueue = CopyOnWriteArrayList<TransferItem>()
    /** 传输事件监听器列表 | Transfer event listeners */
    private val listeners = mutableListOf<TransferListener>()
    /** 传输状态数据库 | Transfer state database */
    private val transferDatabase = TransferStateDatabase(context)
    /** 活跃传输状态映射（transferId -> state）| Active transfer states (transferId -> state) */
    private val activeTransfers = mutableMapOf<String, ActiveTransferState>()
    /** 历史记录数据库 | History database */
    private var historyDb: HistoryDatabase? = null
    /** 远程设备名称 | Remote device name */
    private var deviceName: String = "Remote"

    /**
     * 设置历史记录数据库 | Set history database
     * @param db 历史数据库实例 | History database instance
     */
    fun setHistoryDatabase(db: HistoryDatabase) {
        historyDb = db
    }

    /**
     * 设置远程设备名称 | Set remote device name
     * @param name 设备名称 | Device name
     */
    fun setDeviceName(name: String) {
        deviceName = name
    }

    /**
     * 传输事件监听器接口 | Transfer event listener interface
     */
    interface TransferListener {
        /** 传输开始 | Transfer started */
        fun onTransferStarted(item: TransferItem)
        /** 传输进度更新 | Transfer progress updated */
        fun onTransferProgress(item: TransferItem, progress: Int)
        /** 传输完成 | Transfer completed */
        fun onTransferComplete(item: TransferItem)
        /** 传输错误 | Transfer error */
        fun onTransferError(item: TransferItem, error: String)
    }

    /**
     * 传输项数据类：表示一个待传输或正在传输的文件 | Transfer item data class: Represents a file to be transferred
     */
    data class TransferItem(
        /** 唯一标识 | Unique identifier */
        val id: Long,
        /** 文件名 | File name */
        val name: String,
        /** 文件大小（字节）| File size in bytes */
        val size: Long,
        /** 文件 URI（发送时使用）| File URI (used when sending) */
        val uri: Uri?,
        /** 传输状态 | Transfer status */
        var status: Status = Status.PENDING,
        /** 传输进度（0-100）| Transfer progress (0-100) */
        var progress: Int = 0,
        /** 错误信息 | Error message */
        var errorMessage: String? = null
    ) {
        /**
         * 传输状态枚举 | Transfer status enum
         */
        enum class Status {
            /** 等待中 | Pending */
            PENDING,
            /** 传输中 | Transferring */
            TRANSFERRING,
            /** 已完成 | Done */
            DONE,
            /** 错误 | Error */
            ERROR
        }
    }

    /**
     * 活跃传输状态（内部使用）| Active transfer state (internal use)
     */
    private data class ActiveTransferState(
        /** 传输 ID | Transfer ID */
        val transferId: String,
        /** 随机访问文件（发送时使用）| Random access file (used when sending) */
        val file: RandomAccessFile?,
        /** 输出流（接收时使用）| Output stream (used when receiving) */
        val outputStream: FileOutputStream?,
        /** 当前块索引 | Current chunk index */
        var chunkIndex: Int,
        /** 已传输字节数 | Transferred bytes */
        var transferredBytes: Long,
        /** 文件总大小 | Total file size */
        val totalSize: Long,
        /** 是否为接收模式 | Is receiving mode */
        val isReceiving: Boolean,
        /** 文件名 | File name */
        val fileName: String = "",
        /** 本地路径 | Local path */
        val localPath: String = ""
    )

    init {
        // 确保接收目录存在 | Ensure receive directory exists
        if (!RECEIVE_DIR.exists()) {
            RECEIVE_DIR.mkdirs()
        }
    }

    /**
     * 初始化并自动恢复重连后的中断传输 | Initialize and auto-resume interrupted transfers on reconnect
     *
     * 连接建立后调用此方法，自动恢复上次中断的传输任务 | Call this method after connection is established to auto-resume interrupted tasks
     *
     * @param deviceFingerprint 设备指纹（用于匹配历史传输）| Device fingerprint (for matching historical transfers)
     * @param encryptedChannel 加密通道（用于发送恢复消息）| Encrypted channel (for sending resume messages)
     */
    fun initializeAutoResume(deviceFingerprint: String, encryptedChannel: EncryptedChannel) {
        if (!TransferSettings.isAutoResumeOnReconnectEnabled(context)) {
            Logger.d(TAG, "Auto-resume is disabled")
            return
        }

        val interruptedTransfers = transferDatabase.getAutoResumeTransfers(deviceFingerprint)
        Logger.i(TAG, "Found ${interruptedTransfers.size} interrupted transfers to resume")

        for (transfer in interruptedTransfers) {
            try {
                resumeTransfer(transfer, encryptedChannel)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to resume transfer ${transfer.transferId}", e)
                transferDatabase.markFailed(transfer.transferId, e.message ?: "Resume failed")
            }
        }
    }

    /**
     * 恢复一个中断的传输 | Resume an interrupted transfer
     *
     * @param state 传输状态（包含断点信息）| Transfer state (contains checkpoint information)
     * @param encryptedChannel 加密通道 | Encrypted channel
     */
    private fun resumeTransfer(state: TransferState, encryptedChannel: EncryptedChannel) {
        Logger.i(TAG, "Resuming transfer: ${state.fileName} from byte ${state.transferredBytes}")

        val resumeMsg = FileTransferMessage(
            subType = FileTransferMessage.SUB_FILE_RESUME,
            transferId = state.transferId.hashCode(),
            remotePath = state.remotePath,
            fileSize = state.totalSize,
            chunkIndex = state.chunkIndex
        )

        encryptedChannel.send(resumeMsg)

        // 更新状态为活跃 | Update state to active
        state.status = TransferState.STATUS_ACTIVE
        state.updatedAt = System.currentTimeMillis()
        transferDatabase.saveTransferState(state)
    }

    /**
     * 添加传输事件监听器 | Add transfer event listener
     * @param listener 监听器实例 | Listener instance
     */
    fun addListener(listener: TransferListener) {
        listeners.add(listener)
    }

    /**
     * 移除传输事件监听器 | Remove transfer event listener
     * @param listener 监听器实例 | Listener instance
     */
    fun removeListener(listener: TransferListener) {
        listeners.remove(listener)
    }

    /**
     * 将文件加入发送队列 | Queue a file for sending
     *
     * 从 URI 读取文件元数据并创建 TransferItem | Read file metadata from URI and create TransferItem
     *
     * @param uri 文件 URI（来自文件选择器或分享）| File URI (from file picker or share)
     * @return 传输项 | Transfer item
     */
    fun queueFile(uri: Uri): TransferItem {
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(uri, null, null, null, null)
        val name = cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.getString(nameIndex)
            } else "unknown"
        } ?: "unknown"

        val size = cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                it.getLong(sizeIndex)
            } else 0L
        } ?: 0L

        val item = TransferItem(
            id = System.currentTimeMillis(),
            name = name,
            size = size,
            uri = uri
        )
        transferQueue.add(item)
        return item
    }

    /**
     * 通过加密通道发送文件（支持断点）| Send a file through the writer with checkpoint support
     *
     * 传输流程 | Transfer flow:
     * 1. 发送 BEGIN 消息（包含文件名和大小）| Send BEGIN message (with filename and size)
     * 2. 分块读取并发送 CHUNK 消息 | Read and send CHUNK messages
     * 3. 周期性保存检查点到数据库 | Periodically save checkpoints to database
     * 4. 发送 END 消息标记完成 | Send END message to mark completion
     *
     * @param item 传输项 | Transfer item
     * @param writer 消息写入器 | Message writer
     * @param deviceFingerprint 设备指纹（用于断点恢复匹配）| Device fingerprint (for resume matching)
     */
    fun sendFile(item: TransferItem, writer: MessageWriter, deviceFingerprint: String = "") {
        if (item.uri == null) {
            notifyError(item, "Invalid URI")
            return
        }

        val transferId = UUID.randomUUID().toString()
        val chunkSize = TransferSettings.getChunkSize(context)
        val checkpointInterval = TransferSettings.getCheckpointInterval(context)

        try {
            item.status = TransferItem.Status.TRANSFERRING
            notifyStarted(item)

            // Save initial transfer state
            val transferState = TransferState(
                transferId = transferId,
                fileName = item.name,
                remotePath = item.name,
                localPath = item.uri.toString(),
                totalSize = item.size,
                transferredBytes = 0,
                chunkIndex = 0,
                status = TransferState.STATUS_ACTIVE,
                direction = TransferState.DIRECTION_SEND,
                deviceFingerprint = deviceFingerprint,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                checksum = null
            )
            transferDatabase.saveTransferState(transferState)

            context.contentResolver.openInputStream(item.uri)?.use { input ->
                // Send BEGIN message
                val beginMsg = FileTransferMessage(
                    subType = FileTransferMessage.SUB_FILE_BEGIN,
                    transferId = transferId.hashCode(),
                    remotePath = item.name,
                    fileSize = item.size
                )
                writer.writeMessage(beginMsg)

                // Send file in chunks
                val buffer = ByteArray(chunkSize)
                var totalSent = 0L
                var chunkIndex = 0
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    val chunkMsg = FileTransferMessage(
                        subType = FileTransferMessage.SUB_FILE_CHUNK,
                        transferId = transferId.hashCode(),
                        chunkIndex = chunkIndex,
                        chunkData = buffer.copyOf(read)
                    )
                    writer.writeMessage(chunkMsg)

                    totalSent += read
                    chunkIndex++

                    // Save checkpoint periodically
                    if (chunkIndex % checkpointInterval == 0) {
                        transferDatabase.updateProgress(transferId, totalSent, chunkIndex)
                    }

                    val progress = ((totalSent * 100) / item.size).toInt()
                    item.progress = progress
                    notifyProgress(item, progress)
                }

                // Send END message
                val endMsg = FileTransferMessage(
                    subType = FileTransferMessage.SUB_FILE_END,
                    transferId = transferId.hashCode()
                )
                writer.writeMessage(endMsg)

                // Mark as completed
                transferDatabase.markCompleted(transferId)

                item.status = TransferItem.Status.DONE
                item.progress = 100
                notifyComplete(item)
                Logger.i(TAG, "File sent: ${item.name}")

                // Record history
                historyDb?.recordFileTransfer(
                    direction = HistoryEntry.DIR_SEND,
                    deviceName = deviceName,
                    fileName = item.name,
                    filePath = item.uri.toString(),
                    size = item.size,
                    status = HistoryEntry.STATUS_SUCCESS
                )
            }
        } catch (e: IOException) {
            Logger.e(TAG, "File send error", e)
            item.status = TransferItem.Status.ERROR
            item.errorMessage = e.message

            // Mark as interrupted (can be resumed)
            transferDatabase.markInterrupted(transferId)

            notifyError(item, e.message ?: "Unknown error")

            // Record interrupted transfer in history
            historyDb?.recordFileTransfer(
                direction = HistoryEntry.DIR_SEND,
                deviceName = deviceName,
                fileName = item.name,
                filePath = item.uri?.toString() ?: "",
                size = item.size,
                status = HistoryEntry.STATUS_INTERRUPTED
            )
        }
    }

    /**
     * 处理接收到的文件消息并重组文件（支持断点）| Handle incoming file chunks and reassemble the file with checkpoint support
     *
     * 消息类型处理 | Message type handling:
     * - SUB_FILE_BEGIN: 创建接收状态，准备写入文件 | Create receive state and prepare file
     * - SUB_FILE_CHUNK: 写入数据块，更新进度和检查点 | Write data chunk, update progress and checkpoint
     * - SUB_FILE_END: 关闭文件，标记完成，记录历史 | Close file, mark completed, record history
     * - SUB_FILE_RESUME_ACK: 处理恢复确认 | Handle resume acknowledgment
     * - SUB_FILE_ERROR: 清理失败传输 | Clean up failed transfer
     *
     * @param msg 文件传输消息 | File transfer message
     * @param deviceFingerprint 设备指纹 | Device fingerprint
     */
    fun handleIncomingMessage(msg: FileTransferMessage, deviceFingerprint: String = "") {
        val transferId = msg.transferId.toString()

        when (msg.subType) {
            FileTransferMessage.SUB_FILE_BEGIN -> {
                val fileName = msg.remotePath.substringAfterLast('/').ifEmpty { "unknown_${System.currentTimeMillis()}" }
                val file = File(RECEIVE_DIR, fileName)
                val outputStream = FileOutputStream(file)

                val state = ActiveTransferState(
                    transferId = transferId,
                    file = null,
                    outputStream = outputStream,
                    chunkIndex = 0,
                    transferredBytes = 0,
                    totalSize = msg.fileSize,
                    isReceiving = true,
                    fileName = fileName,
                    localPath = file.absolutePath
                )
                activeTransfers[transferId] = state

                // Save to database
                val transferState = TransferState(
                    transferId = transferId,
                    fileName = fileName,
                    remotePath = msg.remotePath,
                    localPath = file.absolutePath,
                    totalSize = msg.fileSize,
                    transferredBytes = 0,
                    chunkIndex = 0,
                    status = TransferState.STATUS_ACTIVE,
                    direction = TransferState.DIRECTION_RECEIVE,
                    deviceFingerprint = deviceFingerprint,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    checksum = null
                )
                transferDatabase.saveTransferState(transferState)

                Logger.i(TAG, "Receiving file: $fileName (${msg.fileSize} bytes)")
            }

            FileTransferMessage.SUB_FILE_CHUNK -> {
                val state = activeTransfers[transferId]
                if (state == null) {
                    Logger.w(TAG, "Received chunk for unknown transfer $transferId")
                    return
                }

                msg.chunkData?.let { data ->
                    state.outputStream?.write(data)
                    state.transferredBytes += data.size
                    state.chunkIndex++

                    // Save checkpoint periodically
                    val checkpointInterval = TransferSettings.getCheckpointInterval(context)
                    if (state.chunkIndex % checkpointInterval == 0) {
                        transferDatabase.updateProgress(transferId, state.transferredBytes, state.chunkIndex)
                    }

                    val progress = ((state.transferredBytes * 100) / state.totalSize).toInt()
                    Logger.d(TAG, "Received chunk ${msg.chunkIndex}: ${state.transferredBytes}/${state.totalSize} ($progress%)")
                }
            }

            FileTransferMessage.SUB_FILE_END -> {
                val state = activeTransfers.remove(transferId)
                if (state == null) {
                    Logger.w(TAG, "Received end for unknown transfer $transferId")
                    return
                }

                state.outputStream?.close()
                transferDatabase.markCompleted(transferId)
                Logger.i(TAG, "File received successfully")

                // Record history
                historyDb?.recordFileTransfer(
                    direction = HistoryEntry.DIR_RECEIVE,
                    deviceName = deviceName,
                    fileName = state.fileName,
                    filePath = state.localPath,
                    size = state.totalSize,
                    status = HistoryEntry.STATUS_SUCCESS
                )
            }

            FileTransferMessage.SUB_FILE_RESUME_ACK -> {
                val resumeFromByte = msg.fileSize
                Logger.i(TAG, "Resume ACK received: continue from byte $resumeFromByte")
                // The actual resume logic continues in the sending thread
            }

            FileTransferMessage.SUB_FILE_ERROR -> {
                val state = activeTransfers.remove(transferId)
                state?.outputStream?.close()
                transferDatabase.markFailed(transferId, msg.remotePath)
                Logger.e(TAG, "Transfer error: ${msg.remotePath}")
            }
        }
    }

    /**
     * 标记所有活跃传输为中断（连接丢失时调用）| Mark all active transfers as interrupted (on connection loss)
     *
     * 关闭所有文件流并更新数据库状态，以便重连后恢复 | Close all file streams and update database state for later resume
     */
    fun markActiveTransfersAsInterrupted() {
        for ((transferId, state) in activeTransfers) {
            state.outputStream?.close()
            state.file?.close()
            transferDatabase.markInterrupted(transferId)
        }
        activeTransfers.clear()
        Logger.i(TAG, "Marked all active transfers as interrupted")
    }

    /**
     * 获取传输队列的快照 | Get a snapshot of the transfer queue
     * @return 传输项列表 | List of transfer items
     */
    fun getTransferQueue(): List<TransferItem> = transferQueue.toList()

    /** 通知监听器传输开始 | Notify listeners that transfer started */
    private fun notifyStarted(item: TransferItem) {
        listeners.forEach { it.onTransferStarted(item) }
    }

    /** 通知监听器传输进度 | Notify listeners of transfer progress */
    private fun notifyProgress(item: TransferItem, progress: Int) {
        listeners.forEach { it.onTransferProgress(item, progress) }
    }

    /** 通知监听器传输完成 | Notify listeners that transfer completed */
    private fun notifyComplete(item: TransferItem) {
        listeners.forEach { it.onTransferComplete(item) }
    }

    /** 通知监听器传输错误 | Notify listeners of transfer error */
    private fun notifyError(item: TransferItem, error: String) {
        listeners.forEach { it.onTransferError(item, error) }
    }
}
