package com.scrcpybt.app.sync

import com.scrcpybt.common.sync.FileInfo

/**
 * 同步差异数据类：表示本地和远程文件夹状态之间的差异 | Sync Diff: Represents the diff between local and remote folder states
 *
 * 决定双向传输的内容 | Determines what needs to be transferred in either direction
 *
 * @property toDownload 需要从远程下载的文件/块列表 | List of files/blocks to download from remote
 * @property toUpload 需要上传到远程的文件/块列表 | List of files/blocks to upload to remote
 * @property toDelete 需要在本地删除的文件路径列表 | List of file paths to delete locally
 * @property toDeleteRemote 需要在远程删除的文件路径列表 | List of file paths to delete on remote
 * @property conflicts 冲突修改列表 | List of conflicting modifications
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
data class SyncDiff(
    val toDownload: List<FileDownload>,    // 从远程获取的文件/块 | files/blocks to get from remote
    val toUpload: List<FileUpload>,        // 发送到远程的文件/块 | files/blocks to send to remote
    val toDelete: List<String>,            // 本地删除的文件 | files to delete locally
    val toDeleteRemote: List<String>,      // 远程删除的文件 | files to delete on remote
    val conflicts: List<ConflictPair>      // 冲突的修改 | conflicting modifications
) {
    /**
     * 检查是否有工作要做 | Check if there's any work to do
     * @return true 如果没有任何操作需要执行 | true if no operations need to be performed
     */
    fun isEmpty(): Boolean = toDownload.isEmpty() && toUpload.isEmpty() &&
            toDelete.isEmpty() && toDeleteRemote.isEmpty() && conflicts.isEmpty()

    /**
     * 计算总操作数 | Calculate total number of operations
     * @return 所有操作的总数 | Total count of all operations
     */
    fun totalOperations(): Int = toDownload.size + toUpload.size +
            toDelete.size + toDeleteRemote.size + conflicts.size

    /**
     * 计算需要下载的总字节数 | Calculate total bytes to download
     * @return 下载字节总数 | Total download bytes
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
     * 计算需要上传的总字节数 | Calculate total bytes to upload
     * @return 上传字节总数 | Total upload bytes
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
 * 文件下载数据类：表示需要从远程下载的文件 | File Download: Represents a file that needs to be downloaded from remote
 *
 * @property fileInfo 文件信息（包含元数据和块哈希）| File info (contains metadata and block hashes)
 * @property neededBlocks 需要下载的块索引列表（空=所有块/新文件）| Indices of blocks to download (empty = all blocks/new file)
 */
data class FileDownload(
    val fileInfo: FileInfo,
    val neededBlocks: List<Int>  // 要下载的块索引（空=全部块/新文件）| indices of blocks to download (empty = all blocks/new file)
) {
    /**
     * 检查是否需要下载完整文件 | Check if entire file needs to be downloaded
     * @return true 如果需要下载所有块 | true if all blocks need to be downloaded
     */
    fun needsCompleteFile(): Boolean = neededBlocks.isEmpty() ||
        neededBlocks.size == fileInfo.blocks.size

    /**
     * 计算此文件需要下载的字节数 | Calculate bytes to download for this file
     * @return 下载字节数 | Download bytes
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
 * 文件上传数据类：表示需要上传到远程的文件 | File Upload: Represents a file that needs to be uploaded to remote
 *
 * @property fileInfo 文件信息（包含元数据和块哈希）| File info (contains metadata and block hashes)
 * @property blocksToSend 需要发送的块索引列表（空=所有块/新文件）| Indices of blocks to send (empty = all blocks/new file)
 */
data class FileUpload(
    val fileInfo: FileInfo,
    val blocksToSend: List<Int>  // 要发送的块索引（空=全部块/新文件）| indices of blocks to send (empty = all blocks/new file)
) {
    /**
     * 检查是否需要上传完整文件 | Check if entire file needs to be uploaded
     * @return true 如果需要上传所有块 | true if all blocks need to be uploaded
     */
    fun needsCompleteFile(): Boolean = blocksToSend.isEmpty() ||
        blocksToSend.size == fileInfo.blocks.size

    /**
     * 计算此文件需要上传的字节数 | Calculate bytes to upload for this file
     * @return 上传字节数 | Upload bytes
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
 * 冲突对数据类：表示同一文件在两端有不同修改的冲突 | Conflict Pair: Represents a conflict where the same file was modified differently on both sides
 *
 * @property localFile 本地文件信息 | Local file info
 * @property remoteFile 远程文件信息 | Remote file info
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
 * 冲突解决结果密封类 | Conflict Resolution: Result of conflict resolution
 */
sealed class ConflictResolution {
    /**
     * 保留本地版本，远程成为冲突副本 | Keep local version, remote becomes conflict copy
     */
    object KeepLocal : ConflictResolution()

    /**
     * 保留远程版本，本地成为冲突副本 | Keep remote version, local becomes conflict copy
     */
    object KeepRemote : ConflictResolution()

    /**
     * 两者都保留为冲突副本 | Keep both as conflict copies
     */
    object KeepBoth : ConflictResolution()

    /**
     * 基于时间戳保留更新的版本 | Keep newer version based on timestamp
     */
    object KeepNewer : ConflictResolution()

    /**
     * 基于大小保留更大的版本 | Keep larger version based on size
     */
    object KeepLarger : ConflictResolution()
}
