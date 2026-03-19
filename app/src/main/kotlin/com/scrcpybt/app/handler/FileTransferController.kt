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
 * Manages file transfer from the controller side.
 * Handles sending and receiving files through the encrypted channel.
 * Now supports resume/checkpoint capability.
 */
class FileTransferController(private val context: Context) {
    companion object {
        private const val TAG = "FileTransferController"
        private val RECEIVE_DIR = File("/sdcard/ScrcpyBluetooth/received/")
    }

    private val transferQueue = CopyOnWriteArrayList<TransferItem>()
    private val listeners = mutableListOf<TransferListener>()
    private val transferDatabase = TransferStateDatabase(context)
    private val activeTransfers = mutableMapOf<String, ActiveTransferState>()
    private var historyDb: HistoryDatabase? = null
    private var deviceName: String = "Remote"

    fun setHistoryDatabase(db: HistoryDatabase) {
        historyDb = db
    }

    fun setDeviceName(name: String) {
        deviceName = name
    }

    interface TransferListener {
        fun onTransferStarted(item: TransferItem)
        fun onTransferProgress(item: TransferItem, progress: Int)
        fun onTransferComplete(item: TransferItem)
        fun onTransferError(item: TransferItem, error: String)
    }

    data class TransferItem(
        val id: Long,
        val name: String,
        val size: Long,
        val uri: Uri?,
        var status: Status = Status.PENDING,
        var progress: Int = 0,
        var errorMessage: String? = null
    ) {
        enum class Status {
            PENDING, TRANSFERRING, DONE, ERROR
        }
    }

    private data class ActiveTransferState(
        val transferId: String,
        val file: RandomAccessFile?,
        val outputStream: FileOutputStream?,
        var chunkIndex: Int,
        var transferredBytes: Long,
        val totalSize: Long,
        val isReceiving: Boolean
    )

    init {
        // Ensure receive directory exists
        if (!RECEIVE_DIR.exists()) {
            RECEIVE_DIR.mkdirs()
        }
    }

    /**
     * Initialize and auto-resume interrupted transfers on reconnect.
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
     * Resume an interrupted transfer.
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

        // Update state to active
        state.status = TransferState.STATUS_ACTIVE
        state.updatedAt = System.currentTimeMillis()
        transferDatabase.saveTransferState(state)
    }

    fun addListener(listener: TransferListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TransferListener) {
        listeners.remove(listener)
    }

    /**
     * Queue a file for sending.
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
     * Send a file through the writer with checkpoint support.
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
     * Handle incoming file chunks and reassemble the file with checkpoint support.
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
                    isReceiving = true
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
                    filePath = state.file.absolutePath,
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
     * Mark all active transfers as interrupted (on connection loss).
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

    fun getTransferQueue(): List<TransferItem> = transferQueue.toList()

    private fun notifyStarted(item: TransferItem) {
        listeners.forEach { it.onTransferStarted(item) }
    }

    private fun notifyProgress(item: TransferItem, progress: Int) {
        listeners.forEach { it.onTransferProgress(item, progress) }
    }

    private fun notifyComplete(item: TransferItem) {
        listeners.forEach { it.onTransferComplete(item) }
    }

    private fun notifyError(item: TransferItem, error: String) {
        listeners.forEach { it.onTransferError(item, error) }
    }
}
