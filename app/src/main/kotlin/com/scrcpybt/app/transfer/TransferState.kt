package com.scrcpybt.app.transfer

/**
 * Represents the state of a file transfer for resume capability.
 */
data class TransferState(
    val transferId: String,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val totalSize: Long,
    var transferredBytes: Long,
    var chunkIndex: Int,
    var status: String,
    val direction: String,
    val deviceFingerprint: String,
    val createdAt: Long,
    var updatedAt: Long,
    var checksum: String?,
    var autoResume: Boolean = true
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_PAUSED = "paused"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"

        const val DIRECTION_SEND = "send"
        const val DIRECTION_RECEIVE = "receive"
    }
}
