package com.scrcpybt.app.sync

import com.scrcpybt.common.sync.FileInfo

/**
 * Represents the diff between local and remote folder states.
 * This determines what needs to be transferred in either direction.
 */
data class SyncDiff(
    val toDownload: List<FileDownload>,    // files/blocks to get from remote
    val toUpload: List<FileUpload>,        // files/blocks to send to remote
    val toDelete: List<String>,            // files to delete locally
    val toDeleteRemote: List<String>,      // files to delete on remote
    val conflicts: List<ConflictPair>      // conflicting modifications
) {
    /**
     * Check if there's any work to do.
     */
    fun isEmpty(): Boolean = toDownload.isEmpty() && toUpload.isEmpty() &&
            toDelete.isEmpty() && toDeleteRemote.isEmpty() && conflicts.isEmpty()

    /**
     * Total number of operations.
     */
    fun totalOperations(): Int = toDownload.size + toUpload.size +
            toDelete.size + toDeleteRemote.size + conflicts.size

    /**
     * Calculate total bytes to download.
     */
    fun totalDownloadBytes(): Long = toDownload.sumOf { download ->
        if (download.neededBlocks.isEmpty()) {
            download.fileInfo.size
        } else {
            download.neededBlocks.sumOf { blockIdx ->
                download.fileInfo.blocks.getOrNull(blockIdx)?.size?.toLong() ?: 0L
            }
        }
    }

    /**
     * Calculate total bytes to upload.
     */
    fun totalUploadBytes(): Long = toUpload.sumOf { upload ->
        if (upload.blocksToSend.isEmpty()) {
            upload.fileInfo.size
        } else {
            upload.blocksToSend.sumOf { blockIdx ->
                upload.fileInfo.blocks.getOrNull(blockIdx)?.size?.toLong() ?: 0L
            }
        }
    }
}

/**
 * Represents a file that needs to be downloaded from remote.
 */
data class FileDownload(
    val fileInfo: FileInfo,
    val neededBlocks: List<Int>  // indices of blocks to download (empty = all blocks/new file)
) {
    /**
     * Check if entire file needs to be downloaded.
     */
    fun needsCompleteFile(): Boolean = neededBlocks.isEmpty() ||
        neededBlocks.size == fileInfo.blocks.size

    /**
     * Calculate bytes to download for this file.
     */
    fun bytesToDownload(): Long {
        return if (neededBlocks.isEmpty()) {
            fileInfo.size
        } else {
            neededBlocks.sumOf { blockIdx ->
                fileInfo.blocks.getOrNull(blockIdx)?.size?.toLong() ?: 0L
            }
        }
    }
}

/**
 * Represents a file that needs to be uploaded to remote.
 */
data class FileUpload(
    val fileInfo: FileInfo,
    val blocksToSend: List<Int>  // indices of blocks to send (empty = all blocks/new file)
) {
    /**
     * Check if entire file needs to be uploaded.
     */
    fun needsCompleteFile(): Boolean = blocksToSend.isEmpty() ||
        blocksToSend.size == fileInfo.blocks.size

    /**
     * Calculate bytes to upload for this file.
     */
    fun bytesToUpload(): Long {
        return if (blocksToSend.isEmpty()) {
            fileInfo.size
        } else {
            blocksToSend.sumOf { blockIdx ->
                fileInfo.blocks.getOrNull(blockIdx)?.size?.toLong() ?: 0L
            }
        }
    }
}

/**
 * Represents a conflict where the same file was modified differently on both sides.
 */
data class ConflictPair(
    val localFile: FileInfo,
    val remoteFile: FileInfo
) {
    init {
        require(localFile.relativePath == remoteFile.relativePath) {
            "Conflict pair must have same relative path"
        }
    }
}

/**
 * Result of conflict resolution.
 */
sealed class ConflictResolution {
    /**
     * Keep local version, remote becomes conflict copy.
     */
    object KeepLocal : ConflictResolution()

    /**
     * Keep remote version, local becomes conflict copy.
     */
    object KeepRemote : ConflictResolution()

    /**
     * Keep both as conflict copies.
     */
    object KeepBoth : ConflictResolution()

    /**
     * Keep newer version based on timestamp.
     */
    object KeepNewer : ConflictResolution()

    /**
     * Keep larger version based on size.
     */
    object KeepLarger : ConflictResolution()
}
