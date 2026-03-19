package com.scrcpybt.app.history

/**
 * Represents a single history entry for all operations.
 */
data class HistoryEntry(
    val id: Long,
    val type: String,
    val direction: String,
    val deviceName: String,
    val deviceFingerprint: String,
    val timestamp: Long,
    val details: String,
    val status: String,
    val bytesTransferred: Long,
    val fileName: String?,
    val filePath: String?
) {
    companion object {
        // Operation types
        const val TYPE_CLIPBOARD = "clipboard"
        const val TYPE_FILE_TRANSFER = "file_transfer"
        const val TYPE_FOLDER_SYNC = "folder_sync"
        const val TYPE_SHARE_FORWARD = "share_forward"
        const val TYPE_SCREEN_MIRROR = "screen_mirror"

        // Directions
        const val DIR_SEND = "send"
        const val DIR_RECEIVE = "receive"
        const val DIR_BIDIRECTIONAL = "bidirectional"

        // Status values
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_INTERRUPTED = "interrupted"
    }
}
